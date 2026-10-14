"""Download an authorized original from Firebase Storage and process it once."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import tempfile
from pathlib import Path

from google.cloud import firestore, storage

from .cli import execute_job


def run(site_id: str, job_id: str, project_id: str) -> dict[str, object]:
    db = firestore.Client(project=project_id)
    snapshot = db.collection("ingestionJobs").document(job_id).get()
    if not snapshot.exists:
        raise PermissionError("Ingestion job does not exist.")
    job = snapshot.to_dict() or {}
    expected_path_prefix = f"sites/{site_id}/uploads/{job_id}/"
    storage_path = str(job.get("storagePath", ""))
    if job.get("siteId") != site_id or not storage_path.startswith(expected_path_prefix):
        raise PermissionError("Job site or Storage path does not match the authorized site/job.")
    if job.get("status") not in {"UPLOADED", "QUEUED", "PROCESSING", "PARTIAL", "FAILED"}:
        raise PermissionError("Ingestion job is not eligible for processing.")
    uploader = str(job.get("uploadedBy", ""))
    if not uploader:
        raise PermissionError("Ingestion job has no authorized uploader.")
    membership = db.collection("sites").document(site_id).collection("members").document(uploader).get()
    member = membership.to_dict() or {}
    if not membership.exists or member.get("status") != "ACTIVE" or member.get("role") not in {"ADMIN", "PROPERTY_MANAGER"}:
        raise PermissionError("Uploader no longer has permission to process this site job.")
    expected_checksum = str(job.get("checksumSha256", ""))
    if len(expected_checksum) != 64:
        raise ValueError("Ingestion job has no valid source checksum.")

    bucket_name = os.environ.get("FIREBASE_STORAGE_BUCKET") or f"{project_id}.firebasestorage.app"
    blob = storage.Client(project=project_id).bucket(bucket_name).blob(storage_path)
    suffix = Path(storage_path).suffix.lower()
    if suffix not in {".csv", ".json", ".jsonl", ".ndjson"}:
        raise ValueError("The stored source file type is unsupported.")
    with tempfile.TemporaryDirectory(prefix="aquatrace-pipeline-") as directory:
        local_path = Path(directory) / f"source{suffix}"
        blob.download_to_filename(str(local_path), checksum="auto")
        checksum = hashlib.sha256(local_path.read_bytes()).hexdigest()
        if checksum != expected_checksum:
            raise PermissionError("Downloaded original does not match the authorized job checksum.")
        return execute_job(local_path, site_id, job_id, project_id, storage_path)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--site-id", required=True)
    parser.add_argument("--job-id", required=True)
    parser.add_argument("--project-id", default=os.environ.get("FIREBASE_PROJECT_ID"))
    args = parser.parse_args()
    if not args.project_id:
        parser.error("--project-id or FIREBASE_PROJECT_ID is required")
    result = run(args.site_id, args.job_id, args.project_id)
    print(json.dumps(result, default=str, sort_keys=True))


if __name__ == "__main__":
    main()
