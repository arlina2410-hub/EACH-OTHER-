# PHASE_STATUS.md — EACH OTHER

**Current Phase:** Phase 2 — Consent, Device Management & ECDSA P-256 Auth  
**Last Updated:** 2026-11-17

---

## 1. Phase 2 Deliverables Implemented

### Backend (`backend/` — Cloudflare Workers + D1 + TypeScript)
- `backend/migrations/0001_initial_schema.sql`: Updated for **Option 2 (Android Keystore ECDSA P-256)** with `admin_devices`, `admin_pairing_invites`, `auth_challenges`, and `admin_device_id` tracking in `auth_sessions` (removed `password_hash`).
- `backend/src/crypto/crypto.ts`: Replaced PBKDF2 with `canonicalChallengeMessage`, strict ASN.1 `derEcdsaToP1363`, `importP256SpkiPublicKey`, and `verifyEcdsaP256Signature` using Cloudflare Workers Web Crypto (`ECDSA P-256 + SHA-256`), plus 12-char Crockford Base32 recovery & invite codes and HS256 JWT (`adminDeviceId` claim).
- `backend/src/services/authService.ts` & `backend/src/middleware/auth.ts`:
  - Challenge-response Admin auth (`POST /v1/auth/admin/register`, `/challenge`, `/verify`) with atomic single-use challenge consumption.
  - Multi-admin device management (`POST /v1/admin/admin-devices/invite`, `POST /v1/auth/admin/devices/enroll`, `GET /v1/admin/admin-devices`, `POST /v1/admin/admin-devices/:id/revoke`) and active `admin_devices` verification on every authenticated Admin request.
  - Single-use hashed recovery (`POST /v1/auth/admin/recover`) that registers a replacement Keystore key and revokes **all** prior Admin devices, sessions, and unused pairing invites; plus `POST /v1/auth/admin/recovery-codes/regenerate`.
- `backend/src/services/deviceService.ts`: Added `PUT /v1/devices/me/sharing-settings` with server-side truthfulness guard (forces Location/Usage sharing `false` if OS permission is reported `DENIED`/`UNKNOWN`) and syncs `PAUSED`/`ACTIVE` status.

### Native Android Client (`android/` — Kotlin + Jetpack Compose + Gradle)
- `core/security/AdminKeyManager.kt`: Hardware-backed `AndroidKeyStore` ECDSA P-256 (`secp256r1`) keypair generator & signer + pure JVM `EcdsaSignatureCodec.derToP1363` converter.
- `core/security/SharingSettingsStore.kt` & `domain/model/`: DataStore persistence for Connected User's local toggles (defaults OFF, cleared on disconnect) and `LocalAdminIdentity` tracking across Sign Out.
- `core/network/` (`ApiModels.kt`, `EachOtherApi.kt`, `AuthInterceptors.kt`), `core/di/AppContainer.kt`, & `data/repository/AuthRepository.kt`: ECDSA challenge-response flows, `KEYSTORE_ERROR` mapping, multi-admin pairing/revocation, invitation listing/revocation, `reconcileAndSyncSharingSettings`, and graceful `401`/offline disconnect cleanup.
- `ui/components/QrCodeHelper.kt`: Offline ZXing QR bitmap rendering and Photo Picker (`PickVisualMedia`) QR image decoding with **zero `CAMERA` permission**.
- `ui/admin/AdminScreens.kt` & `ui/navigation/EachOtherNavGraph.kt`: Passwordless Create Group / Sign In / Pair Device / Recover tabs, 10 recovery codes one-time display + clipboard copy + regeneration, recovery confirmation showing `revokedPriorDevicesCount`, Registered Admin Devices list + pairing QR + revocation, Invitations list/QR/revoke, Connected Devices consent & OS permission states.
- `ui/connected/ConnectedScreens.kt`, `platform/location/LocationSharingService.kt`, & `platform/notifications/SharingNotificationManager.kt`: Invite + Photo Picker QR enrollment (1-group rule), DataStore loading gate (`initialValue = null`), explicit plain-language Disclosures before OS permissions, 2-step background location guidance, `ON_RESUME` OS permission revocation & Usage Settings return sync, global Pause/Resume switch + notification `PauseSharingReceiver` (`goAsync()`), optional user-started Foreground Location mode (`SecurityException`-guarded), and explicit Disconnect consequences dialog + offline fallback.
- `android/app/src/test/java/com/eachother/app/`: `AuthRepositoryTest.kt` and `AdminKeyInteroperabilityTest.kt` (50-vector `secp256r1` DER -> P1363 round-trip & malformed DER rejection).
- `BUILD_ANDROID.md`: Updated Android Studio / physical-device build and test instructions for ECDSA Keystore auth & Phase 2 tests.

---

## 2. Verification Results

1. **Backend TypeScript Typecheck & Vitest (`workerd` + Miniflare D1):**
   - Commands:
     - `node backend/node_modules/typescript/bin/tsc -p backend/tsconfig.json --noEmit`
     - `node backend/node_modules/vitest/vitest.mjs run --root backend`
   - Result: **PASSED** — 0 TypeScript errors; **3 test files passed (9 / 9 tests)**:
     - `test/crypto.test.ts` (3 tests): ASN.1 DER-to-P1363 verification across 12 signatures, tamper/wrong-key/malformed-DER rejection, Crockford codes, `adminDeviceId` JWT claims.
     - **Measured `workerd` ECDSA P-256 SPKI import + DER verify latency (`n=12`):** **`avg=0.167ms`, `min=0.000ms`, `max=1.000ms`** (1 ms `performance.now()` timer quantization in `workerd`; safely under Cloudflare Workers Free's 10 ms CPU limit).
     - `test/auth_and_isolation.test.ts` (4 tests): Challenge replay rejection, refresh rotation & reuse revocation, multi-admin device pairing/revocation, full prior-device revocation on recovery, truthful `PUT /v1/devices/me/sharing-settings`, cross-group isolation (`404`/`403`), immediate device revocation (`401`), rate limits (`429`).
     - `test/deletion_and_quota.test.ts` (2 tests): Deletion tombstones, storage monitoring, JSON export, HTTP `413` oversized guard.
2. **Root Workspace Build & Lint (`compile_applet` + `lint_applet`):**
   - Result: **PASSED**.
3. **Android Compilation, JVM Unit Tests & Device Verification:**
   - **NOT VERIFIED - needs physical device / Android Studio** (`which java javac gradle ./android/gradlew` returned empty in this Node.js container; see `BUILD_ANDROID.md`).

---

## 3. Scope Intentionally Deferred to Phase 3+
- `POST /v1/sync/batch`, Room local report tables/outbox, and WorkManager periodic data collection & upload loops (**Phase 3–5**).
