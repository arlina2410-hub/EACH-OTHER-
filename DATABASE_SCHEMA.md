# DATABASE_SCHEMA.md — EACH OTHER

**Phase:** Phase 0 — Database Design (Cloudflare D1 & Android Room)  
**Source of Truth:** `PRD.md` §5.6, §9

---

## 1. Design Rules (Both Databases)

- **All timestamps on the wire & stored:** ISO-8601 UTC strings (`TEXT`, e.g., `2026-11-17T12:00:00.000Z`) or epoch milliseconds (`INTEGER`) consistently.
  - **D1:** Store ISO-8601 UTC `TEXT` for readability and SQLite `datetime()` compatibility, or UTC epoch `INTEGER` milliseconds for compact indexed range queries (`WHERE device_id = ? AND recorded_at_ms BETWEEN ? AND ?`). **Choice: UTC epoch milliseconds (`INTEGER`)** for all time-series/expiry columns (`*_at_ms`) because integer B-tree index scans are faster, smaller, and immune to string format mismatches.
- **Event Time vs. Receipt Time:** Every uploaded report stores `recorded_at_ms` (client event time) separately from `received_at_ms` (server acceptance time).
- **Idempotency:** Every client-generated record carries a client UUIDv4 `id`. `sync_receipts` + table primary keys prevent duplicates on retry.
- **Deletion Semantics:** Deleted reports are physically removed from `location_records`, `usage_summaries`, and `device_status_records` immediately on the server; `deletion_commands` stores the deletion scope/tombstone until the source device acknowledges local purge.

---

## 2. Cloudflare D1 Schema (`backend/migrations/0001_initial_schema.sql`)

