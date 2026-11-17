PRAGMA foreign_keys = ON;

CREATE TABLE accounts (
  id TEXT PRIMARY KEY,
  email TEXT NOT NULL UNIQUE,
  status TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'LOCKED', 'DELETED')),
  created_at_ms INTEGER NOT NULL,
  updated_at_ms INTEGER NOT NULL
);

CREATE TABLE groups (
  id TEXT PRIMARY KEY,
  administrator_account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
  name TEXT NOT NULL DEFAULT 'Private Group',
  status TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'ARCHIVED')),
  created_at_ms INTEGER NOT NULL
);
CREATE INDEX idx_groups_admin ON groups(administrator_account_id);

CREATE TABLE admin_devices (
  id TEXT PRIMARY KEY,
  account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
  group_id TEXT NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
  device_name TEXT NOT NULL,
  public_key_spki TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'REVOKED')),
  created_at_ms INTEGER NOT NULL,
  last_auth_at_ms INTEGER,
  revoked_at_ms INTEGER
);
CREATE INDEX idx_admin_devices_account ON admin_devices(account_id, status);

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

CREATE TABLE account_recovery_codes (
  id TEXT PRIMARY KEY,
  account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
  code_hash TEXT NOT NULL,
  used_at_ms INTEGER,
  created_at_ms INTEGER NOT NULL
);
CREATE INDEX idx_recovery_account ON account_recovery_codes(account_id, used_at_ms);

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

CREATE TABLE location_records (
  id TEXT PRIMARY KEY,
  device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  group_id TEXT NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
  latitude REAL NOT NULL CHECK (latitude BETWEEN -90.0 AND 90.0),
  longitude REAL NOT NULL CHECK (longitude BETWEEN -180.0 AND 180.0),
  accuracy_m REAL CHECK (accuracy_m IS NULL OR accuracy_m >= 0.0),
  provider TEXT,
  recorded_at_ms INTEGER NOT NULL,
  received_at_ms INTEGER NOT NULL
);
CREATE INDEX idx_loc_device_time ON location_records(device_id, recorded_at_ms DESC);

CREATE TABLE usage_summaries (
  id TEXT PRIMARY KEY,
  device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  group_id TEXT NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
  package_name TEXT NOT NULL,
  app_label TEXT,
  usage_duration_ms INTEGER NOT NULL CHECK (usage_duration_ms >= 0),
  period_start_ms INTEGER NOT NULL,
  period_end_ms INTEGER NOT NULL,
  data_completeness TEXT NOT NULL DEFAULT 'ESTIMATED' CHECK (data_completeness IN ('COMPLETE', 'ESTIMATED', 'PARTIAL_GAP')),
  recorded_at_ms INTEGER NOT NULL,
  received_at_ms INTEGER NOT NULL
);
CREATE UNIQUE INDEX idx_usage_unique_day ON usage_summaries(device_id, package_name, period_start_ms);
CREATE INDEX idx_usage_device_period ON usage_summaries(device_id, period_start_ms DESC, usage_duration_ms DESC);

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

CREATE TABLE sync_receipts (
  record_id TEXT NOT NULL,
  device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  record_type TEXT NOT NULL CHECK (record_type IN ('LOCATION', 'USAGE', 'DEVICE_STATUS')),
  accepted_at_ms INTEGER NOT NULL,
  tombstoned INTEGER NOT NULL DEFAULT 0 CHECK (tombstoned IN (0, 1)),
  PRIMARY KEY (device_id, record_id)
);
CREATE INDEX idx_receipts_accepted ON sync_receipts(accepted_at_ms);

CREATE TABLE deletion_commands (
  id TEXT PRIMARY KEY,
  group_id TEXT NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
  device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  initiated_by TEXT NOT NULL DEFAULT 'ADMIN' CHECK (initiated_by IN ('ADMIN', 'DEVICE')),
  scope TEXT NOT NULL CHECK (scope IN ('SINGLE_RECORD', 'DATE_RANGE', 'CATEGORY', 'ALL_DEVICE_DATA')),
  category TEXT NOT NULL CHECK (category IN ('LOCATION', 'USAGE', 'DEVICE_STATUS', 'ALL')),
  target_record_id TEXT,
  range_start_ms INTEGER,
  range_end_ms INTEGER,
  status TEXT NOT NULL DEFAULT 'PENDING_DEVICE_ACK' CHECK (status IN ('PENDING_DEVICE_ACK', 'ACKNOWLEDGED', 'EXPIRED')),
  created_at_ms INTEGER NOT NULL,
  acknowledged_at_ms INTEGER
);
CREATE INDEX idx_del_cmd_device_status ON deletion_commands(device_id, status, created_at_ms ASC);

CREATE TABLE rate_limits (
  key TEXT PRIMARY KEY,
  window_start_ms INTEGER NOT NULL,
  count INTEGER NOT NULL
);

CREATE TABLE daily_quota_usage (
  day_utc TEXT PRIMARY KEY,
  request_count INTEGER NOT NULL DEFAULT 0,
  d1_rows_written_est INTEGER NOT NULL DEFAULT 0
);
