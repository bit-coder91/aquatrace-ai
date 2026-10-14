"""Firestore writer with authorized job checks and retry-safe aggregate contributions."""
from __future__ import annotations

from datetime import datetime, timezone
from typing import Any

import numpy as np
import pandas as pd


class FirestoreSink:
    def __init__(self, project_id: str):
        import firebase_admin
        from firebase_admin import firestore

        if not firebase_admin._apps:
            firebase_admin.initialize_app(options={"projectId": project_id})
        self.db = firestore.client()

    def authorize_job(self, job_id: str, site_id: str, checksum: str,
                      storage_path: str | None = None) -> dict[str, Any]:
        job_ref = self.db.collection("ingestionJobs").document(job_id)
        snapshot = job_ref.get()
        if not snapshot.exists:
            raise PermissionError("Ingestion job does not exist.")
        job = snapshot.to_dict() or {}
        if job.get("siteId") != site_id or job.get("checksumSha256") != checksum:
            raise PermissionError("Job site or source checksum does not match.")
        if storage_path and job.get("storagePath") != storage_path:
            raise PermissionError("Source object path does not match the authorized job.")
        if job.get("status") not in {"UPLOADED", "QUEUED", "PROCESSING", "PARTIAL", "FAILED"}:
            raise PermissionError("Ingestion job is not eligible for processing.")
        uid = job.get("uploadedBy")
        if not uid:
            raise PermissionError("Ingestion job has no authorized uploader.")
        membership_ref = self.db.collection("sites").document(site_id).collection("members").document(uid)
        membership_snapshot = membership_ref.get()
        member = membership_snapshot.to_dict() or {}
        if not membership_snapshot.exists or member.get("status") != "ACTIVE" \
                or member.get("role") not in {"ADMIN", "PROPERTY_MANAGER"}:
            raise PermissionError("Uploader no longer has permission to process this site job.")
        return job

    def load_site_configuration(self, site_id: str) -> tuple[str, dict[str, dict[str, Any]]]:
        site_snapshot = self.db.collection("sites").document(site_id).get()
        site = site_snapshot.to_dict() or {}
        if not site_snapshot.exists:
            raise ValueError("Authorized job references a missing site.")
        timezone_name = str(site.get("timezone", "UTC"))
        configs: dict[str, dict[str, Any]] = {}
        meter_query = self.db.collection("sites").document(site_id).collection("meters").where("enabled", "==", True)
        for meter in meter_query.stream():
            data = meter.to_dict() or {}
            thresholds = data.get("thresholds") or {}
            config: dict[str, Any] = {
                "expected_interval_minutes": int(data.get("expectedIntervalMinutes", 60)),
                "meter_type": str(data.get("meterType", "UNKNOWN")),
            }
            if thresholds.get("highUsageLitresPerHour") is not None:
                config["high_usage_litres_per_hour"] = float(thresholds["highUsageLitresPerHour"])
            if thresholds.get("lowUsageLitresPerHour") is not None:
                config["low_usage_litres_per_hour"] = float(thresholds["lowUsageLitresPerHour"])
            window = thresholds.get("lowUsageWindow") or {}
            if window.get("start"):
                config["low_window_start"] = str(window["start"])
            if window.get("end"):
                config["low_window_end"] = str(window["end"])
            if thresholds.get("sustainedIntervals") is not None:
                config["sustained_intervals"] = int(thresholds["sustainedIntervals"])
            configs[meter.id] = config
        return timezone_name, configs

    def mark_job(self, job_id: str, fields: dict[str, Any]) -> None:
        self.db.collection("ingestionJobs").document(job_id).set(fields, merge=True)

    def mark_run(self, run_id: str, fields: dict[str, Any]) -> None:
        self.db.collection("pipelineRuns").document(run_id).set(fields, merge=True)

    def write_outputs(self, site_id: str, site_timezone: str, job_id: str, run_id: str,
                      readings: pd.DataFrame, meter_aggregates: pd.DataFrame,
                      alerts: list[dict[str, Any]], meter_configs: dict[str, dict[str, Any]]) -> None:
        batch = self.db.batch()
        writes = 0
        affected_dates: set[str] = set()

        def commit_if_full(force: bool = False) -> None:
            nonlocal batch, writes
            if writes and (writes >= 400 or force):
                batch.commit()
                batch = self.db.batch()
                writes = 0

        for row in readings.itertuples(index=False):
            data = {
                "eventId": str(row.event_id),
                "sourceId": str(row.source_id),
                "timestamp": row.timestamp.to_pydatetime(),
                "value": float(row.value_litres),
                "rawValue": float(row.value),
                "unitOfMeasure": "L",
                "sourceUnit": str(row.unit),
                "readingMode": str(row.reading_mode),
                "quality": "ACCEPTED",
                "ingestionJobId": job_id,
            }
            ref = self.db.collection("sites").document(site_id).collection("meters") \
                .document(str(row.meter_id)).collection("readings").document(str(row.reading_id))
            batch.set(ref, data, merge=True)
            writes += 1
            commit_if_full()

        contribution_collection = self.db.collection("sites").document(site_id) \
            .collection("dailyAggregateContributions")
        for row in meter_aggregates.itertuples(index=False):
            date_value = str(row.date)
            meter_id = str(row.meter_id)
            contribution_id = f"{date_value}_{job_id}_{meter_id}"
            ref = contribution_collection.document(contribution_id)
            batch.set(ref, {
                "siteId": site_id,
                "date": date_value,
                "meterId": meter_id,
                "meterType": str(meter_configs.get(meter_id, {}).get("meter_type", "UNKNOWN")),
                "jobId": job_id,
                "totalUsageLitres": float(row.total_usage_litres),
                "intervalCount": int(row.interval_count),
                "gapIntervalCount": int(row.gap_interval_count),
                "updatedAt": datetime.now(timezone.utc),
            }, merge=True)
            affected_dates.add(date_value)
            writes += 1
            commit_if_full()

        for alert in alerts:
            data = _firestore_values(alert)
            data["pipelineRunId"] = run_id
            ref = self.db.collection("sites").document(site_id).collection("alerts").document(alert["alert_id"])
            if ref.get().exists:
                # Keep a manager's acknowledgement/resolution when deterministic
                # alert IDs recur on a retry or a later run.
                data.pop("status", None)
            batch.set(ref, data, merge=True)
            writes += 1
            commit_if_full()
        commit_if_full(force=True)

        # Recompute each touched day from indexed per-job/per-meter contributions.
        # Stable contribution IDs make retries and corrected re-runs idempotent.
        for date_value in affected_dates:
            contributions = list(self.db.collection_group("dailyAggregateContributions")
                                  .where("siteId", "==", site_id)
                                  .where("date", "==", date_value).stream())
            rows = [snapshot.to_dict() or {} for snapshot in contributions]
            totals = _site_usage_totals(rows)
            aggregate = self.db.collection("sites").document(site_id) \
                .collection("dailyAggregates").document(date_value)
            aggregate.set({
                "date": date_value,
                "timezone": site_timezone,
                **totals,
                "intervalCount": sum(int(item.get("intervalCount", 0)) for item in rows),
                "meterCount": len({item.get("meterId") for item in rows if item.get("meterId")}),
                "gapIntervalCount": sum(int(item.get("gapIntervalCount", 0)) for item in rows),
                "updatedAt": datetime.now(timezone.utc),
                "pipelineRunId": run_id,
            }, merge=True)


