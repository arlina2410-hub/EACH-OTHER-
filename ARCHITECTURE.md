# ARCHITECTURE.md — EACH OTHER

**Phase:** Phase 0 — System Architecture & Technology Stack  
**Source of Truth:** `PRD.md`

---

## 1. End-to-End System Topology

```text
+-------------------------------------------------------------------+
| CONNECTED USER'S ANDROID DEVICE (Kotlin / Compose / Room / WM)    |
|                                                                   |
|  [Consent & Sharing UI] ---> [SharingPreferences / Keystore]      |
|           |                                                       |
|           v (only when category enabled & permission granted)     |
|  [LocationCollector]   [UsageStatsCollector]   [StatusCollector]  |
|           |                     |                     |           |
|           +---------------------+---------------------+           |
|                                 |                                 |
|                                 v                                 |
|                    [Room SQLite Database]                         |
|              (pending_records, sync_state, commands)              |
|                                 |                                 |
|                                 v                                 |
|                 [WorkManager SyncWorker (Network)]                |
|            1. Pull & execute deletion_commands first              |
|            2. Upload bounded batch (<= 50 records)                |
|            3. Mark ACKed records SYNCED                           |
+---------------------------------+---------------------------------+
                                  |
                                  | HTTPS (TLS 1.2+) + Bearer JWT
                                  v
+-------------------------------------------------------------------+
| CLOUDFLARE WORKER API (TypeScript / Modular Monolith)             |
|                                                                   |
|  [Router] -> [Rate Limiter] -> [Auth Middleware (JWT + Device)]   |
|     -> [Zod Request Validator] -> [Authorization Policy Check]    |
|     -> [Use Cases: Enrollment, Sync, Admin Read, Deletion]        |
|     -> [D1 Repository Layer (Parameterized SQL)]                  |
+---------------------------------+---------------------------------+
                                  |
                                  v
+-------------------------------------------------------------------+
| CLOUDFLARE D1 (SQLite)                                            |
|  accounts | groups | devices | invitations | refresh_tokens       |
|  sharing_settings | location_records | usage_summaries            |
|  device_status_records | sync_receipts | deletion_commands        |
+---------------------------------+---------------------------------+
                                  ^
                                  | HTTPS (TLS 1.2+) + Admin JWT
+---------------------------------+---------------------------------+
| ADMINISTRATOR'S ANDROID DEVICE (Same APK, Admin Mode)             |
|                                                                   |
|  [Device List] -> [Device Detail] -> [Location / Usage / Status]  |
|  [Invitations (Code + QR)]       -> [Deletion & Disconnect]       |
+-------------------------------------------------------------------+
```

---

## 2. Android Application Architecture (Clean MVVM)

All Android code will live under `android/` in a standard multi-package Gradle module (`:app`) structured into strict layers so UI can be tested independently of Android framework APIs:

```text
android/app/src/main/java/com/eachother/app/
├── core/
│   ├── di/                  # Manual AppContainer (lightweight DI; zero Hilt overhead)
│   ├── network/             # OkHttpClient, Ktor/Retrofit API client, AuthInterceptor, TokenAuthenticator
│   ├── security/            # Android Keystore AES-GCM EncryptedSharedPreferences / Tink wrapper
│   ├── time/                # Clock abstraction (UTC Instant provider for deterministic tests)
│   └── result/              # AppResult<T> sealed error hierarchy
├── data/
│   ├── local/
│   │   ├── db/              # AppDatabase (Room), Migrations, TypeConverters
│   │   ├── dao/             # LocationDao, UsageDao, StatusDao, SyncQueueDao, DeletionCommandDao
│   │   └── entity/          # Room @Entity classes
│   ├── remote/
│   │   ├── dto/             # @Serializable request/response DTOs
│   │   └── api/             # EachOtherApi service definitions
│   └── repository/          # Offline-first repository implementations
├── domain/
│   ├── model/               # Pure Kotlin domain models (Device, SyncState, SharingCategory, etc.)
│   ├── repository/          # Repository interfaces
│   └── usecase/             # EnrollDeviceUseCase, SyncBatchUseCase, ProcessDeletionCommandsUseCase, etc.
├── platform/
│   ├── location/            # LocationCollector interface + Fused/Platform implementations + LocationFgs
│   ├── usage/               # UsageStatsCollector interface + UsageStatsManager aggregator
│   ├── status/              # DeviceStatusCollector (BatteryManager, ConnectivityManager, PowerManager)
│   ├── permissions/         # PermissionChecker (Fine, Background, Usage Access, Notifications, Battery)
│   └── notifications/       # NotificationChannels & ForegroundService notification builder
├── background/
│   ├── SyncWorker.kt        # WorkManager CoroutineWorker (constraints: CONNECTED)
│   ├── CollectionWorker.kt  # WorkManager periodic collection coordinator (~15 min)
│   ├── BootReceiver.kt      # Re-enqueues WorkManager jobs after reboot (NO FGS launch from boot on API 35)
│   └── SyncScheduler.kt     # UniquePeriodicWork / OneTimeWork expedited trigger
└── ui/
    ├── theme/               # Material 3 typography, color tokens, spacing
    ├── components/          # StatusBadge, ConsentCard, StaleBanner, Empty/Error/Loading states
    ├── navigation/          # Jetpack Navigation Compose routes (Onboarding, Admin graph, Connected graph)
    ├── onboarding/          # Splash, RoleSelect, PrivacyIntro
    ├── admin/               # AdminAuth, Dashboard, DeviceDetail, LocationHistory, UsageReport, StatusReport, Invites, Deletions
    └── connected/           # JoinGroup, ConnectedHome, CategoryDetail, SyncStatus, PrivacyControls
```

