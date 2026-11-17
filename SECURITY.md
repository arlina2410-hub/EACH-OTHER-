# SECURITY.md — EACH OTHER

**Phase:** Phase 2 — ECDSA P-256 Authentication, Recovery & Threat Model  
**Source of Truth:** `PRD.md`

---

## 1. Authentication & Credential Architecture (Option 2 — Approved)

### 1.1 Administrator Authentication (Android Keystore ECDSA P-256)
- **Account Identifier:** Administrator's email address (used strictly as account identifier, never as a password credential; no email delivery service assumed).
- **Key Generation & Storage:**
  - Each Administrator device generates an **ECDSA P-256 (`secp256r1`)** key pair inside hardware-backed **Android Keystore** (`KeyProperties.KEY_ALGORITHM_EC`, `PURPOSE_SIGN`, `DIGEST_SHA256`).
  - The private key never leaves Android Keystore and cannot be exported or uploaded.
  - Only the X.509 **SubjectPublicKeyInfo (SPKI)** DER public key is uploaded during registration, pairing, or recovery.
- **Challenge-Response Protocol:**
  1. Client requests `POST /v1/auth/admin/challenge` with `{ email, adminDeviceId, operation }`.
  2. Server issues a single-use, 120-second expiring challenge containing a 256-bit random `nonce`, bound in D1 to `(challengeId, accountId, adminDeviceId, operation, expiresAtMs)`.
  3. Client signs canonical challenge bytes `"eachother:auth:v1|<challengeId>|<accountId>|<adminDeviceId>|<operation>|<nonce>|<expiresAtMs>"` using Android Keystore `SHA256withECDSA`.
  4. **Signature Interoperability:** Android `SHA256withECDSA` outputs ASN.1 DER `(r, s)` bytes; Cloudflare Workers Web Crypto (`ECDSA` `P-256` `SHA-256`) expects 64-byte IEEE P1363 `(r || s)`. Both Android and the Worker perform strict ASN.1 DER-to-P1363 conversion and validation (tested with cross-platform vectors).
  5. Server atomically consumes the challenge (`used_at_ms IS NULL`), verifies the signature against the registered `public_key_spki` (confirming `admin_devices.status == 'ACTIVE'`), and issues a short-lived HS256 JWT (15m) + rotating opaque refresh token (30d).

### 1.2 Multi-Device Support & Lost-Phone Recovery
- **Multiple Admin Devices:** An authenticated Admin can issue a short-lived, single-use **Admin Device Pairing Code** (`POST /v1/admin/admin-devices/invite`). A second device generates its own Keystore ECDSA P-256 keypair and enrolls via `POST /v1/auth/admin/devices/enroll`.
- **Admin Device Revocation:** Any Admin can view all registered Admin devices (name, registration date, last auth, status) and revoke a lost/unwanted device (`POST /v1/admin/admin-devices/{id}/revoke`), immediately invalidating all its sessions and blocking future challenge-response logins.
- **10 One-Time Recovery Codes:**
  - Generated at account creation (10 cryptographically random codes, revealed **once** with explicit offline storage warning).
  - Stored in D1 only as `SHA-256(normalizedCode)`.
  - Recovery (`POST /v1/auth/admin/recover`) requires `{ email, recoveryCode, newDeviceName, newPublicKeySpki }` (rate-limited), consumes the code once, registers the replacement device's new Keystore public key, **revokes all previous Admin devices and all previous Admin sessions** (never silently preserving compromised devices), and issues fresh tokens.
  - Safe regeneration (`POST /v1/auth/admin/recovery-codes/regenerate`) invalidates unused old codes and returns 10 new codes once.

### 1.3 Connected Device Enrollment & Authentication
- **Invitation Generation:** 12-character Crockford Base32 (`~60 bits` entropy), single-use, time-limited (5m–24h), revocable, represented identically as text code and `eachother://join?code=...` QR payload. Stored in D1 only as `SHA-256`.
- **Single Group Rule:** A Connected Device joins strictly **one** group. Must explicitly disconnect before joining another.
- **Per-Request Revocation Check:** Every authenticated request verifies `auth_sessions.revoked_at_ms IS NULL` and `devices.status != 'REVOKED'` / `admin_devices.status == 'ACTIVE'`.

### 1.4 Android Credential Storage
- Access & refresh tokens + local role/device state are encrypted in DataStore via **Android Keystore AES-256-GCM**.
- `android:allowBackup="false"` and `data_extraction_rules.xml` exclude Keystore prefs and Room databases.

---

## 2. Threat Model & Mitigations

| Threat | Mitigation | Residual Risk |
| --- | --- | --- |
| **Connected user reads another user's or admin's data** | All `/v1/admin/*` routes require `role == 'ADMIN'`; queries derive `groupId`/`deviceId` strictly from verified JWT. | Low. |
| **Stolen/replayed Admin challenge signature** | Challenges are bound to `accountId + adminDeviceId + operation + nonce + 120s expiry` and atomically marked `used_at_ms` on first verification (`CHANGES == 1`). | None for replay. |
| **Compromised or lost Admin phone** | Admin revokes the device from another registered Admin phone, OR uses a recovery code on a replacement phone which revokes ALL prior Admin devices and sessions. | Access remains until revocation/recovery if the lost phone is unlocked without screen lock. |
| **Forged replacement device during recovery** | Rate-limited recovery requires a valid single-use high-entropy recovery code; invalid attempts never reveal code validity beyond uniform error. | Keep the 10 printed/offline recovery codes physically safe. |
| **Brute-force invitation guessing** | 60-bit Crockford code + strict IP rate limit (`10/15m`) + single-use + uniform `404 INVITATION_INVALID`. | Distributed brute-force across IPs remains rate-bounded by Cloudflare edge. |
| **Deleted records re-uploaded from offline queue** | Tombstone checks on `/sync/batch` + `/commands` before upload. | Cached copy stays on offline device until reconnect. |
