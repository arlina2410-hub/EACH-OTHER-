# EACH OTHER

## Product Requirements Document (PRD) and Technical Build Specification

**Document status:** Phase 0 --- Product and engineering baseline  
**Product type:** Private Android application with consent-based device reporting  
**Primary platform:** Android  
**Initial distribution:** Private APK sharing; no Play Store launch required  
**Budget target:** ₹0 for initial development and testing  
**Document purpose:** This is the single source of truth for the AI coding agent. Read the entire document before making changes. Do not invent requirements that conflict with it.

---

# 1. Product summary

**EACH OTHER** is a private, consent-based Android application that lets a person share selected device activity and location information with a trusted person.

The app has two roles:

- **Administrator (Owner):** The person who creates the private group and receives information that connected users have explicitly chosen to share.
- **Connected User:** A person who installs the same app, chooses to connect to the administrator, reviews the disclosures, grants Android permissions individually, and enables the specific sharing features they want.

The relationship is intentionally one-way for reporting: the administrator can view information shared by connected users. A connected user does not automatically receive the administrator's personal reports, and one connected user must never see another connected user's reports.

The app is not intended to be a hidden tracker, spyware, employee surveillance product, or a way to bypass Android security. Sharing must be visible, informed, revocable, and controlled by the person whose device is reporting.

## 1.1 The core idea

A connected user can choose to share supported information such as:

- Their device's location, periodically, when they have enabled location sharing.
- App usage summaries, when they have granted Android Usage Access and enabled usage reporting.
- Selected basic device status, where Android exposes it through supported APIs.

The connected device records permitted information locally. If it has no internet connection, eligible records are queued locally. When connectivity returns, the app synchronizes pending records automatically in the background, subject to Android scheduling and background-execution limits.

The administrator sees the latest successfully synchronized information, historical reports, timestamps, and the reporting device's last sync status.

**Important limitation:** An administrator cannot receive new information from a remote device while that device has no internet or other communication path. The source phone may still record location locally if GPS is available, but those records cannot reach the administrator until connectivity returns. Nearby Bluetooth or Wi-Fi Direct communication is not a substitute for remote internet connectivity.

## 1.2 Product principles

1. Consent before collection.
2. Clear disclosure before Android permission requests.
3. Visible sharing state and persistent Android notifications wherever required.
4. Individual feature controls; do not bundle unrelated permissions into one consent.
5. Easy pause, revoke, disconnect, and delete controls.
6. No covert collection or permission bypass.
7. Local-first capture and resilient synchronization.
8. Honest status indicators; never pretend a stale report is live.
9. Server-side authorization for every private record.
10. Efficient operation on low-end Android phones.

---

# 2. Goals and non-goals

## 2.1 Goals

- Build one Android APK that supports both Administrator and Connected User roles.
- Allow the administrator to connect multiple trusted users/devices.
- Allow each connected user to understand and control what they share.
- Support periodic location reporting, targeting approximately 15-minute intervals where Android permits.
- Support authorized app-usage reporting using Android's supported Usage Stats APIs.
- Support selected basic device-status reporting using supported Android APIs.
- Store records locally when offline and synchronize automatically when connectivity returns.
- Display timestamps, last successful sync, and clear delayed/offline/unavailable states.
- Let the administrator review synchronized history and delete selected data.
- Use a low-cost, maintainable architecture with a ₹0-first development plan.
- Keep memory, storage, battery, network, and APK size requirements appropriate for entry-level devices.

## 2.2 Non-goals

The initial product must not:

- Capture photos or use the camera.
- Record audio or calls.
- Read SMS, messaging-app messages, email, passwords, or private conversations.
- Record keystrokes or clipboard contents.
- Capture screenshots or screen contents.
- Hide the app, conceal its operation, or suppress Android's required foreground-service notifications.
- Bypass Android permission prompts, Usage Access settings, background-location restrictions, or battery controls.
- Claim guaranteed real-time tracking or exact 15-minute delivery.
- Track a phone remotely while it has no communication path.
- Provide connected users access to other users' data.
- Require paid AI APIs or an AI model at runtime.
- Require a public Play Store release for the initial private build.

---

# 3. Users, roles, and access model

## 3.1 Administrator / Owner

The administrator can:

- Create the private group/account.
- Generate or manage a secure invitation for a connected user.
- See only devices that have successfully joined and authorized sharing.
- See each connected device's enabled sharing categories.
- See the latest synchronized location and its recorded timestamp.
- Browse available location history.
- See app-usage summaries and supported device-status reports.
- See whether a device is online, offline, delayed, or has not synchronized recently.
- Delete individual reports, date ranges, categories, or all reports for a selected device, subject to offline-device deletion rules.
- Disconnect a device and revoke its access.
- Manage their own account and local app settings.

The administrator must not be able to silently enable a feature or grant an Android permission on another person's phone.

