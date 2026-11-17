# PHASE_STATUS.md — EACH OTHER

**Current Phase:** Phase 2.1 — Security, Reliability & Build Verification (Hardening Complete; Phase 3 Not Started)  
**Last Updated:** 2026-11-17

---

## 1. Phase 2.1 Deliverables Implemented

### Backend Hardening (`backend/` — Cloudflare Workers + D1 + TypeScript)
- `backend/migrations/0002_phase2_1_hardening.sql`: Added `auth_session_families`, session family & rotation tracking columns (`family_id`, `rotated_at_ms`, `replaced_by_session_id`) on `auth_sessions`, `rate_limits.expires_at_ms`, and `DEVICE_REVOKED_LOCAL_UNVERIFIED` status in `deletion_commands`.
- `backend/src/services/deviceService.ts` & `backend/src/http/errors.ts`: Enforced atomic monotonic `sharing_settings.settings_version` updates (`WHERE device_id = ? AND settings_version < ?`), returning `409 SETTINGS_VERSION_CONFLICT` with authoritative `authoritativeSettings` and derived `effectiveSharing`, preserving category consent choices across Global Pause, and returning honest `DEVICE_REVOKED_LOCAL_UNVERIFIED` / `localWipeGuaranteed: false` on Admin disconnect-with-wipe.
- `backend/src/services/authService.ts` & `backend/src/middleware/auth.ts`: Implemented atomic single-use refresh-token rotation with `auth_session_families` reuse revocation, distinct error codes (`REFRESH_TOKEN_REUSED`, `REFRESH_TOKEN_REVOKED`, `REFRESH_TOKEN_EXPIRED`, `INVALID_REFRESH_TOKEN`), and `authenticateRequest` family revocation checks.
- `backend/src/crypto/crypto.ts`, `backend/src/services/invitationService.ts`, `backend/src/types/env.ts`, `backend/wrangler.toml`, `backend/vitest.config.ts`: Replaced bare SHA-256 code hashing with fail-closed, domain-separated `HMAC-SHA-256` (`hmac-v1:<hex>`, secret binding `CODE_HASH_PEPPER`) and optional opt-in `ALLOW_LEGACY_UNPEPPERED_CODES='true'` transition support.
- `backend/src/middleware/requestGuard.ts` & `backend/src/middleware/rateLimit.ts`: Removed trust in spoofable `x-forwarded-for`, validated `cf-connecting-ip`, switched to atomic single-statement `INSERT ... ON CONFLICT DO UPDATE ... RETURNING` buckets with HMAC keys and bounded `cleanupExpiredRateLimits`, and added per-account recovery rate limiting in `backend/src/index.ts`.
- `backend/src/services/deletionService.ts`: Applied `sync_receipts` tombstones **before** deletions across **all 4 scopes** (`SINGLE_RECORD`, `DATE_RANGE`, `CATEGORY`, `ALL_DEVICE_DATA`), added `isRecordTombstoned` safety helper, and enforced device ownership in `acknowledgeDeviceCommands` (`{ acknowledgedIds, rejectedCommandIds }`).
- `backend/src/services/consentPolicyService.ts`: Implemented authoritative `evaluateDeviceConsentPolicy` and `assertReportConsentAllowed` (`403 SHARING_PAUSED` / `403 CATEGORY_NOT_ENABLED`) ready for Phase 3 while keeping control endpoints reachable while paused.

