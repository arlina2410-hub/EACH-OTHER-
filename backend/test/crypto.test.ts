import { describe, expect, it } from 'vitest';
import {
  canonicalChallengeMessage,
  derEcdsaToP1363,
  generateInviteCode,
  generateRecoveryCodes,
  normalizeCode,
  signJwt,
  verifyEcdsaP256Signature,
  verifyJwt,
} from '../src/crypto/crypto';

const TEST_JWT_SECRET = 'test-jwt-secret-at-least-32-bytes-long-123';
const textEncoder = new TextEncoder();

function toBase64(bytes: Uint8Array): string {
  let binary = '';
  for (let i = 0; i < bytes.length; i++) {
    binary += String.fromCharCode(bytes[i]!);
  }
  return btoa(binary);
}

function encodeDerInt(raw32: Uint8Array): Uint8Array {
  let start = 0;
  while (start < raw32.length - 1 && raw32[start] === 0x00) {
    start++;
  }
  const trimmed = raw32.slice(start);
  const needsZeroPrefix = (trimmed[0]! & 0x80) !== 0;
  const out = new Uint8Array(2 + trimmed.length + (needsZeroPrefix ? 1 : 0));
  out[0] = 0x02;
  out[1] = trimmed.length + (needsZeroPrefix ? 1 : 0);
  if (needsZeroPrefix) {
    out[2] = 0x00;
    out.set(trimmed, 3);
  } else {
    out.set(trimmed, 2);
  }
  return out;
}

function p1363ToDer(p1363: Uint8Array): Uint8Array {
  const rDer = encodeDerInt(p1363.slice(0, 32));
  const sDer = encodeDerInt(p1363.slice(32, 64));
  const totalLen = rDer.length + sDer.length;
  const der = new Uint8Array(2 + totalLen);
  der[0] = 0x30;
  der[1] = totalLen;
  der.set(rDer, 2);
  der.set(sDer, 2 + rDer.length);
  return der;
}

describe('ECDSA P-256 cryptography, DER interop & benchmark in workerd', () => {
  it('verifies both ASN.1 DER (Android SHA256withECDSA) and P1363 ECDSA P-256 signatures & rejects tampering', async () => {
    const keyPair = (await crypto.subtle.generateKey(
      { name: 'ECDSA', namedCurve: 'P-256' },
      true,
      ['sign', 'verify']
    )) as CryptoKeyPair;

    const spkiBytes = new Uint8Array((await crypto.subtle.exportKey('spki', keyPair.publicKey)) as ArrayBuffer);
    const spkiB64 = toBase64(spkiBytes);

    const otherPair = (await crypto.subtle.generateKey(
      { name: 'ECDSA', namedCurve: 'P-256' },
      true,
      ['sign', 'verify']
    )) as CryptoKeyPair;
    const otherSpkiB64 = toBase64(
      new Uint8Array((await crypto.subtle.exportKey('spki', otherPair.publicKey)) as ArrayBuffer)
    );

    const payload = canonicalChallengeMessage({
      challengeId: '11111111-1111-4111-8111-111111111111',
      accountId: '22222222-2222-4222-8222-222222222222',
      adminDeviceId: '33333333-3333-4333-8333-333333333333',
      operation: 'LOGIN',
      nonce: 'abc123nonce',
      expiresAtMs: 1_800_000_000_000,
    });

    // Test 12 signatures to naturally hit high-bit 0x00 prefixes and stripped leading zeros in DER
    const derVerifyLatenciesMs: number[] = [];
    for (let i = 0; i < 12; i++) {
      const msg = `${payload}|vec=${i}`;
      const p1363Sig = new Uint8Array(
        await crypto.subtle.sign(
          { name: 'ECDSA', hash: 'SHA-256' },
          keyPair.privateKey,
          textEncoder.encode(msg)
        )
      );
      const derSig = p1363ToDer(p1363Sig);
      expect(derEcdsaToP1363(derSig)).toEqual(p1363Sig);

      const t0 = performance.now();
      const derValid = await verifyEcdsaP256Signature(spkiB64, msg, toBase64(derSig));
      const dt = performance.now() - t0;
      derVerifyLatenciesMs.push(dt);

      expect(derValid).toBe(true);
      expect(await verifyEcdsaP256Signature(spkiB64, msg, toBase64(p1363Sig))).toBe(true);
      expect(await verifyEcdsaP256Signature(spkiB64, `${msg}!`, toBase64(derSig))).toBe(false);
      expect(await verifyEcdsaP256Signature(otherSpkiB64, msg, toBase64(derSig))).toBe(false);
    }

    const avgMs = derVerifyLatenciesMs.reduce((a, b) => a + b, 0) / derVerifyLatenciesMs.length;
    const minMs = Math.min(...derVerifyLatenciesMs);
    const maxMs = Math.max(...derVerifyLatenciesMs);
    console.info(
      `[BENCHMARK] workerd ECDSA P-256 SPKI import + DER verify (n=12): avg=${avgMs.toFixed(3)}ms, min=${minMs.toFixed(3)}ms, max=${maxMs.toFixed(3)}ms`
    );

    // Reject malformed / non-minimal ASN.1 DER signatures
    expect(derEcdsaToP1363(new Uint8Array([0x30, 0x02, 0x02, 0x00]))).toBeNull();
    expect(derEcdsaToP1363(new Uint8Array([0x31, 0x06, 0x02, 0x01, 0x01, 0x02, 0x01, 0x01]))).toBeNull();
  });

  it('generates 12-char Crockford Base32 invite codes and 10 high-entropy recovery codes', () => {
    const invite = generateInviteCode(12);
    expect(invite).toHaveLength(12);
    expect(invite).toMatch(/^[0-9A-HJKMNP-TV-Z]{12}$/);
    expect(normalizeCode(` ${invite.slice(0, 4)}-${invite.slice(4, 8)}-${invite.slice(8, 12)} `)).toBe(invite);

    const recovery = generateRecoveryCodes(10);
    expect(recovery).toHaveLength(10);
    for (const c of recovery) {
      expect(c).toMatch(/^[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}$/);
    }
  });

  it('signs and verifies HS256 JWTs with adminDeviceId claim and rejects expired or tampered tokens', async () => {
    const now = 1_700_000_000;
    const token = await signJwt(
      {
        sub: 'acc-1',
        role: 'ADMIN',
        groupId: 'grp-1',
        accountId: 'acc-1',
        adminDeviceId: 'ad-1',
        deviceId: null,
        sessionId: 'sess-1',
        iat: now,
        exp: now + 900,
      },
      TEST_JWT_SECRET
    );

    const verified = await verifyJwt(token, TEST_JWT_SECRET, now + 100);
    expect(verified?.accountId).toBe('acc-1');
    expect(verified?.adminDeviceId).toBe('ad-1');
    expect(verified?.role).toBe('ADMIN');

    expect(await verifyJwt(token, TEST_JWT_SECRET, now + 901)).toBeNull();
    expect(await verifyJwt(`${token}tamper`, TEST_JWT_SECRET, now + 100)).toBeNull();
  });
});
