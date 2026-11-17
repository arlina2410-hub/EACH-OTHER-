# API_SPECIFICATION.md — EACH OTHER

**Phase:** Phase 2.1 — Hardened Auth, Monotonic Settings, Deletion Ownership & Policy Contracts  
**Format:** `application/json; charset=utf-8` | All timestamps: UTC epoch milliseconds (`number`)  
**Max Body:** `262,144` bytes (`413 PAYLOAD_TOO_LARGE`)

---

## 1. Administrator ECDSA P-256 Authentication & Recovery

### `POST /v1/auth/admin/register` (Public, Rate-limited: 5 req/hr/IP)
Registers a new Administrator account, group, first Keystore ECDSA P-256 device, and 10 one-time recovery codes (stored hashed via domain-separated `HMAC-SHA-256` with `CODE_HASH_PEPPER`; fails closed with `500 SERVER_MISCONFIGURED` if secret absent).
- **Request:** `{ "email": string, "groupName": string (1..50), "deviceName": string (1..40), "publicKeySpki": string (base64 SPKI P-256) }`
- **Response (`201`):**
  ```json
  {
    "accountId": "uuid",
    "adminDeviceId": "uuid",
    "groupId": "uuid",
    "groupName": "Private Group",
    "accessToken": "jwt",
    "refreshToken": "opaque",
    "expiresInSec": 900,
    "recoveryCodes": ["ABCD-EFGH-JKLM", "..."]
  }
  ```

### `POST /v1/auth/admin/challenge` (Public, Rate-limited: 20 req/15m/IP)
Issues a single-use 120-second cryptographic challenge bound to account, registered Admin device, and operation.
- **Request:** `{ "email": string, "adminDeviceId": "uuid", "operation": "LOGIN" }`
- **Response (`200`):**
  ```json
  {
    "challengeId": "uuid",
    "canonicalPayload": "eachother:auth:v1|<challengeId>|<accountId>|<adminDeviceId>|LOGIN|<nonce>|<expiresAtMs>",
    "expiresAtMs": 1794900120000
  }
  ```

### `POST /v1/auth/admin/verify` (Public, Rate-limited: 20 req/15m/IP)
Verifies the Keystore `SHA256withECDSA` signature (DER or raw 64-byte P1363) over the bound challenge, atomically consumes the challenge once, and issues session tokens bound to a new `auth_session_families` lineage.
- **Request:** `{ "challengeId": "uuid", "signature": string (base64 DER or P1363 raw) }`
- **Response (`200`):** `{ "accountId", "adminDeviceId", "groupId", "groupName", "accessToken", "refreshToken", "expiresInSec" }`

### `POST /v1/auth/admin/recover` (Public, Rate-limited: 5 req/hr/IP AND 5 req/hr/account)
Consumes one recovery code, registers the replacement device's new ECDSA P-256 keypair, and **revokes all prior Administrator devices, session families, active sessions, and unused pairing invites**.
- **Request:** `{ "email": string, "recoveryCode": string, "newDeviceName": string (1..40), "newPublicKeySpki": string (base64 SPKI P-256) }`
- **Response (`200`):** `{ "accountId", "adminDeviceId", "groupId", "groupName", "accessToken", "refreshToken", "expiresInSec", "revokedPriorDevicesCount": number }`

### `POST /v1/auth/admin/devices/enroll` (Public with pairing code, Rate-limited: 10 req/15m/IP)
Enrolls an additional Administrator device using a single-use pairing code issued by an existing Admin.
- **Request:** `{ "pairingCode": string, "deviceName": string (1..40), "publicKeySpki": string }`
- **Response (`201`):** `{ "accountId", "adminDeviceId", "groupId", "groupName", "accessToken", "refreshToken", "expiresInSec" }`

### `POST /v1/auth/admin/recovery-codes/regenerate` (Auth: `ADMIN` JWT)
Invalidates unused recovery codes and returns 10 new codes (shown once).

### `POST /v1/auth/refresh` (Public with valid refresh token, Rate-limited: 30 req/15m/IP)
Atomically consumes a single-use refresh token and rotates into a new session within the same `auth_session_families` ID.
- **Success (`200`):** `{ "accessToken": string, "refreshToken": string, "expiresInSec": 900 }`
- **Errors (`401`):**
  - `REFRESH_TOKEN_REUSED` — token was already rotated; revokes the entire `auth_session_families` lineage and all sessions in that family.
  - `REFRESH_TOKEN_REVOKED` — session or session family was previously revoked.
  - `REFRESH_TOKEN_EXPIRED` — refresh TTL elapsed.
  - `INVALID_REFRESH_TOKEN` — unrecognized token.