### Native Android Hardening & Reproducible Wrapper (`android/`)
- **Gradle 8.9 Wrapper Committed:** Added official `android/gradle/wrapper/gradle-wrapper.jar` (43,504 bytes, SHA-256 `498495120a03b9a6ab5d155f5de3c8f0d986a449153702fb80fc80e134484f17`), POSIX `android/gradlew` (`chmod 0755`, LF), Windows `android/gradlew.bat` (CRLF), and pinned `distributionSha256Sum` (`d725d707bfabd4dfdc958c624003b3c80accc03f7037b5122c4b1d0ef15cecab`) in `android/gradle/wrapper/gradle-wrapper.properties`. All dependencies pinned via `android/gradle/libs.versions.toml` (AGP `8.7.3`, Kotlin `2.0.21`, KSP `2.0.21-1.0.27`, JDK `17`, SDK `35`).
- `core/security/AdminKeyManager.kt`, `domain/model/AuthModels.kt`, & `data/repository/AuthRepository.kt`: Added unique tracked key IDs (`admin_<uuid>`), safe non-destructive candidate key generation that preserves the active key & session if recovery/pairing fails, and `KeySecurityLevelMapper` / `KeyInfo` inspection (`STRONGBOX_HARDWARE`, `TEE_HARDWARE`, `SOFTWARE`, `UNKNOWN`).
- `core/network/AuthInterceptors.kt`, `NetworkModule.kt`, `ApiModels.kt`, `EachOtherApi.kt`, `core/di/AppContainer.kt`: Added synchronized `TokenAuthenticator` preventing concurrent 401 refresh storms, serialized `reconcileAndSyncSharingSettings` with coroutine `Mutex`, `409 SETTINGS_VERSION_CONFLICT` reconciliation against `authoritativeSettings`, and typed `AdminDisconnectDeviceResponse`.
- `ui/connected/ConnectedScreens.kt`, `ui/admin/AdminScreens.kt`, & `platform/notifications/SharingNotificationManager.kt`: Replaced premature active-reporting claims with `"Sharing permissions enabled — reporting not yet implemented"`, truthful Foreground Location Mode notification wording, `BadgeTone.ERROR` failed-sync banners, immediate `DEVICE_REVOKED` exit handling, honest offline disconnect messaging, and `KeyInfo` + `DEVICE_REVOKED_LOCAL_UNVERIFIED` status display.

### Tests & Documentation Updated
- Backend test suite: `backend/test/hardening_phase2_1.test.ts` (6 comprehensive integration/unit tests) alongside existing `crypto.test.ts`, `auth_and_isolation.test.ts`, and `deletion_and_quota.test.ts`.
- Android JVM test suite: Expanded `AdminKeyInteroperabilityTest.kt` (`KeySecurityLevelMapper`) and `AuthRepositoryTest.kt` (failed recovery key preservation, single coordinated 401 refresh under concurrency, `409` settings reconciliation, offline disconnect honesty, Admin wipe status).
- Updated documentation: `SECURITY.md`, `ARCHITECTURE.md`, `API_SPECIFICATION.md`, `OFFLINE_SYNC.md`, `BUILD_ANDROID.md`, `TEST_PLAN.md`, and `PHASE_STATUS.md`.

---

## 2. Verification Results

1. **Backend TypeScript Typecheck:**
   - Command: `node backend/node_modules/typescript/bin/tsc -p backend/tsconfig.json --noEmit`
   - Result: **PASSED** — 0 TypeScript errors.
2. **Backend Vitest (`workerd` + Miniflare D1 with `0001` & `0002` migrations):**
   - Command: `node backend/node_modules/vitest/vitest.mjs run --root backend`
   - Result: **PASSED** — **4 test files passed (4 / 4), 15 tests passed (15 / 15)**:
     - `test/crypto.test.ts`: 3 / 3 passed (ECDSA `workerd` SPKI + DER verify benchmark `n=12`: `avg=0.250ms`, `min=0.000ms`, `max=1.000ms`).
     - `test/auth_and_isolation.test.ts`: 4 / 4 passed.
     - `test/deletion_and_quota.test.ts`: 2 / 2 passed.
     - `test/hardening_phase2_1.test.ts`: 6 / 6 passed.
3. **Gradle Wrapper Shell & Checksum Verification:**
   - Verified `sh -n android/gradlew` (exit code `0`), LF/CRLF line endings, official `gradle-8.9-bin.zip` SHA-256 (`d725d707bfabd4dfdc958c624003b3c80accc03f7037b5122c4b1d0ef15cecab`), and official `gradle-wrapper.jar` SHA-256 (`498495120a03b9a6ab5d155f5de3c8f0d986a449153702fb80fc80e134484f17`).
4. **Root Workspace Build & Lint (`compile_applet` + `lint_applet`):**
   - Result: **PASSED** — Root Vite build (`compile_applet`) succeeded and `tsc --noEmit` (`lint_applet`) completed with 0 errors.
5. **Android Compilation, JVM Unit Tests & Physical-Device Verification:**
   - **NOT VERIFIED - needs Android Studio / JDK 17 + physical device** (`which java javac gradle` confirmed no Java JDK, Gradle, or Android SDK is installed in this Node.js container; run `./gradlew testDebugUnitTest` and `./gradlew assembleDebug` in Android Studio per `BUILD_ANDROID.md`).

---

## 3. Scope Intentionally Deferred to Phase 3+
- `POST /v1/sync/batch`, Room local report tables/outbox, and WorkManager periodic data collection & upload loops (**Phase 3–5**, not started in Phase 2.1).
