import { verifyJwt } from '../crypto/crypto';
import { AppError } from '../http/errors';
import type { AuthContext, Env } from '../types/env';

export async function authenticateRequest(request: Request, env: Env): Promise<AuthContext> {
  const header = request.headers.get('authorization');
  if (!header || !header.startsWith('Bearer ')) {
    throw new AppError(401, 'UNAUTHORIZED', 'Missing or malformed authorization header.');
  }

  const token = header.slice('Bearer '.length).trim();
  const payload = await verifyJwt(token, env.JWT_SIGNING_SECRET);
  if (!payload) {
    throw new AppError(401, 'INVALID_TOKEN', 'Access token is invalid or expired.');
  }

  const session = await env.DB.prepare(
    `SELECT id, subject_type, account_id, admin_device_id, device_id, group_id, revoked_at_ms, expires_at_ms
     FROM auth_sessions WHERE id = ?`
  )
    .bind(payload.sessionId)
    .first<{
      id: string;
      subject_type: 'ADMIN' | 'DEVICE';
      account_id: string | null;
      admin_device_id: string | null;
      device_id: string | null;
      group_id: string;
      revoked_at_ms: number | null;
      expires_at_ms: number;
    }>();

  if (
    !session ||
    session.subject_type !== payload.role ||
    session.revoked_at_ms !== null ||
    session.expires_at_ms <= Date.now()
  ) {
    throw new AppError(401, 'SESSION_REVOKED', 'Session is no longer valid.');
  }

  if (session.subject_type === 'ADMIN') {
    if (!session.account_id || !session.admin_device_id) {
      throw new AppError(401, 'INVALID_SESSION', 'Invalid administrator session.');
    }

    const [account, adminDevice] = await Promise.all([
      env.DB.prepare('SELECT status FROM accounts WHERE id = ?')
        .bind(session.account_id)
        .first<{ status: string }>(),
      env.DB.prepare('SELECT status FROM admin_devices WHERE id = ? AND account_id = ?')
        .bind(session.admin_device_id, session.account_id)
        .first<{ status: string }>(),
    ]);

    if (!account || account.status !== 'ACTIVE') {
      throw new AppError(401, 'ACCOUNT_INACTIVE', 'Administrator account is not active.');
    }
    if (!adminDevice || adminDevice.status !== 'ACTIVE') {
      throw new AppError(401, 'ADMIN_DEVICE_REVOKED', 'Administrator device has been revoked.');
    }

    return {
      role: 'ADMIN',
      accountId: session.account_id,
      adminDeviceId: session.admin_device_id,
      deviceId: null,
      groupId: session.group_id,
      sessionId: session.id,
    };
  }

  if (!session.device_id) {
    throw new AppError(401, 'INVALID_SESSION', 'Invalid device session.');
  }
  const device = await env.DB.prepare('SELECT status, group_id FROM devices WHERE id = ?')
    .bind(session.device_id)
    .first<{ status: string; group_id: string }>();
  if (!device || device.status === 'REVOKED') {
    throw new AppError(401, 'DEVICE_REVOKED', 'Device access has been revoked.');
  }

  return {
    role: 'DEVICE',
    accountId: null,
    adminDeviceId: null,
    deviceId: session.device_id,
    groupId: device.group_id,
    sessionId: session.id,
  };
}

export function requireAdmin(
  auth: AuthContext
): AuthContext & { accountId: string; adminDeviceId: string } {
  if (auth.role !== 'ADMIN' || !auth.accountId || !auth.adminDeviceId) {
    throw new AppError(403, 'FORBIDDEN', 'Administrator access required.');
  }
  return auth as AuthContext & { accountId: string; adminDeviceId: string };
}

export function requireDevice(auth: AuthContext): AuthContext & { deviceId: string } {
  if (auth.role !== 'DEVICE' || !auth.deviceId) {
    throw new AppError(403, 'FORBIDDEN', 'Connected device access required.');
  }
  return auth as AuthContext & { deviceId: string };
}
