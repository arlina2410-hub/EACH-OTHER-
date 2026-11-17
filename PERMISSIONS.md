# PERMISSIONS.md — EACH OTHER

**Phase:** Phase 0 — Android Permissions, Disclosures & Restrictions  
**Target SDK:** `compileSdk = 35`, `targetSdk = 35`, `minSdk = 26`  

---

## 1. Permission Matrix (Strictly Scoped to PRD)

**Explicitly excluded (NEVER declared in `AndroidManifest.xml`):**
`CAMERA`, `RECORD_AUDIO`, `READ_SMS`, `RECEIVE_SMS`, `READ_CONTACTS`, `READ_CALL_LOG`, `READ_MEDIA_*`, `SYSTEM_ALERT_WINDOW`, `BIND_ACCESSIBILITY_SERVICE`, `REQUEST_INSTALL_PACKAGES`.

### Declared Manifest Permissions

| Permission | Protection Level | Required When | Purpose |
| --- | --- | --- | --- |
| `INTERNET` | Normal | Always (install-time) | HTTPS communication with Cloudflare Worker API |
| `ACCESS_NETWORK_STATE` | Normal | Always (install-time) | Detect offline vs. Wi-Fi/Cellular connectivity for WorkManager & status reporting |
| `RECEIVE_BOOT_COMPLETED` | Normal | Always (install-time) | Re-schedule WorkManager sync/collection tasks after device reboot (never starts FGS directly on API 35) |
| `POST_NOTIFICATIONS` | Runtime (API 33+) | When user enables active sharing / persistent location FGS | Display truthful persistent notification & sync/revocation alerts |
| `ACCESS_COARSE_LOCATION` | Runtime | Only if user enables Location Sharing | Approximate location fix (requested together with Fine location) |
| `ACCESS_FINE_LOCATION` | Runtime | Only if user enables Location Sharing | Accurate location fix (latitude, longitude, accuracy_m) |
| `ACCESS_BACKGROUND_LOCATION` | Runtime (2-step Settings flow, API 29+) | Only if user enables Background Location mode | Allows WorkManager periodic fixes (~15m target) while app UI is closed |
| `FOREGROUND_SERVICE` | Normal (API 28+) | Only if user enables Persistent Active Location mode | Base permission for Foreground Service |
| `FOREGROUND_SERVICE_LOCATION` | Normal (API 34+) | Only if user enables Persistent Active Location mode | Required `foregroundServiceType="location"` permission |
| `PACKAGE_USAGE_STATS` | Special AppOps (`tools:ignore="ProtectedPermissions"`) | Only if user enables App Usage Reporting | Read daily app foreground durations via `UsageStatsManager` |

---

## 2. Step-by-Step Permission Flows & Plain-Language Disclosures

Permissions are **never** requested at first launch or bundled together. Each category has an independent card and pre-prompt disclosure screen.

### 2.1 Location Sharing Flow
1. **Disclosure Screen (before OS prompt):**
   - *What is collected:* Latitude, longitude, accuracy (meters), provider (`fused`/`gps`), and exact UTC timestamp.
   - *Frequency:* Targeted every ~15 minutes when enabled (subject to Android battery/Doze limits).
   - *Who sees it:* Only the Administrator of your joined group.
2. **Step 1 — Foreground Location:** Request `ACCESS_FINE_LOCATION` + `ACCESS_COARSE_LOCATION` via `ActivityResultContracts.RequestMultiplePermissions`.
   - If user grants *Only Coarse*: App accepts coarse location, reports lower accuracy honestly, and never forces Precise.
   - If user denies once: Show plain explanation + "Try again" button.
   - If permanently denied (`shouldShowRequestPermissionRationale == false` after denial): Show "Open App Settings" button; never auto-redirect without tap.
3. **Step 2 — Background Location (`ACCESS_BACKGROUND_LOCATION`, API 29+):**
   - Only offered **after** Step 1 is granted and explained separately:
   - *"To share periodic updates when the app is closed, Android requires 'Allow all the time' in Settings — or you can use Persistent Notification Mode instead."*
   - On Android 11+ (API 30+), Android requires sending the user to App Info -> Permissions -> Location ("Allow all the time").

### 2.2 App Usage Reporting Flow
1. **Disclosure Screen:**
   - *What is collected:* Daily total foreground time per installed app (app name, package ID, duration, date window).
   - *What is NOT collected:* Never messages, typed text, passwords, screenshots, or screen contents.
2. **Check via `AppOpsManager.unsafeCheckOpNoThrow(OPSTR_GET_USAGE_STATS, ...)`** (using `checkOpNoThrow` on API < 29).
3. If not granted: User taps **"Open Usage Access Settings"** -> launches `Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)`.
4. On `ON_RESUME` and before every collection run: Re-check Usage Access. If revoked in Android Settings, automatically mark category `PERMISSION_REQUIRED`, stop usage collection, and update sharing status.

### 2.3 Basic Device Status Flow
- **No dangerous runtime permissions required!**
- `BatteryManager` (battery %, charging state) and `PowerManager.isInteractive` require zero permissions.
- `ConnectivityManager` requires `ACCESS_NETWORK_STATE` (normal permission).
- Still gated strictly behind the user's explicit **"Basic Device Status"** consent toggle (default OFF).

### 2.4 Battery Optimization & Background Execution Awareness
- On `ON_RESUME`, check:
  - `PowerManager.isIgnoringBatteryOptimizations(packageName)`
  - `ActivityManager.isBackgroundRestricted()` (API 28+)
- If background execution is restricted, display a calm informational banner: *"Android battery optimization may delay ~15 minute updates while your screen is off."* Provide an optional button to review system battery settings without coercive language.

---

## 3. Persistent Foreground Service Notification (When Active)

If the user enables Persistent Active Location mode (FGS `foregroundServiceType="location"`):
- Notification channel: `EACH OTHER — Active Sharing` (`IMPORTANCE_LOW`, non-intrusive, non-dismissible while FGS runs).
- **Title:** `"EACH OTHER: Location sharing is active"`
- **Body:** `"Sharing periodic location with <Group Name>. Tap to manage or pause."`
- **Action Button:** `"Pause Sharing"` (directly pauses sharing and stops the FGS cleanly) + tap opens `ConnectedHomeScreen`.
