# REQUIREMENTS.md — EACH OTHER

**Phase:** Phase 0 — Feasibility, Requirements Analysis & Open Decisions  
**Source of Truth:** `PRD.md`

---

## 1. Platform Feasibility & Android API Limitations (Researched for API 26–35)

Because EACH OTHER targets Android 15 (`targetSdk = 35`) and entry-level phones, the following platform constraints are non-negotiable and shape product expectations:

### 1.1 Background Location & 15-Minute Target
- **WorkManager Constraint:** `PeriodicWorkRequest` enforces a **minimum repeat interval of 15 minutes** and is **deferrable**. During **Doze Mode** (stationary, unplugged, screen off) or **App Standby Buckets** (`RARE` / `RESTRICTED`), WorkManager delays periodic execution until system maintenance windows (which can space runs to 1+ hours).
- **Android 14/15 Foreground Service Rules (`targetSdk >= 34/35`):**
  - Any foreground service collecting location **must** declare `android:foregroundServiceType="location"` and hold `FOREGROUND_SERVICE_LOCATION` plus runtime `ACCESS_FINE_LOCATION` or `ACCESS_COARSE_LOCATION`.
  - Starting a `location` FGS from the background (e.g., from a WorkManager worker or `BOOT_COMPLETED` receiver when the app has no visible UI) is blocked on Android 14+/15 unless the app holds `ACCESS_BACKGROUND_LOCATION` ("Allow all the time") OR the FGS was started while the app was in the foreground (with a persistent notification kept alive).
  - Apps targeting API 35 **cannot** launch restricted FGS types directly from `BOOT_COMPLETED`; boot recovery must schedule WorkManager/JobScheduler work instead.
- **FusedLocationProviderClient vs. Platform `LocationManager`:**
  - `FusedLocationProviderClient` (Play Services) fuses GPS + Wi-Fi + cell towers and shares cached fixes across apps with dramatically lower battery drain than raw `LocationManager` GPS wakeups.
  - However, phones without Google Play Services (e.g., de-Googled custom ROMs / Huawei HMS) will fail if Play Services is a hard dependency.
  - **Feasibility Conclusion:** Periodic ~15-minute reporting is a **best-effort target**, never a guarantee. We must support a clean `LocationProvider` interface defaulting to `FusedLocationProviderClient` (with graceful fallback to platform `LocationManager` if Play Services is unavailable), and clearly display **"Delayed / Background Restricted / Last Synced"** rather than claiming live tracking.

### 1.2 App-Usage Reporting (`UsageStatsManager` / `UsageEvents`)
- **Special Access:** `PACKAGE_USAGE_STATS` is a special AppOps permission (`Settings.ACTION_USAGE_ACCESS_SETTINGS`), **not** a runtime dialog. The user must manually toggle it in Android Settings.
- **Measurement Accuracy Limits:**
  - `UsageEvents` (`MOVE_TO_FOREGROUND` / `MOVE_TO_BACKGROUND`, or `ACTIVITY_RESUMED` / `ACTIVITY_PAUSED` on API 29+) does not always emit paired events across crashes, force-stops, multi-window, or OEM aggressive task killers.
  - System retention of granular `UsageEvents` is short (typically ~7–10 days on device), whereas `queryUsageStats(INTERVAL_DAILY, ...)` aggregates by bucket.
  - `UsageStatsManager` queries from backgrounded apps in restricted standby buckets can return empty or stale results.
- **Privacy Compliance:** We must never inspect notifications, accessibility text, or screen content. Only package ID, resolved app label (when queryable under `<queries>` / `QUERY_ALL_PACKAGES` rules — note: on API 30+ package visibility filtering hides third-party app labels unless `QUERY_ALL_PACKAGES` or launcher intent queries are used; since this is a private sideloaded APK, declaring launcher-category `<queries>` handles almost all user-facing apps cleanly without needing `QUERY_ALL_PACKAGES`), foreground duration, and time window are collected.

### 1.3 Basic Device-Status Reporting
- **Battery (`BatteryManager`):** Battery percentage (`EXTRA_LEVEL`/`EXTRA_SCALE` or `BATTERY_PROPERTY_CAPACITY`) and charging state (`EXTRA_STATUS` / `isCharging`) require **no runtime permissions**.
- **Connectivity (`ConnectivityManager`):** Active transport (`WIFI`, `CELLULAR`, `VPN`, `ETHERNET`, `NONE`) and `NET_CAPABILITY_VALIDATED` require only normal install-time `ACCESS_NETWORK_STATE`.
- **What is NOT feasible reliably:** Exact history of when the user toggled mobile data or Wi-Fi off/on while the app was asleep, Wi-Fi SSIDs (requires `ACCESS_FINE_LOCATION` and is restricted in background), or background screen-unlock logs without a persistent service/receiver.
- **Screen Interactive State:** `PowerManager.isInteractive` gives instantaneous screen on/off state at collection time with zero special permissions, whereas continuous screen-on/off transition counting requires a dynamically registered broadcast receiver (`ACTION_SCREEN_ON`/`OFF`) inside a running process/FGS. Point-in-time `is_interactive` is feasible and low-power; continuous screen-session duration is unnecessary and battery-heavy.

