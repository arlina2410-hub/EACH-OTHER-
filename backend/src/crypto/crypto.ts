const textEncoder = new TextEncoder();
const textDecoder = new TextDecoder();

const CROCKFORD_ALPHABET = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';

export function randomBytes(length: number): Uint8Array {
  const bytes = new Uint8Array(length);
  crypto.getRandomValues(bytes);
  return bytes;
}

export function toHex(bytes: Uint8Array): string {
  return Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
}

export function toBase64Url(bytes: Uint8Array): string {
  let binary = '';
  for (let i = 0; i < bytes.length; i++) {
    binary += String.fromCharCode(bytes[i]!);
  }
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '');
}

export function fromBase64Url(value: string): Uint8Array {
  const padded = value.replace(/-/g, '+').replace(/_/g, '/') + '==='.slice((value.length + 3) % 4);
  const binary = atob(padded);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) {
    bytes[i] = binary.charCodeAt(i);
  }
  return bytes;
}

export function fromBase64(value: string): Uint8Array {
  const normalized = value.replace(/\s+/g, '');
  const binary = atob(normalized);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) {
    bytes[i] = binary.charCodeAt(i);
  }
  return bytes;
}

export async function sha256Hex(input: string): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', textEncoder.encode(input));
  return toHex(new Uint8Array(digest));
}

export type ProtectedCodeDomain = 'recovery-v1' | 'device-invite-v1' | 'admin-pair-v1';

export const MIN_CODE_PEPPER_BYTES = 16;

export function assertValidCodePepper(pepper: string | undefined): string {
  const trimmed = pepper?.trim() ?? '';
  if (trimmed.length < MIN_CODE_PEPPER_BYTES) {
    throw new Error('MISSING_OR_WEAK_CODE_HASH_PEPPER');
  }
  return trimmed;
}

export async function hmacSha256Hex(secret: string, message: string): Promise<string> {
  const key = await crypto.subtle.importKey(
    'raw',
    textEncoder.encode(secret),
    { name: 'HMAC', hash: 'SHA-256' },
    false,
    ['sign']
  );
  const sig = await crypto.subtle.sign('HMAC', key, textEncoder.encode(message));
  return toHex(new Uint8Array(sig));
}

/**
 * Computes a server-peppered domain-separated HMAC-SHA-256 digest for recovery codes
 * and invitations. Fails closed if the server pepper secret is missing or too short.
 */
export async function hmacSha256CodeDigest(
  pepper: string | undefined,
  domain: ProtectedCodeDomain,
  normalizedCode: string
): Promise<string> {
  const validPepper = assertValidCodePepper(pepper);
  const hex = await hmacSha256Hex(validPepper, `${domain}:${normalizedCode}`);
  return `hmac-v1:${hex}`;
}

export function canonicalChallengeMessage(params: {
  challengeId: string;
  accountId: string;
  adminDeviceId: string;
  operation: string;
  nonce: string;
  expiresAtMs: number;
}): string {
  return `eachother:auth:v1|${params.challengeId}|${params.accountId}|${params.adminDeviceId}|${params.operation}|${params.nonce}|${params.expiresAtMs}`;
}

/**
 * Converts an ASN.1 DER-encoded ECDSA P-256 signature (produced by Android's "SHA256withECDSA")
 * into IEEE P1363 raw 64-byte (r || s) format expected by Web Crypto subtle.verify.
 */
export function derEcdsaToP1363(der: Uint8Array): Uint8Array | null {
  if (der.length < 8 || der[0] !== 0x30) return null;

  let offset = 1;
  let seqLen = der[offset++]!;
  if ((seqLen & 0x80) !== 0) {
    const lenBytes = seqLen & 0x7f;
    if (lenBytes !== 1 || offset >= der.length) return null;
    seqLen = der[offset++]!;
  }
  if (offset + seqLen !== der.length) return null;

  const readInt32 = (): Uint8Array | null => {
    if (offset + 2 > der.length || der[offset++] !== 0x02) return null;
    const len = der[offset++]!;
    if (len === 0 || offset + len > der.length) return null;
    const raw = der.slice(offset, offset + len);
    offset += len;

    // DER positive integer may have a single leading 0x00 if MSB is set
    let start = 0;
    while (start < raw.length - 1 && raw[start] === 0x00) {
      // Reject non-minimal multi-byte zero padding in DER
      if (raw[start + 1]! < 0x80) return null;
      start++;
    }
    const stripped = raw.slice(start);
    if (stripped.length > 32) return null;

    const out = new Uint8Array(32);
    out.set(stripped, 32 - stripped.length);
    return out;
  };

  const r = readInt32();
  const s = readInt32();
  if (!r || !s || offset !== der.length) return null;

  const p1363 = new Uint8Array(64);
  p1363.set(r, 0);
  p1363.set(s, 32);
  return p1363;
}

