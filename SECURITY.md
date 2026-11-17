# SECURITY.md — EACH OTHER

**Phase:** Phase 2.1 — Security, Reliability & Build Hardening  
**Source of Truth:** `PRD.md`

---

## 1. Authentication & Credential Architecture (Option 2 — Approved)

### 1.1 Administrator Authentication (Android Keystore ECDSA P-256)
- **Account Identifier:** Administrator's email address (used strictly as account identifier, never as a password credential; no email delivery service assumed).
- **Key Generation, Hardware Inspection & Safe Rotation:**
  - Each Administrator device generates an **ECDSA P-256 (`secp256r1`)** key pair inside **Android Keystore** (`KeyProperties.KEY_ALGORITHM_EC`, `PURPOSE_SIGN`, `DIGEST_SHA256`) under unique tracked key IDs (`admin_<uuid>`, with `primary_v1` preserved for legacy sessions).
  - The private key never leaves Android Keystore and cannot be exported or uploaded.
  - **Non-Destructive Key Rotation (`AdminKeyManager.kt` / `AuthRepository.kt`):** Registration, additional-device pairing, and account recovery generate a new candidate key ID first and clean up only the unused candidate on failure or interruption; the previously working active key ID is retired and deleted **only after** server confirmation.
  - **Honest Hardware Status (`KeySecurityLevelMapper`):** Android inspects `KeyInfo.securityLevel` (API 31+) or `KeyInfo.isInsideSecureHardware` (API 26–30) and reports `STRONGBOX_HARDWARE`, `TEE_HARDWARE`, `SOFTWARE`, or `UNKNOWN` truthfully in the UI without falsely claiming hardware isolation if a device falls back to software Keystore.
- **Challenge-Response Protocol:**
  1. Client requests `POST /v1/auth/admin/challenge` with `{ email, adminDeviceId, operation }`.
  2. Server issues a single-use, 120-second expiring challenge containing a 256-bit random `nonce`, bound in D1 to `(challengeId, accountId, adminDeviceId, operation, expiresAtMs)`.
  3. Client signs canonical challenge bytes `"eachother:auth:v1|<challengeId>|<accountId>|<adminDeviceId>|<operation>|<nonce>|<expiresAtMs>"` using Android Keystore `SHA256withECDSA`.
  4. **Signature Interoperability:** Android `SHA256withECDSA` outputs ASN.1 DER `(r, s)` bytes; Cloudflare Workers Web Crypto (`ECDSA` `P-256` `SHA-256`) expects 64-byte IEEE P1363 `(r || s)`. Both Android and the Worker perform strict ASN.1 DER-to-P1363 conversion and validation.
  5. Server atomically consumes the challenge (`used_at_ms IS NULL`), verifies the signature against the registered `public_key_spki` (confirming `admin_devices.status == 'ACTIVE'`), and issues a short-lived HS256 JWT (15m) + single-use rotating refresh token (30d).

### 1.2 Multi-Device Support, Recovery Codes & Server-Peppered Hashing
- **Multiple Admin Devices:** An authenticated Admin can issue a short-lived, single-use **Admin Device Pairing Code** (`POST /v1/admin/admin-devices/invite`). A second device generates its own Keystore ECDSA P-256 keypair and enrolls via `POST /v1/auth/admin/devices/enroll`.
- **Admin Device Revocation:** Any Admin can view all registered Admin devices and revoke a lost/unwanted device (`POST /v1/admin/admin-devices/{id}/revoke`), immediately invalidating all its sessions and session families.
- **Domain-Separated HMAC-SHA-256 Code Storage:**
  - All recovery codes (`recovery-v1`), Administrator pairing codes (`admin-pair-v1`), and Connected Device invitations (`device-invite-v1`) are hashed using **HMAC-SHA-256** keyed with the server secret **`CODE_HASH_PEPPER`** (`hmac-v1:<hex>`).
  - The Worker **fails closed** (`500 SERVER_MISCONFIGURED`) if `CODE_HASH_PEPPER` is absent or shorter than 16 characters.
  - Legacy unpeppered SHA-256 rows are rejected by default and accepted only when `ALLOW_LEGACY_UNPEPPERED_CODES='true'` is explicitly enabled during migration.
- **10 One-Time Recovery Codes:** Revealed **once** at registration/regeneration. Recovery (`POST /v1/auth/admin/recover`) is IP- and account-rate-limited, consumes one code atomically, registers the replacement device's public key, **revokes all previous Admin devices, session families, active sessions, and unused pairing invites**, and issues fresh tokens.