### 1.4 Motorola / OEM Background Restrictions
- OEMs (including Motorola/Lenovo battery management, MIUI, ColorOS, OneUI) may restrict background execution when "Battery optimization" is set to Restricted or Optimized.
- The app must detect `PowerManager.isIgnoringBatteryOptimizations()` and `ActivityManager.isBackgroundRestricted()` (API 28+) and surface an honest UI badge ("Background execution may be restricted by Android") with an optional user-initiated settings shortcut—never bypassing or nagging forcibly.

---

## 2. Functional Requirements Breakdown

| ID | Category | Summary Requirement | Phase |
| --- | --- | --- | --- |
| FR-01 | Onboarding & Roles | Single APK supports Administrator and Connected User role selection at first launch with plain-language disclosures. | Phase 1–2 |
| FR-02 | Group & Enrollment | Admin creates an account & private group, issues time-limited/revocable invitations (code + QR). Connected User joins via invite. | Phase 1–2 |
| FR-03 | Authorization | Server derives group/device/role solely from verified tokens. Zero cross-group or peer-to-peer reads. | Phase 1 |
| FR-04 | Consent Controls | Location, App Usage, and Device Status default to OFF. Separate toggles, separate disclosures, separate permission requests. | Phase 2 |
| FR-05 | Pause & Disconnect | Connected User can pause all sharing globally or disconnect & revoke server credentials at any time. | Phase 2 |
| FR-06 | Offline Queue | Room stores pending records durably with client UUIDs and UTC event timestamps; WorkManager syncs in bounded batches when online. | Phase 3 |
| FR-07 | Location Reporting | ~15-min target collection when enabled + permitted; Admin views latest fix (with stale indicator) and date-filtered history. | Phase 4 |
| FR-08 | App Usage Reporting | Daily/weekly foreground usage summaries & top apps via `UsageStatsManager` when Usage Access is granted; handles revocation & gaps. | Phase 5 |
| FR-09 | Device Status | Battery %, charging state, network transport, and point-in-time `is_interactive` + app health status at collection ticks. | Phase 6 |
| FR-10 | Deletion & Tombstones | Admin deletes 1 record / date range / category / all device data. Offline devices receive deletion commands & ack before re-upload. | Phase 7 |

---

## 3. Non-Functional Requirements

- **Device Profile:** Smooth on 4 GB RAM / 64 GB storage phones; `LazyColumn` pagination (page size 50), indexed Room & D1 tables, no large images or heavy animations, target APK size < 15 MB.
- **CPU & Free-Tier Budget:** Worker CPU < 10 ms/request; avoid full-table scans on D1 (`WHERE device_id = ? AND recorded_at >= ?` backed by composite indexes); batch up to 50 records per `POST /v1/sync/batch`.
- **Reliability:** Crash-safe Room transactions; records marked `SYNCED` only on explicit HTTP 200 per-record acknowledgment.
- **Accessibility:** Material 3 contrast (>= 4.5:1), min 48dp touch targets, font scaling support, non-color status indicators (icons + text badges).

---

## 4. Phase 0 Open Decisions (PRD Section 16) — Recommendations & Approval Items

Each of the 12 open questions from PRD Section 16 has a researched recommendation below. Items marked **[Approval Needed]** affect user-facing behavior or security policy.

