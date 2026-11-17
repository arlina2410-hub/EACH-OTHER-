# API_SPECIFICATION.md — EACH OTHER

**Phase:** Phase 2 — Consent, Device Management & ECDSA P-256 Auth Contracts  
**Format:** `application/json; charset=utf-8` | All timestamps: UTC epoch milliseconds (`number`)  
**Max Body:** `262,144` bytes (`413 PAYLOAD_TOO_LARGE`)

---

## 1. Administrator ECDSA P-256 Authentication & Recovery

### `POST /v1/auth/admin/register` (Public, Rate-limited: 5 req/hr/IP)
Registers a new Administrator account, group, first Keystore ECDSA P-256 device, and 10 one-time recovery codes.
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
    "recoveryCodes": ["ABCD-1234", "..."]
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
Verifies the Keystore `SHA256withECDSA` signature (DER or raw 64-byte P1363) over the bound challenge, consumes the challenge once, and issues session tokens.
- **Request:** `{ "challengeId": "uuid", "signature": string (base64 DER or P1363 raw) }`
- **Response (`200`):** `{ "accountId", "adminDeviceId", "groupId", "groupName", "accessToken", "refreshToken", "expiresInSec" }`

### `POST /v1/auth/admin/recover` (Public, Rate-limited: 5 req/hr/IP)
Consumes one recovery code, registers the replacement device's new ECDSA P-256 keypair, and **revokes all prior Administrator devices and sessions**.
- **Request:** `{ "email": string, "recoveryCode": string, "newDeviceName": string (1..40), "newPublicKeySpki": string (base64 SPKI P-256) }`
- **Response (`200`):** `{ "accountId", "adminDeviceId", "groupId", "groupName", "accessToken", "refreshToken", "expiresInSec", "revokedPriorDevicesCount": number }`

### `POST /v1/auth/admin/devices/enroll` (Public with pairing code, Rate-limited: 10 req/15m/IP)
Enrolls an additional Administrator device using a single-use pairing code issued by an existing Admin.
- **Request:** `{ "pairingCode": string, "deviceName": string (1..40), "publicKeySpki": string }`
- **Response (`201`):** `{ "accountId", "adminDeviceId", "groupId", "groupName", "accessToken", "refreshToken", "expiresInSec" }`

### `POST /v1/auth/admin/recovery-codes/regenerate` (Auth: `ADMIN` JWT)
Invalidates unused recovery codes and returns 10 new codes (shown once).

### `POST /v1/auth/refresh` (Public with valid refresh token)
Rotates refresh token; detects reuse and revokes entire session family.

---

## 2. Administrator Device & Group Management (Auth: `ADMIN` JWT)

- `GET /v1/admin/admin-devices` — Lists all registered Administrator devices (`id`, `device_name`, `status`, `created_at_ms`, `last_auth_at_ms`, `revoked_at_ms`).
- `POST /v1/admin/admin-devices/invite` — Creates a short-lived single-use pairing code to add another Admin device (`ttlMinutes`: 5..60).
- `POST /v1/admin/admin-devices/{adminDeviceId}/revoke` — Revokes a lost/unwanted Admin device and its sessions (cannot silently self-revoke the last active Admin device without warning or allows revoking any other device; returns `400` if trying to leave group with 0 active admin devices unless intended — allows revoking any other device cleanly).
- `POST /v1/admin/invitations` / `GET /v1/admin/invitations` / `POST /v1/admin/invitations/{invitationId}/revoke`
- `GET /v1/admin/devices` / `GET /v1/admin/devices/{deviceId}` / `POST /v1/admin/devices/{deviceId}/disconnect`
- `POST /v1/admin/devices/{deviceId}/deletion-commands`
- `GET /v1/admin/storage` / `GET /v1/admin/export`

---

## 3. Connected Device Enrollment, Consent & Sync

- `POST /v1/invitations/validate` (Public, Rate-limited: 10/15m/IP)
- `POST /v1/devices/enroll` (Public with single-use invite, Rate-limited: 10/15m/IP)
- `PUT /v1/devices/me/sharing-settings` (Auth: `DEVICE` JWT) — Syncs connected user's consent toggles, global pause flag, and Android permission states (`locationPermissionState`, `usagePermissionState`, `backgroundRestricted`, `settingsVersion`) to server so Admin views truthful sharing state.
- `POST /v1/devices/revoke` (Auth: `DEVICE` JWT) — Connected User self-disconnects (`status = 'REVOKED'`).
- `GET /v1/devices/me/sync-state`, `GET /v1/devices/me/commands`, `POST /v1/devices/me/commands/ack`
- `POST /v1/devices/me/deletions`, `GET /v1/devices/me/export`
- `POST /v1/sync/batch` — Scheduled for **Phase 3** (Offline-First Data Foundation).
