import {
  canonicalChallengeMessage,
  generateInviteCode,
  generateRecoveryCodes,
  importP256SpkiPublicKey,
  normalizeCode,
  randomBytes,
  sha256Hex,
  signJwt,
  toBase64Url,
  verifyEcdsaP256Signature,
} from '../crypto/crypto';
import { AppError } from '../http/errors';
import type { Env } from '../types/env';

const ACCESS_TTL_SEC = 900; // 15 minutes
const CHALLENGE_TTL_MS = 120 * 1000; // 2 minutes
const ADMIN_REFRESH_TTL_MS = 30 * 24 * 60 * 60 * 1000;
const DEVICE_REFRESH_TTL_MS = 90 * 24 * 60 * 60 * 1000;

function formatCode12(raw12: string): string {
  return `${raw12.slice(0, 4)}-${raw12.slice(4, 8)}-${raw12.slice(8, 12)}`;
}

export async function issueSessionTokens(
  env: Env,
  params: {
    subjectType: 'ADMIN' | 'DEVICE';
    accountId: string | null;
    adminDeviceId: string | null;
    deviceId: string | null;
    groupId: string;
    nowMs: number;
  }
) {
  const sessionId = crypto.randomUUID();
  const rawRefresh = toBase64Url(randomBytes(32));
  const refreshHash = await sha256Hex(rawRefresh);
  const refreshTtl = params.subjectType === 'ADMIN' ? ADMIN_REFRESH_TTL_MS : DEVICE_REFRESH_TTL_MS;

  await env.DB.prepare(
    `INSERT INTO auth_sessions (
       id, subject_type, account_id, admin_device_id, device_id, group_id,
       refresh_token_hash, expires_at_ms, revoked_at_ms, created_at_ms, last_used_at_ms
     )
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?)`
  )
    .bind(
      sessionId,
      params.subjectType,
      params.accountId,
      params.adminDeviceId,
      params.deviceId,
      params.groupId,
      refreshHash,
      params.nowMs + refreshTtl,
      params.nowMs,
      params.nowMs
    )
    .run();

  const iatSec = Math.floor(params.nowMs / 1000);
  const accessToken = await signJwt(
    {
      sub: params.subjectType === 'ADMIN' ? params.accountId! : params.deviceId!,
      role: params.subjectType,
      groupId: params.groupId,
      accountId: params.accountId,
      adminDeviceId: params.adminDeviceId,
      deviceId: params.deviceId,
      sessionId,
      iat: iatSec,
      exp: iatSec + ACCESS_TTL_SEC,
    },
    env.JWT_SIGNING_SECRET
  );

  return {
    accessToken,
    refreshToken: rawRefresh,
    expiresInSec: ACCESS_TTL_SEC,
  };
}

export async function registerAdmin(
  env: Env,
  input: {
    email: string;
    groupName: string;
    deviceName: string;
    publicKeySpki: string;
  },
  nowMs: number = Date.now()
) {
  const parsedKey = await importP256SpkiPublicKey(input.publicKeySpki);
  if (!parsedKey) {
    throw new AppError(400, 'INVALID_PUBLIC_KEY', 'publicKeySpki must be a valid Base64 ECDSA P-256 SPKI key.');
  }

  const existing = await env.DB.prepare('SELECT id FROM accounts WHERE email = ?')
    .bind(input.email)
    .first<{ id: string }>();
  if (existing) {
    throw new AppError(409, 'EMAIL_EXISTS', 'An account with this email already exists.');
  }

  const accountId = crypto.randomUUID();
  const groupId = crypto.randomUUID();
  const adminDeviceId = crypto.randomUUID();
  const recoveryCodes = generateRecoveryCodes(10);

  const stmts: D1PreparedStatement[] = [
    env.DB.prepare(
      `INSERT INTO accounts (id, email, status, created_at_ms, updated_at_ms)
       VALUES (?, ?, 'ACTIVE', ?, ?)`
    ).bind(accountId, input.email, nowMs, nowMs),
    env.DB.prepare(
      `INSERT INTO groups (id, administrator_account_id, name, status, created_at_ms)
       VALUES (?, ?, ?, 'ACTIVE', ?)`
    ).bind(groupId, accountId, input.groupName, nowMs),
    env.DB.prepare(
      `INSERT INTO admin_devices (id, account_id, group_id, device_name, public_key_spki, status, created_at_ms, last_auth_at_ms, revoked_at_ms)
       VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, ?, NULL)`
    ).bind(adminDeviceId, accountId, groupId, input.deviceName, input.publicKeySpki.trim(), nowMs, nowMs),
  ];

  for (const code of recoveryCodes) {
    const codeHash = await sha256Hex(normalizeCode(code));
    stmts.push(
      env.DB.prepare(
        `INSERT INTO account_recovery_codes (id, account_id, code_hash, used_at_ms, created_at_ms)
         VALUES (?, ?, ?, NULL, ?)`
      ).bind(crypto.randomUUID(), accountId, codeHash, nowMs)
    );
  }

  await env.DB.batch(stmts);
  const tokens = await issueSessionTokens(env, {
    subjectType: 'ADMIN',
    accountId,
    adminDeviceId,
    deviceId: null,
    groupId,
    nowMs,
  });

  return {
    accountId,
    adminDeviceId,
    groupId,
    groupName: input.groupName,
    ...tokens,
    recoveryCodes,
  };
}

