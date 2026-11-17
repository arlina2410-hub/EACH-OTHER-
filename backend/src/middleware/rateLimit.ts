import { hmacSha256Hex } from '../crypto/crypto';
import { AppError } from '../http/errors';

export async function cleanupExpiredRateLimits(
  db: D1Database,
  nowMs: number = Date.now(),
  limit: number = 50
): Promise<number> {
  const res = await db
    .prepare(
      `DELETE FROM rate_limits
       WHERE key IN (
         SELECT key FROM rate_limits
         WHERE expires_at_ms > 0 AND expires_at_ms <= ?
         LIMIT ?
       )`
    )
    .bind(nowMs, limit)
    .run();
  return res.meta.changes ?? 0;
}

export async function enforceRateLimit(
  db: D1Database,
  bucket: string,
  clientKey: string,
  maxRequests: number,
  windowMs: number,
  keySecret: string,
  nowMs: number = Date.now()
): Promise<void> {
  if (!keySecret || keySecret.trim().length < 16) {
    throw new AppError(500, 'SERVER_MISCONFIGURED', 'Required server key secret is not configured.');
  }

  const keyHash = await hmacSha256Hex(keySecret, `${bucket}:${clientKey}`);
  const key = `${bucket}:${keyHash.slice(0, 32)}`;
  const nextExpiryMs = nowMs + windowMs;

  const row = await db
    .prepare(
      `INSERT INTO rate_limits (key, window_start_ms, count, expires_at_ms)
       VALUES (?1, ?2, 1, ?3)
       ON CONFLICT(key) DO UPDATE SET
         window_start_ms = CASE
           WHEN (?2 - rate_limits.window_start_ms) >= ?4 THEN ?2
           ELSE rate_limits.window_start_ms
         END,
         expires_at_ms = CASE
           WHEN (?2 - rate_limits.window_start_ms) >= ?4 THEN ?3
           ELSE rate_limits.expires_at_ms
         END,
         count = CASE
           WHEN (?2 - rate_limits.window_start_ms) >= ?4 THEN 1
           WHEN rate_limits.count >= ?5 THEN ?5 + 1
           ELSE rate_limits.count + 1
         END
       RETURNING count, window_start_ms`
    )
    .bind(key, nowMs, nextExpiryMs, windowMs, maxRequests)
    .first<{ count: number; window_start_ms: number }>();

  // Bounded opportunistic cleanup of expired entries without blocking hot paths
  if (row && (row.count === 1 || row.count === maxRequests)) {
    await cleanupExpiredRateLimits(db, nowMs, 25);
  }

  if ((row?.count ?? 0) > maxRequests) {
    throw new AppError(429, 'RATE_LIMITED', 'Too many requests. Please try again later.');
  }
}
