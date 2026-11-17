import { z } from 'zod';

const uuidSchema = z.string().uuid();

export const adminRegisterSchema = z.object({
  email: z.string().trim().email().max(254).transform((v) => v.toLowerCase()),
  groupName: z.string().trim().min(1).max(50).default('Private Group'),
  deviceName: z.string().trim().min(1).max(40).default('Administrator Phone'),
  publicKeySpki: z.string().trim().min(60).max(512),
});

export const adminChallengeSchema = z.object({
  email: z.string().trim().email().max(254).transform((v) => v.toLowerCase()),
  adminDeviceId: uuidSchema,
  operation: z.enum(['LOGIN']).default('LOGIN'),
});

export const adminVerifySchema = z.object({
  challengeId: uuidSchema,
  signature: z.string().trim().min(64).max(256),
});

export const adminRecoverSchema = z.object({
  email: z.string().trim().email().max(254).transform((v) => v.toLowerCase()),
  recoveryCode: z.string().trim().min(8).max(24),
  newDeviceName: z.string().trim().min(1).max(40).default('Replacement Admin Phone'),
  newPublicKeySpki: z.string().trim().min(60).max(512),
});

export const adminDeviceInviteSchema = z.object({
  ttlMinutes: z.number().int().min(5).max(60).default(15),
});

export const adminDeviceEnrollSchema = z.object({
  pairingCode: z.string().trim().min(8).max(24),
  deviceName: z.string().trim().min(1).max(40).default('Secondary Admin Device'),
  publicKeySpki: z.string().trim().min(60).max(512),
});

export const refreshSchema = z.object({
  refreshToken: z.string().min(20).max(256),
});

export const createInvitationSchema = z.object({
  label: z.string().trim().min(1).max(40).optional(),
  ttlMinutes: z.number().int().min(5).max(1440).default(60),
});

export const validateInvitationSchema = z.object({
  inviteCode: z.string().trim().min(8).max(24),
});

export const enrollDeviceSchema = z.object({
  inviteCode: z.string().trim().min(8).max(24),
  displayName: z.string().trim().min(1).max(40),
  appVersion: z.string().trim().min(1).max(32),
});

export const updateSharingSettingsSchema = z.object({
  globalPaused: z.boolean(),
  locationEnabled: z.boolean(),
  usageEnabled: z.boolean(),
  deviceStatusEnabled: z.boolean(),
  locationPermissionState: z.enum(['UNKNOWN', 'DENIED', 'FOREGROUND_ONLY', 'BACKGROUND_GRANTED']),
  usagePermissionState: z.enum(['UNKNOWN', 'DENIED', 'GRANTED']),
  backgroundRestricted: z.boolean(),
  settingsVersion: z.number().int().min(1),
});

export const disconnectDeviceSchema = z.object({
  deleteServerData: z.boolean().default(false),
});

export const deletionRequestSchema = z
  .object({
    scope: z.enum(['SINGLE_RECORD', 'DATE_RANGE', 'CATEGORY', 'ALL_DEVICE_DATA']),
    category: z.enum(['LOCATION', 'USAGE', 'DEVICE_STATUS', 'ALL']),
    targetRecordId: uuidSchema.optional(),
    rangeStartMs: z.number().int().nonnegative().optional(),
    rangeEndMs: z.number().int().nonnegative().optional(),
  })
  .superRefine((val, ctx) => {
    if (val.scope === 'SINGLE_RECORD' && !val.targetRecordId) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        message: 'targetRecordId is required for SINGLE_RECORD deletion.',
      });
    }
    if (val.scope === 'DATE_RANGE') {
      if (val.rangeStartMs === undefined || val.rangeEndMs === undefined) {
        ctx.addIssue({
          code: z.ZodIssueCode.custom,
          message: 'rangeStartMs and rangeEndMs are required for DATE_RANGE deletion.',
        });
      } else if (val.rangeStartMs > val.rangeEndMs) {
        ctx.addIssue({
          code: z.ZodIssueCode.custom,
          message: 'rangeStartMs must be <= rangeEndMs.',
        });
      }
    }
  });

export const ackCommandsSchema = z.object({
  commandIds: z.array(uuidSchema).min(1).max(50),
});