### 1.3 Atomic Single-Use Refresh-Token Rotation & Session Families
- Every login/enrollment creates an `auth_session_families` row linked to all rotated `auth_sessions` in that lineage.
- `POST /v1/auth/refresh` atomically claims `WHERE id = ? AND rotated_at_ms IS NULL AND revoked_at_ms IS NULL AND expires_at_ms > ?`.
- Concurrent requests cannot create multiple valid replacement sessions: only one request wins the atomic claim; any duplicate/replayed use triggers **family-wide revocation** (`auth_session_families.revoked_at_ms`) and invalidates replacement sessions and active JWTs (`authenticateRequest` joins `auth_session_families`).
- Distinct errors distinguish `REFRESH_TOKEN_REUSED`, `REFRESH_TOKEN_REVOKED`, `REFRESH_TOKEN_EXPIRED`, and `INVALID_REFRESH_TOKEN`.
- On Android, `TokenAuthenticator` coordinates concurrent HTTP 401 responses under a synchronization lock so parallel in-flight requests share a single refresh call instead of racing into reuse revocation.

### 1.4 Connected Device Enrollment & Consent Policy Enforcement
- **Invitation Generation:** 12-character Crockford Base32 (`~60 bits` entropy), single-use, time-limited (5m–24h), revocable, `eachother://join?code=...` QR payload, stored as domain-separated `HMAC-SHA-256`.
- **Single Group Rule:** A Connected Device joins strictly **one** group and must disconnect before joining another.
- **Global Pause & Consent Policy (`consentPolicyService.ts`):** `assertReportConsentAllowed` enforces that any globally paused device (`global_paused == 1` or `devices.status == 'PAUSED'`) is rejected (`403 SHARING_PAUSED`) on report submission while preserving category preferences for unpausing and keeping control endpoints reachable.
- **Atomic Rate Limiting & Header Spoofing Defense:** `getClientIpKey` ignores `x-forwarded-for` (client-spoofable), validates `cf-connecting-ip` (IPv4/IPv6), and increments D1 `rate_limits` atomically in a single `INSERT ... ON CONFLICT DO UPDATE ... RETURNING` query with HMAC bucket keys and bounded expired-row cleanup (`cleanupExpiredRateLimits`).

### 1.5 Android Credential Storage
- Access & refresh tokens, tracked `activeKeyId`, and local role/device state are encrypted in DataStore via **Android Keystore AES-256-GCM**.
- `android:allowBackup="false"` and `data_extraction_rules.xml` exclude Keystore prefs and Room databases.

---

## 2. Threat Model & Mitigations

| Threat | Mitigation | Residual Risk |
| --- | --- | --- |
| **Connected user reads another user's or admin's data** | All `/v1/admin/*` routes require `role == 'ADMIN'`; queries derive `groupId`/`deviceId` strictly from verified JWT. | Low. |
| **Stolen/replayed Admin challenge signature** | Challenges are bound to `accountId + adminDeviceId + operation + nonce + 120s expiry` and atomically marked `used_at_ms` on first verification (`CHANGES == 1`). | None for replay. |
| **Offline database leak exposes invite/recovery hashes** | Domain-separated `HMAC-SHA-256` with server-only secret `CODE_HASH_PEPPER`; fails closed if unset. | Secret rotation requires re-issuing codes unless transition handled. |
| **Stolen/raced refresh token replay** | Atomic single-use claim + `auth_session_families` revocation on reuse + Android `TokenAuthenticator` lock. | Access token valid up to 15m unless session family revoked (`authenticateRequest` checks revocation). |
| **Rate-limit bypass via `x-forwarded-for` or concurrent burst** | Ignores `x-forwarded-for`; atomic single-statement SQL increment using verified `cf-connecting-ip`. | Shared NAT/carrier CGNAT IPs share a bucket; non-CF tests without `cf-connecting-ip` share `'unverified-client'`. |
| **Failed Admin recovery destroys working local Keystore key** | Candidate key generated under fresh `admin_<uuid>` alias and discarded on failure; old key preserved until server confirms. | Loss/wipe of phone still requires offline recovery codes or paired backup Admin device. |
| **Deleted records re-uploaded from offline queue** | Tombstones applied before deletes across all 4 deletion scopes + `isRecordTombstoned` helper + `/commands` before `/sync/batch`. | Phone-local deletion on already-revoked offline device cannot be server-verified (`DEVICE_REVOKED_LOCAL_UNVERIFIED`). |