## 3.2 Connected User

A connected user can:

- Install the same APK and choose the Connected User role.
- Read a plain-language explanation of each information category.
- Join an administrator's group through a secure invitation.
- Grant or deny Android permissions individually.
- Enable or disable each supported sharing category.
- Pause sharing globally.
- Revoke permissions in Android settings.
- See what is being shared and the last successful synchronization time.
- Disconnect from the administrator.
- Request or perform deletion of their local records where appropriate.
- Understand when sharing is paused, permission is missing, the app is offline, or background operation is restricted.

## 3.3 Authorization rules

- Every device has a unique server-side device identity.
- Every report is associated with a specific authenticated device and group.
- The server derives the authorized group and device identity from authenticated credentials; never trust a client-supplied owner ID as authorization.
- An administrator can read only reports belonging to their own group and authorized connected devices.
- A connected user cannot read administrator reports or other connected users' reports.
- A connected user can update only their own device's data and sharing state.
- All sensitive operations require server-side authorization.
- Invitation tokens must be high-entropy, single-use or explicitly revocable, and time-limited.
- Never place long-lived secrets in source code or APK resources.

---

# 4. User journeys

## 4.1 First launch

1. Show the EACH OTHER name and a short explanation.
2. Explain that the app shares only selected information after the device owner enables it.
3. Explain the two roles: Administrator and Connected User.
4. Let the person choose a role.
5. Continue to the relevant setup flow.
6. Do not request sensitive Android permissions before explaining the related feature.

## 4.2 Administrator setup

1. Create an administrator identity using the authentication design selected in Phase 0.
2. Create a private group.
3. Show a secure invitation method, such as a short-lived invite code or QR code.
4. Explain that the invited person must install the app, review sharing details, and approve permissions on their own device.
5. Show an empty connected-devices state until a person joins.

## 4.3 Connected-user onboarding

1. Open the app and choose Connected User.
2. Read a concise explanation of the administrator relationship and reporting direction.
3. Enter or scan an invitation.
4. Verify the invitation with the backend.
5. Show each sharing category separately, including exactly what it collects and why.
6. Let the user choose which categories to enable.
7. Request only the Android permissions or special settings access needed for the selected category.
8. Explain denied permissions and offer a settings link only when appropriate.
9. Show the active sharing state and any required persistent Android notification.
10. Provide a clear pause and disconnect control.

## 4.4 Administrator viewing reports

1. Select a connected device.
2. See its current sharing settings and last successful sync.
3. See latest known location with its timestamp and accuracy where available.
4. Browse location history by date.
5. See app-usage summaries by day or week.
6. See supported device-status history.
7. If the device has not synced recently, label its data as delayed or stale.
8. Never label an old record as live/current.

## 4.5 Offline source device

1. The source device detects that network access is unavailable.
2. Eligible collection continues only if the user has enabled the feature and Android allows it.
3. Records are stored locally with original event timestamps and unique IDs.
4. The app shows that records are waiting to sync.
5. When connectivity returns, WorkManager schedules synchronization.
6. The app uploads bounded batches and verifies server acknowledgments.
7. The app marks only acknowledged records as synchronized.
8. The administrator sees the new records after the backend accepts them.

---

# 5. Functional requirements

## 5.1 Account, group, and device connection

- The same APK supports both roles.
- A role is selected during onboarding and stored locally; server authorization remains authoritative.
- An administrator can create a group.
- An administrator can issue invitations.
- Invitations must expire and be revocable.
- A connected device must explicitly accept the invitation.
- The backend must bind a device to one authorized group according to the chosen product policy.
- Device names should be user-editable and should not expose unnecessary personal information.
- Disconnection must revoke server access promptly.
- Rejoining must require a new valid invitation or approved recovery flow.

## 5.2 Consent and sharing controls

Provide separate controls for:

- Location sharing.
- App-usage reporting.
- Basic device-status reporting.

Requirements:

- Each category has a clear description of collected fields.
- Each category is off until explicitly enabled by the connected user.
- Each category has its own permission flow.
- The user can pause all sharing.
- The user can disable an individual category.
- The UI must reflect permission revocation performed in Android settings.
- The app must not attempt to regain access without a clear user action.
- A sharing-status screen must show enabled, paused, permission-needed, or unavailable states.
- The administrator can view the sharing state but cannot change Android permissions remotely.

## 5.3 Location reporting

### Data to collect

Only when location sharing is enabled and the user has granted the necessary Android access:

- Latitude.
- Longitude.
- Recorded timestamp in UTC.
- Accuracy in meters, if available.
- Provider/source metadata where available and useful.
- Optional altitude/speed/bearing only if justified and supported; do not collect by default without a documented need.
- A locally generated unique record ID.
- Device identifier associated by the authenticated backend.

