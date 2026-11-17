# UI_UX_SPECIFICATION.md — EACH OTHER

**Phase:** Phase 0 — Navigation, Screen Map & Visual System  
**Design System:** Native Jetpack Compose + Material 3  

---

## 1. Visual & UX Principles

- **Tone:** Calm, transparent, trustworthy, privacy-first utility — never surveillance-themed or alarmist.
- **Low-End Device Efficiency:** `LazyColumn` with stable keys, minimal overdraw, zero heavy Lottie/blur/shader effects, compact vector icons.
- **Accessibility:** Supports dynamic font scaling (`sp`), minimum `48dp` touch targets, high-contrast semantic badges that ALWAYS pair **icon + text label + color** (never color alone).
- **Honest Timestamps & Stale Indicators:**
  - **Recent:** Synced `< 30 min` ago (neutral/positive badge).
  - **Delayed:** `30 min – 3 hours` since last sync -> `"Delayed • Last synced 1h 12m ago"`.
  - **Stale / Offline:** `> 3 hours` since last sync -> `"Stale • Last synced Nov 16, 14:20"` (warning surface, never "Live").

---

## 2. Navigation Graph & Screen Inventory

### 2.1 Shared Onboarding & Settings
1. **`SplashRoute`:** Checks Keystore session + stored role -> routes immediately to `Onboarding`, `AdminDashboard`, or `ConnectedHome`.
2. **`RoleSelectionScreen`:**
   - Plain-language summary of EACH OTHER & consent model.
   - Two clear cards: **"I am the Administrator"** and **"I am a Connected User"**.
   - Zero permission prompts on this screen.
3. **`PrivacyOverviewScreen`:** Summarizes one-way reporting, what is never collected, and how pause/disconnect work.
4. **`AppSettingsScreen`:** App version, clear local cache, open-source licenses/privacy summary, sign out / reset role (when disconnected).

### 2.2 Connected User Flow (Consent-First)
5. **`JoinGroupScreen`:**
   - Enter 12-char invite code (formatted `XXXX-XXXX-XXXX`) or scan/import QR (note: if camera permission is avoided per minimal-permission rule, **paste/type code or import QR image from system photo picker without CAMERA permission**! — see `PERMISSIONS.md` strict exclusion of `CAMERA`).
   - Editable device display name (e.g., `"Alex's Phone"`).
   - Verifies invite via `/v1/invitations/validate` -> shows `"Joining: <Group Name>"` confirmation before calling `/v1/devices/enroll`.
6. **`ConnectedHomeScreen` (Primary Dashboard):**
   - **Top Status Banner:** Global state (`Sharing Active`, `Sharing Paused`, `Offline`, `Waiting to Sync (N)`, `Sync Error`, `Disconnected/Revoked`).
   - **Global Pause / Resume Switch:** Prominent top card to pause all collection & uploads immediately.
   - **Three Category Cards (Location, App Usage, Device Status):**
     - Each shows toggle (`ON`/`OFF`), current permission state (`Granted`, `Permission Needed`, `Background Restricted`), and tap-through to disclosure/details.
   - **Sync Summary Card:** Pending record count, last attempt, last successful sync timestamp, `"Sync Now"` button, and honest WorkManager note (*"Background sync runs automatically when online; Android may delay updates in Doze mode."*).
   - **Background Restriction Warning Banner** (shown only if Android restricts battery/background execution).
7. **`CategoryDisclosureSheet / Screen` (per category):**
   - Lists exact fields collected, collection cadence, and what is NOT collected.
   - Triggers only that category's permission flow after user taps **"I Understand & Enable"**.
8. **`LocalHistoryAndControlsScreen`:**
   - Lets user inspect their own recent local queued/synced records, delete local history, or **Disconnect from Group** (with clear confirmation explaining server-retained data policy).

### 2.3 Administrator Flow
9. **`AdminAuthScreen`:** Tabbed Sign In / Create Admin Account + Group + `"Use Recovery Code"` flow; shows generated 10 recovery codes once with mandatory `"I saved these codes"` confirmation checkbox.
10. **`AdminDashboardScreen` (Connected Devices List):**
    - Empty state with `"Create Invite"` CTA when 0 devices joined.
    - Device cards showing: Display name, overall sync state badge (`Synced`, `Delayed`, `Stale`, `Paused`, `Revoked`), enabled categories pills, last sync timestamp, and pending offline deletion indicator if applicable.
11. **`InviteManagementScreen`:**
    - Create short-lived invitation (optional label + TTL selector), displays invite code + copy button + locally rendered QR code (via ZXing, zero network/camera needed).
    - List of active/used/expired invitations with `"Revoke"` action.
12. **`DeviceDetailScreen`:**
    - Device summary + sharing settings snapshot (read-only view of what the Connected User has enabled or paused, plus permission warnings reported by the device).
    - **Latest Known Location Card:** Coordinates, accuracy (`±18 m`), recorded timestamp, sync timestamp, explicit **Stale/Current** label, and `"Open in Maps app"` intent (zero Google Maps SDK key required for ₹0 budget; optional OSM/map preview deferred only if needed).
    - Navigation tabs/buttons to **Location History**, **App Usage**, **Device Status**, and **Manage & Delete Data**.
13. **`LocationHistoryScreen`:** Date range filter + paginated `LazyColumn` of fixes with timestamps, accuracy, and single-record delete action.
14. **`AppUsageReportScreen`:** Toggle `Daily` / `Weekly`, date selector, ranked app list (`app_label`, duration bar, `data_completeness` gap badge when Android UsageStats had incomplete data).
15. **`DeviceStatusReportScreen`:** Paginated battery %, charging state, network transport, screen interactive state, and observed timestamp.
16. **`DeletionAndDisconnectScreen`:**
    - Delete by: single record, date range + category, entire category, or all device data.
    - Displays server deletion result vs. `"Waiting for offline device acknowledgment"` status.
    - `"Disconnect & Revoke Device"` with destructive confirmation dialog.
