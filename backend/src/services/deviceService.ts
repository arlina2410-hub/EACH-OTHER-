import { AppError } from '../http/errors';
import type { Env } from '../types/env';
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
  const nextDeviceStatus = input.globalPaused ? 'PAUSED' : 'ACTIVE';

  await env.DB.batch([
    env.DB.prepare(
      `INSERT INTO sharing_settings (
         device_id, global_paused, location_enabled, usage_enabled, device_status_enabled,
         location_permission_state, usage_permission_state, background_restricted, settings_version, updated_at_ms
       )
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
       ON CONFLICT(device_id) DO UPDATE SET
         global_paused = excluded.global_paused,
         location_enabled = excluded.location_enabled,
         usage_enabled = excluded.usage_enabled,
         device_status_enabled = excluded.device_status_enabled,
         location_permission_state = excluded.location_permission_state,
         usage_permission_state = excluded.usage_permission_state,
         background_restricted = excluded.background_restricted,
         settings_version = MAX(sharing_settings.settings_version + 1, excluded.settings_version),
         updated_at_ms = excluded.updated_at_ms`
    ).bind(
      deviceId,
      input.globalPaused ? 1 : 0,
      effectiveLocationEnabled ? 1 : 0,
      effectiveUsageEnabled ? 1 : 0,
      input.deviceStatusEnabled ? 1 : 0,
      input.locationPermissionState,
      input.usagePermissionState,
      input.backgroundRestricted ? 1 : 0,
      input.settingsVersion,
      nowMs
    ),
    env.DB.prepare('UPDATE devices SET status = ?, last_seen_at_ms = ? WHERE id = ? AND group_id = ?').bind(
      nextDeviceStatus,
      nowMs,
      deviceId,
      groupId
    ),
  ]);

  const saved = await env.DB.prepare(
    `SELECT device_id, global_paused, location_enabled, usage_enabled, device_status_enabled,
            location_permission_state, usage_permission_state, background_restricted, settings_version, updated_at_ms
     FROM sharing_settings WHERE device_id = ?`
  )
    .bind(deviceId)
    .first<{
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

  return {
    deviceId,
    deviceStatus: nextDeviceStatus,
    globalPaused: (saved?.global_paused ?? 0) === 1,
    locationEnabled: (saved?.location_enabled ?? 0) === 1,
    usageEnabled: (saved?.usage_enabled ?? 0) === 1,
    deviceStatusEnabled: (saved?.device_status_enabled ?? 0) === 1,
    locationPermissionState: saved?.location_permission_state ?? input.locationPermissionState,
    usagePermissionState: saved?.usage_permission_state ?? input.usagePermissionState,
    backgroundRestricted: (saved?.background_restricted ?? 0) === 1,
    settingsVersion: saved?.settings_version ?? input.settingsVersion,
    updatedAtMs: saved?.updated_at_ms ?? nowMs,
  };
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
    env.DB.prepare('UPDATE devices SET status = ?, revoked_at_ms = COALESCE(revoked_at_ms, ?) WHERE id = ? AND group_id = ?').bind(
      'REVOKED',
      nowMs,
      deviceId,
      groupId
    ),
    env.DB.prepare('UPDATE auth_sessions SET revoked_at_ms = ? WHERE device_id = ? AND revoked_at_ms IS NULL').bind(
      nowMs,
      deviceId
    ),
  ]);

  let deletionResult = null;
  if (deleteServerData) {
    deletionResult = await createDeletionCommand(
      env,
      {
        groupId,
        deviceId,
        initiatedBy: 'ADMIN',
        scope: 'ALL_DEVICE_DATA',
        category: 'ALL',
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
    env.DB.prepare('UPDATE devices SET status = ?, revoked_at_ms = COALESCE(revoked_at_ms, ?) WHERE id = ? AND group_id = ?').bind(
      'REVOKED',
      nowMs,
      deviceId,
      groupId
    ),
    env.DB.prepare('UPDATE auth_sessions SET revoked_at_ms = ? WHERE device_id = ? AND revoked_at_ms IS NULL').bind(
      nowMs,
      deviceId
    ),
  ]);

  return { deviceId, status: 'REVOKED', revokedAtMs: nowMs };
}

export async function getDeviceSyncState(env: Env, deviceId: string, groupId: string) {
  const row = await env.DB.prepare(
    `SELECT d.id, d.status, d.last_sync_at_ms,
            (SELECT COUNT(*) FROM deletion_commands dc WHERE dc.device_id = d.id AND dc.status = 'PENDING_DEVICE_ACK') AS pending_deletion_count
     FROM devices d WHERE d.id = ? AND d.group_id = ?`
  )
    .bind(deviceId, groupId)
    .first<{
      id: string;
      status: string;
      last_sync_at_ms: number | null;
      pending_deletion_count: number;
    }>();

  if (!row) {
    throw new AppError(404, 'DEVICE_NOT_FOUND', 'Device not found.');
  }
  return {
    deviceId: row.id,
    status: row.status,
    lastSyncAtMs: row.last_sync_at_ms,
    pendingDeletionCount: row.pending_deletion_count,
  };
}