### Collection behavior

- Target a nominal update interval of approximately 15 minutes.
- This is a target, not a guarantee.
- Android may delay or restrict background work due to Doze, battery optimization, location permission level, OEM behavior, or service restrictions.
- Use supported Android location APIs and the least intrusive permission level that meets the explicitly enabled feature.
- If background location is genuinely required, explain why and request it through Android's official flow.
- Use a foreground location service only when legally and technically appropriate, with a truthful persistent notification.
- Do not create a hidden service or hide its notification.
- Avoid excessive GPS polling; prefer efficient fused/batched updates where appropriate.
- Record actual timestamps, not scheduled timestamps.
- If a location cannot be obtained, do not fabricate coordinates.
- Display accuracy and stale status where possible.

### Administrator view

- Latest known location.
- Timestamp of the location fix.
- Accuracy where available.
- Last successful server synchronization time.
- Location history with date filtering.
- Clear stale/offline labels.
- A map may be used in the UI, but the map must not imply a record is live if it is old.

### Offline behavior

- Save eligible location records locally.
- Upload them later in timestamp order or a documented deterministic order.
- Preserve the original event timestamp.
- Deduplicate by unique record ID.
- Do not claim the administrator can see unsynchronized records.

## 5.4 App-usage reporting

Use Android `UsageStatsManager` and/or `UsageEvents`, subject to current Android API requirements.

### Access

- Usage Access is a special user-granted setting, not a normal runtime permission.
- Explain what it allows before directing the user to Android settings.
- Re-check access when the app resumes and before collection.
- If access is revoked, stop usage collection and show the correct status.

### Data scope

Collect only what is required for usage summaries, such as:

- App package identifier.
- App display name where available.
- Usage duration or foreground interval derived from supported events.
- Date/time window.
- Aggregated daily and weekly totals.
- Most-used apps ranking.

Do not collect:

- Message contents.
- Passwords.
- Text typed into apps.
- Screenshots.
- App screen contents.
- Private conversations.
- Browsing contents or credentials.

### Reporting

- Daily app usage summary.
- Weekly summary.
- Most-used apps.
- Usage duration per app.
- Clearly identify gaps where Android does not provide complete event data.
- Avoid claiming perfect measurement; Android API behavior and vendor restrictions can vary.

## 5.5 Basic device-status reporting

Only collect fields that are available through normal Android APIs and are necessary for the product.

Potential fields:

- Battery percentage.
- Charging/not charging state.
- Timestamp of observation.
- Network connectivity state (connected/disconnected and transport category where available).
- Screen interactive/on-off state only if supported and justified.
- App's own sync/permission/service health state.

Limitations:

- Do not promise complete mobile-data toggle history.
- Android may restrict access to certain system state transitions.
- Do not collect Wi-Fi passwords, network traffic contents, or unrelated identifiers.
- Document each field's API source, permission needs, Android-version limitations, and collection frequency before implementation.

## 5.6 Local storage

- Use Room over SQLite for structured local data.
- Store pending reports durably.
- Use database migrations.
- Keep local data scoped to the signed-in device/account.
- Use appropriate Android backup exclusions or backup policy for sensitive local data.
- Avoid storing authentication secrets in plain text.
- Use Android Keystore-backed secure storage for tokens or encryption keys where appropriate.
- Define retention and cleanup for temporary logs/cache, but do not silently delete user-requested location history.
- Local data should remain usable while offline.

## 5.7 Automatic synchronization

Use WorkManager for persistent, constraint-aware background synchronization.

Requirements:

- Network-constrained work.
- Retry with exponential backoff for transient failures.
- Bounded upload batches.
- Idempotent server writes.
- Unique record IDs generated on the source device.
- Preserve original event timestamps.
- Server acknowledgment for accepted records.
- Mark records synced only after confirmed acknowledgment.
- Do not delete pending records just because an upload request was attempted.
- Handle partial batch success safely.
- Reconcile server state after ambiguous network failures.
- Avoid duplicate reports after retries.
- Provide a manual "Sync now" action as a convenience, not as a requirement for normal automatic operation.
- Show last attempt, last success, pending count, and meaningful error state.
- WorkManager is not an exact real-time scheduler; the UI and documentation must say so.

## 5.8 Data deletion and revocation

Administrator deletion controls:

- Delete one report.
- Delete a date range for a selected category/device.
- Delete a category's history for a selected device.
- Delete all reports for a selected device.
- Disconnect/revoke a device.

Deletion behavior:

- Backend authorization is mandatory.
- Use explicit deletion records/tombstones or another robust synchronization strategy.
- A deletion must prevent old pending records from being re-uploaded.
- If a source device is offline, the backend can revoke access and record a pending deletion command.
- Do not claim the source device has deleted local copies until it acknowledges the deletion or the product can otherwise verify it.
- The administrator UI must distinguish "server-side data deleted" from "offline device deletion pending."
- When the device reconnects, it must process deletion commands before uploading potentially deleted records.
- Deletion must be idempotent.
- Maintain only the minimum audit metadata needed to secure deletion operations; do not retain deleted report contents in ordinary application tables.

