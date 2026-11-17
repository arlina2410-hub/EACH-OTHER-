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
2. **Networking:** **Retrofit + OkHttp + Kotlinx Serialization** (single networking stack, no Ktor alongside it). OkHttp provides battle-tested TLS, connection pooling, and `Authenticator` for seamless 401 token refresh.
3. **Platform Interfaces:** `LocationSource`, `UsageStatsSource`, `DeviceStatusSource`, `PermissionChecker`, and `Clock` are Kotlin interfaces so every domain use case and ViewModel can be unit-tested on the JVM without Robolectric or a physical phone.

---

## 3. Recommended Android Dependency Matrix

> Note: Exact patch versions will be pinned once an Android Gradle / JDK environment is provisioned (see `PHASE_STATUS.md` environment finding). Below is the compatible baseline for **AGP 8.7.x / Kotlin 2.0.21 / Compose BOM 2024.10+ / targetSdk 35**.

| Library | Recommended Version | Purpose |
| --- | --- | --- |
| Kotlin | `2.0.21` (with KSP `2.0.21-1.0.27`) | Language + Compose compiler plugin |
| Android Gradle Plugin | `8.7.3` | Build toolchain (JDK 17 required) |
| `compileSdk` / `targetSdk` / `minSdk` | `35` / `35` / `26` | Android 8.0+ to Android 15 |
| Jetpack Compose BOM | `2024.10.01` | Material 3, UI, Navigation Compose |
| Lifecycle & ViewModel | `2.8.7` | `viewModelScope`, `collectAsStateWithLifecycle` |
| Room | `2.6.1` (via KSP) | Offline SQLite persistence + auto/manual migrations |
| WorkManager | `2.10.0` | Network-constrained sync & periodic scheduling |
| OkHttp | `4.12.0` | HTTPS client, TLS 1.2+, TokenAuthenticator |
| Retrofit | `2.11.0` | Typed REST interface |
| Kotlinx Serialization | `1.7.3` | Zero-reflection JSON serialization |
| Coroutines | `1.9.0` | Async flows & structured concurrency |
| Security Crypto / Tink | `androidx.security:security-crypto:1.1.0-alpha06` or Keystore AES-GCM directly | Encrypted token storage (note: wrapping Keystore + DataStore directly avoids deprecated `EncryptedSharedPreferences` alpha warnings—recommended!) |
| Play Services Location | `com.google.android.gms:play-services-location:21.3.0` | Battery-efficient fused location (behind `LocationSource` fallback) |
| ZXing Core (`com.google.zxing:core`) | `3.5.3` | Lightweight offline QR code generation (no camera needed for Admin; Connected User can paste code or use system/lightweight scanner if approved) |
| Testing | JUnit 4 (`4.13.2`), Coroutines Test (`1.9.0`), MockWebServer (`4.12.0`), Room Testing (`2.6.1`) | Automated JVM & instrumented tests |

---

## 4. Backend Architecture (Cloudflare Workers + D1)

All backend code will live under `backend/` as a modular TypeScript monolith:

```text
backend/
├── wrangler.toml            # D1 binding, dev/prod environments, compatibility_date
├── package.json             # wrangler, vitest, @cloudflare/vitest-pool-workers, zod, jose
├── migrations/
│   └── 0001_initial_schema.sql
├── src/
│   ├── index.ts             # Worker fetch() entrypoint & top-level error boundary
│   ├── router.ts            # Lightweight typed router (zero heavy framework overhead)
│   ├── middleware/
│   │   ├── auth.ts          # JWT verification + revocation check against D1
│   │   ├── rateLimit.ts     # Fixed-window/sliding rate limit (D1 or KV/in-memory fallback)
│   │   └── requestGuard.ts  # Content-Type, max body size (256 KB), security headers
│   ├── validation/          # Zod schemas for every endpoint
│   ├── policy/              # Authorization assertions (assertAdminOwnsGroup, assertDeviceActive)
│   ├── services/            # AuthService, GroupService, InvitationService, SyncService, DeletionService
│   ├── repositories/        # D1 prepared-statement queries (strictly parameterized)
│   └── crypto/              # WebCrypto ECDSA P-256 verify (DER->P1363), SHA-256 token hashing, HS256 JWT signing
└── test/
    ├── auth.test.ts
    ├── authorization.test.ts
    ├── sync.test.ts
    └── deletion.test.ts
```

### Why This Backend Fits ₹0 & 10 ms CPU Limits
- **Zero Heavy Dependencies:** Native WebCrypto (`crypto.subtle`) handles password hashing (PBKDF2-SHA256), SHA-256 token hashing, and JWT signing/verification with minimal bundle size and < 3 ms CPU overhead.
- **Strict Query Discipline:** Every D1 query uses composite indexes `(device_id, recorded_at)` or `(group_id, created_at)` so row scans stay proportional to returned pages, protecting the 5M rows-read/day free limit.
- **No Unprotected Endpoints:** Public routes are strictly limited to `/v1/health`, `/v1/auth/admin/register`, `/v1/auth/admin/login`, `/v1/auth/admin/recover`, `/v1/auth/refresh`, `/v1/invitations/validate`, and `/v1/devices/enroll` — all protected by strict Zod validation and rate limiting.
