from __future__ import annotations

import pandas as pd
import pytest

from aquatrace_pipeline import aggregate_daily, aggregate_daily_by_meter, derive_usage_intervals, validate_and_clean
from aquatrace_pipeline.alerts import detect_alerts
from aquatrace_pipeline.firestore_sink import _firestore_values, _site_usage_totals
from aquatrace_pipeline import cli
from aquatrace_pipeline.pipeline import SchemaError, read_source


def source_rows(rows: list[dict]) -> pd.DataFrame:
    return pd.DataFrame(rows, columns=[
        "event_id", "site_id", "meter_id", "timestamp", "value", "unit", "reading_mode", "source_id"
    ])


def row(event: str, timestamp: str, value: float, *, meter: str = "m-1", unit: str = "L",
        mode: str = "CUMULATIVE", site: str = "s-1", source: str = "sim") -> dict:
    return {"event_id": event, "site_id": site, "meter_id": meter, "timestamp": timestamp,
            "value": value, "unit": unit, "reading_mode": mode, "source_id": source}


def test_schema_rejects_missing_required_columns():
    with pytest.raises(SchemaError, match="Missing required columns"):
        validate_and_clean(pd.DataFrame({"event_id": ["x"]}))


def test_unknown_meter_ids_are_rejected_when_site_inventory_is_supplied():
    result = validate_and_clean(source_rows([
        row("known", "2026-10-13T00:00:00Z", 1, meter="meter-1"),
        row("unknown", "2026-10-13T00:00:00Z", 1, meter="meter-x"),
    ]), known_meter_ids={"meter-1"})

    assert len(result.accepted) == 1
    assert result.rejected.iloc[0]["rejection_reason"] == "UNKNOWN_METER_ID"


def test_site_total_uses_main_meter_without_double_counting_submeters():
    totals = _site_usage_totals([
        {"meterType": "MAIN", "totalUsageLitres": 1000},
        {"meterType": "SUBMETER", "totalUsageLitres": 420},
        {"meterType": "SUBMETER", "totalUsageLitres": 510},
    ])
    assert totals == {
        "totalUsageLitres": 1000,
        "mainMeterUsageLitres": 1000,
        "submeterTotalUsageLitres": 930,
        "reconciliationDifferenceLitres": 70,
    }


def test_alert_documents_convert_field_names_to_camel_case():
    assert _firestore_values({"site_id": "site-a", "rule": {"threshold_litres": 10}}) == {
        "siteId": "site-a", "rule": {"thresholdLitres": 10}
    }


def test_duplicate_is_idempotent_but_conflicting_event_is_quarantined():
    same = row("e1", "2026-10-13T00:00:00Z", 1)
    conflicting = row("e1", "2026-10-13T00:00:00Z", 2)
    result = validate_and_clean(source_rows([same, same, conflicting]))

    assert len(result.accepted) == 1
    assert result.duplicate_count == 1
    assert result.conflict_count == 1
    assert result.rejected.iloc[0]["rejection_reason"] == "EVENT_ID_CONFLICT"
    assert result.accepted.iloc[0]["reading_id"]


def test_invalid_rows_keep_explanations_and_cubic_metres_normalize_to_litres():
    result = validate_and_clean(source_rows([
        row("e1", "2026-10-13T00:00:00Z", 1.25, unit="M3"),
        row("e2", "not-a-time", -1),
    ]))

    assert result.accepted.iloc[0]["value_litres"] == 1250
    assert "INVALID_TIMESTAMP" in result.rejected.iloc[0]["rejection_reason"]
    assert "NEGATIVE_READING" in result.rejected.iloc[0]["rejection_reason"]