Connected-user controls:

- Pause sharing.
- Disable individual categories.
- Disconnect.
- Delete local data where supported.
- Explain that disconnecting stops future uploads but does not automatically guarantee deletion of data already received by the administrator; make the product's deletion policy explicit.

## 5.9 Status and notifications

Show distinct states:

- Sharing active.
- Sharing paused.
- Permission required.
- Location unavailable.
- Offline.
- Waiting to sync.
- Syncing.
- Last synced at [timestamp].
- Sync error.
- Background execution may be restricted.

Use Android notifications honestly. Any foreground service must display the required persistent notification and provide an appropriate user-facing control or route back to the app.

---

# 6. Non-functional requirements

## 6.1 Performance and device compatibility

Target entry-level devices with:

- 4 GB RAM.
- 64 GB storage.
- Common Android phones with limited CPU/GPU performance.
- Intermittent or slow mobile internet.

Requirements:

- No heavy background polling.
- No unnecessary animations or large image assets.
- Paginate long history lists.
- Use database indexes for common filters.
- Batch network requests.
- Avoid keeping large datasets in memory.
- Use efficient Compose lists (`LazyColumn`, etc.).
- Avoid requiring a powerful GPU.
- Keep APK size modest and measure actual release APK size.
- Test on a physical low-end device or emulator profile where possible.
- Do not promise compatibility with every Android version until minSdk/targetSdk are researched and selected.

## 6.2 Battery and network

- Location cadence should be energy-conscious.
- Use network constraints and batching.
- Avoid frequent wakeups.
- Do not run a permanent polling loop.
- Document expected battery impact and test it.
- Respect Android background restrictions and user battery settings.

## 6.3 Reliability

- No data loss on ordinary process death or device restart for records already committed locally.
- Crash-safe local writes.
- Retry transient network failures.
- Idempotent sync.
- Clear error states.
- Database migrations tested.
- Server and client timestamps must be handled consistently.

## 6.4 Accessibility and usability

- Plain language.
- Readable text and touch targets.
- Good contrast.
- Support system font scaling.
- Do not rely on color alone for status.
- Provide clear empty, loading, error, offline, and permission-denied states.

---

# 7. Required technology stack

The AI coding agent must use this stack unless Phase 0 research identifies a concrete compatibility or platform limitation. Any proposed change must be documented with a reason and approved before implementation.

## 7.1 Android client

| Area | Required technology | Purpose |
| --- | --- | --- |
| Language | Kotlin | Native Android implementation |
| UI | Jetpack Compose + Material 3 | Native responsive interface |
| Architecture | MVVM with clear data/domain/UI separation | Maintainability and testability |
| Async | Kotlin Coroutines + Flow | Background and reactive data work |
| Dependency injection | Prefer manual/lightweight DI initially; use Hilt only if justified | Avoid unnecessary complexity |
| Local database | Room (SQLite) | Offline-first durable storage |
| Background sync | WorkManager | Persistent network-constrained sync |
| Location | Android location APIs; evaluate Google Play services Fused Location Provider versus platform alternatives | Efficient location collection |
| App usage | UsageStatsManager / UsageEvents | Authorized app-usage summaries |
| Connectivity | Android ConnectivityManager | Network state |
| Battery | Android BatteryManager / supported system broadcasts | Battery state |
| Secure storage | Android Keystore; encrypted token storage where appropriate | Protect credentials |
| Serialization | Kotlin Serialization or another consistent maintained serializer | API payloads |
| Networking | Retrofit + OkHttp or Ktor Client; choose one, not both without reason | HTTPS API |
| Testing | JUnit, coroutine tests, Room/database tests, MockWebServer or equivalent | Automated verification |

The coding agent must inspect the installed Android Gradle Plugin, Kotlin, Gradle, and Android SDK versions before selecting compatible dependency versions. Do not blindly use the newest version if it breaks the existing environment.

## 7.2 Backend

| Area | Required technology | Purpose |
| --- | --- | --- |
| Runtime | Cloudflare Workers | HTTPS API and server-side logic |
| Language | TypeScript | Backend implementation |
| Database | Cloudflare D1 (SQLite) | Central synchronized reports and account/group data |
| Schema changes | Versioned SQL migrations | Safe database evolution |
| Validation | Zod or equivalent runtime schema validation | Validate all untrusted inputs |
| Authentication | Secure device/account authentication designed in Phase 0 | Identify users/devices |
| Secrets | Cloudflare Worker secrets | Never hardcode signing secrets |
| Testing | Vitest and Workers-compatible test tooling | Unit/integration tests |
| Local development | Wrangler | Local Worker/D1 development |

