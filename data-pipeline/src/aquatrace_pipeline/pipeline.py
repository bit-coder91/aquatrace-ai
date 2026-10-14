"""Deterministic AquaTrace ingestion and time-series transformations."""

from __future__ import annotations

from dataclasses import dataclass
from hashlib import sha256
from pathlib import Path
from typing import Any

import numpy as np
import pandas as pd

REQUIRED_COLUMNS = {
    "event_id", "site_id", "meter_id", "timestamp", "value",
    "unit", "reading_mode", "source_id",
}
MAX_UPLOAD_BYTES = 10 * 1024 * 1024
MAX_ROWS = 100_000


class SchemaError(ValueError):
    """The source file does not contain the required columns or shape."""


@dataclass(frozen=True)
class CleanResult:
    accepted: pd.DataFrame
    rejected: pd.DataFrame
    duplicate_count: int
    conflict_count: int


def read_source(path: str | Path, max_bytes: int = MAX_UPLOAD_BYTES) -> pd.DataFrame:
    """Read CSV, JSON array, or JSON Lines without accepting oversized files."""
    source = Path(path)
    size = source.stat().st_size
    if size > max_bytes:
        raise ValueError(f"Input is {size} bytes; limit is {max_bytes} bytes.")
    suffix = source.suffix.lower()
    if suffix == ".csv":
        frame = pd.read_csv(source, dtype={"event_id": "string", "site_id": "string", "meter_id": "string", "source_id": "string"})
    elif suffix in {".json", ".jsonl", ".ndjson"}:
        frame = pd.read_json(source, lines=suffix in {".jsonl", ".ndjson"})
    else:
        raise ValueError("Input file must use .csv, .json, .jsonl, or .ndjson.")
    if len(frame.index) > MAX_ROWS:
        raise ValueError(f"Input contains {len(frame.index)} rows; limit is {MAX_ROWS}.")
    return frame


def _fingerprint(row: pd.Series) -> tuple[Any, ...]:
    return (row["timestamp"], float(row["value_litres"]), row["reading_mode"], row["unit"])


def _stable_reading_id(site_id: str, meter_id: str, source_id: str, event_id: str) -> str:
    key = "|".join((site_id, meter_id, source_id, event_id))
    return sha256(key.encode("utf-8")).hexdigest()


def validate_and_clean(source: pd.DataFrame, known_meter_ids: set[str] | None = None) -> CleanResult:
    """Validate rows, normalize identifiers/units/times, and separate duplicates.

    Identical repeated source events are counted and omitted. Reuse of an event
    key with different timestamp/value/mode is quarantined as a conflict.
    Values are normalized to litres while original value and unit are retained.
    """
    missing = sorted(REQUIRED_COLUMNS.difference(source.columns))
    if missing:
        raise SchemaError("Missing required columns: " + ", ".join(missing))
    if len(source.index) > MAX_ROWS:
        raise ValueError(f"Input contains {len(source.index)} rows; limit is {MAX_ROWS}.")

    frame = source.copy().reset_index(drop=True)
    frame["source_row"] = np.arange(2, len(frame) + 2)
    errors: list[list[str]] = [[] for _ in range(len(frame))]

    for column in ("event_id", "site_id", "meter_id", "source_id"):
        frame[column] = frame[column].astype("string").str.strip()
        bad = frame[column].isna() | frame[column].eq("")
        for idx in np.flatnonzero(bad.to_numpy()):
            errors[idx].append(f"MISSING_{column.upper()}")

    frame["unit"] = frame["unit"].astype("string").str.strip().str.upper()
    frame["reading_mode"] = frame["reading_mode"].astype("string").str.strip().str.upper()
    frame["timestamp"] = pd.to_datetime(frame["timestamp"], errors="coerce", utc=True)
    frame["value"] = pd.to_numeric(frame["value"], errors="coerce")

    for idx, row in frame.iterrows():
        if known_meter_ids is not None and not pd.isna(row["meter_id"]) and str(row["meter_id"]).strip() not in known_meter_ids:
            errors[idx].append("UNKNOWN_METER_ID")
        if pd.isna(row["timestamp"]):
            errors[idx].append("INVALID_TIMESTAMP")
        if pd.isna(row["value"]) or not np.isfinite(row["value"]):
            errors[idx].append("INVALID_NUMERIC_VALUE")
        elif row["value"] < 0:
            errors[idx].append("NEGATIVE_READING")
        if row["unit"] not in {"L", "M3"}:
            errors[idx].append("UNSUPPORTED_UNIT")
        if row["reading_mode"] not in {"CUMULATIVE", "INTERVAL"}:
            errors[idx].append("INVALID_READING_MODE")

    frame["value_litres"] = np.where(frame["unit"].eq("M3"), frame["value"] * 1000.0, frame["value"])
    frame["rejection_reason"] = [";".join(dict.fromkeys(reasons)) for reasons in errors]
    invalid_mask = frame["rejection_reason"].ne("")
    rejected = frame.loc[invalid_mask].copy()
    valid = frame.loc[~invalid_mask].copy()

    seen: dict[tuple[str, str, str, str], tuple[Any, ...]] = {}
    keep: list[int] = []
    duplicate_count = 0
    conflict_indices: list[int] = []
    for idx, row in valid.iterrows():
        key = (row["site_id"], row["meter_id"], row["source_id"], row["event_id"])
        fingerprint = _fingerprint(row)
        if key not in seen:
            seen[key] = fingerprint
            keep.append(idx)
        elif seen[key] == fingerprint:
            duplicate_count += 1
        else:
            conflict_indices.append(idx)

    conflicts = valid.loc[conflict_indices].copy()
    if not conflicts.empty:
        conflicts["rejection_reason"] = "EVENT_ID_CONFLICT"
        rejected = pd.concat([rejected, conflicts], ignore_index=True)
    valid = valid.loc[keep].copy()
    valid["reading_id"] = [
        _stable_reading_id(row.site_id, row.meter_id, row.source_id, row.event_id)
        for row in valid.itertuples(index=False)
    ]
    valid["quality"] = "ACCEPTED"
    rejected = rejected.sort_values("source_row", kind="stable").reset_index(drop=True)
    return CleanResult(valid.reset_index(drop=True), rejected, duplicate_count, len(conflict_indices))