---

## 2. Administrator Device & Group Management (Auth: `ADMIN` JWT)

- `GET /v1/admin/admin-devices` — Lists all registered Administrator devices.
- `POST /v1/admin/admin-devices/invite` — Creates a short-lived single-use pairing code to add another Admin device (`ttlMinutes`: 5..60).
- `POST /v1/admin/admin-devices/{adminDeviceId}/revoke` — Revokes a lost/unwanted Admin device and its session families (`400 LAST_ACTIVE_ADMIN_DEVICE` if trying to revoke the sole remaining active Admin device).
- `POST /v1/admin/invitations` / `GET /v1/admin/invitations` / `POST /v1/admin/invitations/{invitationId}/revoke`
- `GET /v1/admin/devices` / `GET /v1/admin/devices/{deviceId}`
- `POST /v1/admin/devices/{deviceId}/disconnect` (`{ "deleteServerData": boolean }`)
  - Marks the device `REVOKED` and revokes its sessions/session families.
  - When `deleteServerData == true`, deletes server rows and creates tombstones across all categories, returning an honest `deletionResult` with `deviceDeletionStatus: "DEVICE_REVOKED_LOCAL_UNVERIFIED"` and `localWipeGuaranteed: false` (because revoked devices can no longer call `/commands` or `/commands/ack`).
- `POST /v1/admin/devices/{deviceId}/deletion-commands` — Applies pre-delete tombstones across all 4 scopes (`SINGLE_RECORD`, `DATE_RANGE`, `CATEGORY`, `ALL_DEVICE_DATA`), deletes matching server rows, and creates a `PENDING_DEVICE_ACK` command.
- `GET /v1/admin/storage` / `GET /v1/admin/export`

---

## 3. Connected Device Enrollment, Consent & Sync

- `POST /v1/invitations/validate` (Public, Rate-limited: 10/15m/IP)
- `POST /v1/devices/enroll` (Public with single-use invite, Rate-limited: 10/15m/IP)
- `PUT /v1/devices/me/sharing-settings` (Auth: `DEVICE` JWT)
  - Atomic monotonic conditional update (`WHERE device_id = ? AND settings_version < ?`).
  - **Conflict (`409 SETTINGS_VERSION_CONFLICT`):** Returned whenever `settingsVersion <= server.settings_version`; includes `{ "error": {...}, "authoritativeSettings": { ... } }` so the Android client reconciles immediately to authoritative server state without overwriting newer decisions.
  - **Response (`200`):** Includes persisted category consent choices (`globalPaused`, `locationEnabled`, `usageEnabled`, `deviceStatusEnabled`, `settingsVersion`) plus derived `effectiveSharing: { globalPaused, locationActive, usageActive, deviceStatusActive }`. Global Pause preserves category toggles while forcing every `effectiveSharing.*Active` flag `false`.
- `GET /v1/devices/me/sync-state` (Auth: `DEVICE` JWT) — Returns device status, `pendingDeletionCount`, `settingsVersion`, and authoritative `effectiveSharing`.
- `POST /v1/devices/revoke` (Auth: `DEVICE` JWT) — Connected User self-disconnects (`status = 'REVOKED'`, revokes sessions and families).
- `GET /v1/devices/me/commands` (Auth: `DEVICE` JWT) — Lists `PENDING_DEVICE_ACK` deletion commands owned by this device.
- `POST /v1/devices/me/commands/ack` (Auth: `DEVICE` JWT)
  - Acknowledges **only** commands owned by the authenticated `deviceId`.
  - **Response (`200`):** `{ "acknowledgedIds": string[], "rejectedCommandIds": string[] }` (foreign/nonexistent/non-pending IDs are never echoed as acknowledged).
- `POST /v1/devices/me/deletions`, `GET /v1/devices/me/export`
- `POST /v1/sync/batch` — Scheduled for **Phase 3** (Offline-First Data Foundation).
  - **Server-Side Pause & Consent Contract (`consentPolicyService.ts`):** Future report submission handlers must call `assertReportConsentAllowed`, which rejects globally paused devices with **`403 SHARING_PAUSED`** and disabled/unpermitted categories with **`403 CATEGORY_NOT_ENABLED`**. Meanwhile, device control endpoints (`/sync-state`, `/sharing-settings`, `/commands`, `/commands/ack`, `/deletions`, `/export`, `/auth/refresh`) remain allowed while paused.