export async function createAdminChallenge(
  env: Env,
  input: { email: string; adminDeviceId: string; operation: 'LOGIN' },
  nowMs: number = Date.now()
) {
  const row = await env.DB.prepare(
    `SELECT a.id AS account_id, a.status AS account_status, ad.id AS admin_device_id, ad.status AS device_status
     FROM accounts a
     JOIN admin_devices ad ON ad.account_id = a.id
     WHERE a.email = ? AND ad.id = ?`
  )
    .bind(input.email, input.adminDeviceId)
    .first<{
      account_id: string;
      account_status: string;
      admin_device_id: string;
      device_status: string;
    }>();

  if (!row || row.account_status !== 'ACTIVE' || row.device_status !== 'ACTIVE') {
    throw new AppError(401, 'AUTHENTICATION_FAILED', 'Account or registered administrator device is not active.');
  }

  const challengeId = crypto.randomUUID();
  const nonce = toBase64Url(randomBytes(24));
  const expiresAtMs = nowMs + CHALLENGE_TTL_MS;

  await env.DB.prepare(
    `INSERT INTO auth_challenges (id, account_id, admin_device_id, operation, nonce, expires_at_ms, used_at_ms, created_at_ms)
     VALUES (?, ?, ?, ?, ?, ?, NULL, ?)`
  )
    .bind(challengeId, row.account_id, row.admin_device_id, input.operation, nonce, expiresAtMs, nowMs)
    .run();

  const canonicalPayload = canonicalChallengeMessage({
    challengeId,
    accountId: row.account_id,
    adminDeviceId: row.admin_device_id,
    operation: input.operation,
    nonce,
    expiresAtMs,
  });

  return {
    challengeId,
    canonicalPayload,
    expiresAtMs,
  };
}