Do not introduce a paid service by default. Check current Cloudflare free-tier limits and design within them. Free-tier limits can change and are not a promise of unlimited use.

## 7.3 Hosting and deployment

- Cloudflare Workers for the API.
- Cloudflare D1 for central data.
- Use a development environment first.
- Do not deploy production during Phase 0.
- Do not purchase a domain during Phase 0.
- Do not expose an unprotected admin endpoint.
- Use HTTPS.
- Keep development and production credentials separate.
- Document deployment steps and costs/limits before any production deployment.

## 7.4 Explicitly avoid unless later approved

- Firebase.
- Supabase.
- Paid cloud databases.
- Paid analytics.
- Paid AI APIs.
- Unnecessary microservices.
- Docker requirement unless a specific dependency truly needs it.
- Heavy cross-platform frameworks such as React Native or Flutter for this native Android project.
- Storing secrets in the APK.
- A custom AI model; this product does not need AI at runtime.

---

# 8. Proposed system architecture

## 8.1 High-level flow

```text
CONNECTED USER'S ANDROID PHONE
  ├── Consent and sharing controls
  ├── Location collector (only when enabled)
  ├── UsageStats collector (only when enabled)
  ├── Device-status collector (only when enabled)
  ├── Room local database
  ├── Pending upload queue
  └── WorkManager sync
          |
          | HTTPS + authenticated, validated batches
          v
CLOUDFLARE WORKER API
  ├── Authentication
  ├── Authorization
  ├── Input validation
  ├── Idempotency checks
  ├── Deletion command handling
  └── D1 repository layer
          |
          v
CLOUDFLARE D1
  ├── Accounts and groups
  ├── Devices and invitations
  ├── Sharing settings
  ├── Location records
  ├── Usage summaries
  ├── Device-status records
  ├── Sync cursors/receipts
  └── Deletion tombstones/commands
          |
          | authenticated read requests
          v
ADMINISTRATOR'S ANDROID PHONE
  ├── Connected-device list
  ├── Latest known location
  ├── Location history
  ├── Usage reports
  ├── Device-status reports
  └── Deletion and device-management controls
```

## 8.2 Android app architecture

Use clear layers:

- **Presentation:** Compose screens, UI state, navigation.
- **Domain:** use cases and business rules.
- **Data:** repositories, Room DAOs, API clients, mappers.
- **Platform services:** location, usage access, connectivity, battery, notifications.
- **Background:** WorkManager jobs and sync orchestration.

Keep Android platform APIs behind interfaces so they can be tested. Avoid putting database, API, and permission logic directly inside Compose screens.

## 8.3 Backend architecture

Use a modular monolith Worker initially:

- Route/controller layer.
- Authentication middleware.
- Authorization policies.
- Runtime validation.
- Service/use-case layer.
- D1 repository layer.
- Error mapping and structured logs.

Do not split into microservices for the MVP.

---

# 9. Data model --- initial proposal

The following is a logical starting point, not permission to create a careless schema. Validate relationships, indexes, retention, and deletion semantics during Phase 0.

## 9.1 Core entities

### accounts
- id
- role or account type where appropriate
- created_at
- status

### groups
- id
- administrator_account_id
- created_at
- status

### devices
- id
- group_id
- account_id or owner relationship as designed
- display_name
- platform
- app_version
- created_at
- last_seen_at
- revoked_at
- status

### invitations
- id
- group_id
- token_hash (never store raw invitation secret if avoidable)
- expires_at
- used_at
- revoked_at
- created_at

### sharing_settings
- device_id
- location_enabled
- usage_enabled
- device_status_enabled
- updated_at
- settings_version

### location_records
- id (client-generated unique ID)
- device_id
- latitude
- longitude
- accuracy_m
- recorded_at
- received_at

### usage_summaries
- id
- device_id
- package_name
- app_label where available
- usage_duration_ms
- period_start
- period_end
- recorded_at
- received_at

### device_status_records
- id
- device_id
- battery_percent
- charging_state
- network_state
- observed_at
- received_at

### sync_receipts
- id or unique idempotency key
- device_id
- record_id
- record_type
- accepted_at

### deletion_commands / tombstones
- id
- device_id
- scope/category
- date range or record IDs as appropriate
- created_at
- acknowledged_at
- status

### device_credentials / sessions
- Store only appropriately protected credential material or token hashes.
- Define rotation, revocation, expiry, and recovery.
- Never store raw reusable secrets unnecessarily.

## 9.2 Data rules

