import { sha256Hex } from '../crypto/crypto';
import { AppError } from '../http/errors';

export async function enforceRateLimit(
  db: D1Database,
  bucket: string,
  clientKey: string,
  maxRequests: number,
  windowMs: number,
  nowMs: number = Date.now()
): Promise<void> {
  const keyHash = await sha256Hex(`${bucket}:${clientKey}`);
  const key = `${bucket}:${keyHash.slice(0, 24)}`;

  const existing = await db
    .prepare('SELECT window_start_ms, count FROM rate_limits WHERE key = ?')
    .bind(key)
    .first<{ window_start_ms: number; count: number }>();

  if (!existing || nowMs - existing.window_start_ms >= windowMs) {
    await db
      .prepare(
        'INSERT INTO rate_limits (key, window_start_ms, count) VALUES (?, ?, 1) ON CONFLICT(key) DO UPDATE SET window_start_ms = excluded.window_start_ms, count = 1'
      )
      .bind(key, nowMs)
      .run();
    return;
  }

  if (existing.count >= maxRequests) {
    throw new AppError(429, 'RATE_LIMITED', 'Too many requests. Please try again later.');
  }

  await db
    .prepare('UPDATE rate_limits SET count = count + 1 WHERE key = ?')
    .bind(key)
    .run();
}
