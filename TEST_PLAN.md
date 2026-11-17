# TEST_PLAN.md — EACH OTHER

**Phase:** Phase 0 — Verification & Acceptance Criteria  
**Source of Truth:** `PRD.md` §13

---

## 1. Automated Backend Test Suite (`backend/test/` via Vitest + Miniflare/D1)

Every backend phase requires automated tests running against a real local D1 SQLite instance before sign-off:
1. **Auth & Crypto:** Password hashing/verification, JWT signing/expiry, refresh token rotation, refresh token reuse revocation, recovery code single-use consumption.
2. **Authorization & Isolation:**
   - Connected device calling any `/v1/admin/*` route -> `403 FORBIDDEN`.
   - Admin A requesting Admin B's devices, locations, usage, status, or deletions -> `404 DEVICE_NOT_FOUND`.
   - Connected device attempting to spoof another `deviceId` in payload -> server uses JWT `deviceId` only.
3. **Invitations:** Valid code passes; expired/used/revoked code fails with uniform `404`; rate limit returns `429` after threshold.
4. **Sync & Idempotency:**
   - Batch > 50 items or > 256 KB rejected (`400`/`413`).
   - Out-of-range latitude/longitude or future timestamps rejected per-item without crashing valid items.
   - Re-sending identical `record_id`s returns `duplicateIds` with zero duplicate D1 rows.
   - Partial batch handling (`acceptedIds` + `rejectedInvalid`).
5. **Revocation & Deletion:**
   - Revoked device receives `401 DEVICE_REVOKED` immediately on `/commands` and `/sync/batch`.
   - Deletion command removes D1 rows, marks `sync_receipts.tombstoned = 1`, stays `PENDING_DEVICE_ACK` until `/commands/ack`.
   - Re-uploading tombstoned record returns `rejectedTombstonedIds` and does NOT re-insert into D1.
6. **Migrations:** Apply `0001_initial_schema.sql` cleanly from scratch on empty D1.

---

## 2. Automated Android Test Suite (`android/app/src/test` & `androidTest`)

> **Environment Note (from Phase 0 audit):** JVM unit tests and Android instrumented tests require JDK 17 + Android SDK, which are not installed in the current Node.js 22 container. They will be executed in an Android-capable environment (Android Studio / Gradle runner).

1. **JVM Unit Tests (`src/test`):**
   - Domain rules: consent toggle states, global pause blocking collection, stale-threshold classification (`<30m`, `30m–3h`, `>3h`).
   - Sync use cases with fake API & fake clock: FIFO batching, partial ack, ambiguous retry, commands-before-upload ordering, tombstone deletion execution.
   - `UsageAggregator` with fixture `UsageEvents` (including missing background events / crash gaps -> `PARTIAL_GAP`).
   - `LocationCollector` & `DeviceStatusCollector` via faked platform sources.
   - ViewModel state transitions (`Loading`, `Empty`, `Error`, `PermissionDenied`, `Offline`).
   - `MockWebServer` integration tests for Retrofit DTO serialization & OkHttp `TokenAuthenticator` refresh flow.
2. **Android Instrumented Tests (`src/androidTest`):**
   - Room DAO CRUD, unique constraints, and `MigrationTestHelper` schema upgrades.
   - WorkManager `TestListenableWorkerBuilder` constraint & retry behavior.
   - Compose UI tests (`createComposeRule`) verifying Role Selection, Consent Disclosure defaults (all OFF), Pause toggle, and Stale banner text.

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