def test_counter_resets_and_out_of_order_rows_are_flagged_without_negative_usage():
    rows = [
        row("e1", "2026-10-13T00:00:00Z", 100),
        row("e3", "2026-10-13T02:00:00Z", 120),
        row("e2", "2026-10-13T01:00:00Z", 110),
        row("e4", "2026-10-13T03:00:00Z", 2),
    ]
    cleaned = validate_and_clean(source_rows(rows))
    intervals = derive_usage_intervals(cleaned.accepted, {"m-1": 60})

    assert intervals.iloc[0]["interval_status"] == "BASELINE"
    assert intervals.iloc[1]["usage_litres"] == 10
    assert intervals.iloc[2]["usage_litres"] == 10
    assert intervals.iloc[3]["interval_status"] == "COUNTER_RESET"
    assert pd.isna(intervals.iloc[3]["usage_litres"])
    original_order_later_row = intervals.loc[intervals["event_id"].eq("e2")].iloc[0]
    assert original_order_later_row["was_out_of_order"]


def test_long_gap_is_not_interpolated_and_is_counted_in_daily_quality():
    cleaned = validate_and_clean(source_rows([
        row("e1", "2026-10-13T00:00:00Z", 10),
        row("e2", "2026-10-13T04:00:00Z", 50),
    ]))
    intervals = derive_usage_intervals(cleaned.accepted, {"m-1": 60})
    daily = aggregate_daily(intervals)

    assert intervals.iloc[1]["interval_status"] == "MISSING_INTERVALS"
    assert intervals.iloc[1]["usage_litres"] == 40
    assert daily.iloc[0]["gap_interval_count"] == 1
    assert daily.iloc[0]["total_usage_litres"] == 40


def test_interval_readings_use_the_configured_interval_duration():
    cleaned = validate_and_clean(source_rows([
        row("e1", "2026-10-13T01:00:00Z", 3, mode="INTERVAL"),
    ]))
    intervals = derive_usage_intervals(cleaned.accepted, {"m-1": 30})

    assert intervals.iloc[0]["usage_litres"] == 3
    assert intervals.iloc[0]["interval_minutes"] == 30
    assert intervals.iloc[0]["interval_status"] == "OBSERVED_INTERVAL"


def test_daily_aggregate_uses_the_site_timezone():
    cleaned = validate_and_clean(source_rows([
        row("e1", "2026-10-13T18:30:00Z", 3, mode="INTERVAL"),
    ]))
    intervals = derive_usage_intervals(cleaned.accepted, {"m-1": 60})
    daily = aggregate_daily(intervals, {"s-1": "Asia/Kolkata"})

    assert daily.iloc[0]["date"] == "2026-10-14"
    assert daily.iloc[0]["total_usage_litres"] == 3


def test_rule_alerts_use_configured_thresholds_and_stable_keys():
    cleaned = validate_and_clean(source_rows([
        row("e1", "2026-10-13T00:00:00Z", 2, mode="INTERVAL"),
        row("e2", "2026-10-13T01:00:00Z", 3, mode="INTERVAL"),
        row("e3", "2026-10-13T02:00:00Z", 4, mode="INTERVAL"),
        row("e4", "2026-10-13T03:00:00Z", 5, mode="INTERVAL"),
        row("e5", "2026-10-13T04:00:00Z", 6, mode="INTERVAL"),
        row("e6", "2026-10-13T05:00:00Z", 7, mode="INTERVAL"),
    ]))
    intervals = derive_usage_intervals(cleaned.accepted, {"m-1": 60})
    config = {"m-1": {
        "expected_interval_minutes": 60,
        "high_usage_litres_per_hour": 2,
        "low_usage_litres_per_hour": 1,
        "low_window_start": "00:00",
        "low_window_end": "05:00",
        "sustained_intervals": 3,
    }}
    alerts = detect_alerts(intervals, config, {"s-1": "UTC"}, pd.Timestamp("2026-10-13T02:30:00Z").to_pydatetime())

    assert any(alert["type"] == "HIGH_USAGE" for alert in alerts)
    sustained = [alert for alert in alerts if alert["type"] == "SUSTAINED_LOW_PERIOD_USAGE"]
    assert len(sustained) == 1
    assert len(sustained[0]["reading_ids"]) == 3
    assert len({alert["alert_id"] for alert in alerts}) == len(alerts)


