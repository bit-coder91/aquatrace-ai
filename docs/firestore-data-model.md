# Firestore collection and access reference

## Modeling principles

- Scope site-owned resources beneath `sites/{siteId}` so ownership is visible in document paths.
- Store user profile data at `users/{uid}` and memberships under each site.
- Use deterministic IDs for ingestion outputs to make retries idempotent.
- Keep documents bounded; do not append readings, alerts, or errors to unbounded arrays.
- Query time-series readings by meter and timestamp. Use precomputed aggregates for dashboard ranges.
- Backend and pipeline authorization is mandatory because Firebase Admin SDK bypasses Firestore Security Rules.

## Collections and query/access plan

| Path | Purpose | Main query patterns | Access and retention |
| --- | --- | --- | --- |
| `users/{uid}` | User profile/preferences | Direct lookup by authenticated UID | User reads/updates permitted fields; retain while account exists. |
| `sites/{siteId}` | Building configuration | Direct lookup; sites discovered through memberships | Site admins manage configuration; retain while site exists. |
| `sites/{siteId}/members/{uid}` | Site role and status | Direct membership lookup for each authorization check | Site admins manage; active membership controls access. |
| `sites/{siteId}/meters/{meterId}` | Meter details and thresholds | List per site; filter enabled/type | Managers manage; viewers read. Retain disabled metadata for history. |
| `sites/{siteId}/meters/{meterId}/readings/{readingId}` | Validated meter events | Timestamp range within one meter; optional quality filter | API/pipeline write after authorization; read through authorized API. Retention must account for time-series growth. |
| `sites/{siteId}/dailyAggregates/{yyyy-MM-dd}` | Curated daily metrics | Exact day or date range | Pipeline writes; site members read. Recomputable from readings. |
| `sites/{siteId}/dailyAggregateContributions/{date_jobId_meterId}` | Idempotent per-job/per-meter daily rollup contribution | Collection-group query filtered by `siteId` and `date` to recompute one day | Pipeline writes; API does not expose directly. One bounded document per job, day, and meter; retention/compaction may be needed at higher ingestion frequency. |
| `sites/{siteId}/alerts/{alertId}` | Rules-based investigations | Status, severity, and detection time within site | Pipeline creates; managers acknowledge/resolve. Retain for history. |
| `ingestionJobs/{jobId}` | Upload metadata and status | Site plus creation time; direct job lookup | Create after membership check; expose only for authorized site members. Bounded summaries only. |
| `pipelineRuns/{runId}` | Execution metadata and quality counts | Job ID or site/time through job relationship | Pipeline service writes; authorized members read through API. Retain enough history to diagnose runs. |

## Example documents

### `users/u_123`

```json
{
  "displayName": "A. Rao",
  "email": "user@example.com",
  "createdAt": "Firestore timestamp",
  "updatedAt": "Firestore timestamp"
}
```

### `sites/site_lakeview` and `sites/site_lakeview/members/u_123`

```json
{
  "name": "Lakeview Apartments",
  "timezone": "Asia/Kolkata",
  "createdBy": "u_123",
  "createdAt": "Firestore timestamp"
}
```

```json
{
  "role": "PROPERTY_MANAGER",
  "status": "ACTIVE",
  "joinedAt": "Firestore timestamp"
}
```

### `sites/site_lakeview/meters/meter_4a`

```json
{
  "label": "Unit 4A",
  "meterType": "SUBMETER",
  "unitId": "unit_4a",
  "unitOfMeasure": "L",
  "readingMode": "CUMULATIVE",
  "expectedIntervalMinutes": 60,
  "installationDate": "2026-01-01",
  "enabled": true,
  "thresholds": {
    "highUsageLitresPerHour": 250,
    "lowUsageWindow": { "start": "00:00", "end": "05:00" },
    "lowUsageLitresPerHour": 20
  }
}
```

Thresholds are configurable examples, not universal definitions of normal use. Detection records the configured threshold and baseline used.

### `sites/site_lakeview/meters/meter_4a/readings/{readingId}`

```json
{
  "eventId": "sim-0000042",
  "sourceId": "demo-generator-v1",
  "timestamp": "Firestore timestamp",
  "value": 1254.2,
  "unitOfMeasure": "L",
  "readingMode": "CUMULATIVE",
  "quality": "ACCEPTED",
  "ingestionJobId": "job_abc"
}
```

