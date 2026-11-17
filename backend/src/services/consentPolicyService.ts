import { AppError } from '../http/errors';
import type { Env } from '../types/env';

export type SharingCategory = 'LOCATION' | 'USAGE' | 'DEVICE_STATUS';

export interface EffectiveSharingState {
  globalPaused: boolean;
  locationActive: boolean;
  usageActive: boolean;
  deviceStatusActive: boolean;
}

export interface DeviceConsentSnapshot {
  deviceId: string;
  groupId: string;
  deviceStatus: 'ACTIVE' | 'PAUSED' | 'REVOKED';
  settingsVersion: number;
  effectiveSharing: EffectiveSharingState;
}

export function computeEffectiveSharing(params: {
  deviceStatus: string;
  globalPaused: boolean;
  locationEnabled: boolean;
  usageEnabled: boolean;
  deviceStatusEnabled: boolean;
  locationPermissionState: string;
  usagePermissionState: string;
}): EffectiveSharingState {
  const paused = params.globalPaused || params.deviceStatus === 'PAUSED' || params.deviceStatus === 'REVOKED';
  const hasLocationPerm =
    params.locationPermissionState === 'FOREGROUND_ONLY' ||
    params.locationPermissionState === 'BACKGROUND_GRANTED';
  const hasUsagePerm = params.usagePermissionState === 'GRANTED';

  return {
    globalPaused: paused,
    locationActive: !paused && params.locationEnabled && hasLocationPerm,
    usageActive: !paused && params.usageEnabled && hasUsagePerm,
    deviceStatusActive: !paused && params.deviceStatusEnabled,
  };
}

/**
 * Authoritative server-side consent check used by future Phase 3+ collection/upload
 * endpoints. Never trusts Android client UI flags alone.
 */
export async function evaluateDeviceConsentPolicy(
  env: Env,
  deviceId: string,
  groupId: string
): Promise<DeviceConsentSnapshot> {
  const row = await env.DB.prepare(
    `SELECT d.id, d.group_id, d.status AS device_status,
            s.global_paused, s.location_enabled, s.usage_enabled, s.device_status_enabled,
            s.location_permission_state, s.usage_permission_state, s.settings_version
     FROM devices d
     LEFT JOIN sharing_settings s ON s.device_id = d.id
     WHERE d.id = ? AND d.group_id = ?`
  )
    .bind(deviceId, groupId)
    .first<{
      id: string;
      group_id: string;
      device_status: 'ACTIVE' | 'PAUSED' | 'REVOKED';
      global_paused: number | null;
      location_enabled: number | null;
      usage_enabled: number | null;
      device_status_enabled: number | null;
      location_permission_state: string | null;
      usage_permission_state: string | null;
      settings_version: number | null;
    }>();

  if (!row || row.device_status === 'REVOKED') {
    throw new AppError(401, 'DEVICE_REVOKED', 'Device access has been revoked.');
  }

  const effectiveSharing = computeEffectiveSharing({
    deviceStatus: row.device_status,
    globalPaused: (row.global_paused ?? 0) === 1,
    locationEnabled: (row.location_enabled ?? 0) === 1,
    usageEnabled: (row.usage_enabled ?? 0) === 1,
    deviceStatusEnabled: (row.device_status_enabled ?? 0) === 1,
    locationPermissionState: row.location_permission_state ?? 'UNKNOWN',
    usagePermissionState: row.usage_permission_state ?? 'UNKNOWN',
  });

  return {
    deviceId: row.id,
    groupId: row.group_id,
    deviceStatus: row.device_status,
    settingsVersion: row.settings_version ?? 1,
    effectiveSharing,
  };
}

/**
 * Enforces that a connected device may submit reports for a given category.
 * Rejects paused devices with 403 SHARING_PAUSED and disabled/unpermitted categories
 * with 403 CATEGORY_NOT_ENABLED while allowing control endpoints (sync-state,
 * sharing-settings, deletion commands/ack) to remain reachable while paused.
 */
export async function assertReportConsentAllowed(
  env: Env,
  deviceId: string,
  groupId: string,
  category: SharingCategory
): Promise<DeviceConsentSnapshot> {
  const snapshot = await evaluateDeviceConsentPolicy(env, deviceId, groupId);

  if (snapshot.effectiveSharing.globalPaused || snapshot.deviceStatus === 'PAUSED') {
    throw new AppError(
      403,
      'SHARING_PAUSED',
      'Sharing is globally paused for this device. New collection uploads are rejected.'
    );
  }

  const allowed =
    (category === 'LOCATION' && snapshot.effectiveSharing.locationActive) ||
    (category === 'USAGE' && snapshot.effectiveSharing.usageActive) ||
    (category === 'DEVICE_STATUS' && snapshot.effectiveSharing.deviceStatusActive);

  if (!allowed) {
    throw new AppError(
      403,
      'CATEGORY_NOT_ENABLED',
      `Sharing category ${category} or its required Android permission is not enabled on the server.`
    );
  }

  return snapshot;
}
