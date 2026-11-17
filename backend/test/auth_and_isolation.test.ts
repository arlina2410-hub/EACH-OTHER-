import { SELF } from 'cloudflare:test';
import { describe, expect, it } from 'vitest';

const textEncoder = new TextEncoder();

function toBase64(bytes: Uint8Array): string {
  let binary = '';
  for (let i = 0; i < bytes.length; i++) {
    binary += String.fromCharCode(bytes[i]!);
  }
  return btoa(binary);
}

function encodeDerInt(raw32: Uint8Array): Uint8Array {
  let start = 0;
  while (start < raw32.length - 1 && raw32[start] === 0x00) {
    start++;
  }
  const trimmed = raw32.slice(start);
  const needsZeroPrefix = (trimmed[0]! & 0x80) !== 0;
  const out = new Uint8Array(2 + trimmed.length + (needsZeroPrefix ? 1 : 0));
  out[0] = 0x02;
  out[1] = trimmed.length + (needsZeroPrefix ? 1 : 0);
  if (needsZeroPrefix) {
    out[2] = 0x00;
    out.set(trimmed, 3);
  } else {
    out.set(trimmed, 2);
  }
  return out;
}

function p1363ToDer(p1363: Uint8Array): Uint8Array {
  const rDer = encodeDerInt(p1363.slice(0, 32));
  const sDer = encodeDerInt(p1363.slice(32, 64));
  const totalLen = rDer.length + sDer.length;
  const der = new Uint8Array(2 + totalLen);
  der[0] = 0x30;
  der[1] = totalLen;
  der.set(rDer, 2);
  der.set(sDer, 2 + rDer.length);
  return der;
}

async function generateAdminKeyPair() {
  const kp = (await crypto.subtle.generateKey(
    { name: 'ECDSA', namedCurve: 'P-256' },
    true,
    ['sign', 'verify']
  )) as CryptoKeyPair;
  const spkiB64 = toBase64(new Uint8Array((await crypto.subtle.exportKey('spki', kp.publicKey)) as ArrayBuffer));
  return { privateKey: kp.privateKey, spkiB64 };
}

async function signPayloadDer(privateKey: CryptoKey, canonicalPayload: string): Promise<string> {
  const p1363 = new Uint8Array(
    await crypto.subtle.sign(
      { name: 'ECDSA', hash: 'SHA-256' },
      privateKey,
      textEncoder.encode(canonicalPayload)
    )
  );
  return toBase64(p1363ToDer(p1363));
}

async function sendJson(
  method: 'POST' | 'PUT',
  path: string,
  body: unknown,
  token?: string,
  ip: string = '10.0.0.1'
) {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    'cf-connecting-ip': ip,
  };
  if (token) headers.Authorization = `Bearer ${token}`;
  return SELF.fetch(`https://example.test${path}`, {
    method,
    headers,
    body: JSON.stringify(body),
  });
}

async function getJson(path: string, token?: string, ip: string = '10.0.0.1') {
  const headers: Record<string, string> = { 'cf-connecting-ip': ip };
  if (token) headers.Authorization = `Bearer ${token}`;
  return SELF.fetch(`https://example.test${path}`, { method: 'GET', headers });
}

