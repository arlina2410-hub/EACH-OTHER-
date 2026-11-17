import { AppError, errorResponse, jsonResponse } from './http/errors';
import { authenticateRequest, requireAdmin, requireDevice } from './middleware/auth';
import { enforceRateLimit } from './middleware/rateLimit';
import { getClientIpKey, parseJsonBody } from './middleware/requestGuard';
import {
  createAdminChallenge,
  createAdminPairingInvite,
  enrollAdditionalAdminDevice,
  listRegisteredAdminDevices,
  recoverAdminAccount,
  refreshAuthSession,
  regenerateRecoveryCodes,
  registerAdmin,
  revokeRegisteredAdminDevice,
  verifyAdminChallenge,
} from './services/authService';
import {
  acknowledgeDeviceCommands,
  createDeletionCommand,
  getPendingDeviceCommands,
} from './services/deletionService';
import {
  disconnectDeviceByAdmin,
  getAdminDeviceDetail,
  getDeviceSyncState,
  listAdminDevices,
  revokeOwnDevice,
  updateConnectedSharingSettings,
} from './services/deviceService';
import {
  createInvitation,
  enrollDevice,
  listInvitations,
  revokeInvitation,
  validateInvitation,
} from './services/invitationService';
import { exportGroupData, getStorageAndQuotaStatus } from './services/quotaService';
import type { Env } from './types/env';
import {
  ackCommandsSchema,
  adminChallengeSchema,
  adminDeviceEnrollSchema,
  adminDeviceInviteSchema,
  adminRecoverSchema,
  adminRegisterSchema,
  adminVerifySchema,
  createInvitationSchema,
  deletionRequestSchema,
  disconnectDeviceSchema,
  enrollDeviceSchema,
  refreshSchema,
  updateSharingSettingsSchema,
  validateInvitationSchema,
} from './validation/schemas';

const UUID_REGEX = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

