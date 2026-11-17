export interface Env {
  DB: D1Database;
  JWT_SIGNING_SECRET: string;
  CODE_HASH_PEPPER: string;
  ALLOW_LEGACY_UNPEPPERED_CODES?: string;
  ENVIRONMENT: string;
  D1_SOFT_WARN_BYTES: string;
  D1_MAX_BYTES: string;
}

export type SubjectRole = 'ADMIN' | 'DEVICE';

export interface AuthContext {
  role: SubjectRole;
  accountId: string | null;
  adminDeviceId: string | null;
  deviceId: string | null;
  groupId: string;
  sessionId: string;
}
