import { AppError } from '../http/errors';
import type { Env } from '../types/env';
import { computeEffectiveSharing } from './consentPolicyService';
import { createDeletionCommand } from './deletionService';

export async function listAdminDevices(env: Env, groupId: string) {
  const rows = await env.DB.prepare(
    `SELECT d.id, d.display_name, d.platform, d.app_version, d.status, d.created_at_ms, d.last_seen_at_ms, d.last_sync_at_ms, d.revoked_at_ms,
            s.global_paused, s.location_enabled, s.usage_enabled, s.device_status_enabled,
            s.location_permission_state, s.usage_permission_state, s.background_restricted, s.settings_version, s.updated_at_ms AS settings_updated_at_ms,
            (SELECT COUNT(*) FROM deletion_commands dc WHERE dc.device_id = d.id AND dc.status = 'PENDING_DEVICE_ACK') AS pending_deletion_count
     FROM devices d
     LEFT JOIN sharing_settings s ON s.device_id = d.id
     WHERE d.group_id = ?
     ORDER BY d.created_at_ms DESC`
  )
    .bind(groupId)
    .all();

  return { devices: rows.results };
}

export async function getAdminDeviceDetail(env: Env, groupId: string, deviceId: string) {
  const row = await env.DB.prepare(
    `SELECT d.id, d.display_name, d.platform, d.app_version, d.status, d.created_at_ms, d.last_seen_at_ms, d.last_sync_at_ms, d.revoked_at_ms,
            s.global_paused, s.location_enabled, s.usage_enabled, s.device_status_enabled,
            s.location_permission_state, s.usage_permission_state, s.background_restricted, s.settings_version, s.updated_at_ms AS settings_updated_at_ms,
            (SELECT COUNT(*) FROM deletion_commands dc WHERE dc.device_id = d.id AND dc.status = 'PENDING_DEVICE_ACK') AS pending_deletion_count
     FROM devices d
     LEFT JOIN sharing_settings s ON s.device_id = d.id
     WHERE d.id = ? AND d.group_id = ?`
  )
    .bind(deviceId, groupId)
    .first();

  if (!row) {
    throw new AppError(404, 'DEVICE_NOT_FOUND', 'Device not found in your group.');
  }
  return { device: row };
}

async function readAuthoritativeSettingsResponse(
  env: Env,
  deviceId: string,
  groupId: string
) {
  const row = await env.DB.prepare(
    `SELECT d.status AS device_status,
            s.device_id, s.global_paused, s.location_enabled, s.usage_enabled, s.device_status_enabled,
            s.location_permission_state, s.usage_permission_state, s.background_restricted,
            s.settings_version, s.updated_at_ms
     FROM devices d
     JOIN sharing_settings s ON s.device_id = d.id
     WHERE d.id = ? AND d.group_id = ?`
  )
    .bind(deviceId, groupId)
    .first<{
      device_status: 'ACTIVE' | 'PAUSED' | 'REVOKED';
      device_id: string;
      global_paused: number;
      location_enabled: number;
      usage_enabled: number;
      device_status_enabled: number;
      location_permission_state: string;
      usage_permission_state: string;
      background_restricted: number;
      settings_version: number;
      updated_at_ms: number;
    }>();

  if (!row || row.device_status === 'REVOKED') {
    throw new AppError(401, 'DEVICE_REVOKED', 'Device access has been revoked.');
  }

  const globalPaused = row.global_paused === 1;
  const locationEnabled = row.location_enabled === 1;
  const usageEnabled = row.usage_enabled === 1;
  const deviceStatusEnabled = row.device_status_enabled === 1;

  return {
    deviceId: row.device_id,
    deviceStatus: row.device_status,
    globalPaused,
    locationEnabled,
    usageEnabled,
    deviceStatusEnabled,
    locationPermissionState: row.location_permission_state,
    usagePermissionState: row.usage_permission_state,
    backgroundRestricted: row.background_restricted === 1,
    settingsVersion: row.settings_version,
    updatedAtMs: row.updated_at_ms,
    effectiveSharing: computeEffectiveSharing({
      deviceStatus: row.device_status,
      globalPaused,
      locationEnabled,
      usageEnabled,
      deviceStatusEnabled,
      locationPermissionState: row.location_permission_state,
      usagePermissionState: row.usage_permission_state,
    }),
  };
}