| # | Open Decision | Recommended Default | Rationale & Trade-off | Status |
| --- | --- | --- | --- | --- |
| 1 | **Min SDK & Target SDK** | `minSdk = 26` (Android 8.0), `targetSdk = 35` (Android 15), `compileSdk = 35` | Covers >96% active Android devices while avoiding pre-8.0 notification/JobScheduler fragmentation; aligns with modern Moto/Android 14–15 devices. | Recommended default |
| 2 | **Play Services vs. Platform Location** | Hybrid `LocationProvider` abstraction: **Play Services `FusedLocationProviderClient` primary**, fallback to Android `LocationManager` if GMS unavailable | Fused Location saves significant battery on standard GMS phones (like Motorola Moto G-series) while fallback keeps APK functional on non-GMS devices. | Recommended default |
| 3 | **Background Location vs. Foreground Service** | **Tiered model:** Default to WorkManager periodic check + Foreground Service (`foregroundServiceType="location"`) with persistent notification when active sharing is ON; optionally allow `ACCESS_BACKGROUND_LOCATION` ("Allow all the time") if user wants updates without keeping a persistent FGS notification running continuously | **[Approval Needed]** On Android 14/15, either (A) a visible Foreground Service runs while sharing is active (clear persistent notification, no "Allow all the time" needed if started in foreground, though OEMs/swiping may stop it unless restarted by user), or (B) WorkManager runs every ~15 min (subject to Doze) which requires `ACCESS_BACKGROUND_LOCATION` to read location from background. **Recommendation:** Offer WorkManager + optional `ACCESS_BACKGROUND_LOCATION` as primary low-battery path, plus an optional "Persistent active mode (Foreground Service)" toggle if the user wants more reliable ~15m fixes. | **[Approval Needed]** |
| 4 | **Authentication Model** | **Admin:** Email/username + strong password (PBKDF2-SHA256 100k+ iterations via WebCrypto in Workers) + short-lived JWT access token (15m) + rotating opaque refresh token (30d, SHA-256 hashed in D1). **Connected Device:** Single-use 8-char / QR invite + device-generated Ed25519 or ECDSA P-256 keypair (or high-entropy 256-bit device secret) bound at `/v1/devices/enroll` -> device Bearer token + refresh token. | Zero paid IdP needed, ₹0, WebCrypto native in Cloudflare Workers (`< 5 ms` CPU for JWT/HMAC/PBKDF2), cryptographically revocable per device. | **[Approval Needed]** |
| 5 | **Single vs. Multi-Group Device** | **One connected device belongs to strictly ONE group at a time** | Dramatically simplifies consent UI, authorization isolation, deletion semantics, and prevents accidental cross-admin data confusion. Rejoining another group requires disconnecting first. | **[Approval Needed]** |
| 6 | **Usage Data Granularity** | **Daily aggregated summaries per app (`package_name`, `app_label`, `usage_duration_ms`, `date_utc`)** synced periodically, rolled up into Weekly views on Admin UI | Uploading raw `UsageEvents` blows through D1 row-write quotas and exposes needlessly granular behavioral timelines. Daily summaries keep D1 writes tiny (~20–40 rows/device/day) and directly serve Daily/Weekly/Top-Apps reports. | **[Approval Needed]** |
| 7 | **Exact Device-Status Fields & Frequency** | `battery_percent` (0–100), `charging_state` (`CHARGING`/`DISCHARGING`/`FULL`/`UNKNOWN`), `network_state` (`WIFI`/`CELLULAR`/`NONE`/`OTHER`), `is_interactive` (boolean), `app_health` (`OK`/`PERMISSION_MISSING`/`BG_RESTRICTED`); collected alongside sync/location cadence (~15–30 min when enabled) | Zero extra dangerous permissions, low battery, and completely transparent. | Recommended default |
| 8 | **Local History Retention on Connected Device** | **Retain synced records locally for 7 days** (user-configurable or clearable manually via "Delete local history"), purge `SYNCED` rows older than 7 days to protect 64 GB storage | Connected user can verify what they shared recently without unbounded local SQLite growth. `PENDING` rows are never auto-purged. | Recommended default |
| 9 | **Admin Account Recovery (Lost Phone)** | **Email + Password login + 10 one-time Recovery Codes** generated at account creation (stored SHA-256 hashed in D1) | Fits ₹0 offline-email-free setup (no paid SMTP required initially) while allowing Admin to log into a new phone without losing their group. | **[Approval Needed]** |
| 10 | **Scale & Cloudflare Free-Tier Sizing** | Designed for **1–10 connected devices per group**, ~15-min location + hourly/periodic status + daily usage = ~150–250 records/device/day (~2,500 D1 row writes/day for 10 devices — **2.5% of the 100k/day free limit**) | Easily fits within Cloudflare Workers & D1 free tiers with >95% headroom. | Recommended default |
| 11 | **Deletion Policy (Admin vs. Connected)** | Admin deletions permanently delete server D1 rows and enqueue a `deletion_command` tombstone so an offline Connected Device purges matching local pending/cached records before next upload | Prevents "zombie" re-uploads from offline queues while honoring Admin audit/deletion controls. | Recommended default |
| 12 | **Can Connected User Delete Server Reports?** | **No direct server deletion by Connected User in MVP** (Connected User can pause, disable categories, delete local data, and disconnect; Admin controls server-side report deletion). Clear disclosure shown during onboarding. | Matches PRD §5.8 ("Explain that disconnecting stops future uploads but does not automatically guarantee deletion of data already received by the administrator"). Adding self-service server wipe is a clean Phase 7 toggle if desired. | **[Approval Needed]** |
