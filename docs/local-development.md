# Local Firebase development

## Emulator project and endpoints

The repository uses `demo-aquatrace-local`, a demo project ID with no live Firebase resources. Emulator endpoints are Auth `9099`, Firestore `8080`, Storage `9199`, and Emulator Suite UI `4000`. `firebase.json` loads restrictive client rules and indexes. Browser Firestore and Storage access is denied because the app routes data through Spring Boot; the Admin SDK bypasses rules, so API and pipeline membership checks are required.

## Start the local stack

With Docker Desktop and Compose installed, run from the repository root:

```powershell
docker compose up --build
```

The app will be available at `http://localhost:3000`, the API at `http://localhost:8081`, and emulator UI at `http://localhost:4000`. To run the emulators directly, install Node.js and Firebase CLI, then run `firebase emulators:start --project demo-aquatrace-local` from the repository root. Java 21 is recommended for the emulators.

## Host process configuration

For host processes, use `.env.example` values:

- `FIREBASE_PROJECT_ID=demo-aquatrace-local`
- `FIREBASE_AUTH_EMULATOR_HOST=127.0.0.1:9099`
- `FIRESTORE_EMULATOR_HOST=127.0.0.1:8080`
- `STORAGE_EMULATOR_HOST=http://127.0.0.1:9199`
- `FIREBASE_STORAGE_BUCKET=demo-aquatrace-local.firebasestorage.app`

The Next.js Firebase client connects Auth to `http://127.0.0.1:9099` when `NEXT_PUBLIC_FIREBASE_EMULATOR=true`. The Auth emulator variable makes Admin SDK token verification accept emulator-issued tokens. Never set emulator variables in production.

The Java API uploads original files using Firebase Admin Storage; Docker Compose supplies the Google Cloud Storage emulator host. That server-to-emulator path has not been exercised in this workspace, and Firebase's Admin SDK compatibility tables have limitations for server Storage emulator support. Verify uploads against the emulator before relying on local ingestion. Do not point local services at a production bucket as a workaround.

## Credentials

- `.env.example` contains placeholders and is safe for emulator-only work. Copy it to `.env` for local use; `.env` is git-ignored.
- Do not download production service-account credentials for emulator-only development.
- For production, use workload identity or Application Default Credentials on trusted server and batch runtimes. Configure Vercel server-side environment and GitHub Actions secrets separately.
- Firebase Web config is public client configuration. Never place service-account JSON or privileged credentials in `NEXT_PUBLIC_*` variables.
- The emulators do not model IAM. Emulator success does not validate production IAM or rules.

## Verification limits

Backend unit tests and Python transform tests pass. The Firebase emulators, Docker Compose stack, Storage upload path, and frontend build have not been run in this environment. Local Node is present but npm is unavailable on PATH, and the managed pnpm invocation is blocked by Windows workspace `realpath` permissions. Docker is unavailable.