Use a stable source event ID as document ID where available. Otherwise derive it from normalized site, meter, timestamp, source, and value. Report a reused event ID with different content as a conflict instead of silently accepting it.

### `sites/site_lakeview/dailyAggregates/2026-10-13`

```json
{
  "date": "2026-10-13",
  "timezone": "Asia/Kolkata",
  "totalUsageLitres": 8240.5,
  "mainMeterUsageLitres": 8240.5,
  "submeterTotalUsageLitres": 8012.4,
  "reconciliationDifferenceLitres": 228.1,
  "meterCount": 20,
  "intervalCount": 480,
  "gapIntervalCount": 3,
  "updatedAt": "Firestore timestamp",
  "pipelineRunId": "run_xyz"
}
```

When a main meter and submeters both exist, `totalUsageLitres` uses the main meter so overlapping measurements are not double-counted. The submeter sum and `reconciliationDifferenceLitres` remain available to inspect coverage and possible discrepancies.

### `sites/site_lakeview/alerts/alert_7f`

```json
{
  "type": "SUSTAINED_LOW_PERIOD_USAGE",
  "severity": "MEDIUM",
  "meterId": "meter_4a",
  "detectedAt": "Firestore timestamp",
  "readingIds": ["reading_a", "reading_b"],
  "baseline": { "window": "previous 14 comparable days", "medianLitresPerHour": 4.2 },
  "observedValue": { "litresPerHour": 31.0, "intervalCount": 4 },
  "rule": { "thresholdLitresPerHour": 20, "lowUsageWindow": "00:00-05:00" },
  "explanation": "Usage stayed above the configured low-usage threshold across four intervals.",
  "recommendedInvestigation": "Check the unit meter and fixtures for continuous flow.",
  "status": "OPEN",
  "acknowledgedBy": null,
  "resolvedAt": null
}
```

The alert describes a suspected issue; it does not claim that a leak has been physically confirmed.

### `ingestionJobs/job_abc` and `pipelineRuns/run_xyz`

```json
{
  "siteId": "site_lakeview",
  "uploadedBy": "u_123",
  "storagePath": "sites/site_lakeview/uploads/job_abc/original.csv",
  "checksumSha256": "computed-at-ingestion",
  "status": "SUCCEEDED",
  "createdAt": "Firestore timestamp",
  "inputRows": 1200,
  "acceptedRows": 1187,
  "rejectedRows": 8,
  "duplicateRows": 5,
  "errorSummary": []
}
```

```json
{
  "jobId": "job_abc",
  "siteId": "site_lakeview",
  "status": "SUCCEEDED",
  "startedAt": "Firestore timestamp",
  "finishedAt": "Firestore timestamp",
  "inputRows": 1200,
  "acceptedRows": 1187,
  "rejectedRows": 8,
  "duplicates": 5,
  "durationMs": 1832,
  "errorSummary": []
}
```

## Index and query plan

- List a user's sites through their membership documents, not a global site scan.
- Fetch meter history with timestamp filters/order within one meter's readings subcollection.
- Fetch daily aggregates by date document ID or date range.
- Fetch alerts by status and detection time within one site's alert collection.
- Fetch jobs with `siteId` and creation time; every result still requires membership authorization.
- Fetch pipeline runs by job ID and start time; avoid global scans.

Compound indexes are query-specific. Add indexes to `firestore.indexes.json` when the corresponding application query is implemented and exercised. Do not assume an index before the query exists.

## Security and retention

- Treat browser-submitted IDs as untrusted. Resolve authenticated UID, check active membership, then enforce the role required by the operation.
- Verify that meters, readings, jobs, and Storage object paths belong to the authorized site.
- Keep browser rules narrow; privileged writes go through the API or pipeline.
- Never put Firebase service-account JSON in this repository or frontend environment variables.
- Original uploads and readings are the high-growth data. Define an archival/deletion policy before production; daily aggregates can be regenerated if source readings remain.
- Firestore is not an ad hoc warehouse. Precomputed aggregates keep dashboard queries bounded and predictable.