function assertUuid(value: string, field: string): string {
  if (!UUID_REGEX.test(value)) {
    throw new AppError(400, 'INVALID_ID', `Invalid ${field} identifier.`);
  }
  return value;
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    try {
      const url = new URL(request.url);
      const path = url.pathname;
      const method = request.method.toUpperCase();
      const ipKey = getClientIpKey(request);

      if (method === 'GET' && path === '/v1/health') {
        return jsonResponse({ status: 'ok', version: '0.2.0', environment: env.ENVIRONMENT });
      }

      // --- Public Auth & Enrollment ---
      if (method === 'POST' && path === '/v1/auth/admin/register') {
        await enforceRateLimit(env.DB, 'admin_register', ipKey, 5, 60 * 60 * 1000, env.JWT_SIGNING_SECRET);
        const body = await parseJsonBody(request, adminRegisterSchema);
        const result = await registerAdmin(env, body);
        return jsonResponse(result, 201);
      }

      if (method === 'POST' && path === '/v1/auth/admin/challenge') {
        await enforceRateLimit(env.DB, 'admin_challenge', ipKey, 20, 15 * 60 * 1000, env.JWT_SIGNING_SECRET);
        const body = await parseJsonBody(request, adminChallengeSchema);
        const result = await createAdminChallenge(env, body);
        return jsonResponse(result, 200);
      }

      if (method === 'POST' && path === '/v1/auth/admin/verify') {
        await enforceRateLimit(env.DB, 'admin_verify', ipKey, 20, 15 * 60 * 1000, env.JWT_SIGNING_SECRET);
        const body = await parseJsonBody(request, adminVerifySchema);
        const result = await verifyAdminChallenge(env, body);
        return jsonResponse(result, 200);
      }

      if (method === 'POST' && path === '/v1/auth/admin/recover') {
        await enforceRateLimit(env.DB, 'admin_recover', ipKey, 5, 60 * 60 * 1000, env.JWT_SIGNING_SECRET);
        const body = await parseJsonBody(request, adminRecoverSchema);
        await enforceRateLimit(env.DB, 'admin_recover_acct', body.email, 5, 60 * 60 * 1000, env.JWT_SIGNING_SECRET);
        const result = await recoverAdminAccount(env, body);
        return jsonResponse(result, 200);
      }

      if (method === 'POST' && path === '/v1/auth/admin/devices/enroll') {
        await enforceRateLimit(env.DB, 'admin_device_enroll', ipKey, 10, 15 * 60 * 1000, env.JWT_SIGNING_SECRET);
        const body = await parseJsonBody(request, adminDeviceEnrollSchema);
        const result = await enrollAdditionalAdminDevice(env, body);
        return jsonResponse(result, 201);
      }

      if (method === 'POST' && path === '/v1/auth/refresh') {
        await enforceRateLimit(env.DB, 'auth_refresh', ipKey, 30, 15 * 60 * 1000, env.JWT_SIGNING_SECRET);
        const body = await parseJsonBody(request, refreshSchema);
        const result = await refreshAuthSession(env, body.refreshToken);
        return jsonResponse(result, 200);
      }

      if (method === 'POST' && path === '/v1/invitations/validate') {
        await enforceRateLimit(env.DB, 'invite_validate', ipKey, 10, 15 * 60 * 1000, env.JWT_SIGNING_SECRET);
        const body = await parseJsonBody(request, validateInvitationSchema);
        const result = await validateInvitation(env, body.inviteCode);
        return jsonResponse(result, 200);
      }

      if (method === 'POST' && path === '/v1/devices/enroll') {
        await enforceRateLimit(env.DB, 'device_enroll', ipKey, 10, 15 * 60 * 1000, env.JWT_SIGNING_SECRET);
        const body = await parseJsonBody(request, enrollDeviceSchema);
        const result = await enrollDevice(env, body);
        return jsonResponse(result, 201);
      }

      // --- Authenticated Routes ---
      const auth = await authenticateRequest(request, env);

      // Admin: regenerate recovery codes
      if (method === 'POST' && path === '/v1/auth/admin/recovery-codes/regenerate') {
        const admin = requireAdmin(auth);
        const result = await regenerateRecoveryCodes(env, admin.accountId);
        return jsonResponse(result, 200);
      }

      // Connected Device routes
      if (method === 'PUT' && path === '/v1/devices/me/sharing-settings') {
        const dev = requireDevice(auth);
        const body = await parseJsonBody(request, updateSharingSettingsSchema);
        const result = await updateConnectedSharingSettings(env, dev.deviceId, dev.groupId, body);
        return jsonResponse(result, 200);
      }

      if (method === 'POST' && path === '/v1/devices/revoke') {
        const dev = requireDevice(auth);
        const result = await revokeOwnDevice(env, dev.deviceId, dev.groupId);
        return jsonResponse(result, 200);
      }

      if (method === 'GET' && path === '/v1/devices/me/sync-state') {
        const dev = requireDevice(auth);
        const result = await getDeviceSyncState(env, dev.deviceId, dev.groupId);
        return jsonResponse(result, 200);
      }

      if (method === 'GET' && path === '/v1/devices/me/commands') {
        const dev = requireDevice(auth);
        const result = await getPendingDeviceCommands(env, dev.deviceId, dev.groupId);
        return jsonResponse(result, 200);
      }

      if (method === 'POST' && path === '/v1/devices/me/commands/ack') {
        const dev = requireDevice(auth);
        const body = await parseJsonBody(request, ackCommandsSchema);
        const result = await acknowledgeDeviceCommands(env, dev.deviceId, body.commandIds);
        return jsonResponse(result, 200);
      }

      if (method === 'POST' && path === '/v1/devices/me/deletions') {
        const dev = requireDevice(auth);
        const body = await parseJsonBody(request, deletionRequestSchema);
        const result = await createDeletionCommand(env, {
          groupId: dev.groupId,
          deviceId: dev.deviceId,
          initiatedBy: 'DEVICE',
          ...body,
        });
        return jsonResponse(result, 201);
      }

      if (method === 'GET' && path === '/v1/devices/me/export') {
        const dev = requireDevice(auth);
        const result = await exportGroupData(env, dev.groupId, dev.deviceId);
        return jsonResponse(result, 200);
      }

      // Administrator routes
      if (path.startsWith('/v1/admin/')) {
        const admin = requireAdmin(auth);

        if (method === 'GET' && path === '/v1/admin/admin-devices') {
          return jsonResponse(await listRegisteredAdminDevices(env, admin.accountId), 200);
        }

        if (method === 'POST' && path === '/v1/admin/admin-devices/invite') {
          const body = await parseJsonBody(request, adminDeviceInviteSchema);
          return jsonResponse(
            await createAdminPairingInvite(env, {
              accountId: admin.accountId,
              groupId: admin.groupId,
              ttlMinutes: body.ttlMinutes,
            }),
            201
          );
        }

        const adminDeviceRevokeMatch = path.match(/^\/v1\/admin\/admin-devices\/([^/]+)\/revoke$/);
        if (method === 'POST' && adminDeviceRevokeMatch) {
          const adminDeviceId = assertUuid(adminDeviceRevokeMatch[1]!, 'adminDeviceId');
          return jsonResponse(
            await revokeRegisteredAdminDevice(env, admin.accountId, adminDeviceId),
            200
          );
        }

        if (method === 'GET' && path === '/v1/admin/devices') {
          return jsonResponse(await listAdminDevices(env, admin.groupId), 200);
        }

        if (method === 'POST' && path === '/v1/admin/invitations') {
          const body = await parseJsonBody(request, createInvitationSchema);
          return jsonResponse(
            await createInvitation(env, {
              groupId: admin.groupId,
              accountId: admin.accountId,
              ...body,
            }),
            201
          );
        }

        if (method === 'GET' && path === '/v1/admin/invitations') {
          return jsonResponse(await listInvitations(env, admin.groupId), 200);
        }

        if (method === 'GET' && path === '/v1/admin/storage') {
          return jsonResponse(await getStorageAndQuotaStatus(env, admin.groupId), 200);
        }

        if (method === 'GET' && path === '/v1/admin/export') {
          const deviceIdParam = url.searchParams.get('deviceId') ?? undefined;
          if (deviceIdParam) assertUuid(deviceIdParam, 'deviceId');
          return jsonResponse(await exportGroupData(env, admin.groupId, deviceIdParam), 200);
        }

        const inviteRevokeMatch = path.match(/^\/v1\/admin\/invitations\/([^/]+)\/revoke$/);
        if (method === 'POST' && inviteRevokeMatch) {
          const invitationId = assertUuid(inviteRevokeMatch[1]!, 'invitationId');
          return jsonResponse(await revokeInvitation(env, admin.groupId, invitationId), 200);
        }

        const deviceDetailMatch = path.match(/^\/v1\/admin\/devices\/([^/]+)$/);
        if (method === 'GET' && deviceDetailMatch) {
          const deviceId = assertUuid(deviceDetailMatch[1]!, 'deviceId');
          return jsonResponse(await getAdminDeviceDetail(env, admin.groupId, deviceId), 200);
        }

        const deviceDisconnectMatch = path.match(/^\/v1\/admin\/devices\/([^/]+)\/disconnect$/);
        if (method === 'POST' && deviceDisconnectMatch) {
          const deviceId = assertUuid(deviceDisconnectMatch[1]!, 'deviceId');
          const body = await parseJsonBody(request, disconnectDeviceSchema);
          return jsonResponse(
            await disconnectDeviceByAdmin(env, admin.groupId, deviceId, body.deleteServerData),
            200
          );
        }

        const deviceDeleteMatch = path.match(/^\/v1\/admin\/devices\/([^/]+)\/deletion-commands$/);
        if (method === 'POST' && deviceDeleteMatch) {
          const deviceId = assertUuid(deviceDeleteMatch[1]!, 'deviceId');
          const body = await parseJsonBody(request, deletionRequestSchema);
          return jsonResponse(
            await createDeletionCommand(env, {
              groupId: admin.groupId,
              deviceId,
              initiatedBy: 'ADMIN',
              ...body,
            }),
            201
          );
        }
      }

      return jsonResponse(
        { error: { code: 'NOT_FOUND', message: 'Route not found.' } },
        404
      );
    } catch (err) {
      return errorResponse(err);
    }
  },
};
