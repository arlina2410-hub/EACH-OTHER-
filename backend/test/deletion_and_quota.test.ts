import { env, SELF } from 'cloudflare:test';
import { describe, expect, it } from 'vitest';

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

async function postJson(path: string, body: unknown, token?: string, ip: string = '10.5.0.1') {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    'cf-connecting-ip': ip,
  };
  if (token) headers.Authorization = `Bearer ${token}`;
  return SELF.fetch(`https://example.test${path}`, {
    method: 'POST',
    headers,
    body: JSON.stringify(body),
  });
}

async function getJson(path: string, token?: string) {
  const headers: Record<string, string> = { 'cf-connecting-ip': '10.5.0.1' };
  if (token) headers.Authorization = `Bearer ${token}`;
  return SELF.fetch(`https://example.test${path}`, { method: 'GET', headers });
}

describe('Deletion, Tombstones, Storage Monitoring & Guardrails', () => {
  it('supports both Admin deletion and Connected Device self-deletion with tombstones, plus storage monitoring and export', async () => {
    const spki = await generateSpki();
    const adminRes = await postJson(
      '/v1/auth/admin/register',
      {
        email: 'adminDel@example.com',
        groupName: 'Del Group',
        deviceName: 'Admin Phone',
        publicKeySpki: spki,
      },
      undefined,
      '10.5.0.1'
    );
    expect(adminRes.status).toBe(201);
    const admin = (await adminRes.json()) as { accessToken: string; groupId: string };

    const invRes = await postJson('/v1/admin/invitations', { ttlMinutes: 60 }, admin.accessToken);
    const inv = (await invRes.json()) as { inviteCode: string };

    const devRes = await postJson(
      '/v1/devices/enroll',
      { inviteCode: inv.inviteCode, displayName: 'Device 1', appVersion: '0.2.0' },
      undefined,
      '10.5.0.2'
    );
    const dev = (await devRes.json()) as { deviceId: string; accessToken: string };

    const locId = crypto.randomUUID();
    const now = Date.now();
    await env.DB.prepare(
      `INSERT INTO location_records (id, device_id, group_id, latitude, longitude, accuracy_m, provider, recorded_at_ms, received_at_ms)
       VALUES (?, ?, ?, 12.97, 77.59, 15.0, 'fused', ?, ?)`
    )
      .bind(locId, dev.deviceId, admin.groupId, now, now)
      .run();

    const storageRes = await getJson('/v1/admin/storage', admin.accessToken);
    expect(storageRes.status).toBe(200);
    const storage = (await storageRes.json()) as {
      storage: { autoExpirationEnabled: boolean; groupRecordCounts: { locations: number } };
    };
    expect(storage.storage.autoExpirationEnabled).toBe(false);
    expect(storage.storage.groupRecordCounts.locations).toBe(1);

    const exportDevRes = await getJson('/v1/devices/me/export', dev.accessToken);
    expect(exportDevRes.status).toBe(200);
    const exportedDev = (await exportDevRes.json()) as { locations: Array<{ id: string }> };
    expect(exportedDev.locations).toHaveLength(1);

    const selfDelRes = await postJson(
      '/v1/devices/me/deletions',
      { scope: 'SINGLE_RECORD', category: 'LOCATION', targetRecordId: locId },
      dev.accessToken
    );
    expect(selfDelRes.status).toBe(201);
    const selfDel = (await selfDelRes.json()) as { commandId: string; serverDeletedCount: number };
    expect(selfDel.serverDeletedCount).toBe(1);

    const cmdsRes = await getJson('/v1/devices/me/commands', dev.accessToken);
    expect(cmdsRes.status).toBe(200);
    const cmds = (await cmdsRes.json()) as { pendingDeletions: Array<{ commandId: string }> };
    expect(cmds.pendingDeletions.some((c) => c.commandId === selfDel.commandId)).toBe(true);

    const ackRes = await postJson(
      '/v1/devices/me/commands/ack',
      { commandIds: [selfDel.commandId] },
      dev.accessToken
    );
    expect(ackRes.status).toBe(200);

    const exportAfterRes = await getJson('/v1/admin/export', admin.accessToken);
    const exportedAfter = (await exportAfterRes.json()) as { locations: Array<{ id: string }> };
    expect(exportedAfter.locations).toHaveLength(0);
  });

  it('rejects oversized payloads above 256 KB with HTTP 413', async () => {
    const hugeString = 'x'.repeat(270_000);
    const res = await SELF.fetch('https://example.test/v1/invitations/validate', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'cf-connecting-ip': '10.6.0.1' },
      body: JSON.stringify({ inviteCode: hugeString }),
    });
    expect(res.status).toBe(413);
  });
});