def derive_usage_intervals(
    readings: pd.DataFrame,
    expected_interval_minutes: dict[str, int] | None = None,
) -> pd.DataFrame:
    """Derive interval usage; resets, gaps, and source-order regressions stay explicit."""
    expected_interval_minutes = expected_interval_minutes or {}
    columns = list(readings.columns) + [
        "interval_start", "usage_litres", "interval_minutes", "interval_status", "was_out_of_order"
    ]
    if readings.empty:
        return pd.DataFrame(columns=columns)

    frame = readings.copy()
    frame["was_out_of_order"] = False
    for _, group in frame.groupby(["site_id", "meter_id"], sort=False):
        original_order = group.sort_values("source_row", kind="stable")
        prior_time = None
        for idx, row in original_order.iterrows():
            if prior_time is not None and row["timestamp"] < prior_time:
                frame.at[idx, "was_out_of_order"] = True
            prior_time = row["timestamp"]

    output: list[dict[str, Any]] = []
    for (_, meter_id), group in frame.groupby(["site_id", "meter_id"], sort=False):
        ordered = group.sort_values(["timestamp", "source_row"], kind="stable")
        previous: pd.Series | None = None
        for _, row in ordered.iterrows():
            item = row.to_dict()
            item["interval_start"] = pd.NaT
            item["usage_litres"] = np.nan
            item["interval_minutes"] = np.nan
            item["interval_status"] = "BASELINE"
            if row["reading_mode"] == "INTERVAL":
                expected = int(expected_interval_minutes.get(meter_id, 60))
                item["interval_start"] = row["timestamp"] - pd.Timedelta(minutes=expected)
                item["usage_litres"] = float(row["value_litres"])
                item["interval_minutes"] = expected
                item["interval_status"] = "OBSERVED_INTERVAL"
            elif previous is not None:
                elapsed = (row["timestamp"] - previous["timestamp"]).total_seconds() / 60.0
                item["interval_start"] = previous["timestamp"]
                item["interval_minutes"] = elapsed
                expected = int(expected_interval_minutes.get(meter_id, 60))
                difference = float(row["value_litres"] - previous["value_litres"])
                if difference < 0:
                    item["interval_status"] = "COUNTER_RESET"
                else:
                    item["usage_litres"] = difference
                    item["interval_status"] = "MISSING_INTERVALS" if elapsed > expected * 1.5 else "OBSERVED_CUMULATIVE"
            output.append(item)
            previous = row
    return pd.DataFrame(output).sort_values(["site_id", "meter_id", "timestamp"], kind="stable").reset_index(drop=True)


def aggregate_daily(intervals: pd.DataFrame, site_timezones: dict[str, str] | None = None) -> pd.DataFrame:
    """Aggregate usage by the local date of the interval end timestamp."""
    site_timezones = site_timezones or {}
    columns = ["site_id", "date", "total_usage_litres", "interval_count", "meter_count", "gap_interval_count"]
    if intervals.empty:
        return pd.DataFrame(columns=columns)
    usable = intervals.loc[intervals["usage_litres"].notna()].copy()
    if usable.empty:
        return pd.DataFrame(columns=columns)
    usable["local_date"] = [
        timestamp.tz_convert(site_timezones.get(site_id, "UTC")).date().isoformat()
        for site_id, timestamp in zip(usable["site_id"], usable["timestamp"], strict=True)
    ]
    result = usable.groupby(["site_id", "local_date"], as_index=False).agg(
        total_usage_litres=("usage_litres", "sum"),
        interval_count=("usage_litres", "count"),
        meter_count=("meter_id", "nunique"),
        gap_interval_count=("interval_status", lambda values: int(values.eq("MISSING_INTERVALS").sum())),
    ).rename(columns={"local_date": "date"})
    return result[columns].sort_values(["site_id", "date"], kind="stable").reset_index(drop=True)


def aggregate_daily_by_meter(intervals: pd.DataFrame, site_timezones: dict[str, str] | None = None) -> pd.DataFrame:
    """Build idempotent daily contribution rows per site and meter."""
    site_timezones = site_timezones or {}
    columns = ["site_id", "date", "meter_id", "total_usage_litres", "interval_count", "gap_interval_count"]
    if intervals.empty:
        return pd.DataFrame(columns=columns)
    usable = intervals.loc[intervals["usage_litres"].notna()].copy()
    if usable.empty:
        return pd.DataFrame(columns=columns)
    usable["local_date"] = [
        timestamp.tz_convert(site_timezones.get(site_id, "UTC")).date().isoformat()
        for site_id, timestamp in zip(usable["site_id"], usable["timestamp"], strict=True)
    ]
    result = usable.groupby(["site_id", "local_date", "meter_id"], as_index=False).agg(
        total_usage_litres=("usage_litres", "sum"),
        interval_count=("usage_litres", "count"),
        gap_interval_count=("interval_status", lambda values: int(values.eq("MISSING_INTERVALS").sum())),
    ).rename(columns={"local_date": "date"})
    return result[columns].sort_values(["site_id", "date", "meter_id"], kind="stable").reset_index(drop=True)
