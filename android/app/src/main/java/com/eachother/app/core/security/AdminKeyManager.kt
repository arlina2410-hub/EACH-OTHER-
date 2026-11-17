package com.eachother.app.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * Pure JVM helper for converting Android Keystore's ASN.1 DER ECDSA P-256 signature
 * ("SHA256withECDSA") into fixed 64-byte IEEE P1363 (r || s) format expected by Web Crypto.
 */
object EcdsaSignatureCodec {
    fun derToP1363(der: ByteArray): ByteArray? {
        if (der.size < 8 || der[0] != 0x30.toByte()) return null

        var offset = 1
        var seqLen = der[offset++].toInt() and 0xFF
        if ((seqLen and 0x80) != 0) {
            val lenBytes = seqLen and 0x7F
            if (lenBytes != 1 || offset >= der.size) return null
            seqLen = der[offset++].toInt() and 0xFF
        }
        if (offset + seqLen != der.size) return null

        fun readInt32(): ByteArray? {
            if (offset + 2 > der.size || der[offset++] != 0x02.toByte()) return null
            val len = der[offset++].toInt() and 0xFF
            if (len == 0 || offset + len > der.size) return null
            val raw = der.copyOfRange(offset, offset + len)
            offset += len

            var start = 0
            while (start < raw.size - 1 && raw[start] == 0x00.toByte()) {
                // Non-minimal multi-byte zero prefix in DER is invalid
                if ((raw[start + 1].toInt() and 0x80) == 0) return null
                start++
            }
            val stripped = raw.copyOfRange(start, raw.size)
            if (stripped.size > 32) return null

            val out = ByteArray(32)
            System.arraycopy(stripped, 0, out, 32 - stripped.size, stripped.size)
            return out
        }

        val r = readInt32() ?: return null
        val s = readInt32() ?: return null
        if (offset != der.size) return null

        val p1363 = ByteArray(64)
        System.arraycopy(r, 0, p1363, 0, 32)
        System.arraycopy(s, 0, p1363, 32, 32)
        return p1363
    }
}

interface AdminKeySigner {
    fun ensureKeyPair(keyId: String): String
    fun rotateKeyPair(keyId: String): String
    fun getPublicKeySpki(keyId: String): String?
    fun signCanonicalChallenge(keyId: String, canonicalPayload: String): String
}

/**
 * Hardware-backed Android Keystore ECDSA P-256 (secp256r1) key manager.
 * Private keys never leave Android Keystore; only X.509 SPKI public keys are exported.
 */
class AndroidKeystoreAdminKeyManager : AdminKeySigner {

    private fun fullAlias(keyId: String): String = "$ALIAS_PREFIX$keyId"

    override fun ensureKeyPair(keyId: String): String {
        val existing = getPublicKeySpki(keyId)
        if (existing != null) return existing
        return generateKeyPairInternal(fullAlias(keyId))
    }

    override fun rotateKeyPair(keyId: String): String {
        val alias = fullAlias(keyId)
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (ks.containsAlias(alias)) {
            ks.deleteEntry(alias)
        }
        return generateKeyPairInternal(alias)
    }

    override fun getPublicKeySpki(keyId: String): String? {
        val alias = fullAlias(keyId)
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val entry = ks.getEntry(alias, null) as? KeyStore.PrivateKeyEntry ?: return null
        val spkiBytes = entry.certificate?.publicKey?.encoded ?: return null
        return Base64.encodeToString(spkiBytes, Base64.NO_WRAP)
    }

    override fun signCanonicalChallenge(keyId: String, canonicalPayload: String): String {
        val alias = fullAlias(keyId)
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val privateKey = ks.getKey(alias, null) as? PrivateKey
            ?: error("Administrator Keystore key '$keyId' not found on this device. Recover or pair this device.")

        val signature = Signature.getInstance("SHA256withECDSA").apply {
            initSign(privateKey)
            update(canonicalPayload.toByteArray(Charsets.UTF_8))
        }.sign()

        val p1363 = EcdsaSignatureCodec.derToP1363(signature)
            ?: error("Failed to convert Keystore ECDSA signature to P1363 format.")

        return Base64.encodeToString(p1363, Base64.NO_WRAP)
    }

    private fun generateKeyPairInternal(alias: String): String {
        val generator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            ANDROID_KEYSTORE
        )
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_SIGN
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .build()
        generator.initialize(spec)
        val keyPair = generator.generateKeyPair()
        return Base64.encodeToString(keyPair.public.encoded, Base64.NO_WRAP)
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val ALIAS_PREFIX = "each_other_admin_ecdsa_"
        const val ACTIVE_ADMIN_KEY_ID = "primary_v1"
    }
}
