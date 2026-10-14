# Vercel Deployment Guide for AquaTrace

This guide walks through deploying the **AquaTrace** monorepo to production on [Vercel](https://vercel.com) and connecting it to Firebase.

---

## Architecture Overview

AquaTrace is configured as a multi-project deployment from a single GitHub repository (`bit-coder91/aquatrace-ai`):

1. **Frontend Project (`aquatrace-web`)**: Next.js 16 App Router interface running natively on Vercel Edge/Serverless.
2. **Backend Project (`aquatrace-api`)**: Spring Boot 4 / Java 21 REST API deployed as a container using `backend/Dockerfile.vercel` on Vercel Functions (Fluid Compute) or Google Cloud Run.
3. **Data Pipeline**: Automated batch processor running on GitHub Actions or Google Cloud Run jobs.

---

## Phase 1: Firebase Production Setup

Before deploying to Vercel, configure your live Firebase project:

1. **Create or Open Firebase Project**:
   - Go to [Firebase Console](https://console.firebase.google.com/) and create a project (e.g. `aquatrace-prod`).
2. **Enable Authentication**:
   - Go to **Build** > **Authentication** > **Get Started**.
   - Enable **Email/Password** sign-in provider.
3. **Enable Cloud Firestore**:
   - Go to **Build** > **Firestore Database** > **Create database**.
   - Choose production mode and your preferred cloud region.
4. **Enable Firebase Storage**:
   - Go to **Build** > **Storage** > **Get Started**.
5. **Obtain Web App Config**:
   - Go to **Project Settings** (gear icon) > **General** > **Your apps** > click **Web (`</>`)**.
   - Register the app and copy:
     - `apiKey`
     - `authDomain`
     - `projectId`
     - `appId`
     - `storageBucket`
6. **Generate Service Account Key (for Backend & Pipeline)**:
   - Go to **Project Settings** > **Service accounts**.
   - Click **Generate new private key** and download the JSON file securely.

---

## Phase 2: Deploy Backend API (`aquatrace-api`)

### Option A: Vercel Container Function (Fluid Compute)

1. Go to [Vercel Dashboard](https://vercel.com/dashboard) and click **Add New...** > **Project**.
2. Select your repository: `bit-coder91/aquatrace-ai`.
3. In **Project Configuration**:
   - **Project Name**: `aquatrace-api`
   - **Framework Preset**: **Other**
   - **Root Directory**: Click *Edit* and select `backend`
4. Expand **Environment Variables** and add:

   | Variable Name | Value | Description |
   | :--- | :--- | :--- |
   | `FIREBASE_PROJECT_ID` | `your-firebase-project-id` | Your production Firebase project ID |
   | `FIREBASE_STORAGE_BUCKET` | `your-project.firebasestorage.app` | Your Firebase Storage bucket name |
   | `FIREBASE_EMULATOR_MODE` | `false` | Must be `false` for live Firebase |
   | `AQUATRACE_CORS_ALLOWED_ORIGINS` | `https://aquatrace-web.vercel.app` | Frontend URL (update after Phase 3) |
   | `GOOGLE_APPLICATION_CREDENTIALS` | `/tmp/service-account.json` | Path to credentials (or provide via secret) |

5. Click **Deploy**. Note down your deployed API domain: e.g. `https://aquatrace-api.vercel.app`.

> **💡 Alternative: Google Cloud Run for Java Backend**
> For high-throughput enterprise workloads, Spring Boot services can also be deployed to **Google Cloud Run** (`gcloud run deploy`) in the same region as Firestore to benefit from zero latency and native IAM Workload Identity without manual JSON keys.

---

## Phase 3: Deploy Frontend Web App (`aquatrace-web`)

1. In the [Vercel Dashboard](https://vercel.com/dashboard), click **Add New...** > **Project**.
2. Select the same repository: `bit-coder91/aquatrace-ai`.
3. In **Project Configuration**:
   - **Project Name**: `aquatrace-web`
   - **Framework Preset**: **Next.js**
   - **Root Directory**: Click *Edit* and select `frontend`
   - **Package Manager**: `pnpm` (automatically detected)
4. Expand **Environment Variables** and configure:

   | Variable Name | Value | Description |
   | :--- | :--- | :--- |
   | `NEXT_PUBLIC_API_BASE_URL` | `https://aquatrace-api.vercel.app` | URL of your deployed Backend API |
   | `NEXT_PUBLIC_FIREBASE_API_KEY` | `AIzaSy...` | Firebase Web API Key |
   | `NEXT_PUBLIC_FIREBASE_AUTH_DOMAIN` | `your-project.firebaseapp.com` | Firebase Auth Domain |
   | `NEXT_PUBLIC_FIREBASE_PROJECT_ID` | `your-firebase-project-id` | Firebase Project ID |
   | `NEXT_PUBLIC_FIREBASE_APP_ID` | `1:000000000000:web:...` | Firebase Web App ID |
   | `NEXT_PUBLIC_FIREBASE_EMULATOR` | `false` | Disables local emulator mode |

5. Click **Deploy**.
6. When deployment finishes, copy your frontend domain (e.g. `https://aquatrace-web.vercel.app`).

---

## Phase 4: Connect & Whitelist Domains

1. **Update Backend CORS**:
   - In Vercel, open your `aquatrace-api` project settings > **Environment Variables**.
   - Set `AQUATRACE_CORS_ALLOWED_ORIGINS` to your frontend production URL (e.g. `https://aquatrace-web.vercel.app`).
   - Trigger a redeployment of `aquatrace-api`.
2. **Authorize Domain in Firebase Auth**:
   - Go to [Firebase Console](https://console.firebase.google.com/) > **Authentication** > **Settings** > **Authorized domains**.
   - Click **Add domain** and enter your Vercel frontend domain: `aquatrace-web.vercel.app`.

---

## Phase 5: GitHub Actions Batch Pipeline Secrets

The data pipeline processes uploaded readings asynchronously:

1. In your GitHub repository (`bit-coder91/aquatrace-ai`), go to **Settings** > **Secrets and variables** > **Actions**.
2. Under **Repository secrets**, click **New repository secret**:
   - **Name**: `FIREBASE_SERVICE_ACCOUNT_JSON`
   - **Value**: Paste the entire contents of your Firebase Service Account JSON file.
3. Under **Repository variables**, click **New repository variable**:
   - `FIREBASE_PROJECT_ID` = `your-firebase-project-id`
   - `FIREBASE_STORAGE_BUCKET` = `your-project.firebasestorage.app`
4. The manual pipeline can now be triggered from the **Actions** tab whenever an ingestion job is uploaded!
