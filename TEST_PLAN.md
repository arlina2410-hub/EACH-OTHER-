# TEST_PLAN.md — EACH OTHER

**Phase:** Phase 2.1 — Security, Reliability & Build Verification  
**Source of Truth:** `PRD.md` §13

---

## 1. Automated Backend Test Suite (`backend/test/` via Vitest + Miniflare/D1)

Every backend phase requires automated tests running against a real local D1 SQLite instance (migrations `0001_initial_schema.sql` + `0002_phase2_1_hardening.sql`) before sign-off:
1. **Auth, Crypto & Peppered Digests (`crypto.test.ts`, `auth_and_isolation.test.ts`, `hardening_phase2_1.test.ts`):**
   - ECDSA P-256 SPKI + ASN.1 DER-to-P1363 verification + latency benchmark in `workerd`.
   - Domain-separated `HMAC-SHA-256` digests (`CODE_HASH_PEPPER`), fail-closed `500 SERVER_MISCONFIGURED` when unset, and opt-in `ALLOW_LEGACY_UNPEPPERED_CODES` behavior.
   - Single-use challenge consumption and recovery code revocation of all prior Admin devices/sessions.
   - Atomic single-use refresh token rotation under concurrent `Promise.all` requests, session family revocation on reuse, and distinct `REFRESH_TOKEN_REUSED` / `REFRESH_TOKEN_REVOKED` / `REFRESH_TOKEN_EXPIRED` / `INVALID_REFRESH_TOKEN` errors.
2. **Authorization, Monotonic Settings & Global Pause (`auth_and_isolation.test.ts`, `hardening_phase2_1.test.ts`):**
   - Connected device calling any `/v1/admin/*` route -> `403 FORBIDDEN`; Admin cross-group isolation -> `404`.
   - Out-of-order and concurrent `PUT /v1/devices/me/sharing-settings` -> `409 SETTINGS_VERSION_CONFLICT` with `authoritativeSettings`, category preference preservation across Global Pause, and derived `effectiveSharing`.
   - `assertReportConsentAllowed`: `403 SHARING_PAUSED` when paused and `403 CATEGORY_NOT_ENABLED` when category/permission disabled, while control endpoints remain reachable.
3. **Rate Limiting & Header Spoofing Defense (`hardening_phase2_1.test.ts`):**
   - Atomic DB rate limiters under concurrent bursts, ignoring spoofed `x-forwarded-for`, validating `cf-connecting-ip`, and `cleanupExpiredRateLimits`.
4. **Deletions, Tombstones & Honest Disconnect (`deletion_and_quota.test.ts`, `hardening_phase2_1.test.ts`):**
   - Tombstones applied before deletes across all 4 scopes (`SINGLE_RECORD`, `DATE_RANGE`, `CATEGORY`, `ALL_DEVICE_DATA`), `isRecordTombstoned` safety check, ownership-enforced `/commands/ack` (`acknowledgedIds` + `rejectedCommandIds`), and honest `DEVICE_REVOKED_LOCAL_UNVERIFIED` / `localWipeGuaranteed: false` on Admin disconnect-with-wipe.
5. **Sync & Idempotency (Scheduled for Phase 3):** `/v1/sync/batch` batching, deduplication, and live upload tombstone rejection.

---

## 2. Automated Android Test Suite (`android/app/src/test` & `androidTest`)

> **Environment Note:** JVM unit tests and Android instrumented tests require JDK 17 + Android SDK, which are not installed in the current Node.js 22 container. Committed Gradle 8.9 wrapper enables direct execution in Android Studio or any JDK 17 CI runner (`./gradlew testDebugUnitTest`).

1. **JVM Unit Tests Implemented (`src/test`):**
   - `AdminKeyInteroperabilityTest`: 50-signature real `secp256r1` ASN.1 DER -> 64-byte IEEE P1363 round-trips + `KeySecurityLevelMapper` assertions across StrongBox, TEE, Software, API 26–30, and unknown/missing states.
   - `AuthRepositoryTest`: Admin registration (`activeKeyId`), non-destructive `recoverAdmin` preserving old key/session on server failure, `TokenAuthenticator` single-refresh coordination across concurrent 401s with `MockWebServer`, `reconcileAndSyncSharingSettings` `409` reconciliation, honest offline disconnect messaging, and `DEVICE_REVOKED_LOCAL_UNVERIFIED` response parsing.
2. **Scheduled for Phase 3–7 (`src/test` & `src/androidTest`):** Room DAOs/migrations, WorkManager `TestListenableWorkerBuilder`, offline outbox queue & sync use cases, `UsageAggregator`, and Compose UI instrumented tests.

---

## 3. End-to-End Acceptance Matrix (PRD §13.3 — All 18 Scenarios)

| # | Scenario | Verification Method | Target Phase |
| --- | --- | --- | --- |
| 1 | Admin creates group and invitation | Backend Vitest + Android UI | Phase 2 |
| 2 | Connected user joins with valid invitation | Backend Vitest + Android UI | Phase 2 |
| 3 | Expired/revoked invitation rejected | Backend Vitest + Android UI | Phase 2 |
| 4 | Connected user enables location & grants permission | Android UI + Physical device | Phase 4 |
| 5 | Location record stored locally | Room DAO test + Device | Phase 3–4 |
| 6 | Network disabled; record remains pending | WorkManager test + Device | Phase 3–4 |
| 7 | Network returns; WorkManager syncs automatically | WorkManager + Physical device | Phase 3–4 |
| 8 | Admin sees record with original timestamp | E2E API + Admin UI | Phase 4 |
| 9 | Retrying upload creates zero duplicates | Backend Vitest + MockWebServer | Phase 3 |
| 10 | Usage Access denied keeps usage unavailable | Permission unit test + Device | Phase 5 |
| 11 | Revoking Usage Access stops future collection | `ON_RESUME` test + Device | Phase 5 |
| 12 | Pausing sharing prevents new uploads | UseCase unit test + Device | Phase 2–3 |
| 13 | Revoking device blocks subsequent server requests | Backend Vitest + Android Auth test | Phase 2 |
| 14 | Admin cannot read another group's data | Backend Vitest | Phase 1 |
| 15 | Connected user cannot read another device's reports | Backend Vitest | Phase 1 |
| 16 | Deletion prevents queued records from reappearing | Backend Vitest + SyncUseCase test | Phase 7 |
| 17 | Offline deletion shows pending until ack | Backend Vitest + Admin UI | Phase 7 |
| 18 | Stale device clearly marked stale, not live | ViewModel + Compose UI test | Phase 4 / 7 |