def test_sustained_flow_requires_consecutive_above_threshold_intervals():
    cleaned = validate_and_clean(source_rows([
        row("e1", "2026-10-13T00:00:00Z", 2, mode="INTERVAL"),
        row("e2", "2026-10-13T01:00:00Z", 0.5, mode="INTERVAL"),
        row("e3", "2026-10-13T02:00:00Z", 2, mode="INTERVAL"),
        row("e4", "2026-10-13T03:00:00Z", 2, mode="INTERVAL"),
        row("e5", "2026-10-13T04:00:00Z", 2, mode="INTERVAL"),
    ]))
    intervals = derive_usage_intervals(cleaned.accepted, {"m-1": 60})
    config = {"m-1": {"expected_interval_minutes": 60, "low_usage_litres_per_hour": 1,
                       "low_window_start": "00:00", "low_window_end": "05:00", "sustained_intervals": 3}}

    alerts = detect_alerts(intervals, config, {"s-1": "UTC"}, pd.Timestamp("2026-10-13T05:00:00Z").to_pydatetime())

    sustained = [alert for alert in alerts if alert["type"] == "SUSTAINED_LOW_PERIOD_USAGE"]
    assert len(sustained) == 1
    assert sustained[0]["reading_ids"] == list(cleaned.accepted.loc[cleaned.accepted["event_id"].isin(["e3", "e4", "e5"]), "reading_id"])


def test_read_source_enforces_file_size_and_supported_extensions(tmp_path):
    path = tmp_path / "readings.csv"
    path.write_text("a,b\n1,2\n", encoding="utf-8")
    with pytest.raises(ValueError, match="limit"):
        read_source(path, max_bytes=2)
    unsupported = tmp_path / "readings.txt"
    unsupported.write_text("data", encoding="utf-8")
    with pytest.raises(ValueError, match="must use"):
        read_source(unsupported)


def test_cli_records_partial_status_and_rejection_counts(tmp_path, monkeypatch):
    path = tmp_path / "readings.csv"
    pd.DataFrame([
        row("good", "2026-10-13T00:00:00Z", 10, meter="m-1"),
        row("bad", "not-a-timestamp", -1, meter="m-1"),
    ]).to_csv(path, index=False)

    class FakeSink:
        instances = []
        def __init__(self, project_id):
            self.jobs = []
            self.__class__.instances.append(self)
        def authorize_job(self, *args): return {}
        def load_site_configuration(self, site_id): return "UTC", {"m-1": {"expected_interval_minutes": 60}}
        def mark_job(self, job_id, fields): self.jobs.append(fields)
        def mark_run(self, run_id, fields): pass
        def write_outputs(self, *args): pass

    monkeypatch.setattr(cli, "FirestoreSink", FakeSink)
    result = cli.execute_job(path, "s-1", "job-1", "demo-aquatrace-local")

    assert result["status"] == "PARTIAL"
    assert result["acceptedRows"] == 1
    assert result["rejectedRows"] == 1
    assert result["errorSummary"][0]["reason"] == "INVALID_TIMESTAMP;NEGATIVE_READING"
    assert FakeSink.instances[-1].jobs[-1]["status"] == "PARTIAL"


def test_cli_marks_job_failed_when_load_fails(tmp_path, monkeypatch):
    path = tmp_path / "readings.csv"
    pd.DataFrame([row("good", "2026-10-13T00:00:00Z", 10)]).to_csv(path, index=False)

    class FakeSink:
        instances = []
        def __init__(self, project_id):
            self.jobs = []
            self.__class__.instances.append(self)
        def authorize_job(self, *args): return {}
        def load_site_configuration(self, site_id): return "UTC", {"m-1": {"expected_interval_minutes": 60}}
        def mark_job(self, job_id, fields): self.jobs.append(fields)
        def mark_run(self, run_id, fields): pass
        def write_outputs(self, *args): raise RuntimeError("simulated Firestore load failure")

    monkeypatch.setattr(cli, "FirestoreSink", FakeSink)
    with pytest.raises(RuntimeError, match="simulated Firestore"):
        cli.execute_job(path, "s-1", "job-2", "demo-aquatrace-local")
    assert FakeSink.instances[-1].jobs[-1]["status"] == "FAILED"

