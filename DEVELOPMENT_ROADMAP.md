# DEVELOPMENT_ROADMAP.md — EACH OTHER

**Phase:** Phase 0 — Phased Delivery Plan  
**Source of Truth:** `PRD.md` §14

---

## Phase Overview

- **Phase 0 — Foundation & Feasibility (CURRENT PHASE):** Complete repository/environment audit, Android/Cloudflare research, schemas, API contracts, sync/deletion protocol, security threat model, UI/UX map, and open decisions. **No production code written.**
- **Phase 1 — Project Skeleton & Secure Foundation:**
  - *Backend:* Initialize `backend/` Cloudflare Worker + Wrangler + D1 migration `0001_initial_schema.sql`, implement WebCrypto auth, JWT/refresh rotation, group/device authorization middleware, rate limiting, and Vitest suite. (Can be built and fully tested in Node.js 22 right away once approved.)
  - *Android:* Create `android/` Kotlin + Compose + Material 3 project skeleton with `AppContainer`, Keystore token storage, Retrofit/OkHttp auth client, and Role/Splash navigation shell. *(Requires JDK 17 + Android SDK to compile APK & run Gradle tests.)*
- **Phase 2 — Consent & Device Management:**
  - Role onboarding, Admin registration/login/recovery, invitation creation (code + local ZXing QR) & revocation, Connected User enrollment, per-category disclosure screens, global Pause, and Disconnect/Revoke.
- **Phase 3 — Offline-First Data Foundation:**
  - Room entities/DAOs (`AppDatabase`), `SyncWorker` (WorkManager), `POST /v1/sync/batch` + `GET /v1/devices/me/commands`, idempotency receipts, partial batch handling, and manual "Sync now".
- **Phase 4 — Location Reporting:**
  - Two-step location permission flow, `LocationSource` (`FusedLocationProviderClient` + `LocationManager` fallback), optional Foreground Service (`foregroundServiceType="location"`) with persistent notification, ~15m WorkManager collection, Admin latest location + paginated date-filtered history + stale indicators. Physical-device battery & Doze test.
- **Phase 5 — App Usage Reporting:**
  - Usage Access disclosure & `ACTION_USAGE_ACCESS_SETTINGS` flow, `ON_RESUME` revocation check, `UsageStatsManager` daily aggregation + data-gap detection, sync, and Admin Daily/Weekly/Top-Apps views.
- **Phase 6 — Basic Device Status:**
  - `BatteryManager`, `ConnectivityManager`, `PowerManager.isInteractive`, and app health snapshot collection + Admin device status history.
- **Phase 7 — Deletion, Hardening & Release:**
  - Full `deletion_commands` & tombstone enforcement on server and client, offline-pending UI status, full 18-scenario E2E verification, APK size & low-end device profiling (4 GB RAM target), release APK build guide, and explicit user-approved staging/production Wrangler deployment.

---

## Environment Strategy for Android + Backend Co-Development

Because this AI Studio container runs **Node.js 22 without JDK/Android SDK**:
1. **Cloudflare Worker Backend (`backend/`):** Developed, migrated locally with Wrangler/Miniflare, and verified with `vitest` directly inside this workspace.
2. **Android Client (`android/`):** Either:
   - Built/compiled in a separate Android-enabled AI Studio workspace or local **Android Studio** installation (JDK 17 + Android SDK API 35), OR
   - Authored cleanly as standard Gradle/Kotlin source files in `android/` and compiled/tested by the user in Android Studio on their machine with step-by-step instructions.