export async function verifyAdminChallenge(
  env: Env,
  input: { challengeId: string; signature: string },
  nowMs: number = Date.now()
) {
  const row = await env.DB.prepare(
    `SELECT c.id, c.account_id, c.admin_device_id, c.operation, c.nonce, c.expires_at_ms, c.used_at_ms,
            a.status AS account_status,
            ad.public_key_spki, ad.status AS device_status, ad.group_id,
            g.name AS group_name
     FROM auth_challenges c
     JOIN accounts a ON a.id = c.account_id
     JOIN admin_devices ad ON ad.id = c.admin_device_id
     JOIN groups g ON g.id = ad.group_id
     WHERE c.id = ?`
  )
    .bind(input.challengeId)
    .first<{
      id: string;
      account_id: string;
      admin_device_id: string;
      operation: string;
      nonce: string;
      expires_at_ms: number;
      used_at_ms: number | null;
      account_status: string;
      public_key_spki: string;
      device_status: string;
      group_id: string;
      group_name: string;
    }>();

  if (
    !row ||
    row.used_at_ms !== null ||
    row.expires_at_ms <= nowMs ||
    row.account_status !== 'ACTIVE' ||
    row.device_status !== 'ACTIVE'
  ) {
    throw new AppError(401, 'CHALLENGE_INVALID', 'Authentication challenge is invalid, expired, or already used.');
  }

  const canonicalPayload = canonicalChallengeMessage({
    challengeId: row.id,
    accountId: row.account_id,
    adminDeviceId: row.admin_device_id,
    operation: row.operation,
    nonce: row.nonce,
    expiresAtMs: row.expires_at_ms,
  });

  const validSig = await verifyEcdsaP256Signature(
    row.public_key_spki,
    canonicalPayload,
    input.signature
  );
  if (!validSig) {
    throw new AppError(401, 'INVALID_SIGNATURE', 'Cryptographic signature verification failed.');
  }

  // Atomically consume the single-use challenge
  const claim = await env.DB.prepare(
    'UPDATE auth_challenges SET used_at_ms = ? WHERE id = ? AND used_at_ms IS NULL AND expires_at_ms > ?'
  )
    .bind(nowMs, row.id, nowMs)
    .run();

  if ((claim.meta.changes ?? 0) !== 1) {
    throw new AppError(401, 'CHALLENGE_REPLAYED', 'Authentication challenge was already consumed.');
  }

  await env.DB.prepare('UPDATE admin_devices SET last_auth_at_ms = ? WHERE id = ?')
    .bind(nowMs, row.admin_device_id)
    .run();

  const tokens = await issueSessionTokens(env, {
    subjectType: 'ADMIN',
    accountId: row.account_id,
    adminDeviceId: row.admin_device_id,
    deviceId: null,
    groupId: row.group_id,
    nowMs,
  });

  return {
    accountId: row.account_id,
    adminDeviceId: row.admin_device_id,
    groupId: row.group_id,
    groupName: row.group_name,
    ...tokens,
  };
}

export async function recoverAdminAccount(
  env: Env,
  input: {
    email: string;
    recoveryCode: string;
    newDeviceName: string;
    newPublicKeySpki: string;
  },
  nowMs: number = Date.now()
) {
  const parsedKey = await importP256SpkiPublicKey(input.newPublicKeySpki);
  if (!parsedKey) {
    throw new AppError(400, 'INVALID_PUBLIC_KEY', 'newPublicKeySpki must be a valid Base64 ECDSA P-256 SPKI key.');
  }

  const account = await env.DB.prepare('SELECT id, status FROM accounts WHERE email = ?')
    .bind(input.email)
    .first<{ id: string; status: string }>();

  if (!account || account.status !== 'ACTIVE') {
    throw new AppError(401, 'RECOVERY_FAILED', 'Invalid recovery credentials.');
  }

  const normalized = normalizeCode(input.recoveryCode);
  if (normalized.length !== 12) {
    throw new AppError(401, 'RECOVERY_FAILED', 'Invalid recovery credentials.');
  }
  const codeHash = await sha256Hex(normalized);

  const codeRow = await env.DB.prepare(
    'SELECT id FROM account_recovery_codes WHERE account_id = ? AND code_hash = ? AND used_at_ms IS NULL'
  )
    .bind(account.id, codeHash)
    .first<{ id: string }>();

  if (!codeRow) {
    throw new AppError(401, 'RECOVERY_FAILED', 'Invalid recovery credentials.');
  }

  const group = await env.DB.prepare(
    'SELECT id, name FROM groups WHERE administrator_account_id = ? AND status = ? ORDER BY created_at_ms ASC LIMIT 1'
  )
    .bind(account.id, 'ACTIVE')
    .first<{ id: string; name: string }>();

  if (!group) {
    throw new AppError(500, 'GROUP_MISSING', 'Administrator group could not be found.');
  }

  // Atomically consume single-use recovery code before mutating devices/sessions
  const consumeRes = await env.DB.prepare(
    'UPDATE account_recovery_codes SET used_at_ms = ? WHERE id = ? AND used_at_ms IS NULL'
  )
    .bind(nowMs, codeRow.id)
    .run();

  if ((consumeRes.meta.changes ?? 0) !== 1) {
    throw new AppError(401, 'RECOVERY_FAILED', 'Recovery code has already been used.');
  }

  const newAdminDeviceId = crypto.randomUUID();
  const batchRes = await env.DB.batch([
    env.DB.prepare(
      `UPDATE admin_devices SET status = 'REVOKED', revoked_at_ms = COALESCE(revoked_at_ms, ?)
       WHERE account_id = ? AND status = 'ACTIVE'`
    ).bind(nowMs, account.id),
    env.DB.prepare(
      `UPDATE auth_sessions SET revoked_at_ms = ? WHERE account_id = ? AND revoked_at_ms IS NULL`
    ).bind(nowMs, account.id),
    env.DB.prepare(
      `UPDATE admin_pairing_invites SET revoked_at_ms = COALESCE(revoked_at_ms, ?)
       WHERE account_id = ? AND used_at_ms IS NULL`
    ).bind(nowMs, account.id),
    env.DB.prepare(
      `INSERT INTO admin_devices (id, account_id, group_id, device_name, public_key_spki, status, created_at_ms, last_auth_at_ms, revoked_at_ms)
       VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, ?, NULL)`
    ).bind(
      newAdminDeviceId,
      account.id,
      group.id,
      input.newDeviceName,
      input.newPublicKeySpki.trim(),
      nowMs,
      nowMs
    ),
    env.DB.prepare('UPDATE accounts SET updated_at_ms = ? WHERE id = ?').bind(nowMs, account.id),
  ]);

  const revokedPriorDevicesCount = batchRes[0]?.meta.changes ?? 0;

  const tokens = await issueSessionTokens(env, {
    subjectType: 'ADMIN',
    accountId: account.id,
    adminDeviceId: newAdminDeviceId,
    deviceId: null,
    groupId: group.id,
    nowMs,
  });

  return {
    accountId: account.id,
    adminDeviceId: newAdminDeviceId,
    groupId: group.id,
    groupName: group.name,
    ...tokens,
    revokedPriorDevicesCount,
  };
}