- Use UTC timestamps on the wire.
- Preserve event time separately from server receipt time.
- Use stable unique IDs for idempotency.
- Index common queries by device and recorded timestamp.
- Validate coordinate ranges, payload size, timestamp bounds, and enum values.
- Enforce group/device access in every query.
- Avoid collecting unnecessary personal data.
- Define whether usage data is uploaded as raw intervals, daily summaries, or both. Prefer the least detailed representation that meets the product need.
- Define deletion behavior for every table and derived report.

---

# 10. API specification --- initial endpoint plan

Exact routes and request/response schemas must be finalized in `API_SPECIFICATION.md` before implementation.

Potential route groups:

## 10.1 Authentication and enrollment
- `POST /v1/invitations/validate`
- `POST /v1/devices/enroll`
- `POST /v1/devices/session/refresh` (only if refresh-token architecture is selected)
- `POST /v1/devices/revoke`

## 10.2 Connected-device sync
- `POST /v1/sync/batch`
- `GET /v1/devices/me/sync-state`
- `GET /v1/devices/me/commands`
- `POST /v1/devices/me/commands/ack`

## 10.3 Administrator reads
- `GET /v1/admin/devices`
- `GET /v1/admin/devices/{deviceId}`
- `GET /v1/admin/devices/{deviceId}/locations`
- `GET /v1/admin/devices/{deviceId}/usage`
- `GET /v1/admin/devices/{deviceId}/status`

## 10.4 Administrator actions
- `POST /v1/admin/devices/{deviceId}/deletion-commands`
- `POST /v1/admin/devices/{deviceId}/disconnect`
- `POST /v1/admin/invitations`
- `POST /v1/admin/invitations/{invitationId}/revoke`

Security requirements:
- Validate every request body.
- Authenticate every private endpoint.
- Authorize every device/group relationship server-side.
- Rate-limit enrollment, invitation validation, and sensitive actions.
- Bound batch sizes and request body size.
- Use idempotency for report uploads and deletion commands.
- Return minimal error information; do not leak secrets or internal stack traces.
- Do not trust client-provided role, group, or owner IDs.
- Add tests proving cross-group reads and writes are rejected.

---

# 11. Security and privacy requirements

## 11.1 Data in transit and at rest
- HTTPS only.
- Do not disable TLS certificate validation.
- Use secure credential storage on Android.
- Store Worker secrets using the platform's secret-management mechanism.
- Restrict D1 access to the Worker.
- Avoid logging precise coordinates, tokens, invitation secrets, or full report payloads.
- Define a minimal audit log that does not duplicate sensitive report data.

## 11.2 Authentication
Approved authentication design (selected after Phase 1 CPU benchmarking on Cloudflare Workers Free):
- **Administrator authentication:** Android Keystore ECDSA P-256 (`secp256r1`) asymmetric authentication. Email is used strictly as the account identifier (never as a password credential; no email delivery assumed). Private keys stay inside Android Keystore and never leave the device; public keys (SPKI) are registered with the backend.
- **Challenge-response:** Short-lived, single-use, operation-bound server challenges signed with `SHA256withECDSA` and verified with Cloudflare Workers Web Crypto.
- **Multiple Administrator devices:** Multiple explicitly registered Admin devices are supported, with a device-management view and per-device revocation.
- **Token rotation and revocation:** Short-lived JWTs + rotating refresh tokens with reuse detection and immediate server-side revocation.
- **Recovery:** 10 cryptographically random, single-use hashed recovery codes allow a replacement phone to register a new Keystore key pair while invalidating all prior Administrator device sessions and revoking prior Administrator keys.
- **Connected device enrollment:** Single-use, expiring, revocable, rate-limited 12-character invitations (plus QR representation).

## 11.3 Threat model
At minimum, consider:
- A connected user attempting to access another user's data.
- A connected user attempting to become an administrator.
- A stolen APK or extracted app resources.
- Stolen/replayed device credentials.
- Forged device IDs or group IDs.
- Replayed upload requests.
- Malformed or oversized payloads.
- Unauthorized invitation reuse.
- A revoked device continuing to upload.
- Deleted data being re-uploaded from an offline queue.
- Accidental exposure through logs, backups, or debug builds.
- An administrator's phone being lost or compromised.

Document mitigations and remaining risks. Do not claim the system is "unhackable".

## 11.4 Privacy by design
- Collect only fields listed in this PRD.
- Keep collection off until the user enables it.
- Provide a clear disclosure and sharing-status screen.
- Make pause and disconnect easy to find.
- No camera, microphone, messages, keystrokes, screenshots, or private content.
- No hidden collection.
- No permission bypass.
- No silent remote activation.
- No misleading notification or UI wording.

---

# 12. UI/UX direction

The interface should feel like a polished, modern native Android product, not a generic admin dashboard.