describe('Phase 2 ECDSA Auth, Multi-Device Admin, Recovery, Consent & Isolation', () => {
  it('registers admin with Keystore P-256 key, authenticates via signed challenges, blocks replays & detects refresh reuse', async () => {
    const key1 = await generateAdminKeyPair();
    const wrongKey = await generateAdminKeyPair();

    const regRes = await sendJson(
      'POST',
      '/v1/auth/admin/register',
      {
        email: 'Admin1@Example.com',
        groupName: 'Family Group',
        deviceName: 'Pixel Admin 1',
        publicKeySpki: key1.spkiB64,
      },
      undefined,
      '10.1.0.1'
    );
    expect(regRes.status).toBe(201);
    const reg = (await regRes.json()) as {
      accountId: string;
      adminDeviceId: string;
      groupId: string;
      accessToken: string;
      refreshToken: string;
      recoveryCodes: string[];
    };
    expect(reg.recoveryCodes).toHaveLength(10);

    // Duplicate email rejected
    const dupRes = await sendJson(
      'POST',
      '/v1/auth/admin/register',
      {
        email: 'admin1@example.com',
        groupName: 'Dup',
        deviceName: 'Other',
        publicKeySpki: wrongKey.spkiB64,
      },
      undefined,
      '10.1.0.2'
    );
    expect(dupRes.status).toBe(409);

    // Challenge -> wrong signature rejected -> authentic DER signature succeeds -> replay rejected
    const chalRes = await sendJson(
      'POST',
      '/v1/auth/admin/challenge',
      { email: 'admin1@example.com', adminDeviceId: reg.adminDeviceId, operation: 'LOGIN' },
      undefined,
      '10.1.0.3'
    );
    expect(chalRes.status).toBe(200);
    const chal = (await chalRes.json()) as { challengeId: string; canonicalPayload: string };

    const badSig = await signPayloadDer(wrongKey.privateKey, chal.canonicalPayload);
    const badVerifyRes = await sendJson(
      'POST',
      '/v1/auth/admin/verify',
      { challengeId: chal.challengeId, signature: badSig },
      undefined,
      '10.1.0.3'
    );
    expect(badVerifyRes.status).toBe(401);

    const goodSig = await signPayloadDer(key1.privateKey, chal.canonicalPayload);
    const verifyRes = await sendJson(
      'POST',
      '/v1/auth/admin/verify',
      { challengeId: chal.challengeId, signature: goodSig },
      undefined,
      '10.1.0.3'
    );
    expect(verifyRes.status).toBe(200);
    const login = (await verifyRes.json()) as { accessToken: string; refreshToken: string };

    // Challenge replay blocked
    const replayRes = await sendJson(
      'POST',
      '/v1/auth/admin/verify',
      { challengeId: chal.challengeId, signature: goodSig },
      undefined,
      '10.1.0.3'
    );
    expect(replayRes.status).toBe(401);

    // Refresh rotation + reuse detection
    const refRes = await sendJson('POST', '/v1/auth/refresh', { refreshToken: login.refreshToken });
    expect(refRes.status).toBe(200);
    const refreshed = (await refRes.json()) as { accessToken: string; refreshToken: string };
    expect(refreshed.refreshToken).not.toBe(login.refreshToken);

    const reuseRes = await sendJson('POST', '/v1/auth/refresh', { refreshToken: login.refreshToken });
    expect(reuseRes.status).toBe(401);

    const afterReuseRes = await sendJson('POST', '/v1/auth/refresh', { refreshToken: refreshed.refreshToken });
    expect(afterReuseRes.status).toBe(401);
  });

  it('supports multi-admin device pairing/revocation and full session/key revocation on recovery', async () => {
    const key1 = await generateAdminKeyPair();
    const regRes = await sendJson(
      'POST',
      '/v1/auth/admin/register',
      {
        email: 'adminMulti@example.com',
        groupName: 'Multi Admin Group',
        deviceName: 'Primary Tablet',
        publicKeySpki: key1.spkiB64,
      },
      undefined,
      '10.2.1.1'
    );
    const reg = (await regRes.json()) as {
      adminDeviceId: string;
      accessToken: string;
      recoveryCodes: string[];
    };

    // Cannot revoke the sole active admin device
    const lastRevokeRes = await sendJson(
      'POST',
      `/v1/admin/admin-devices/${reg.adminDeviceId}/revoke`,
      {},
      reg.accessToken
    );
    expect(lastRevokeRes.status).toBe(400);

    // Pair a second admin device
    const pairInviteRes = await sendJson(
      'POST',
      '/v1/admin/admin-devices/invite',
      { ttlMinutes: 15 },
      reg.accessToken
    );
    expect(pairInviteRes.status).toBe(201);
    const pairInvite = (await pairInviteRes.json()) as { pairingCode: string };

    const key2 = await generateAdminKeyPair();
    const enrollAdmin2Res = await sendJson(
      'POST',
      '/v1/auth/admin/devices/enroll',
      {
        pairingCode: pairInvite.pairingCode,
        deviceName: 'Backup Phone',
        publicKeySpki: key2.spkiB64,
      },
      undefined,
      '10.2.1.2'
    );
    expect(enrollAdmin2Res.status).toBe(201);
    const admin2 = (await enrollAdmin2Res.json()) as { adminDeviceId: string; accessToken: string };

    // Pairing code is single-use
    const reusePairRes = await sendJson(
      'POST',
      '/v1/auth/admin/devices/enroll',
      {
        pairingCode: pairInvite.pairingCode,
        deviceName: 'Rogue Phone',
        publicKeySpki: key2.spkiB64,
      },
      undefined,
      '10.2.1.3'
    );
    expect(reusePairRes.status).toBe(404);

    // Recovery revokes ALL prior admin devices & sessions and installs replacement Keystore key
    const key3 = await generateAdminKeyPair();
    const recRes = await sendJson(
      'POST',
      '/v1/auth/admin/recover',
      {
        email: 'adminMulti@example.com',
        recoveryCode: reg.recoveryCodes[0]!,
        newDeviceName: 'Replacement Admin Phone',
        newPublicKeySpki: key3.spkiB64,
      },
      undefined,
      '10.2.1.4'
    );
    expect(recRes.status).toBe(200);
    const recovered = (await recRes.json()) as {
      adminDeviceId: string;
      accessToken: string;
      revokedPriorDevicesCount: number;
    };
    expect(recovered.revokedPriorDevicesCount).toBe(2);

    // Prior devices' access tokens & challenges fail immediately
    expect((await getJson('/v1/admin/devices', reg.accessToken)).status).toBe(401);
    expect((await getJson('/v1/admin/devices', admin2.accessToken)).status).toBe(401);
    expect(
      (
        await sendJson(
          'POST',
          '/v1/auth/admin/challenge',
          { email: 'adminMulti@example.com', adminDeviceId: reg.adminDeviceId, operation: 'LOGIN' },
          undefined,
          '10.2.1.5'
        )
      ).status
    ).toBe(401);

    // Recovery code is single-use
    expect(
      (
        await sendJson(
          'POST',
          '/v1/auth/admin/recover',
          {
            email: 'adminMulti@example.com',
            recoveryCode: reg.recoveryCodes[0]!,
            newDeviceName: 'Rogue',
            newPublicKeySpki: key2.spkiB64,
          },
          undefined,
          '10.2.1.6'
        )
      ).status
    ).toBe(401);

    // Replacement admin sees 3 admin devices (1 ACTIVE, 2 REVOKED) and can regenerate recovery codes
    const listAdminDevsRes = await getJson('/v1/admin/admin-devices', recovered.accessToken);
    expect(listAdminDevsRes.status).toBe(200);
    const listBody = (await listAdminDevsRes.json()) as {
      adminDevices: Array<{ id: string; status: string }>;
    };
    expect(listBody.adminDevices).toHaveLength(3);
    expect(listBody.adminDevices.filter((d) => d.status === 'ACTIVE')).toHaveLength(1);

    const regenRes = await sendJson(
      'POST',
      '/v1/auth/admin/recovery-codes/regenerate',
      {},
      recovered.accessToken
    );
    expect(regenRes.status).toBe(200);
    const regen = (await regenRes.json()) as { recoveryCodes: string[] };
    expect(regen.recoveryCodes).toHaveLength(10);
  });

  it('syncs Connected Device consent & OS permission states truthfully while enforcing cross-group isolation', async () => {
    const keyA = await generateAdminKeyPair();
    const keyB = await generateAdminKeyPair();

    const adminARes = await sendJson(
      'POST',
      '/v1/auth/admin/register',
      { email: 'adminA@example.com', groupName: 'Group A', deviceName: 'Admin A', publicKeySpki: keyA.spkiB64 },
      undefined,
      '10.3.0.1'
    );
    const adminA = (await adminARes.json()) as { accessToken: string; groupId: string };

    const adminBRes = await sendJson(
      'POST',
      '/v1/auth/admin/register',
      { email: 'adminB@example.com', groupName: 'Group B', deviceName: 'Admin B', publicKeySpki: keyB.spkiB64 },
      undefined,
      '10.3.0.2'
    );
    const adminB = (await adminBRes.json()) as { accessToken: string; groupId: string };

    const invRes = await sendJson(
      'POST',
      '/v1/admin/invitations',
      { label: 'Moto G57 Power', ttlMinutes: 60 },
      adminA.accessToken
    );
    const invite = (await invRes.json()) as { inviteCode: string; qrPayload: string };
    expect(invite.qrPayload).toBe(`eachother://join?code=${invite.inviteCode}`);

    const enrollRes = await sendJson(
      'POST',
      '/v1/devices/enroll',
      { inviteCode: invite.inviteCode, displayName: 'Moto G57', appVersion: '0.2.0' },
      undefined,
      '10.3.0.3'
    );
    expect(enrollRes.status).toBe(201);
    const device = (await enrollRes.json()) as {
      deviceId: string;
      groupId: string;
      accessToken: string;
    };
    expect(device.groupId).toBe(adminA.groupId);

    // Same single-use invite rejected
    const reuseInviteRes = await sendJson(
      'POST',
      '/v1/devices/enroll',
      { inviteCode: invite.inviteCode, displayName: 'Rogue', appVersion: '0.2.0' },
      undefined,
      '10.3.0.4'
    );
    expect(reuseInviteRes.status).toBe(404);

    // Connected user updates sharing settings & OS permission states (and truth guard forces locationEnabled=false if DENIED)
    const updateDeniedRes = await sendJson(
      'PUT',
      '/v1/devices/me/sharing-settings',
      {
        globalPaused: false,
        locationEnabled: true,
        usageEnabled: true,
        deviceStatusEnabled: true,
        locationPermissionState: 'DENIED',
        usagePermissionState: 'GRANTED',
        backgroundRestricted: false,
        settingsVersion: 2,
      },
      device.accessToken
    );
    expect(updateDeniedRes.status).toBe(200);
    const updatedDenied = (await updateDeniedRes.json()) as {
      locationEnabled: boolean;
      usageEnabled: boolean;
      deviceStatus: string;
    };
    expect(updatedDenied.locationEnabled).toBe(false);
    expect(updatedDenied.usageEnabled).toBe(true);
    expect(updatedDenied.deviceStatus).toBe('ACTIVE');

    // Global pause reflects PAUSED device status to Admin
    const pauseRes = await sendJson(
      'PUT',
      '/v1/devices/me/sharing-settings',
      {
        globalPaused: true,
        locationEnabled: true,
        usageEnabled: true,
        deviceStatusEnabled: true,
        locationPermissionState: 'BACKGROUND_GRANTED',
        usagePermissionState: 'GRANTED',
        backgroundRestricted: true,
        settingsVersion: 3,
      },
      device.accessToken
    );
    expect(pauseRes.status).toBe(200);

    const detailRes = await getJson(`/v1/admin/devices/${device.deviceId}`, adminA.accessToken);
    expect(detailRes.status).toBe(200);
    const detail = (await detailRes.json()) as {
      device: {
        status: string;
        global_paused: number;
        location_enabled: number;
        location_permission_state: string;
        background_restricted: number;
      };
    };
    expect(detail.device.status).toBe('PAUSED');
    expect(detail.device.global_paused).toBe(1);
    expect(detail.device.location_enabled).toBe(1);
    expect(detail.device.location_permission_state).toBe('BACKGROUND_GRANTED');
    expect(detail.device.background_restricted).toBe(1);

    // Role & cross-group isolation
    expect((await getJson('/v1/admin/devices', device.accessToken)).status).toBe(403);
    expect((await getJson(`/v1/admin/devices/${device.deviceId}`, adminB.accessToken)).status).toBe(404);
    expect(
      (
        await sendJson(
          'PUT',
          '/v1/devices/me/sharing-settings',
          {
            globalPaused: false,
            locationEnabled: false,
            usageEnabled: false,
            deviceStatusEnabled: false,
            locationPermissionState: 'UNKNOWN',
            usagePermissionState: 'UNKNOWN',
            backgroundRestricted: false,
            settingsVersion: 1,
          },
          adminA.accessToken
        )
      ).status
    ).toBe(403);

    // Revoking device blocks subsequent requests immediately
    const discRes = await sendJson(
      'POST',
      `/v1/admin/devices/${device.deviceId}/disconnect`,
      { deleteServerData: false },
      adminA.accessToken
    );
    expect(discRes.status).toBe(200);
    expect((await getJson('/v1/devices/me/sync-state', device.accessToken)).status).toBe(401);
  });

  it('rejects revoked invitations and rate-limits excessive validation attempts', async () => {
    const key = await generateAdminKeyPair();
    const adminRes = await sendJson(
      'POST',
      '/v1/auth/admin/register',
      { email: 'adminRL@example.com', groupName: 'RL Group', deviceName: 'RL Admin', publicKeySpki: key.spkiB64 },
      undefined,
      '10.4.0.1'
    );
    const admin = (await adminRes.json()) as { accessToken: string };

    const invRes = await sendJson('POST', '/v1/admin/invitations', { ttlMinutes: 30 }, admin.accessToken);
    const inv = (await invRes.json()) as { invitationId: string; inviteCode: string };

    const revokeRes = await sendJson('POST', `/v1/admin/invitations/${inv.invitationId}/revoke`, {}, admin.accessToken);
    expect(revokeRes.status).toBe(200);

    const valRevoked = await sendJson('POST', '/v1/invitations/validate', { inviteCode: inv.inviteCode }, undefined, '10.4.0.2');
    expect(valRevoked.status).toBe(404);

    for (let i = 0; i < 10; i++) {
      await sendJson('POST', '/v1/invitations/validate', { inviteCode: 'AAAA-BBBB-CCCC' }, undefined, '10.9.9.9');
    }
    const limited = await sendJson('POST', '/v1/invitations/validate', { inviteCode: 'AAAA-BBBB-CCCC' }, undefined, '10.9.9.9');
    expect(limited.status).toBe(429);
  });
});
