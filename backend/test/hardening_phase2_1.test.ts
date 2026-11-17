import { env, SELF } from 'cloudflare:test';
import { describe, expect, it } from 'vitest';
import { hmacSha256CodeDigest, sha256Hex } from '../src/crypto/crypto';
import { cleanupExpiredRateLimits } from '../src/middleware/rateLimit';
import { getClientIpKey } from '../src/middleware/requestGuard';
import { refreshAuthSession, registerAdmin } from '../src/services/authService';
import { assertReportConsentAllowed } from '../src/services/consentPolicyService';
import { createDeletionCommand, isRecordTombstoned } from '../src/services/deletionService';
import { validateInvitation } from '../src/services/invitationService';

function toBase64(bytes: Uint8Array): string {
  let binary = '';
  for (let i = 0; i < bytes.length; i++) {
    binary += String.fromCharCode(bytes[i]!);
  }
  return btoa(binary);
}

async function generateSpki(): Promise<string> {
  const kp = (await crypto.subtle.generateKey(
    { name: 'ECDSA', namedCurve: 'P-256' },
    true,
    ['sign', 'verify']
  )) as CryptoKeyPair;
  return toBase64(new Uint8Array((await crypto.subtle.exportKey('spki', kp.publicKey)) as ArrayBuffer));
}

async function sendJson(
  method: 'POST' | 'PUT',
  path: string,
  body: unknown,
  token?: string,
  ip: string = '10.20.0.1',
  extraHeaders: Record<string, string> = {}
) {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    'cf-connecting-ip': ip,
    ...extraHeaders,
  };
  if (token) headers.Authorization = `Bearer ${token}`;
  return SELF.fetch(`https://example.test${path}`, {
    method,
    headers,
    body: JSON.stringify(body),
  });
}

async function getJson(path: string, token?: string, ip: string = '10.20.0.1') {
  const headers: Record<string, string> = { 'cf-connecting-ip': ip };
  if (token) headers.Authorization = `Bearer ${token}`;
  return SELF.fetch(`https://example.test${path}`, { method: 'GET', headers });
}

async function registerAdminAndDevice(tag: string, ip: string) {
  const spki = await generateSpki();
  const adminRes = await sendJson(
    'POST',
    '/v1/auth/admin/register',
    {
      email: `admin_${tag}@example.com`,
      groupName: `Group ${tag}`,
      deviceName: `Admin ${tag}`,
      publicKeySpki: spki,
    },
    undefined,
    ip
  );
  expect(adminRes.status).toBe(201);
  const admin = (await adminRes.json()) as {
    accountId: string;
    adminDeviceId: string;
    groupId: string;
    accessToken: string;
    refreshToken: string;
    recoveryCodes: string[];
  };

  const invRes = await sendJson('POST', '/v1/admin/invitations', { ttlMinutes: 60 }, admin.accessToken, ip);
  const inv = (await invRes.json()) as { inviteCode: string };

  const devRes = await sendJson(
    'POST',
    '/v1/devices/enroll',
    { inviteCode: inv.inviteCode, displayName: `Phone ${tag}`, appVersion: '0.2.1' },
    undefined,
    ip
  );
  expect(devRes.status).toBe(201);
  const device = (await devRes.json()) as {
    deviceId: string;
    groupId: string;
    accessToken: string;
    refreshToken: string;
  };
  return { admin, device };
}