```sql
PRAGMA foreign_keys = ON;

-- 1. Administrator accounts (email is identifier only; no password_hash)
CREATE TABLE accounts (
  id TEXT PRIMARY KEY,                  -- UUIDv4
  email TEXT NOT NULL UNIQUE,           -- Normalized lowercase identifier
  status TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'LOCKED', 'DELETED')),
  created_at_ms INTEGER NOT NULL,
  updated_at_ms INTEGER NOT NULL
);

-- 2. Registered Administrator ECDSA P-256 devices
CREATE TABLE admin_devices (
  id TEXT PRIMARY KEY,
  account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
  group_id TEXT NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
  device_name TEXT NOT NULL,
  public_key_spki TEXT NOT NULL,        -- Base64 X.509 SubjectPublicKeyInfo (ECDSA P-256)
  status TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'REVOKED')),
  created_at_ms INTEGER NOT NULL,
  last_auth_at_ms INTEGER,
  revoked_at_ms INTEGER
);
CREATE INDEX idx_admin_devices_account ON admin_devices(account_id, status);

-- 3. Single-use pairing invitations to add an additional Administrator device
CREATE TABLE admin_pairing_invites (
  id TEXT PRIMARY KEY,
  account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
  group_id TEXT NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
  token_hash TEXT NOT NULL UNIQUE,
  expires_at_ms INTEGER NOT NULL,
  used_at_ms INTEGER,
  used_by_admin_device_id TEXT REFERENCES admin_devices(id),
  revoked_at_ms INTEGER,
  created_at_ms INTEGER NOT NULL
);

-- 4. Single-use cryptographic challenges for ECDSA login verification
CREATE TABLE auth_challenges (
  id TEXT PRIMARY KEY,
  account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
  admin_device_id TEXT NOT NULL REFERENCES admin_devices(id) ON DELETE CASCADE,
  operation TEXT NOT NULL CHECK (operation IN ('LOGIN')),
  nonce TEXT NOT NULL,
  expires_at_ms INTEGER NOT NULL,
  used_at_ms INTEGER,
  created_at_ms INTEGER NOT NULL
);
CREATE INDEX idx_challenges_expiry ON auth_challenges(expires_at_ms);

-- 5. Admin one-time recovery codes (stored hashed)
CREATE TABLE account_recovery_codes (
  id TEXT PRIMARY KEY,
  account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
  code_hash TEXT NOT NULL,              -- SHA-256 hex of normalized recovery code
  used_at_ms INTEGER,
  created_at_ms INTEGER NOT NULL
);
CREATE INDEX idx_recovery_account ON account_recovery_codes(account_id, used_at_ms);

-- 6. Private groups
CREATE TABLE groups (
  id TEXT PRIMARY KEY,
  administrator_account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
  name TEXT NOT NULL DEFAULT 'Private Group',
  status TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'ARCHIVED')),
  created_at_ms INTEGER NOT NULL
);
CREATE INDEX idx_groups_admin ON groups(administrator_account_id);

-- 7. Connected devices (1 group per device)
CREATE TABLE devices (
  id TEXT PRIMARY KEY,
  group_id TEXT NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
  role TEXT NOT NULL CHECK (role IN ('CONNECTED')),
  display_name TEXT NOT NULL,
  platform TEXT NOT NULL DEFAULT 'ANDROID',
  app_version TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'PAUSED', 'REVOKED')),
  created_at_ms INTEGER NOT NULL,
  last_seen_at_ms INTEGER,
  last_sync_at_ms INTEGER,
  revoked_at_ms INTEGER
);
CREATE INDEX idx_devices_group ON devices(group_id, status);

-- 8. Invitations for Connected Devices (single-use, expiring, revocable)
CREATE TABLE invitations (
  id TEXT PRIMARY KEY,
  group_id TEXT NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
  created_by_account_id TEXT NOT NULL REFERENCES accounts(id),
  token_hash TEXT NOT NULL UNIQUE,
  label TEXT,
  expires_at_ms INTEGER NOT NULL,
  used_at_ms INTEGER,
  used_by_device_id TEXT REFERENCES devices(id),
  revoked_at_ms INTEGER,
  created_at_ms INTEGER NOT NULL
);
CREATE INDEX idx_invitations_group ON invitations(group_id, created_at_ms DESC);

-- 9. Refresh token sessions (Admin device & Connected device)
CREATE TABLE auth_sessions (
  id TEXT PRIMARY KEY,
  subject_type TEXT NOT NULL CHECK (subject_type IN ('ADMIN', 'DEVICE')),
  account_id TEXT REFERENCES accounts(id) ON DELETE CASCADE,
  admin_device_id TEXT REFERENCES admin_devices(id) ON DELETE CASCADE,
  device_id TEXT REFERENCES devices(id) ON DELETE CASCADE,
  group_id TEXT REFERENCES groups(id) ON DELETE CASCADE,
  refresh_token_hash TEXT NOT NULL UNIQUE,
  expires_at_ms INTEGER NOT NULL,
  revoked_at_ms INTEGER,
  created_at_ms INTEGER NOT NULL,
  last_used_at_ms INTEGER NOT NULL
);
CREATE INDEX idx_sessions_device ON auth_sessions(device_id, revoked_at_ms);
CREATE INDEX idx_sessions_account ON auth_sessions(account_id, revoked_at_ms);
CREATE INDEX idx_sessions_admin_device ON auth_sessions(admin_device_id, revoked_at_ms);

-- 7. Sharing settings snapshot per connected device
CREATE TABLE sharing_settings (
  device_id TEXT PRIMARY KEY REFERENCES devices(id) ON DELETE CASCADE,
  global_paused INTEGER NOT NULL DEFAULT 0 CHECK (global_paused IN (0, 1)),
  location_enabled INTEGER NOT NULL DEFAULT 0 CHECK (location_enabled IN (0, 1)),
  usage_enabled INTEGER NOT NULL DEFAULT 0 CHECK (usage_enabled IN (0, 1)),
  device_status_enabled INTEGER NOT NULL DEFAULT 0 CHECK (device_status_enabled IN (0, 1)),
  location_permission_state TEXT NOT NULL DEFAULT 'UNKNOWN',
  usage_permission_state TEXT NOT NULL DEFAULT 'UNKNOWN',
  background_restricted INTEGER NOT NULL DEFAULT 0 CHECK (background_restricted IN (0, 1)),
  settings_version INTEGER NOT NULL DEFAULT 1,
  updated_at_ms INTEGER NOT NULL
);

-- 8. Location records
CREATE TABLE location_records (
  id TEXT PRIMARY KEY,                  -- Client-generated UUIDv4
  device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  group_id TEXT NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
  latitude REAL NOT NULL CHECK (latitude BETWEEN -90.0 AND 90.0),
  longitude REAL NOT NULL CHECK (longitude BETWEEN -180.0 AND 180.0),
  accuracy_m REAL CHECK (accuracy_m IS NULL OR accuracy_m >= 0.0),
  provider TEXT,                        -- 'fused', 'gps', 'network', etc.
  recorded_at_ms INTEGER NOT NULL,
  received_at_ms INTEGER NOT NULL
);
CREATE INDEX idx_loc_device_time ON location_records(device_id, recorded_at_ms DESC);

-- 9. Daily aggregated app usage summaries
CREATE TABLE usage_summaries (
  id TEXT PRIMARY KEY,                  -- Client-generated deterministic or UUIDv4 ID
  device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  group_id TEXT NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
  package_name TEXT NOT NULL,
  app_label TEXT,
  usage_duration_ms INTEGER NOT NULL CHECK (usage_duration_ms >= 0),
  period_start_ms INTEGER NOT NULL,     -- Start of UTC/local reporting day window
  period_end_ms INTEGER NOT NULL,
  data_completeness TEXT NOT NULL DEFAULT 'ESTIMATED' CHECK (data_completeness IN ('COMPLETE', 'ESTIMATED', 'PARTIAL_GAP')),
  recorded_at_ms INTEGER NOT NULL,
  received_at_ms INTEGER NOT NULL
);
CREATE INDEX idx_usage_device_period ON usage_summaries(device_id, period_start_ms DESC, usage_duration_ms DESC);

-- 10. Basic device status records
CREATE TABLE device_status_records (
  id TEXT PRIMARY KEY,
  device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  group_id TEXT NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
  battery_percent INTEGER NOT NULL CHECK (battery_percent BETWEEN 0 AND 100),
  charging_state TEXT NOT NULL CHECK (charging_state IN ('CHARGING', 'DISCHARGING', 'FULL', 'NOT_CHARGING', 'UNKNOWN')),
  network_state TEXT NOT NULL CHECK (network_state IN ('WIFI', 'CELLULAR', 'ETHERNET', 'VPN', 'NONE', 'OTHER')),
  is_interactive INTEGER CHECK (is_interactive IN (0, 1)),
  app_health TEXT NOT NULL DEFAULT 'OK',
  observed_at_ms INTEGER NOT NULL,
  received_at_ms INTEGER NOT NULL
);
CREATE INDEX idx_status_device_time ON device_status_records(device_id, observed_at_ms DESC);

-- 11. Sync idempotency receipts (tracks accepted or tombstoned record IDs)
CREATE TABLE sync_receipts (
  record_id TEXT NOT NULL,
  device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  record_type TEXT NOT NULL CHECK (record_type IN ('LOCATION', 'USAGE', 'DEVICE_STATUS')),
  accepted_at_ms INTEGER NOT NULL,
  tombstoned INTEGER NOT NULL DEFAULT 0 CHECK (tombstoned IN (0, 1)),
  PRIMARY KEY (device_id, record_id)
);
CREATE INDEX idx_receipts_accepted ON sync_receipts(accepted_at_ms);

-- 12. Deletion commands (tombstone propagation to offline devices)
CREATE TABLE deletion_commands (
  id TEXT PRIMARY KEY,
  group_id TEXT NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
  device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  scope TEXT NOT NULL CHECK (scope IN ('SINGLE_RECORD', 'DATE_RANGE', 'CATEGORY', 'ALL_DEVICE_DATA')),
  category TEXT CHECK (category IN ('LOCATION', 'USAGE', 'DEVICE_STATUS', 'ALL')),
  target_record_id TEXT,
  range_start_ms INTEGER,
  range_end_ms INTEGER,
  status TEXT NOT NULL DEFAULT 'PENDING_DEVICE_ACK' CHECK (status IN ('PENDING_DEVICE_ACK', 'ACKNOWLEDGED', 'EXPIRED')),
  created_at_ms INTEGER NOT NULL,
  acknowledged_at_ms INTEGER
);
CREATE INDEX idx_del_cmd_device_status ON deletion_commands(device_id, status, created_at_ms ASC);

-- 13. Lightweight rate-limit counter table (for public auth/invite endpoints)
CREATE TABLE rate_limits (
  key TEXT PRIMARY KEY,                 -- e.g. 'invite_validate:<ip_hash>'
  window_start_ms INTEGER NOT NULL,
  count INTEGER NOT NULL
);
```