export async function createAdminPairingInvite(
  env: Env,
  params: { accountId: string; groupId: string; ttlMinutes: number },
  nowMs: number = Date.now()
) {
  const rawCode = generateInviteCode(12);
  const tokenHash = await sha256Hex(rawCode);
  const pairingInviteId = crypto.randomUUID();
  const expiresAtMs = nowMs + params.ttlMinutes * 60 * 1000;

  await env.DB.prepare(
    `INSERT INTO admin_pairing_invites (id, account_id, group_id, token_hash, expires_at_ms, used_at_ms, used_by_admin_device_id, revoked_at_ms, created_at_ms)
     VALUES (?, ?, ?, ?, ?, NULL, NULL, NULL, ?)`
  )
    .bind(pairingInviteId, params.accountId, params.groupId, tokenHash, expiresAtMs, nowMs)
    .run();

  const pairingCode = formatCode12(rawCode);
  return {
    pairingInviteId,
    pairingCode,
    qrPayload: `eachother://admin-pair?code=${pairingCode}`,
    expiresAtMs,
  };
}

export async function enrollAdditionalAdminDevice(
  env: Env,
  input: { pairingCode: string; deviceName: string; publicKeySpki: string },
  nowMs: number = Date.now()
) {
  const parsedKey = await importP256SpkiPublicKey(input.publicKeySpki);
  if (!parsedKey) {
    throw new AppError(400, 'INVALID_PUBLIC_KEY', 'publicKeySpki must be a valid Base64 ECDSA P-256 SPKI key.');
  }

  const normalized = normalizeCode(input.pairingCode);
  if (normalized.length !== 12) {
    throw new AppError(404, 'PAIRING_INVALID', 'Administrator pairing code is invalid, expired, or used.');
  }
  const tokenHash = await sha256Hex(normalized);

  const invite = await env.DB.prepare(
    `SELECT p.id, p.account_id, p.group_id, p.expires_at_ms, p.used_at_ms, p.revoked_at_ms,
            a.status AS account_status, g.name AS group_name, g.status AS group_status
     FROM admin_pairing_invites p
     JOIN accounts a ON a.id = p.account_id
     JOIN groups g ON g.id = p.group_id
     WHERE p.token_hash = ?`
  )
    .bind(tokenHash)
    .first<{
      id: string;
      account_id: string;
      group_id: string;
      expires_at_ms: number;
      used_at_ms: number | null;
      revoked_at_ms: number | null;
      account_status: string;
      group_name: string;
      group_status: string;
    }>();

  if (
    !invite ||
    invite.used_at_ms !== null ||
    invite.revoked_at_ms !== null ||
    invite.expires_at_ms <= nowMs ||
    invite.account_status !== 'ACTIVE' ||
    invite.group_status !== 'ACTIVE'
  ) {
    throw new AppError(404, 'PAIRING_INVALID', 'Administrator pairing code is invalid, expired, or used.');
  }

  const newAdminDeviceId = crypto.randomUUID();
  const batchRes = await env.DB.batch([
    env.DB.prepare(
      `INSERT INTO admin_devices (id, account_id, group_id, device_name, public_key_spki, status, created_at_ms, last_auth_at_ms, revoked_at_ms)
       VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, ?, NULL)`
    ).bind(
      newAdminDeviceId,
      invite.account_id,
      invite.group_id,
      input.deviceName,
      input.publicKeySpki.trim(),
      nowMs,
      nowMs
    ),
    env.DB.prepare(
      `UPDATE admin_pairing_invites
       SET used_at_ms = ?, used_by_admin_device_id = ?
       WHERE id = ? AND used_at_ms IS NULL AND revoked_at_ms IS NULL AND expires_at_ms > ?`
    ).bind(nowMs, newAdminDeviceId, invite.id, nowMs),
  ]);

  if ((batchRes[1]?.meta.changes ?? 0) !== 1) {
    await env.DB.prepare('DELETE FROM admin_devices WHERE id = ?').bind(newAdminDeviceId).run();
    throw new AppError(404, 'PAIRING_INVALID', 'Administrator pairing code is invalid, expired, or used.');
  }

  const tokens = await issueSessionTokens(env, {
    subjectType: 'ADMIN',
    accountId: invite.account_id,
    adminDeviceId: newAdminDeviceId,
    deviceId: null,
    groupId: invite.group_id,
    nowMs,
  });

  return {
    accountId: invite.account_id,
    adminDeviceId: newAdminDeviceId,
    groupId: invite.group_id,
    groupName: invite.group_name,
    ...tokens,
  };
}

