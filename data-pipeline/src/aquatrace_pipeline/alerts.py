"""Transparent, configurable rules for investigation alerts."""

from __future__ import annotations

from hashlib import sha256
from datetime import datetime, time, timezone
from typing import Any

import pandas as pd


def _alert_id(*parts: str) -> str:
    return sha256("|".join(parts).encode("utf-8")).hexdigest()


def _in_window(value: time, start: time, end: time) -> bool:
    if start <= end:
        return start <= value < end
    return value >= start or value < end


def detect_alerts(
    intervals: pd.DataFrame,
    meter_config: dict[str, dict[str, Any]],
    site_timezones: dict[str, str] | None = None,
    as_of: datetime | None = None,
) -> list[dict[str, Any]]:
    """Return idempotently keyed high-use, sustained-flow, and stale-meter alerts.

    Configuration is keyed by meter ID. Supported fields include
    ``expected_interval_minutes``, ``high_usage_litres_per_hour``,
    ``low_window_start``, ``low_window_end``, and
    ``low_usage_litres_per_hour``. No universal usage threshold is assumed.
    """
    site_timezones = site_timezones or {}
    now = as_of or datetime.now(timezone.utc)
    if now.tzinfo is None:
        raise ValueError("as_of must include a timezone.")
    alerts: list[dict[str, Any]] = []
    if intervals.empty:
        return alerts

    for meter_id, config in meter_config.items():
        meters = intervals.loc[intervals["meter_id"].eq(meter_id)].sort_values("timestamp", kind="stable")
        if meters.empty:
            continue
        site_id = str(meters.iloc[-1]["site_id"])
        timezone_name = site_timezones.get(site_id, "UTC")
        expected = int(config.get("expected_interval_minutes", 60))
        high_limit = config.get("high_usage_litres_per_hour")
        low_limit = config.get("low_usage_litres_per_hour")

        if high_limit is not None:
            for _, row in meters.loc[meters["usage_litres"].notna()].iterrows():
                duration = float(row["interval_minutes"])
                if duration <= 0:
                    continue
                rate = float(row["usage_litres"]) * 60.0 / duration
                if rate > float(high_limit):
                    reading_id = str(row["reading_id"])
                    alerts.append({
                        "alert_id": _alert_id("HIGH_USAGE", site_id, meter_id, reading_id),
                        "type": "HIGH_USAGE",
                        "severity": "HIGH" if rate >= float(high_limit) * 2 else "MEDIUM",
                        "site_id": site_id,
                        "meter_id": meter_id,
                        "detected_at": row["timestamp"],
                        "reading_ids": [reading_id],
                        "baseline": None,
                        "observed_value": {"litres_per_hour": rate},
                        "rule": {"threshold_litres_per_hour": float(high_limit)},
                        "explanation": "Usage exceeded this meter's configured hourly threshold.",
                        "recommended_investigation": "Inspect the meter and nearby fixtures for an unusual draw.",
                        "status": "OPEN",
                    })

        if low_limit is not None and config.get("low_window_start") and config.get("low_window_end"):
            start = time.fromisoformat(str(config["low_window_start"]))
            end = time.fromisoformat(str(config["low_window_end"]))
            sequence: list[dict[str, Any]] = []
            emitted_for_sequence = False
            for _, current in meters.iterrows():
                if pd.isna(current["usage_litres"]) or float(current["interval_minutes"]) <= 0:
                    sequence = []
                    emitted_for_sequence = False
                    continue
                local_time = current["timestamp"].tz_convert(timezone_name).time().replace(tzinfo=None)
                rate = float(current["usage_litres"]) * 60.0 / float(current["interval_minutes"])
                if not _in_window(local_time, start, end) or rate <= float(low_limit):
                    sequence = []
                    emitted_for_sequence = False
                    continue
                if sequence:
                    previous = sequence[-1]["row"]
                    elapsed = (current["timestamp"] - previous["timestamp"]).total_seconds() / 60.0
                    if elapsed > expected * 1.5:
                        sequence = []
                        emitted_for_sequence = False
                sequence.append({"row": current, "rate": rate})
                required = int(config.get("sustained_intervals", 3))
                if len(sequence) >= required and not emitted_for_sequence:
                    rows = [entry["row"] for entry in sequence]
                    final = rows[-1]
                    ids = [str(entry["reading_id"]) for entry in rows]
                    alerts.append({
                        "alert_id": _alert_id("SUSTAINED_LOW_PERIOD_USAGE", site_id, meter_id, *ids),
                        "type": "SUSTAINED_LOW_PERIOD_USAGE",
                        "severity": "MEDIUM",
                        "site_id": site_id,
                        "meter_id": meter_id,
                        "detected_at": final["timestamp"],
                        "reading_ids": ids,
                        "baseline": config.get("baseline"),
                        "observed_value": {"litres_per_hour": max(entry["rate"] for entry in sequence), "interval_count": len(sequence)},
                        "rule": {"threshold_litres_per_hour": float(low_limit), "window_start": str(start), "window_end": str(end)},
                        "explanation": f"Usage stayed above the configured low-usage threshold across {len(sequence)} intervals.",
                        "recommended_investigation": "Check for continuous flow during the configured low-usage period.",
                        "status": "OPEN",
                    })
                    emitted_for_sequence = True

        latest = meters.iloc[-1]
        age_minutes = (now - latest["timestamp"].to_pydatetime()).total_seconds() / 60.0
        if age_minutes > expected * 2:
            latest_id = str(latest["reading_id"])
            alerts.append({
                "alert_id": _alert_id("STALE_METER", site_id, meter_id, latest_id),
                "type": "STALE_METER",
                "severity": "LOW",
                "site_id": site_id,
                "meter_id": meter_id,
                "detected_at": now,
                "reading_ids": [latest_id],
                "baseline": None,
                "observed_value": {"minutes_since_last_reading": age_minutes},
                "rule": {"expected_interval_minutes": expected, "stale_after_intervals": 2},
                "explanation": "No reading arrived within twice this meter's configured reporting interval.",
                "recommended_investigation": "Check the meter connection and its reporting schedule.",
                "status": "OPEN",
            })
    return alerts


