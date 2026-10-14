# AquaTrace Spring Boot API

Java 21 API using Spring Boot 4.1.1 and Firebase Admin Java SDK 9.11.0. Firebase ID tokens are verified server-side. Every site-scoped operation checks active membership; writes require an allowed role. Firestore and Storage browser rules deny direct access so operations pass through the API or trusted pipeline.

## Routes

| Method | Path | Access |
| --- | --- | --- |
| GET | `/actuator/health`, `/actuator/info` | Public |
| GET | `/api/v1/me` | Authenticated Firebase ID token |
| POST / GET | `/api/v1/sites` | Authenticated; create ADMIN site or list user's sites |
| GET | `/api/v1/sites/{siteId}` | Site member |
| GET | `/api/v1/sites/{siteId}/membership` | Site member |
| POST / GET | `/api/v1/sites/{siteId}/meters` | Manager creates; members list |
| POST / GET | `/api/v1/sites/{siteId}/meters/{meterId}/readings` | Site member, meter unit/mode enforced |
| GET | `/api/v1/sites/{siteId}/daily-aggregates?from=YYYY-MM-DD&to=YYYY-MM-DD` | Site member; maximum 90 days |
| GET | `/api/v1/sites/{siteId}/alerts?status=OPEN` | Site member; latest 100 |
| PATCH | `/api/v1/sites/{siteId}/alerts/{alertId}/acknowledge` or `/resolve` | ADMIN or PROPERTY_MANAGER |
| POST / GET | `/api/v1/sites/{siteId}/ingestion-jobs` | Manager uploads CSV/JSON (10 MiB max); members list |
| GET | `/api/v1/sites/{siteId}/ingestion-jobs/{jobId}` | Site member; cross-site lookup concealed |

Meter lists return the latest reading timestamp and a freshness state (`CURRENT`, `STALE`, or `NO_DATA`) using twice the meter's expected interval. Lists are capped at 200 meters per request. Reading POST uses an `eventId`, `sourceId`, ISO-8601 timestamp, numeric `value`, `unitOfMeasure`, and `readingMode`. `(sourceId,eventId)` is a stable idempotency key; reuse with differing payload returns `409`. Read history is capped at 500 rows and supports optional `from` / `to` timestamps.

A file upload creates a Firestore `UPLOADED` job, computes SHA-256, and stores the unchanged file at `sites/{siteId}/uploads/{jobId}/{fileName}` in the configured Firebase Storage bucket. The Python batch pipeline is invoked separately and checks job/site/checksum authorization before loading outputs. Storage emulator support must be verified for the chosen Java/GCS client version before relying on local upload behavior.

## Run locally

Start Firebase emulators using Docker Compose from the repository root, or the Firebase CLI. Then run the API:

```powershell
$env:FIREBASE_PROJECT_ID = "demo-aquatrace-local"
$env:FIREBASE_STORAGE_BUCKET = "demo-aquatrace-local.firebasestorage.app"
$env:FIREBASE_EMULATOR_MODE = "true"
$env:FIREBASE_AUTH_EMULATOR_HOST = "127.0.0.1:9099"
$env:FIRESTORE_EMULATOR_HOST = "127.0.0.1:8080"
$env:STORAGE_EMULATOR_HOST = "http://127.0.0.1:9199"
$env:AQUATRACE_CORS_ALLOWED_ORIGINS = "http://localhost:3000"
mvn -f backend/pom.xml spring-boot:run
```

The API listens on `PORT` or `8081`. Never set emulator variables in production. Production uses Application Default Credentials or workload identity, with a real Firebase project and bucket.

## Verification

```powershell
mvn -f backend/pom.xml test
```

Unit tests cover active/missing/insufficient membership and valid/missing Firebase bearer tokens. Firestore and Storage emulator integration tests have not yet been run.
