import { z } from 'zod';
import { AppError } from '../http/errors';

export const MAX_BODY_BYTES = 262_144; // 256 KB

export async function parseJsonBody<T extends z.ZodTypeAny>(
  request: Request,
  schema: T
): Promise<z.infer<T>> {
  const contentLengthHeader = request.headers.get('content-length');
  if (contentLengthHeader) {
    const len = Number(contentLengthHeader);
    if (Number.isFinite(len) && len > MAX_BODY_BYTES) {
      throw new AppError(413, 'PAYLOAD_TOO_LARGE', 'Request body exceeds 256 KB limit.');
    }
  }

  const contentType = request.headers.get('content-type') ?? '';
  if (!contentType.toLowerCase().includes('application/json')) {
    throw new AppError(415, 'UNSUPPORTED_MEDIA_TYPE', 'Content-Type must be application/json.');
  }

  const rawText = await request.text();
  if (new TextEncoder().encode(rawText).byteLength > MAX_BODY_BYTES) {
    throw new AppError(413, 'PAYLOAD_TOO_LARGE', 'Request body exceeds 256 KB limit.');
  }

  let parsedJson: unknown;
  try {
    parsedJson = JSON.parse(rawText);
  } catch {
    throw new AppError(400, 'INVALID_JSON', 'Malformed JSON body.');
  }

  const result = schema.safeParse(parsedJson);
  if (!result.success) {
    throw new AppError(400, 'VALIDATION_ERROR', 'Request payload failed validation.');
  }
  return result.data;
}

const IPV4_REGEX =
  /^(?:(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)$/;
const IPV6_REGEX = /^[0-9a-fA-F:]{2,45}$/;

export function isValidIpAddress(candidate: string): boolean {
  if (IPV4_REGEX.test(candidate)) return true;
  if (candidate.includes(':') && IPV6_REGEX.test(candidate)) return true;
  return false;
}

/**
 * Derives the rate-limit client IP strictly from Cloudflare's edge-overwritten
 * CF-Connecting-IP header (never client-supplied X-Forwarded-For / Forwarded / X-Real-IP).
 */
export function getClientIpKey(request: Request): string {
  const cfIp = request.headers.get('cf-connecting-ip')?.trim();
  if (cfIp && isValidIpAddress(cfIp)) {
    return cfIp.toLowerCase();
  }
  return 'unverified-client';
}