Design goals:
- Calm, clean, premium, and trustworthy.
- Native Jetpack Compose components.
- Material 3 foundations, customized with a consistent design system.
- Responsive on compact and large phones.
- Good typography, spacing, hierarchy, and readable data visualization.
- Subtle motion only; avoid heavy animations.
- Clear status labels and timestamps.
- Strong empty, loading, error, offline, and permission states.
- No deceptive visual patterns.
- Connected users should be able to understand and control sharing without navigating complex menus.

Suggested primary screens:

### Shared
- Splash/launch.
- Role selection.
- Onboarding and privacy explanation.
- Sign-in/enrollment.
- Settings.
- Permission and sharing status.

### Administrator
- Home/dashboard.
- Connected devices.
- Device details.
- Latest location.
- Location history.
- App usage report.
- Device status report.
- Invitations.
- Deletion management.
- Account/security settings.

### Connected User
- Home/sharing status.
- Sharing categories and toggles.
- Location permission status.
- Usage Access status.
- Sync status and pending records.
- Pause sharing.
- Disconnect.
- Privacy/data controls.

Do not build all screens during Phase 0. Phase 0 should document navigation, screen responsibilities, and key states.

---

# 13. Testing and acceptance criteria

## 13.1 Android tests
- Unit tests for domain rules and mappers.
- Room DAO tests.
- Migration tests.
- Sync queue tests.
- Idempotent retry tests.
- Permission-state handling tests.
- Location collector tests using fakes.
- Usage aggregation tests using fixture events.
- ViewModel/UI-state tests.
- Compose UI tests for core onboarding and sharing controls.

## 13.2 Backend tests
- Authentication tests.
- Authorization tests.
- Cross-group access denial tests.
- Invitation expiry/reuse/revocation tests.
- Payload validation tests.
- Idempotent upload tests.
- Partial batch handling tests.
- Deletion command tests.
- Revoked device rejection tests.
- Rate-limit and request-size tests.
- D1 migration tests.

## 13.3 End-to-end scenarios
1. Administrator creates a group and invitation.
2. Connected user joins with valid invitation.
3. Expired/revoked invitation is rejected.
4. Connected user enables location and grants required permission.
5. Location record is stored locally.
6. Network is disabled; record remains pending.
7. Network returns; record synchronizes automatically when WorkManager runs.
8. Administrator sees the record with its original timestamp.
9. Retrying the same upload does not create duplicates.
10. Usage Access denied means usage reports remain unavailable.
11. Revoking Usage Access stops future usage collection.
12. Pausing sharing prevents new uploads.
13. Revoking a device blocks subsequent server requests.
14. Administrator cannot read another group's data.
15. Connected user cannot read another device's reports.
16. Deletion does not allow queued records to reappear.
17. Offline deletion is shown as pending until acknowledgment.
18. A stale device is clearly marked stale, not live.

## 13.4 Definition of done
A feature is not done merely because its screen exists. It must include:
- Implemented behavior.
- Permission and denial handling.
- Offline behavior where applicable.
- Loading/error/empty states.
- Security and authorization tests.
- Relevant automated tests.
- Updated documentation.
- No fabricated test results.

---

# 14. Delivery roadmap

## Phase 0 --- Foundation and feasibility (current phase)

No production implementation.

Tasks:
1. Inspect the existing repository and development environment.
2. Record the actual Android project status and tool versions.
3. Research current Android requirements for background location, foreground services, Usage Access, WorkManager, and target SDK behavior.
4. Validate the feasibility and limitations of each requested data category.
5. Finalize architecture and data flow.
6. Finalize authentication/enrollment design.
7. Define database schema and migrations.
8. Define API routes and payload contracts.
9. Define offline queue, idempotency, and deletion protocol.
10. Define privacy, security, threat model, and permission disclosures.
11. Define screen map and UI states.
12. Create a realistic implementation roadmap and test plan.
13. Identify any feature that is not feasible or requires a changed expectation.

Expected documentation files:
- `PRD.md` --- this complete product specification.
- `PROJECT_OVERVIEW.md`
- `REQUIREMENTS.md`
- `ARCHITECTURE.md`
- `DATABASE_SCHEMA.md`
- `API_SPECIFICATION.md`
- `PERMISSIONS.md`
- `OFFLINE_SYNC.md`
- `SECURITY.md`
- `UI_UX_SPECIFICATION.md`
- `TEST_PLAN.md`
- `DEVELOPMENT_ROADMAP.md`
- `PHASE_STATUS.md`

The AI coding agent may create or refine these documents, but must preserve this PRD as the source of truth and must not silently change product requirements.

Phase 0 exit criteria:
- Android feasibility and limitations documented.
- Stack and compatible versions selected.
- Architecture approved.
- Database and API contracts reviewed.
- Security and deletion design reviewed.
- Implementation phases and acceptance criteria defined.
- No production deployment.
- No paid purchase.
- No full feature implementation.

