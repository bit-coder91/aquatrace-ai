# AquaTrace data pipeline

Python/Pandas batch processing for meter CSV/JSON data. The pipeline distinguishes original uploads and ingestion metadata (raw), accepted normalized events (cleaned), per-job daily contributions and site/day rollups (curated), and quality metrics/alerts (analytical outputs).

## Stages

1. Read CSV, JSON array, or JSON Lines input with a 10 MiB file limit and 100,000 row limit.
2. Validate required columns, IDs, UTC timestamps, units, modes, and non-negative numeric readings. Preserve rejected row numbers and reasons.
3. Normalize IDs, units (`M3` to litres), and timestamps. Deduplicate identical event keys; quarantine conflicting reuse.
4. Create deterministic reading IDs from site, meter, source, and event ID.
5. Derive cumulative usage intervals. First readings establish a baseline; counter drops are flagged as resets without inventing usage; long intervals preserve measured usage and are flagged as gaps; do not interpolate.
6. Aggregate by site-local day and meter. Configurable rules create high-use, sustained low-period-flow, and stale-meter alerts.
7. Load accepted readings and alert/job/run metadata to Firestore. Per-job/per-meter contribution documents make retries idempotent; aggregates are recomputed from the indexed site/date contributions.

The API upload endpoint stores the unchanged original in Firebase Storage, then creates an `UPLOADED` Firestore job with a checksum. The batch CLI reads a local copy and verifies the checksum, job/site, and uploader's current `ADMIN` or `PROPERTY_MANAGER` membership before loading. It will not create jobs from arbitrary CLI input. Pipeline execution remains an explicit batch step; the web container does not run a permanent worker.

## Install and tests

```powershell
python -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -e ".[test]"
python -m pytest
```

Tests cover malformed schema and timestamps, negative values, duplicates/conflicts, unit conversion, resets, out-of-order rows, missing intervals, local-day bucketing, deterministic alerts, and file-size/type limits.

## Generate sample data

From the repository root:

```powershell
python scripts/generate_demo_data.py --site-id <your-site-id> --submeter-ids <meter-id-1>,<meter-id-2>,<meter-id-3> --main-meter-id <main-meter-id> --output data-pipeline/sample-data/demo_readings.csv --duplicate-sample
```

The deterministic seven-day sample contains three unit meters and a building meter, a missing interval, sustained overnight flow on one meter, and an optional exact duplicate event. Create the meters in the app first, then use their displayed document IDs and the actual site ID. The committed sample uses placeholder IDs (`unit-101`, `unit-102`, `unit-103`, and `building-main`) and site `demo-building`; it must be regenerated for your site before processing.

## Run an authorized batch job

Install Firebase Admin dependencies and use a source file whose SHA-256 matches an already uploaded job. Configure `FIREBASE_PROJECT_ID`, `FIREBASE_AUTH_EMULATOR_HOST`, `FIRESTORE_EMULATOR_HOST` for local emulators or Google Application Default Credentials for a trusted production batch runner. Then:

```powershell
python -m aquatrace_pipeline.cli --input .\sample.csv --site-id <site-id> --job-id <job-id>
```

For GitHub Actions, configure repository variable `FIREBASE_PROJECT_ID`, optional bucket variable `FIREBASE_STORAGE_BUCKET`, and secret `FIREBASE_SERVICE_ACCOUNT_JSON`. The manual workflow takes a site ID and existing job ID, downloads the original from Storage, checks its checksum and the uploader's site role, then processes it. Do not store service-account JSON in the repository or pass arbitrary site/file pairs as write authorization.

## Storage emulator note

The Java API uploads through Firebase Admin's Storage client and the Python pipeline loads Firestore records. `STORAGE_EMULATOR_HOST` is wired in Docker Compose, but server-side Storage emulator behavior has not been exercised here. Use the emulator only for local work and never fall back to a live bucket. The Auth and Firestore emulator connections use their documented environment variables.

## Quality/performance

Each run records input, accepted, rejected, duplicate and conflicting counts; processing duration; throughput; generated alerts; and up to 100 row rejection details. No benchmark result is claimed. Use reproducible 10k/100k/1M-row datasets before reporting throughput.