export function normalizeEcdsaSignature(sigBytes: Uint8Array): Uint8Array | null {
  if (sigBytes.length === 64) {
    return sigBytes;
  }
  return derEcdsaToP1363(sigBytes);
}

export async function importP256SpkiPublicKey(spkiBase64: string): Promise<CryptoKey | null> {
  try {
    const spkiBytes = fromBase64(spkiBase64);
    if (spkiBytes.length < 64 || spkiBytes.length > 256) return null;
    return await crypto.subtle.importKey(
      'spki',
      spkiBytes,
      { name: 'ECDSA', namedCurve: 'P-256' },
      false,
      ['verify']
    );
  } catch {
    return null;
  }
}

export async function verifyEcdsaP256Signature(
  spkiBase64: string,
  message: string,
  signatureBase64: string
): Promise<boolean> {
  const key = await importP256SpkiPublicKey(spkiBase64);
  if (!key) return false;

  let rawSig: Uint8Array;
  try {
    rawSig = fromBase64(signatureBase64);
  } catch {
    return false;
  }

  const p1363 = normalizeEcdsaSignature(rawSig);
  if (!p1363) return false;

  try {
    return await crypto.subtle.verify(
      { name: 'ECDSA', hash: 'SHA-256' },
      key,
      p1363,
      textEncoder.encode(message)
    );
  } catch {
    return false;
  }
}

/**
 * Generates a 12-character cryptographically random Crockford Base32 invitation code (~60 bits entropy).
 */
export function generateInviteCode(length: number = 12): string {
  const chars: string[] = [];
  while (chars.length < length) {
    const buf = randomBytes(length * 2);
    for (let i = 0; i < buf.length && chars.length < length; i++) {
      const byte = buf[i]!;
      if (byte < 224) {
        chars.push(CROCKFORD_ALPHABET[byte % 32]!);
      }
    }
  }
  return chars.join('');
}

export function normalizeCode(input: string): string {
  return input
    .toUpperCase()
    .replace(/[\s-]/g, '')
    .replace(/O/g, '0')
    .replace(/[IL]/g, '1');
}

export function generateRecoveryCodes(count: number = 10): string[] {
  const codes: string[] = [];
  for (let i = 0; i < count; i++) {
    const raw = generateInviteCode(12);
    codes.push(`${raw.slice(0, 4)}-${raw.slice(4, 8)}-${raw.slice(8, 12)}`);
  }
  return codes;
}

export interface JwtPayload {
  sub: string;
  role: 'ADMIN' | 'DEVICE';
  groupId: string;
  accountId: string | null;
  adminDeviceId: string | null;
  deviceId: string | null;
  sessionId: string;
  iat: number;
  exp: number;
}

export async function signJwt(payload: JwtPayload, secret: string): Promise<string> {
  const header = toBase64Url(textEncoder.encode(JSON.stringify({ alg: 'HS256', typ: 'JWT' })));
  const body = toBase64Url(textEncoder.encode(JSON.stringify(payload)));
  const signingInput = `${header}.${body}`;

  const key = await crypto.subtle.importKey(
    'raw',
    textEncoder.encode(secret),
    { name: 'HMAC', hash: 'SHA-256' },
    false,
    ['sign']
  );
  const sig = await crypto.subtle.sign('HMAC', key, textEncoder.encode(signingInput));
  return `${signingInput}.${toBase64Url(new Uint8Array(sig))}`;
}

export async function verifyJwt(
  token: string,
  secret: string,
  nowSec: number = Math.floor(Date.now() / 1000)
): Promise<JwtPayload | null> {
  const parts = token.split('.');
  if (parts.length !== 3) return null;
  const [headerB64, bodyB64, sigB64] = parts as [string, string, string];

  let headerJson: { alg?: string };
  try {
    headerJson = JSON.parse(textDecoder.decode(fromBase64Url(headerB64)));
  } catch {
    return null;
  }
  if (headerJson.alg !== 'HS256') return null;

  const key = await crypto.subtle.importKey(
    'raw',
    textEncoder.encode(secret),
    { name: 'HMAC', hash: 'SHA-256' },
    false,
    ['verify']
  );
  let sigBytes: Uint8Array;
  try {
    sigBytes = fromBase64Url(sigB64);
  } catch {
    return null;
  }

  const validSig = await crypto.subtle.verify(
    'HMAC',
    key,
    sigBytes,
    textEncoder.encode(`${headerB64}.${bodyB64}`)
  );
  if (!validSig) return null;

  let payload: JwtPayload;
  try {
    payload = JSON.parse(textDecoder.decode(fromBase64Url(bodyB64))) as JwtPayload;
  } catch {
    return null;
  }

  if (
    typeof payload.exp !== 'number' ||
    typeof payload.iat !== 'number' ||
    payload.exp <= nowSec ||
    !payload.sub ||
    !payload.groupId ||
    !payload.sessionId
  ) {
    return null;
  }
  return payload;
}