describe('Phase 2.1 Hardening — Concurrency, Cryptography, Tombstones & Policy Enforcement', () => {
  it('enforces monotonic sharing settings versions under out-of-order and concurrent updates while preserving category preferences across pause', async () => {
    const { device } = await registerAdminAndDevice('settings', '10.21.0.1');

    // Update to settingsVersion 2
    const v2Res = await sendJson(
      'PUT',
      '/v1/devices/me/sharing-settings',
      {
        globalPaused: false,
        locationEnabled: true,
        usageEnabled: true,
        deviceStatusEnabled: true,
        locationPermissionState: 'BACKGROUND_GRANTED',
        usagePermissionState: 'GRANTED',
        backgroundRestricted: false,
        settingsVersion: 2,
      },
      device.accessToken
    );
    expect(v2Res.status).toBe(200);

    // Pause at settingsVersion 3 while keeping user category choices enabled
    const v3Res = await sendJson(
      'PUT',
      '/v1/devices/me/sharing-settings',
      {
        globalPaused: true,
        locationEnabled: true,
        usageEnabled: true,
        deviceStatusEnabled: true,
        locationPermissionState: 'BACKGROUND_GRANTED',
        usagePermissionState: 'GRANTED',
        backgroundRestricted: false,
        settingsVersion: 3,
      },
      device.accessToken
    );
    expect(v3Res.status).toBe(200);
    const v3Body = (await v3Res.json()) as {
      deviceStatus: string;
      globalPaused: boolean;
      locationEnabled: boolean;
      usageEnabled: boolean;
      deviceStatusEnabled: boolean;
      effectiveSharing: {
        globalPaused: boolean;
        locationActive: boolean;
        usageActive: boolean;
        deviceStatusActive: boolean;
      };
    };
    expect(v3Body.deviceStatus).toBe('PAUSED');
    expect(v3Body.locationEnabled).toBe(true);
    expect(v3Body.usageEnabled).toBe(true);
    expect(v3Body.deviceStatusEnabled).toBe(true);
    expect(v3Body.effectiveSharing).toEqual({
      globalPaused: true,
      locationActive: false,
      usageActive: false,
      deviceStatusActive: false,
    });

    // Out-of-order stale version (version 2 after 3) returns 409 with authoritativeSettings
    const staleRes = await sendJson(
      'PUT',
      '/v1/devices/me/sharing-settings',
      {
        globalPaused: false,
        locationEnabled: false,
        usageEnabled: false,
        deviceStatusEnabled: false,
        locationPermissionState: 'DENIED',
        usagePermissionState: 'DENIED',
        backgroundRestricted: false,
        settingsVersion: 2,
      },
      device.accessToken
    );
    expect(staleRes.status).toBe(409);
    const staleBody = (await staleRes.json()) as {
      error: { code: string };
      authoritativeSettings: { settingsVersion: number; globalPaused: boolean; locationEnabled: boolean };
    };
    expect(staleBody.error.code).toBe('SETTINGS_VERSION_CONFLICT');
    expect(staleBody.authoritativeSettings.settingsVersion).toBe(3);
    expect(staleBody.authoritativeSettings.globalPaused).toBe(true);
    expect(staleBody.authoritativeSettings.locationEnabled).toBe(true);

    // Concurrent requests targeting identical next version 4: exactly one wins, the others get 409
    const concurrentResponses = await Promise.all(
      [1, 2, 3].map(() =>
        sendJson(
          'PUT',
          '/v1/devices/me/sharing-settings',
          {
            globalPaused: false,
            locationEnabled: true,
            usageEnabled: true,
            deviceStatusEnabled: true,
            locationPermissionState: 'BACKGROUND_GRANTED',
            usagePermissionState: 'GRANTED',
            backgroundRestricted: false,
            settingsVersion: 4,
          },
          device.accessToken
        )
      )
    );
    const statuses = concurrentResponses.map((r) => r.status).sort();
    expect(statuses).toEqual([200, 409, 409]);
  });

  it('enforces atomic single-use refresh rotation, family reuse revocation, and distinct error codes', async () => {
    const { device } = await registerAdminAndDevice('refresh', '10.22.0.1');

    // Concurrent refresh race with same token: only ONE succeeds
    const raceResponses = await Promise.all(
      [1, 2, 3].map((i) =>
        sendJson('POST', '/v1/auth/refresh', { refreshToken: device.refreshToken }, undefined, `10.22.1.${i}`)
      )
    );
    const ok = raceResponses.filter((r) => r.status === 200);
    const failed = raceResponses.filter((r) => r.status === 401);
    expect(ok).toHaveLength(1);
    expect(failed).toHaveLength(2);

    const winner = (await ok[0]!.json()) as { accessToken: string; refreshToken: string };
    // Reuse detection revoked the session family, so even the replacement token is now revoked
    const postReuse = await sendJson(
      'POST',
      '/v1/auth/refresh',
      { refreshToken: winner.refreshToken },
      undefined,
      '10.22.1.9'
    );
    expect(postReuse.status).toBe(401);
    const postReuseErr = (await postReuse.json()) as { error: { code: string } };
    expect(postReuseErr.error.code).toBe('REFRESH_TOKEN_REVOKED');

    // Access token in the revoked family is rejected on authenticated routes
    const accessCheck = await getJson('/v1/devices/me/sync-state', winner.accessToken);
    expect(accessCheck.status).toBe(401);

    // Distinct error codes on service directly: invalid, revoked, expired
    await expect(refreshAuthSession(env, 'completely-invalid-refresh-token')).rejects.toMatchObject({
      code: 'INVALID_REFRESH_TOKEN',
    });

    const { admin } = await registerAdminAndDevice('refresh_codes', '10.22.0.2');
    // Force expiry of admin's current refresh token
    await env.DB.prepare('UPDATE auth_sessions SET expires_at_ms = ? WHERE account_id = ?')
      .bind(1_000_000, admin.accountId)
      .run();
    await expect(refreshAuthSession(env, admin.refreshToken, 2_000_000)).rejects.toMatchObject({
      code: 'REFRESH_TOKEN_EXPIRED',
    });
  });

  it('enforces atomic DB rate limits under concurrency, ignores spoofed x-forwarded-for, and cleans up expired buckets', async () => {
    // Direct check: spoofed x-forwarded-for is ignored; valid cf-connecting-ip is honored
    const spoofedReq = new Request('https://example.test/v1/invitations/validate', {
      headers: { 'x-forwarded-for': '1.2.3.4' },
    });
    expect(getClientIpKey(spoofedReq)).toBe('unverified-client');

    const cfReq = new Request('https://example.test/v1/invitations/validate', {
      headers: { 'cf-connecting-ip': '198.51.100.7', 'x-forwarded-for': '203.0.113.99' },
    });
    expect(getClientIpKey(cfReq)).toBe('198.51.100.7');

    // Concurrent 14 requests from same IP (cap = 10 for invite_validate) -> rotating x-forwarded-for cannot bypass
    const responses = await Promise.all(
      Array.from({ length: 14 }, (_, i) =>
        sendJson(
          'POST',
          '/v1/invitations/validate',
          { inviteCode: 'AAAA-BBBB-CCCC' },
          undefined,
          '10.23.9.9',
          { 'x-forwarded-for': `203.0.113.${i + 1}` }
        )
      )
    );
    const nonLimited = responses.filter((r) => r.status !== 429);
    const limited = responses.filter((r) => r.status === 429);
    expect(nonLimited).toHaveLength(10);
    expect(limited).toHaveLength(4);

    // Bounded cleanup removes expired rows after window passes
    const farFuture = Date.now() + 2 * 60 * 60 * 1000;
    const deleted = await cleanupExpiredRateLimits(env.DB, farFuture, 100);
    expect(deleted).toBeGreaterThanOrEqual(1);
  });

  it('requires CODE_HASH_PEPPER (fail closed), rejects unpeppered hashes by default, and supports opt-in legacy migration', async () => {
    await expect(hmacSha256CodeDigest('', 'recovery-v1', 'ABCD1234EFGH')).rejects.toThrow();
    await expect(hmacSha256CodeDigest('short', 'recovery-v1', 'ABCD1234EFGH')).rejects.toThrow();

    const digestA = await hmacSha256CodeDigest(env.CODE_HASH_PEPPER, 'device-invite-v1', 'ABCD1234EFGH');
    const digestB = await hmacSha256CodeDigest('another-distinct-pepper-secret-0123456', 'device-invite-v1', 'ABCD1234EFGH');
    expect(digestA.startsWith('hmac-v1:')).toBe(true);
    expect(digestA).not.toBe(digestB);

    // Missing CODE_HASH_PEPPER fails closed on admin registration & invite validation
    const envWithoutPepper = { ...env, CODE_HASH_PEPPER: '' };
    const spki = await generateSpki();
    await expect(
      registerAdmin(envWithoutPepper, {
        email: 'nopepper@example.com',
        groupName: 'No Pepper',
        deviceName: 'Pixel',
        publicKeySpki: spki,
      })
    ).rejects.toMatchObject({ code: 'SERVER_MISCONFIGURED' });

    // Legacy unpeppered SHA-256 invite hash rejected by default, accepted only when ALLOW_LEGACY_UNPEPPERED_CODES='true'
    const { admin } = await registerAdminAndDevice('legacy_code', '10.24.0.1');
    const legacyCode = 'ZZZZ9999YYYY';
    const legacyHash = await sha256Hex(legacyCode);
    await env.DB.prepare(
      `INSERT INTO invitations (id, group_id, created_by_account_id, token_hash, label, expires_at_ms, used_at_ms, used_by_device_id, revoked_at_ms, created_at_ms)
       VALUES (?, ?, ?, ?, 'Legacy', ?, NULL, NULL, NULL, ?)`
    )
      .bind(crypto.randomUUID(), admin.groupId, admin.accountId, legacyHash, Date.now() + 600_000, Date.now())
      .run();

    await expect(validateInvitation(env, legacyCode)).rejects.toMatchObject({ code: 'INVITATION_INVALID' });

    const validatedLegacy = await validateInvitation({ ...env, ALLOW_LEGACY_UNPEPPERED_CODES: 'true' }, legacyCode);
    expect(validatedLegacy.valid).toBe(true);
  });

  it('writes tombstones across ALL 4 deletion scopes, enforces command ACK ownership, and uses honest revoked wipe status', async () => {
    const { admin, device } = await registerAdminAndDevice('tombstones', '10.25.0.1');
    const { device: otherDevice } = await registerAdminAndDevice('other_dev', '10.25.0.2');

    const t1 = 1_700_000_000_000;
    const t2 = 1_700_000_010_000;
    const t3 = 1_700_000_020_000;
    const t4 = 1_700_000_030_000;

    // Seed location, usage, and device_status rows across timestamps
    const locSingle = crypto.randomUUID();
    const locRange = crypto.randomUUID();
    const usageCat = crypto.randomUUID();
    const statusAll = crypto.randomUUID();

    await env.DB.batch([
      env.DB.prepare(
        `INSERT INTO location_records (id, device_id, group_id, latitude, longitude, accuracy_m, provider, recorded_at_ms, received_at_ms)
         VALUES (?, ?, ?, 12.9, 77.5, 10, 'fused', ?, ?)`
      ).bind(locSingle, device.deviceId, admin.groupId, t1, t1),
      env.DB.prepare(
        `INSERT INTO location_records (id, device_id, group_id, latitude, longitude, accuracy_m, provider, recorded_at_ms, received_at_ms)
         VALUES (?, ?, ?, 12.91, 77.51, 10, 'fused', ?, ?)`
      ).bind(locRange, device.deviceId, admin.groupId, t2, t2),
      env.DB.prepare(
        `INSERT INTO usage_summaries (id, device_id, group_id, package_name, app_label, usage_duration_ms, period_start_ms, period_end_ms, data_completeness, recorded_at_ms, received_at_ms)
         VALUES (?, ?, ?, 'org.example', 'Example', 5000, ?, ?, 'COMPLETE', ?, ?)`
      ).bind(usageCat, device.deviceId, admin.groupId, t3, t3 + 1000, t3, t3),
      env.DB.prepare(
        `INSERT INTO device_status_records (id, device_id, group_id, battery_percent, charging_state, network_state, observed_at_ms, received_at_ms)
         VALUES (?, ?, ?, 80, 'CHARGING', 'WIFI', ?, ?)`
      ).bind(statusAll, device.deviceId, admin.groupId, t4, t4),
    ]);

    // 1. SINGLE_RECORD
    const cmd1 = await createDeletionCommand(
      env,
      { groupId: admin.groupId, deviceId: device.deviceId, initiatedBy: 'ADMIN', scope: 'SINGLE_RECORD', category: 'LOCATION', targetRecordId: locSingle },
      t1 + 500
    );
    expect(cmd1.serverDeletedCount).toBe(1);
    expect(await isRecordTombstoned(env, device.deviceId, locSingle, 'LOCATION', t1)).toBe(true);

    // 2. DATE_RANGE
    const cmd2 = await createDeletionCommand(
      env,
      { groupId: admin.groupId, deviceId: device.deviceId, initiatedBy: 'ADMIN', scope: 'DATE_RANGE', category: 'LOCATION', rangeStartMs: t2 - 100, rangeEndMs: t2 + 100 },
      t2 + 500
    );
    expect(cmd2.serverDeletedCount).toBe(1);
    expect(await isRecordTombstoned(env, device.deviceId, locRange, 'LOCATION', t2)).toBe(true);
    // Offline late upload captured inside deleted DATE_RANGE is also blocked
    expect(await isRecordTombstoned(env, device.deviceId, 'late-offline-id', 'LOCATION', t2 + 50)).toBe(true);

    // 3. CATEGORY
    const cmd3 = await createDeletionCommand(
      env,
      { groupId: admin.groupId, deviceId: device.deviceId, initiatedBy: 'DEVICE', scope: 'CATEGORY', category: 'USAGE' },
      t3 + 500
    );
    expect(cmd3.serverDeletedCount).toBe(1);
    expect(await isRecordTombstoned(env, device.deviceId, usageCat, 'USAGE', t3)).toBe(true);

    // 4. ALL_DEVICE_DATA
    const cmd4 = await createDeletionCommand(
      env,
      { groupId: admin.groupId, deviceId: device.deviceId, initiatedBy: 'ADMIN', scope: 'ALL_DEVICE_DATA', category: 'ALL' },
      t4 + 500
    );
    expect(cmd4.serverDeletedCount).toBe(1);
    expect(await isRecordTombstoned(env, device.deviceId, statusAll, 'DEVICE_STATUS', t4)).toBe(true);

    // Ownership enforcement on ACK: foreign device tries to ACK device's cmd1
    const otherAckRes = await sendJson(
      'POST',
      '/v1/devices/me/commands/ack',
      { commandIds: [cmd1.commandId] },
      otherDevice.accessToken
    );
    expect(otherAckRes.status).toBe(200);
    const otherAckBody = (await otherAckRes.json()) as { acknowledgedIds: string[]; rejectedCommandIds: string[] };
    expect(otherAckBody.acknowledgedIds).toEqual([]);
    expect(otherAckBody.rejectedCommandIds).toEqual([cmd1.commandId]);

    // Real owner ACKs cmd1 & cmd2 -> succeeds
    const ownerAckRes = await sendJson(
      'POST',
      '/v1/devices/me/commands/ack',
      { commandIds: [cmd1.commandId, cmd2.commandId, '11111111-1111-4111-8111-111111111111'] },
      device.accessToken
    );
    expect(ownerAckRes.status).toBe(200);
    const ownerAckBody = (await ownerAckRes.json()) as { acknowledgedIds: string[]; rejectedCommandIds: string[] };
    expect(ownerAckBody.acknowledgedIds).toEqual([cmd1.commandId, cmd2.commandId]);
    expect(ownerAckBody.rejectedCommandIds).toEqual(['11111111-1111-4111-8111-111111111111']);

    // Admin disconnects device with deleteServerData=true -> honest DEVICE_REVOKED_LOCAL_UNVERIFIED
    await env.DB.prepare(
      `INSERT INTO location_records (id, device_id, group_id, latitude, longitude, accuracy_m, provider, recorded_at_ms, received_at_ms)
       VALUES (?, ?, ?, 12.92, 77.52, 10, 'fused', ?, ?)`
    )
      .bind(crypto.randomUUID(), device.deviceId, admin.groupId, t4 + 1000, t4 + 1000)
      .run();

    const discRes = await sendJson(
      'POST',
      `/v1/admin/devices/${device.deviceId}/disconnect`,
      { deleteServerData: true },
      admin.accessToken
    );
    expect(discRes.status).toBe(200);
    const discBody = (await discRes.json()) as {
      status: string;
      deletionResult: { serverDeletedCount: number; deviceDeletionStatus: string; localWipeGuaranteed: boolean };
    };
    expect(discBody.status).toBe('REVOKED');
    expect(discBody.deletionResult.serverDeletedCount).toBe(1);
    expect(discBody.deletionResult.deviceDeletionStatus).toBe('DEVICE_REVOKED_LOCAL_UNVERIFIED');
    expect(discBody.deletionResult.localWipeGuaranteed).toBe(false);
  });

  it('enforces server-side Global Pause and category consent policy while keeping control endpoints reachable', async () => {
    const { admin, device } = await registerAdminAndDevice('pause_policy', '10.26.0.1');

    // Enable Location & Device Status (Usage disabled) while ACTIVE
    const activeRes = await sendJson(
      'PUT',
      '/v1/devices/me/sharing-settings',
      {
        globalPaused: false,
        locationEnabled: true,
        usageEnabled: false,
        deviceStatusEnabled: true,
        locationPermissionState: 'FOREGROUND_ONLY',
        usagePermissionState: 'DENIED',
        backgroundRestricted: false,
        settingsVersion: 2,
      },
      device.accessToken
    );
    expect(activeRes.status).toBe(200);

    await expect(assertReportConsentAllowed(env, device.deviceId, admin.groupId, 'LOCATION')).resolves.toMatchObject({
      deviceStatus: 'ACTIVE',
    });
    await expect(assertReportConsentAllowed(env, device.deviceId, admin.groupId, 'DEVICE_STATUS')).resolves.toMatchObject({
      deviceStatus: 'ACTIVE',
    });
    await expect(assertReportConsentAllowed(env, device.deviceId, admin.groupId, 'USAGE')).rejects.toMatchObject({
      status: 403,
      code: 'CATEGORY_NOT_ENABLED',
    });

    // Global Pause -> all 3 report categories blocked with 403 SHARING_PAUSED
    const pauseRes = await sendJson(
      'PUT',
      '/v1/devices/me/sharing-settings',
      {
        globalPaused: true,
        locationEnabled: true,
        usageEnabled: false,
        deviceStatusEnabled: true,
        locationPermissionState: 'FOREGROUND_ONLY',
        usagePermissionState: 'DENIED',
        backgroundRestricted: false,
        settingsVersion: 3,
      },
      device.accessToken
    );
    expect(pauseRes.status).toBe(200);

    await expect(assertReportConsentAllowed(env, device.deviceId, admin.groupId, 'LOCATION')).rejects.toMatchObject({
      status: 403,
      code: 'SHARING_PAUSED',
    });
    await expect(assertReportConsentAllowed(env, device.deviceId, admin.groupId, 'DEVICE_STATUS')).rejects.toMatchObject({
      status: 403,
      code: 'SHARING_PAUSED',
    });
    await expect(assertReportConsentAllowed(env, device.deviceId, admin.groupId, 'USAGE')).rejects.toMatchObject({
      status: 403,
      code: 'SHARING_PAUSED',
    });

    // Control endpoints remain allowed while PAUSED
    expect((await getJson('/v1/devices/me/sync-state', device.accessToken)).status).toBe(200);
    expect((await getJson('/v1/devices/me/commands', device.accessToken)).status).toBe(200);
    expect((await getJson('/v1/devices/me/export', device.accessToken)).status).toBe(200);
    expect(
      (
        await sendJson(
          'POST',
          '/v1/devices/me/deletions',
          { scope: 'ALL_DEVICE_DATA', category: 'ALL' },
          device.accessToken
        )
      ).status
    ).toBe(201);
  });
});
