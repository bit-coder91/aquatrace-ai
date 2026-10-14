# AquaTrace

Smart Water Usage Analytics & Leak Intelligence Platform. AquaTrace helps building teams inspect meter usage, upload original readings, validate data, and investigate rules-based alerts. Software-generated alerts are signals to investigate; they never confirm a physical leak.

## Current implementation

- **Spring Boot API:** Firebase ID-token verification, site membership and roles, site and meter registration, bounded Firestore access, original file upload to Firebase Storage, ingestion job history, and idempotent simulated reading submission/history.
- **Python data pipeline:** CSV/JSON validation, normalization, deduplication/conflict detection, cumulative-to-interval conversion, site-local daily meter aggregates, quality summaries, configurable anomaly rules, and deterministic Firestore output keys.
- **Next.js interface:** Firebase email/password sign-in, site creation/listing, meter setup and optional thresholds, usage chart, open-alert acknowledgement, file upload, and ingestion quality/history. It uses React Hook Form/Zod, Recharts, Lucide, Tailwind, and a local shadcn-style button primitive.
- **Developer infrastructure:** Firebase emulator configuration with deny-by-default client rules, Dockerfiles, Docker Compose, and GitHub Actions checks/manual pipeline workflow.

The dashboard shows daily site totals, active/current meter counts, open rules-based signals, and the latest import acceptance ratio. User profile settings and a self-serve demo seed flow are not implemented. Uploading creates an `UPLOADED` job; it does not automatically execute the batch pipeline. The authorized GitHub Actions workflow downloads that job's original Storage object, verifies its checksum and uploader role, then processes it. Firebase Storage emulator access from the Admin SDK and end-to-end emulator execution still need a local or CI verification pass.

## Repository layout

```text
frontend/                  Next.js App Router, TypeScript, Tailwind CSS, Firebase Auth
backend/                   Java 21, Spring Boot, Firebase Admin SDK
data-pipeline/              Python, Pandas, Firebase Admin SDK, pytest
scripts/                    Firebase emulator container helper
.github/workflows/          validation and manual pipeline execution
docs/                       architecture, Firestore model, local development
firebase.json               Auth, Firestore, Storage emulator configuration
firestore.rules              deny-by-default browser Firestore access
storage.rules                deny-by-default browser Storage access
docker-compose.yml           local application and Firebase emulator services
```

## Documentation

- [Architecture and MVP plan](docs/architecture.md)
- [Firestore data model](docs/firestore-data-model.md)
- [Local development](docs/local-development.md)
- [Spring API](backend/README.md)
- [Data pipeline](data-pipeline/README.md)

## Local setup

Requirements: Java 21, Maven 3.9+, Python 3.11+, Node.js 20.9+, pnpm, Docker with Compose, and (for non-container workflows) Firebase CLI.

Copy `.env.example` to `.env` for local placeholders. Never put production keys in frontend environment variables or commit service-account credentials. Start the full stack with `docker compose up --build`; emulator endpoints are Auth `9099`, Firestore `8080`, Storage `9199`, and UI `4000`. Review [local development](docs/local-development.md) for credential and emulator constraints.

Run focused checks:

```powershell
mvn -f backend/pom.xml test
python -m pip install -r data-pipeline/requirements.txt
python -m pytest data-pipeline/tests -q
pnpm --dir frontend install
pnpm --dir frontend build
```

## Deployment

Deploy `frontend/` and `backend/` as separate Vercel projects from this monorepo. The backend project uses `backend/Dockerfile.vercel`, listens on Vercel's injected `PORT`, and requires production Firebase project/bucket configuration and Google Application Default Credentials or workload identity. The GitHub pipeline requires repository variable `FIREBASE_PROJECT_ID`, optional bucket variable `FIREBASE_STORAGE_BUCKET`, secret `FIREBASE_SERVICE_ACCOUNT_JSON`, and an already uploaded job ID. No service is deployed by this repository setup.