export async function updateConnectedSharingSettings(
  env: Env,
  deviceId: string,
  groupId: string,
  input: {
    globalPaused: boolean;
    locationEnabled: boolean;
    usageEnabled: boolean;
    deviceStatusEnabled: boolean;
    locationPermissionState: 'UNKNOWN' | 'DENIED' | 'FOREGROUND_ONLY' | 'BACKGROUND_GRANTED';
    usagePermissionState: 'UNKNOWN' | 'DENIED' | 'GRANTED';
    backgroundRestricted: boolean;
    settingsVersion: number;
  },
  nowMs: number = Date.now()
) {
  const device = await env.DB.prepare('SELECT id, status FROM devices WHERE id = ? AND group_id = ?')
    .bind(deviceId, groupId)
    .first<{ id: string; status: string }>();

  if (!device || device.status === 'REVOKED') {
    throw new AppError(401, 'DEVICE_REVOKED', 'Device access has been revoked.');
  }

  // Truthfulness guard: sharing cannot remain marked enabled if the required OS permission is absent/revoked
  const hasLocationPermission =
    input.locationPermissionState === 'FOREGROUND_ONLY' ||
    input.locationPermissionState === 'BACKGROUND_GRANTED';
  const effectiveLocationEnabled = input.locationEnabled && hasLocationPermission;
  const effectiveUsageEnabled = input.usageEnabled && input.usagePermissionState === 'GRANTED';

  // Ensure a row exists with settings_version = 0 if legacy/uninitialized (no-op when enrolled row already exists)
  await env.DB.prepare(
    `INSERT OR IGNORE INTO sharing_settings (
       device_id, global_paused, location_enabled, usage_enabled, device_status_enabled,
       location_permission_state, usage_permission_state, background_restricted, settings_version, updated_at_ms
     )
     VALUES (?, 0, 0, 0, 0, 'UNKNOWN', 'UNKNOWN', 0, 0, ?)`
  )
    .bind(deviceId, nowMs)
    .run();

  // Atomic monotonic conditional update: stale versions (< or == current) can never overwrite newer consent decisions
  const updateRes = await env.DB.prepare(
    `UPDATE sharing_settings
     SET
       global_paused = ?,
       location_enabled = ?,
       usage_enabled = ?,
       device_status_enabled = ?,
       location_permission_state = ?,
       usage_permission_state = ?,
       background_restricted = ?,
       settings_version = ?,
       updated_at_ms = ?
     WHERE device_id = ?
       AND settings_version < ?`
  )
    .bind(
      input.globalPaused ? 1 : 0,
      effectiveLocationEnabled ? 1 : 0,
      effectiveUsageEnabled ? 1 : 0,
      input.deviceStatusEnabled ? 1 : 0,
      input.locationPermissionState,
      input.usagePermissionState,
      input.backgroundRestricted ? 1 : 0,
      input.settingsVersion,
      nowMs,
      deviceId,
      input.settingsVersion
    )
    .run();

  if ((updateRes.meta.changes ?? 0) !== 1) {
    const authoritativeSettings = await readAuthoritativeSettingsResponse(env, deviceId, groupId);
    throw new AppError(
      409,
      'SETTINGS_VERSION_CONFLICT',
      'Sharing settings update is stale; reconcile with authoritative server settings.',
      { authoritativeSettings }
    );
  }

  // Derive devices.status atomically from authoritative sharing_settings.global_paused (never overwriting REVOKED)
  const devUpdateRes = await env.DB.prepare(
    `UPDATE devices
     SET status = CASE
           WHEN (SELECT global_paused FROM sharing_settings WHERE device_id = ?) = 1 THEN 'PAUSED'
           ELSE 'ACTIVE'
         END,
         last_seen_at_ms = ?
     WHERE id = ? AND group_id = ? AND status != 'REVOKED'`
  )
    .bind(deviceId, nowMs, deviceId, groupId)
    .run();

  if ((devUpdateRes.meta.changes ?? 0) !== 1) {
    throw new AppError(401, 'DEVICE_REVOKED', 'Device access has been revoked.');
  }

  return readAuthoritativeSettingsResponse(env, deviceId, groupId);
}