### Key Android Architectural Decisions
1. **Manual Dependency Injection (`AppContainer`):** Keeps APK size small, build times fast, and avoids kapt/ksp Hilt codegen complexity on entry-level machines while preserving constructor-injected interfaces for unit testing.
2. **Networking:** **Retrofit + OkHttp + Kotlinx Serialization** (single networking stack, no Ktor alongside it). OkHttp provides TLS 1.2+ and a lock-coordinated `TokenAuthenticator` so concurrent 401 responses share a single refresh-token rotation without tripping backend reuse detection.
3. **Keystore Lifecycle & Settings Concurrency:** `AndroidKeystoreAdminKeyManager` generates unique candidate keys (`admin_<uuid>`) during recovery/pairing and cleans up unused keys on failure without overwriting active keys. `KeySecurityLevelMapper` verifies `KeyInfo` hardware vs software status honestly. `AuthRepository` serializes sharing-settings updates with a coroutine `Mutex` and reconciles `409 SETTINGS_VERSION_CONFLICT` responses against authoritative server state.
4. **Reproducible Gradle Build:** Official **Gradle 8.9** wrapper (`gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `distributionSha256Sum`) and `gradle/libs.versions.toml` version catalog are committed under `android/`.
5. **Platform Interfaces:** `PermissionChecker`, `AdminKeySigner`, `SessionStore`, `SharingSettingsStore`, and `Clock` (plus future Phase 3–5 collector interfaces) are Kotlin abstractions for pure-JVM unit testing without Robolectric.

---

## 3. Recommended Android Dependency Matrix (Pinned in `android/gradle/libs.versions.toml`)

| Library | Pinned Version | Purpose |
| --- | --- | --- |
| Kotlin | `2.0.21` (with KSP `2.0.21-1.0.27`) | Language + Compose compiler plugin |
| Android Gradle Plugin | `8.7.3` | Build toolchain (Gradle `8.9`, JDK `17` required) |
| `compileSdk` / `targetSdk` / `minSdk` | `35` / `35` / `26` | Android 8.0+ to Android 15 |
| Jetpack Compose BOM | `2024.10.01` | Material 3, UI, Navigation Compose |
| Lifecycle & ViewModel | `2.8.7` | `viewModelScope`, `collectAsStateWithLifecycle` |
| Room | `2.6.1` (via KSP) | Offline SQLite persistence (activated in Phase 3) |
| WorkManager | `2.10.0` | Network-constrained sync & periodic scheduling (activated in Phase 3–5) |
| OkHttp | `4.12.0` | HTTPS client, TLS 1.2+, synchronized `TokenAuthenticator` |
| Retrofit | `2.11.0` | Typed REST interface |
| Kotlinx Serialization | `1.7.3` | Zero-reflection JSON serialization |
| Coroutines | `1.9.0` | Async flows & structured concurrency |
| Keystore + DataStore | DataStore `1.1.1` + Keystore AES-256-GCM | Encrypted session storage (avoids deprecated alpha `EncryptedSharedPreferences`) |
| Play Services Location | `21.3.0` | Fused location provider (wired in Phase 4 behind consent gates) |
| ZXing Core (`com.google.zxing:core`) | `3.5.3` | Offline QR bitmap generation & Photo Picker QR image decode (zero `CAMERA` permission) |
| Testing | JUnit 4 (`4.13.2`), Coroutines Test (`1.9.0`), MockWebServer (`4.12.0`) | Automated JVM unit tests |

---

## 4. Backend Architecture (Cloudflare Workers + D1)

All backend code lives under `backend/` as a modular TypeScript monolith:

```text
backend/
├── wrangler.toml            # D1 binding, compatibility_date, secret bindings (JWT_SIGNING_SECRET, CODE_HASH_PEPPER)
├── package.json             # wrangler, vitest, @cloudflare/vitest-pool-workers, zod
├── migrations/
│   ├── 0001_initial_schema.sql
│   └── 0002_phase2_1_hardening.sql
├── src/
│   ├── index.ts             # Worker fetch() entrypoint & top-level error boundary
│   ├── http/                # Typed AppError (with optional structured conflict payload) & JSON responses
│   ├── middleware/
│   │   ├── auth.ts          # HS256 JWT verify + D1 session/family/device revocation checks
│   │   ├── rateLimit.ts     # Atomic single-statement SQL rate-limit buckets + bounded cleanup
│   │   └── requestGuard.ts  # Content-Type, max body size (256 KB), verified cf-connecting-ip extraction
│   ├── validation/          # Zod schemas for every endpoint
│   ├── services/
│   │   ├── authService.ts           # ECDSA P-256 challenge/verify, recovery, HMAC code hashes, atomic refresh rotation
│   │   ├── invitationService.ts     # Single-use Crockford invite codes (HMAC-SHA-256 + optional legacy migration)
│   │   ├── deviceService.ts         # Monotonic sharing settings (`409` reconciliation), sync-state, device disconnect
│   │   ├── deletionService.ts       # Pre-delete tombstones across all 4 scopes, ownership-enforced ACKs
│   │   ├── consentPolicyService.ts  # Authoritative effectiveSharing + Global Pause report enforcement (for Phase 3+)
│   │   └── quotaService.ts          # Free-tier D1 storage estimation & JSON export
│   └── crypto/              # WebCrypto ECDSA P-256 verify (DER->P1363), HMAC-SHA-256 code digests, HS256 JWTs
└── test/
    ├── crypto.test.ts
    ├── auth_and_isolation.test.ts
    ├── deletion_and_quota.test.ts
    └── hardening_phase2_1.test.ts
```

### Why This Backend Fits ₹0 & 10 ms CPU Limits
- **Zero Heavy Dependencies:** Native WebCrypto (`crypto.subtle`) handles ECDSA P-256 signature verification (measured `avg=0.250ms` in `workerd`), HMAC-SHA-256 code hashing, and HS256 JWTs within strict Cloudflare Workers Free CPU budgets.
- **Strict Query Discipline:** Parameterized SQL and composite indexes keep D1 reads/writes bounded, with bounded expired rate-limit cleanup preventing unbounded table growth.
- **No Unprotected Endpoints:** Public routes are strictly limited to `/v1/health`, `/v1/auth/admin/register`, `/v1/auth/admin/challenge`, `/v1/auth/admin/verify`, `/v1/auth/admin/recover`, `/v1/auth/admin/devices/enroll`, `/v1/auth/refresh`, `/v1/invitations/validate`, and `/v1/devices/enroll` — all guarded by Zod validation, verified IP/account rate limiting, and fail-closed secrets.