## Phase 1 --- Project skeleton and secure foundation
- Create/verify native Android project.
- Establish Compose design system and navigation shell.
- Establish Worker project and D1 migrations.
- Implement secure enrollment/authentication foundation.
- Implement role and group authorization.
- Add automated tests.
- No broad collection features until the security foundation passes.

## Phase 2 --- Consent and device management
- Role-specific onboarding.
- Invitation generation and joining.
- Device list and device identity.
- Sharing disclosures and individual controls.
- Pause, disconnect, and revocation.
- Permission status UI.

## Phase 3 --- Offline-first data foundation
- Room entities and DAOs.
- Sync queue.
- Worker batch endpoint.
- Idempotency and acknowledgments.
- WorkManager synchronization.
- Offline/retry testing.

## Phase 4 --- Location reporting
- Permission flow.
- Supported location collection.
- Efficient scheduling/foreground service if required.
- Local history and sync.
- Administrator latest location and history.
- Stale/accuracy status.
- Physical-device battery and background testing.

## Phase 5 --- App usage reporting
- Usage Access explanation and settings flow.
- UsageStats collection.
- Daily/weekly aggregation.
- Sync and administrator reports.
- Revocation and data-gap handling.

## Phase 6 --- Basic device status
- Battery and charging status.
- Supported network state.
- Optional supported screen state only if approved in Phase 0.
- Collection limits and reporting UI.

## Phase 7 --- Deletion, hardening, and release
- Deletion commands and tombstones.
- Offline deletion acknowledgment.
- Security review.
- End-to-end tests.
- Performance and battery tests.
- Release APK build.
- Private installation/update guide.
- Production deployment only after explicit approval.

---

# 15. AI coding agent operating instructions

The AI coding agent must follow these rules throughout the project:

1. Read this entire PRD before taking action.
2. Inspect the actual repository before assuming its structure.
3. Do not delete, overwrite, or restructure existing files without understanding them.
4. Do not start implementation until Phase 0 is complete and the user approves moving to Phase 1.
5. Work in small, reviewable steps.
6. Prefer complete, coherent files over scattered partial snippets when making changes.
7. Before adding a dependency, explain why it is needed and check compatibility.
8. Do not add paid services or require API keys without explicit approval.
9. Do not claim a command, build, test, migration, or deployment succeeded unless it actually ran and its result was checked.
10. Do not fabricate test counts, screenshots, logs, or deployment URLs.
11. Run relevant tests after each implementation step and report exact outcomes.
12. Keep documentation synchronized with implementation.
13. If a requirement conflicts with current Android restrictions, explain the limitation and propose the closest safe alternative. Do not bypass platform security.
14. Never implement hidden monitoring, covert camera/microphone capture, permission bypass, or notification suppression.
15. Never add collection fields beyond this PRD without asking first.
16. Treat server-side authorization as mandatory, not optional.
17. Keep the app usable on low-end devices and intermittent networks.
18. Ask the user one focused question when a decision materially blocks progress; otherwise make a clearly documented, reversible recommendation.
19. Do not deploy, buy a domain, or incur charges without explicit user approval.
20. At the end of each phase, provide:
    - Files created/changed.
    - What works.
    - What does not work yet.
    - Commands/tests actually run and their results.
    - Known limitations.
    - The exact next step requiring user approval.

---

# 16. Open decisions to resolve during Phase 0

Do not guess these silently. Research and recommend a default, then ask for approval if the choice changes product behavior or security.

1. Minimum supported Android version and target SDK.
2. Whether Google Play services are available on all intended phones; select a location implementation that matches distribution targets.
3. Whether location should require "Allow all the time" or whether a visible foreground-service model can meet the intended use with a less broad permission.
4. Authentication model for administrator and connected users.
5. Whether one connected device can belong to more than one administrator group.
6. Whether usage reports should upload raw intervals, daily aggregates, or both.
7. Exact device-status fields and collection frequency.
8. Whether local history on the connected device is retained after server synchronization.
9. How the administrator recovers access if their phone is lost.
10. Expected number of connected devices and expected report volume for Cloudflare free-tier sizing.
11. Exact deletion policy for data already synchronized to the administrator's account.
12. Whether a connected user can delete their own server-synchronized reports directly or must request administrator deletion.

---

# 17. Final product promise

EACH OTHER should make trusted sharing understandable and controllable.

A connected person must always be able to tell:
- What categories are enabled.
- What information each category collects.
- Whether the app has the required Android access.
- Whether the phone is offline or waiting to sync.
- When the last successful synchronization occurred.
- How to pause or stop sharing.

The administrator must always be able to tell:
- Which device a report came from.
- When the information was actually recorded.
- When it reached the server.
- Whether it is current or stale.
- Whether a device is disconnected or has stopped syncing.
- Whether a deletion has completed or is still waiting for an offline device.

**The product must prefer truthful limitations, user control, and data integrity over pretending to offer perfect tracking.**
