import { generateInviteCode, normalizeCode, sha256Hex } from '../crypto/crypto';
import { AppError } from '../http/errors';
import type { Env } from '../types/env';
import { issueSessionTokens } from './authService';

export function formatInviteCode(raw12: string): string {
  return `${raw12.slice(0, 4)}-${raw12.slice(4, 8)}-${raw12.slice(8, 12)}`;
}

export async function createInvitation(
  env: Env,
  params: { groupId: string; accountId: string; label?: string; ttlMinutes: number },
  nowMs: number = Date.now()
) {
  const rawCode = generateInviteCode(12);
  const tokenHash = await sha256Hex(rawCode);
  const invitationId = crypto.randomUUID();
  const expiresAtMs = nowMs + params.ttlMinutes * 60 * 1000;

  await env.DB.prepare(
    `INSERT INTO invitations (id, group_id, created_by_account_id, token_hash, label, expires_at_ms, used_at_ms, used_by_device_id, revoked_at_ms, created_at_ms)
     VALUES (?, ?, ?, ?, ?, ?, NULL, NULL, NULL, ?)`
  )
    .bind(invitationId, params.groupId, params.accountId, tokenHash, params.label ?? null, expiresAtMs, nowMs)
    .run();

  const formattedCode = formatInviteCode(rawCode);
  return {
    invitationId,
    inviteCode: formattedCode,
    qrPayload: `eachother://join?code=${formattedCode}`,
    expiresAtMs,
    label: params.label ?? null,
  };
}

export async function listInvitations(env: Env, groupId: string) {
  const rows = await env.DB.prepare(
    `SELECT id, label, expires_at_ms, used_at_ms, used_by_device_id, revoked_at_ms, created_at_ms
     FROM invitations WHERE group_id = ? ORDER BY created_at_ms DESC LIMIT 50`
  )
    .bind(groupId)
    .all<{
      id: string;
      label: string | null;
      expires_at_ms: number;
      used_at_ms: number | null;
      used_by_device_id: string | null;
      revoked_at_ms: number | null;
      created_at_ms: number;
    }>();

  return { invitations: rows.results };
}

export async function revokeInvitation(
  env: Env,
  groupId: string,
  invitationId: string,
  nowMs: number = Date.now()
) {
  const res = await env.DB.prepare(
    'UPDATE invitations SET revoked_at_ms = ? WHERE id = ? AND group_id = ? AND revoked_at_ms IS NULL'
  )
    .bind(nowMs, invitationId, groupId)
    .run();

  if ((res.meta.changes ?? 0) === 0) {
    throw new AppError(404, 'INVITATION_NOT_FOUND', 'Invitation not found or already revoked.');
  }

  return { invitationId, revokedAtMs: nowMs };
}

export async function validateInvitation(
  env: Env,
  inviteCode: string,
  nowMs: number = Date.now()
) {
  const normalized = normalizeCode(inviteCode);
  if (normalized.length !== 12) {
    throw new AppError(404, 'INVITATION_INVALID', 'Invitation is invalid, expired, or no longer active.');
  }
  const tokenHash = await sha256Hex(normalized);

  const row = await env.DB.prepare(
    `SELECT i.id, i.expires_at_ms, i.used_at_ms, i.revoked_at_ms, g.name AS group_name
     FROM invitations i
     JOIN groups g ON g.id = i.group_id
     WHERE i.token_hash = ? AND g.status = 'ACTIVE'`
  )
    .bind(tokenHash)
    .first<{
      id: string;
      expires_at_ms: number;
      used_at_ms: number | null;
      revoked_at_ms: number | null;
      group_name: string;
    }>();

  if (!row || row.used_at_ms !== null || row.revoked_at_ms !== null || row.expires_at_ms <= nowMs) {
    throw new AppError(404, 'INVITATION_INVALID', 'Invitation is invalid, expired, or no longer active.');
  }

  return {
    valid: true,
    groupName: row.group_name,
    expiresAtMs: row.expires_at_ms,
  };
}

export async function enrollDevice(
  env: Env,
  input: { inviteCode: string; displayName: string; appVersion: string },
  nowMs: number = Date.now()
) {
  const normalized = normalizeCode(input.inviteCode);
  if (normalized.length !== 12) {
    throw new AppError(404, 'INVITATION_INVALID', 'Invitation is invalid, expired, or no longer active.');
  }
  const tokenHash = await sha256Hex(normalized);

  const invite = await env.DB.prepare(
    `SELECT i.id, i.group_id, i.expires_at_ms, i.used_at_ms, i.revoked_at_ms, g.name AS group_name
     FROM invitations i
     JOIN groups g ON g.id = i.group_id
     WHERE i.token_hash = ? AND g.status = 'ACTIVE'`
  )
    .bind(tokenHash)
    .first<{
      id: string;
      group_id: string;
      expires_at_ms: number;
      used_at_ms: number | null;
      revoked_at_ms: number | null;
      group_name: string;
    }>();

  if (!invite || invite.used_at_ms !== null || invite.revoked_at_ms !== null || invite.expires_at_ms <= nowMs) {
    throw new AppError(404, 'INVITATION_INVALID', 'Invitation is invalid, expired, or no longer active.');
  }

  const deviceId = crypto.randomUUID();
  const results = await env.DB.batch([
    env.DB.prepare(
      `INSERT INTO devices (id, group_id, role, display_name, platform, app_version, status, created_at_ms, last_seen_at_ms)
       VALUES (?, ?, 'CONNECTED', ?, 'ANDROID', ?, 'ACTIVE', ?, ?)`
    ).bind(deviceId, invite.group_id, input.displayName, input.appVersion, nowMs, nowMs),
    env.DB.prepare(
      `UPDATE invitations
       SET used_at_ms = ?, used_by_device_id = ?
       WHERE id = ? AND used_at_ms IS NULL AND revoked_at_ms IS NULL AND expires_at_ms > ?`
    ).bind(nowMs, deviceId, invite.id, nowMs),
    env.DB.prepare(
      `INSERT INTO sharing_settings (device_id, global_paused, location_enabled, usage_enabled, device_status_enabled, updated_at_ms)
       VALUES (?, 0, 0, 0, 0, ?)`
    ).bind(deviceId, nowMs),
  ]);

  const inviteUpdateChanges = results[1]?.meta.changes ?? 0;
  if (inviteUpdateChanges !== 1) {
    // Concurrent enrollment consumed the single-use invite; clean up newly inserted device
    await env.DB.prepare('DELETE FROM devices WHERE id = ?').bind(deviceId).run();
    throw new AppError(404, 'INVITATION_INVALID', 'Invitation is invalid, expired, or no longer active.');
  }

  const tokens = await issueSessionTokens(env, {
    subjectType: 'DEVICE',
    accountId: null,
    adminDeviceId: null,
    deviceId,
    groupId: invite.group_id,
    nowMs,
  });

  return {
    deviceId,
    groupId: invite.group_id,
    groupName: invite.group_name,
    displayName: input.displayName,
    ...tokens,
  };
}
