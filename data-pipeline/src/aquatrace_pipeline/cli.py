"""Command-line batch entrypoint used locally and by GitHub Actions."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from .alerts import detect_alerts
from .firestore_sink import FirestoreSink
from .pipeline import aggregate_daily_by_meter, derive_usage_intervals, read_source, validate_and_clean


def execute_job(input_path: Path, site_id: str, job_id: str, project_id: str,
                storage_path: str | None = None) -> dict[str, Any]:
    started = time.perf_counter()
    now = datetime.now(timezone.utc)
    checksum = hashlib.sha256(input_path.read_bytes()).hexdigest()
    run_id = hashlib.sha256(f"{job_id}|{checksum}".encode("utf-8")).hexdigest()
    sink = FirestoreSink(project_id)
    sink.authorize_job(job_id, site_id, checksum, storage_path)
    site_timezone, meter_configs = sink.load_site_configuration(site_id)
    sink.mark_job(job_id, {"siteId": site_id, "status": "PROCESSING", "startedAt": now, "updatedAt": now})
    sink.mark_run(run_id, {"jobId": job_id, "siteId": site_id, "status": "PROCESSING", "startedAt": now})

    try:
        source = read_source(input_path)
        source_sites = source["site_id"].astype("string").str.strip()
        if source_sites.isna().any() or source_sites.ne(site_id).any():
            raise ValueError("Input contains missing or unauthorized site IDs.")

        cleaned = validate_and_clean(source, known_meter_ids=set(meter_configs))
        expected = {meter_id: int(config.get("expected_interval_minutes", 60))
                    for meter_id, config in meter_configs.items()}
        intervals = derive_usage_intervals(cleaned.accepted, expected)
        meter_aggregates = aggregate_daily_by_meter(intervals, {site_id: site_timezone})
        alerts = detect_alerts(intervals, meter_configs, {site_id: site_timezone})
        sink.write_outputs(site_id, site_timezone, job_id, run_id, cleaned.accepted,
                           meter_aggregates, alerts, meter_configs)

        elapsed = max(time.perf_counter() - started, 0.000001)
        accepted = len(cleaned.accepted.index)
        rejected = len(cleaned.rejected.index)
        status = "PARTIAL" if rejected else "SUCCEEDED"
        errors = [
            {"sourceRow": int(row.source_row), "reason": str(row.rejection_reason)}
            for row in cleaned.rejected.head(100).itertuples(index=False)
        ]
        finished_at = datetime.now(timezone.utc)
        summary = {
            "siteId": site_id,
            "status": status,
            "finishedAt": finished_at,
            "inputRows": len(source.index),
            "acceptedRows": accepted,
            "rejectedRows": rejected,
            "duplicateRows": cleaned.duplicate_count,
            "conflictRows": cleaned.conflict_count,
            "processingDurationMs": round(elapsed * 1000),
            "rowsPerSecond": round(len(source.index) / elapsed, 2),
            "generatedAlerts": len(alerts),
            "errorSummary": errors,
        }
        sink.mark_job(job_id, summary)
        sink.mark_run(run_id, {
            "jobId": job_id,
            "siteId": site_id,
            "status": status,
            "startedAt": now,
            "finishedAt": finished_at,
            "inputRows": len(source.index),
            "acceptedRows": accepted,
            "rejectedRows": rejected,
            "duplicates": cleaned.duplicate_count,
            "durationMs": summary["processingDurationMs"],
            "rowsPerSecond": summary["rowsPerSecond"],
            "generatedAlerts": len(alerts),
            "errorSummary": errors,
        })
        return {"jobId": job_id, "runId": run_id, **summary}
    except Exception as exception:
        failed_at = datetime.now(timezone.utc)
        safe_reason = f"{type(exception).__name__}: {exception}"[:300]
        sink.mark_job(job_id, {"siteId": site_id, "status": "FAILED", "finishedAt": failed_at,
                               "errorSummary": [{"reason": safe_reason}]})
        sink.mark_run(run_id, {"jobId": job_id, "siteId": site_id, "status": "FAILED",
                               "finishedAt": failed_at, "errorSummary": [{"reason": safe_reason}]})
        raise


def main() -> None:
    parser = argparse.ArgumentParser(description="Process an authorized AquaTrace ingestion job.")
    parser.add_argument("--input", required=True, type=Path, help="Downloaded source CSV/JSON file")
    parser.add_argument("--site-id", required=True, help="Site ID already authorized on the ingestion job")
    parser.add_argument("--job-id", required=True, help="Stable ingestion job ID for safe retries")
    parser.add_argument("--project-id", default=None, help="Firebase project (defaults to FIREBASE_PROJECT_ID)")
    parser.add_argument("--storage-path", default=None, help="Expected original Firebase Storage object path")
    args = parser.parse_args()

    project_id = args.project_id or os.environ.get("FIREBASE_PROJECT_ID")
    if not project_id:
        parser.error("--project-id or FIREBASE_PROJECT_ID is required")
    result = execute_job(args.input, args.site_id, args.job_id, project_id, args.storage_path)
    print(json.dumps(result, default=str, sort_keys=True))


if __name__ == "__main__":
    main()