### D1 Retention & Free-Tier Housekeeping
- `sync_receipts`: Prune rows older than 90 days (if `tombstoned = 0`) via periodic maintenance or on-write cleanup, keeping tombstones for active deletion ranges.
- `rate_limits`: Upsert and delete expired windows on check.

---

## 3. Android Room Local Schema (`AppDatabase`, `version = 1`)

The Connected Device stores its own consent state, outbox records, and local history in Room (`exportSchema = true` for tested migrations):

1. `local_sharing_config`: single row (`id = 1`) holding `role`, `deviceId`, `groupId`, `displayName`, `globalPaused`, `locationEnabled`, `usageEnabled`, `deviceStatusEnabled`, `persistentFgsEnabled`, `settingsVersion`, `lastSyncAttemptMs`, `lastSyncSuccessMs`, `lastSyncError`.
2. `local_location_records`: mirrors `location_records` + `sync_state` (`PENDING`, `IN_FLIGHT`, `SYNCED`, `FAILED_RETRYABLE`) + `sync_attempts` + `last_error`.
   - Index: `(sync_state, recorded_at_ms ASC)` for fast FIFO batching, `(recorded_at_ms DESC)` for local history.
3. `local_usage_summaries`: mirrors `usage_summaries` + `sync_state`.
   - Index: `(sync_state, period_start_ms ASC)`, unique constraint `(package_name, period_start_ms)` for upsert of same-day summary updates.
4. `local_device_status_records`: mirrors `device_status_records` + `sync_state`.
   - Index: `(sync_state, observed_at_ms ASC)`.
5. `local_deletion_log`: records executed `command_id`s so re-delivered deletion commands are idempotent and rejected records never re-enter the upload queue.

### Room Migration & Backup Policy
- `android:allowBackup="false"` and `android:dataExtractionRules="@xml/data_extraction_rules"` excluding Room DB and Keystore-backed prefs from cloud/ADB backups.
- `AppDatabase` exports JSON schemas to `app/schemas/` and every version bump requires a tested `Migration(v, v+1)` using `MigrationTestHelper`.