export async function listRegisteredAdminDevices(env: Env, accountId: string) {
  const rows = await env.DB.prepare(
    `SELECT id, device_name, status, created_at_ms, last_auth_at_ms, revoked_at_ms
     FROM admin_devices
     WHERE account_id = ?
     ORDER BY created_at_ms DESC`
  )
    .bind(accountId)
    .all<{
      id: string;
      device_name: string;
      status: string;
      created_at_ms: number;
      last_auth_at_ms: number | null;
      revoked_at_ms: number | null;
    }>();

  return { adminDevices: rows.results };
}

export async function revokeRegisteredAdminDevice(
  env: Env,
  accountId: string,
  targetAdminDeviceId: string,
  nowMs: number = Date.now()
) {
  const target = await env.DB.prepare(
    'SELECT id, status FROM admin_devices WHERE id = ? AND account_id = ?'
  )
    .bind(targetAdminDeviceId, accountId)
    .first<{ id: string; status: string }>();

  if (!target) {
    throw new AppError(404, 'ADMIN_DEVICE_NOT_FOUND', 'Registered administrator device not found.');
  }

  const activeCountRow = await env.DB.prepare(
    `SELECT COUNT(*) AS cnt FROM admin_devices WHERE account_id = ? AND status = 'ACTIVE'`
  )
    .bind(accountId)
    .first<{ cnt: number }>();

  if (target.status === 'ACTIVE' && (activeCountRow?.cnt ?? 0) <= 1) {
    throw new AppError(
      400,
      'LAST_ACTIVE_ADMIN_DEVICE',
      'Cannot revoke the only active administrator device. Pair another device first or use a recovery code on a replacement phone.'
    );
  }

  await env.DB.batch([
    env.DB.prepare(
      `UPDATE admin_devices SET status = 'REVOKED', revoked_at_ms = COALESCE(revoked_at_ms, ?)
       WHERE id = ? AND account_id = ?`
    ).bind(nowMs, targetAdminDeviceId, accountId),
    env.DB.prepare(
      'UPDATE auth_sessions SET revoked_at_ms = ? WHERE admin_device_id = ? AND revoked_at_ms IS NULL'
    ).bind(nowMs, targetAdminDeviceId),
  ]);

  return {
    adminDeviceId: targetAdminDeviceId,
    status: 'REVOKED',
    revokedAtMs: nowMs,
  };
}