export async function disconnectDeviceByAdmin(
  env: Env,
  groupId: string,
  deviceId: string,
  deleteServerData: boolean,
  nowMs: number = Date.now()
) {
  const device = await env.DB.prepare('SELECT id, status FROM devices WHERE id = ? AND group_id = ?')
    .bind(deviceId, groupId)
    .first<{ id: string; status: string }>();

  if (!device) {
    throw new AppError(404, 'DEVICE_NOT_FOUND', 'Device not found in your group.');
  }

  await env.DB.batch([
    env.DB.prepare(
      `UPDATE devices
       SET status = 'REVOKED', revoked_at_ms = COALESCE(revoked_at_ms, ?)
       WHERE id = ? AND group_id = ?`
    ).bind(nowMs, deviceId, groupId),
    env.DB.prepare(
      'UPDATE auth_sessions SET revoked_at_ms = ? WHERE device_id = ? AND revoked_at_ms IS NULL'
    ).bind(nowMs, deviceId),
    env.DB.prepare(
      `UPDATE auth_session_families
       SET revoked_at_ms = ?, revoked_reason = COALESCE(revoked_reason, 'DEVICE_REVOKED_BY_ADMIN')
       WHERE device_id = ? AND revoked_at_ms IS NULL`
    ).bind(nowMs, deviceId),
  ]);

  let deletionResult = null;
  if (deleteServerData) {
    // Device is now REVOKED and cannot authenticate to /commands or /commands/ack.
    // Record honest status DEVICE_REVOKED_LOCAL_UNVERIFIED rather than pretending ACK or remote wipe succeeded.
    deletionResult = await createDeletionCommand(
      env,
      {
        groupId,
        deviceId,
        initiatedBy: 'ADMIN',
        scope: 'ALL_DEVICE_DATA',
        category: 'ALL',
        initialStatus: 'DEVICE_REVOKED_LOCAL_UNVERIFIED',
      },
      nowMs
    );
  }

  return {
    deviceId,
    status: 'REVOKED',
    revokedAtMs: nowMs,
    deletionResult,
  };
}

export async function revokeOwnDevice(
  env: Env,
  deviceId: string,
  groupId: string,
  nowMs: number = Date.now()
) {
  await env.DB.batch([
    env.DB.prepare(
      `UPDATE devices
       SET status = 'REVOKED', revoked_at_ms = COALESCE(revoked_at_ms, ?)
       WHERE id = ? AND group_id = ?`
    ).bind(nowMs, deviceId, groupId),
    env.DB.prepare(
      'UPDATE auth_sessions SET revoked_at_ms = ? WHERE device_id = ? AND revoked_at_ms IS NULL'
    ).bind(nowMs, deviceId),
    env.DB.prepare(
      `UPDATE auth_session_families
       SET revoked_at_ms = ?, revoked_reason = COALESCE(revoked_reason, 'SELF_DISCONNECTED')
       WHERE device_id = ? AND revoked_at_ms IS NULL`
    ).bind(nowMs, deviceId),
  ]);

  return { deviceId, status: 'REVOKED', revokedAtMs: nowMs };
}

export async function getDeviceSyncState(env: Env, deviceId: string, groupId: string) {
  const row = await env.DB.prepare(
    `SELECT d.id, d.status, d.last_sync_at_ms,
            s.global_paused, s.location_enabled, s.usage_enabled, s.device_status_enabled,
            s.location_permission_state, s.usage_permission_state, s.settings_version,
            (SELECT COUNT(*) FROM deletion_commands dc WHERE dc.device_id = d.id AND dc.status = 'PENDING_DEVICE_ACK') AS pending_deletion_count
     FROM devices d
     LEFT JOIN sharing_settings s ON s.device_id = d.id
     WHERE d.id = ? AND d.group_id = ?`
  )
    .bind(deviceId, groupId)
    .first<{
      id: string;
      status: string;
      last_sync_at_ms: number | null;
      global_paused: number | null;
      location_enabled: number | null;
      usage_enabled: number | null;
      device_status_enabled: number | null;
      location_permission_state: string | null;
      usage_permission_state: string | null;
      settings_version: number | null;
      pending_deletion_count: number;
    }>();

  if (!row || row.status === 'REVOKED') {
    throw new AppError(401, 'DEVICE_REVOKED', 'Device access has been revoked.');
  }

  return {
    deviceId: row.id,
    status: row.status,
    lastSyncAtMs: row.last_sync_at_ms,
    pendingDeletionCount: row.pending_deletion_count,
    settingsVersion: row.settings_version ?? 1,
    effectiveSharing: computeEffectiveSharing({
      deviceStatus: row.status,
      globalPaused: (row.global_paused ?? 0) === 1,
      locationEnabled: (row.location_enabled ?? 0) === 1,
      usageEnabled: (row.usage_enabled ?? 0) === 1,
      deviceStatusEnabled: (row.device_status_enabled ?? 0) === 1,
      locationPermissionState: row.location_permission_state ?? 'UNKNOWN',
      usagePermissionState: row.usage_permission_state ?? 'UNKNOWN',
    }),
  };
}