def _firestore_values(value: Any) -> Any:
    if isinstance(value, dict):
        return {_camel_case(str(key)): _firestore_values(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [_firestore_values(item) for item in value]
    if isinstance(value, pd.Timestamp):
        return value.to_pydatetime()
    if isinstance(value, np.generic):
        return value.item()
    if value is None:
        return None
    try:
        if pd.isna(value):
            return None
    except (TypeError, ValueError):
        pass
    return value


def _camel_case(value: str) -> str:
    parts = value.split("_")
    return parts[0] + "".join(part[:1].upper() + part[1:] for part in parts[1:])


def _site_usage_totals(contributions: list[dict[str, Any]]) -> dict[str, float | None]:
    main = [row for row in contributions if row.get("meterType") == "MAIN"]
    sub = [row for row in contributions if row.get("meterType") == "SUBMETER"]
    main_usage = sum(float(row.get("totalUsageLitres", 0)) for row in main)
    sub_usage = sum(float(row.get("totalUsageLitres", 0)) for row in sub)
    # Main and sub-meters measure overlapping water; never add them together.
    total = main_usage if main else sub_usage if sub else sum(float(row.get("totalUsageLitres", 0)) for row in contributions)
    return {
        "totalUsageLitres": total,
        "mainMeterUsageLitres": main_usage if main else None,
        "submeterTotalUsageLitres": sub_usage if sub else None,
        "reconciliationDifferenceLitres": main_usage - sub_usage if main and sub else None,
    }