export async function regenerateRecoveryCodes(
  env: Env,
  accountId: string,
  nowMs: number = Date.now()
) {
  const newCodes = generateRecoveryCodes(10);
  const stmts: D1PreparedStatement[] = [
    env.DB.prepare(
      'DELETE FROM account_recovery_codes WHERE account_id = ? AND used_at_ms IS NULL'
    ).bind(accountId),
  ];

  for (const code of newCodes) {
    const codeHash = await sha256Hex(normalizeCode(code));
    stmts.push(
      env.DB.prepare(
        `INSERT INTO account_recovery_codes (id, account_id, code_hash, used_at_ms, created_at_ms)
         VALUES (?, ?, ?, NULL, ?)`
      ).bind(crypto.randomUUID(), accountId, codeHash, nowMs)
    );
  }

  await env.DB.batch(stmts);
  return { recoveryCodes: newCodes };
}

export async function refreshAuthSession(
  env: Env,
  refreshToken: string,
  nowMs: number = Date.now()
) {
  const hash = await sha256Hex(refreshToken);
  const session = await env.DB.prepare(
    `SELECT id, subject_type, account_id, admin_device_id, device_id, group_id, expires_at_ms, revoked_at_ms
     FROM auth_sessions WHERE refresh_token_hash = ?`
  )
    .bind(hash)
    .first<{
      id: string;
      subject_type: 'ADMIN' | 'DEVICE';
      account_id: string | null;
      admin_device_id: string | null;
      device_id: string | null;
      group_id: string;
      expires_at_ms: number;
      revoked_at_ms: number | null;
    }>();

  if (!session) {
    throw new AppError(401, 'INVALID_REFRESH_TOKEN', 'Refresh token is invalid.');
  }

  if (session.revoked_at_ms !== null) {
    if (session.subject_type === 'ADMIN' && session.account_id) {
      await env.DB.prepare(
        'UPDATE auth_sessions SET revoked_at_ms = ? WHERE account_id = ? AND revoked_at_ms IS NULL'
      )
        .bind(nowMs, session.account_id)
        .run();
    } else if (session.device_id) {
      await env.DB.prepare(
        'UPDATE auth_sessions SET revoked_at_ms = ? WHERE device_id = ? AND revoked_at_ms IS NULL'
      )
        .bind(nowMs, session.device_id)
        .run();
    }
    throw new AppError(401, 'REFRESH_TOKEN_REUSED', 'Session revoked due to token reuse.');
  }

  if (session.expires_at_ms <= nowMs) {
    throw new AppError(401, 'REFRESH_TOKEN_EXPIRED', 'Refresh token has expired.');
  }

  if (session.subject_type === 'ADMIN') {
    if (!session.account_id || !session.admin_device_id) {
      throw new AppError(401, 'INVALID_SESSION', 'Invalid administrator session.');
    }
    const adminDevice = await env.DB.prepare(
      'SELECT status FROM admin_devices WHERE id = ? AND account_id = ?'
    )
      .bind(session.admin_device_id, session.account_id)
      .first<{ status: string }>();
    if (!adminDevice || adminDevice.status !== 'ACTIVE') {
      throw new AppError(401, 'ADMIN_DEVICE_REVOKED', 'Administrator device has been revoked.');
    }
  } else if (session.device_id) {
    const dev = await env.DB.prepare('SELECT status FROM devices WHERE id = ?')
      .bind(session.device_id)
      .first<{ status: string }>();
    if (!dev || dev.status === 'REVOKED') {
      throw new AppError(401, 'DEVICE_REVOKED', 'Device access has been revoked.');
    }
  }

  await env.DB.prepare('UPDATE auth_sessions SET revoked_at_ms = ?, last_used_at_ms = ? WHERE id = ?')
    .bind(nowMs, nowMs, session.id)
    .run();

  return issueSessionTokens(env, {
    subjectType: session.subject_type,
    accountId: session.account_id,
    adminDeviceId: session.admin_device_id,
    deviceId: session.device_id,
    groupId: session.group_id,
    nowMs,
  });
}
