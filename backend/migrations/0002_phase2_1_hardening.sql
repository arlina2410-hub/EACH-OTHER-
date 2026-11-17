PRAGMA foreign_keys = ON;

-- Phase 2.1 Security, Concurrency & Deletion Hardening

-- 1. Session families for atomic refresh-token reuse detection & clean state distinction
CREATE TABLE auth_session_families (
  id TEXT PRIMARY KEY,
  subject_type TEXT NOT NULL CHECK (subject_type IN ('ADMIN', 'DEVICE')),
  account_id TEXT REFERENCES accounts(id) ON DELETE CASCADE,
  admin_device_id TEXT REFERENCES admin_devices(id) ON DELETE CASCADE,
  device_id TEXT REFERENCES devices(id) ON DELETE CASCADE,
  created_at_ms INTEGER NOT NULL,
  revoked_at_ms INTEGER,
  revoked_reason TEXT
);
CREATE INDEX idx_session_families_account ON auth_session_families(account_id, revoked_at_ms);
CREATE INDEX idx_session_families_device ON auth_session_families(device_id, revoked_at_ms);

ALTER TABLE auth_sessions ADD COLUMN family_id TEXT REFERENCES auth_session_families(id) ON DELETE CASCADE;
ALTER TABLE auth_sessions ADD COLUMN rotated_at_ms INTEGER;
ALTER TABLE auth_sessions ADD COLUMN replaced_by_session_id TEXT;
CREATE INDEX idx_sessions_family ON auth_sessions(family_id, revoked_at_ms);

-- 2. Rate-limit expiry metadata for bounded cleanup
ALTER TABLE rate_limits ADD COLUMN expires_at_ms INTEGER NOT NULL DEFAULT 0;
CREATE INDEX idx_rate_limits_expiry ON rate_limits(expires_at_ms);

-- 3. Extend deletion_commands status constraint for honest offline-revoked disconnects
CREATE TABLE deletion_commands_v2 (
  id TEXT PRIMARY KEY,
  group_id TEXT NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
  device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  initiated_by TEXT NOT NULL DEFAULT 'ADMIN' CHECK (initiated_by IN ('ADMIN', 'DEVICE')),
  scope TEXT NOT NULL CHECK (scope IN ('SINGLE_RECORD', 'DATE_RANGE', 'CATEGORY', 'ALL_DEVICE_DATA')),
  category TEXT NOT NULL CHECK (category IN ('LOCATION', 'USAGE', 'DEVICE_STATUS', 'ALL')),
  target_record_id TEXT,
  range_start_ms INTEGER,
  range_end_ms INTEGER,
  status TEXT NOT NULL DEFAULT 'PENDING_DEVICE_ACK' CHECK (
    status IN ('PENDING_DEVICE_ACK', 'ACKNOWLEDGED', 'EXPIRED', 'DEVICE_REVOKED_LOCAL_UNVERIFIED')
  ),
  created_at_ms INTEGER NOT NULL,
  acknowledged_at_ms INTEGER
);

INSERT INTO deletion_commands_v2 (
  id, group_id, device_id, initiated_by, scope, category,
  target_record_id, range_start_ms, range_end_ms, status, created_at_ms, acknowledged_at_ms
)
SELECT
  id, group_id, device_id, initiated_by, scope, category,
  target_record_id, range_start_ms, range_end_ms, status, created_at_ms, acknowledged_at_ms
FROM deletion_commands;

DROP TABLE deletion_commands;
ALTER TABLE deletion_commands_v2 RENAME TO deletion_commands;
CREATE INDEX idx_del_cmd_device_status ON deletion_commands(device_id, status, created_at_ms ASC);
