package com.eachother.app.core.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

enum class KeySecurityLevel {
    STRONGBOX_HARDWARE,
    TEE_HARDWARE,
    SOFTWARE,
    UNKNOWN
}

data class KeyHardwareStatus(
    val keyId: String,
    val exists: Boolean,
    val securityLevel: KeySecurityLevel,
    val isInsideSecureHardware: Boolean,
    val summary: String
)

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

/**
 * Pure JVM helper translating Android KeyInfo fields into explicit, honest hardware backing status.
 * Uses KeyProperties.SECURITY_LEVEL_* numeric constants (API 31+):
 *   2 = SECURITY_LEVEL_STRONGBOX
 *   1 = SECURITY_LEVEL_TRUSTED_ENVIRONMENT
 *   0 = SECURITY_LEVEL_SOFTWARE
 */
object KeySecurityLevelMapper {
    const val SDK_INT_API_31 = 31
    const val SECURITY_LEVEL_SOFTWARE = 0
    const val SECURITY_LEVEL_TRUSTED_ENVIRONMENT = 1
    const val SECURITY_LEVEL_STRONGBOX = 2

    fun mapKeyInfoSecurityLevel(
        keyId: String,
        exists: Boolean,
        sdkInt: Int,
        securityLevelConst: Int?,
        isInsideSecureHardwareFlag: Boolean?
    ): KeyHardwareStatus {
        if (!exists) {
            return KeyHardwareStatus(
                keyId = keyId,
                exists = false,
                securityLevel = KeySecurityLevel.UNKNOWN,
                isInsideSecureHardware = false,
                summary = "No local Keystore key present for '$keyId'."
            )
        }

        if (sdkInt >= SDK_INT_API_31 && securityLevelConst != null) {
            return when (securityLevelConst) {
                SECURITY_LEVEL_STRONGBOX -> KeyHardwareStatus(
                    keyId = keyId,
                    exists = true,
                    securityLevel = KeySecurityLevel.STRONGBOX_HARDWARE,
                    isInsideSecureHardware = true,
                    summary = "StrongBox hardware-backed ECDSA P-256 key"
                )
                SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> KeyHardwareStatus(
                    keyId = keyId,
                    exists = true,
                    securityLevel = KeySecurityLevel.TEE_HARDWARE,
                    isInsideSecureHardware = true,
                    summary = "Hardware-backed (TEE) ECDSA P-256 key"
                )
                SECURITY_LEVEL_SOFTWARE -> KeyHardwareStatus(
                    keyId = keyId,
                    exists = true,
                    securityLevel = KeySecurityLevel.SOFTWARE,
                    isInsideSecureHardware = false,
                    summary = "Software-backed Android Keystore key (not hardware-isolated)"
                )
                else -> KeyHardwareStatus(
                    keyId = keyId,
                    exists = true,
                    securityLevel = KeySecurityLevel.UNKNOWN,
                    isInsideSecureHardware = false,
                    summary = "Android Keystore key security level could not be verified"
                )
            }
        }

        return when (isInsideSecureHardwareFlag) {
            true -> KeyHardwareStatus(
                keyId = keyId,
                exists = true,
                securityLevel = KeySecurityLevel.TEE_HARDWARE,
                isInsideSecureHardware = true,
                summary = "Hardware-backed secure environment ECDSA P-256 key"
            )
            false -> KeyHardwareStatus(
                keyId = keyId,
                exists = true,
                securityLevel = KeySecurityLevel.SOFTWARE,
                isInsideSecureHardware = false,
                summary = "Software-backed Android Keystore key (not hardware-isolated)"
            )
            null -> KeyHardwareStatus(
                keyId = keyId,
                exists = true,
                securityLevel = KeySecurityLevel.UNKNOWN,
                isInsideSecureHardware = false,
                summary = "Android Keystore key security level could not be verified"
            )
        }
    }
}

interface AdminKeySigner {
    fun ensureKeyPair(keyId: String): String
    fun generateCandidateKeyPair(keyId: String): String
    fun deleteKeyPair(keyId: String)
    fun getPublicKeySpki(keyId: String): String?
    fun signCanonicalChallenge(keyId: String, canonicalPayload: String): String
    fun inspectKeyHardwareStatus(keyId: String): KeyHardwareStatus
}

/**
 * Android Keystore ECDSA P-256 (secp256r1) key manager.
 * Private keys never leave Android Keystore; only X.509 SPKI public keys are exported.
 * Supports non-destructive candidate key generation so failed recovery or pairing never destroys the active key.
 */
class AndroidKeystoreAdminKeyManager : AdminKeySigner {

    private fun fullAlias(keyId: String): String = "$ALIAS_PREFIX$keyId"

    override fun ensureKeyPair(keyId: String): String {
        val existing = getPublicKeySpki(keyId)
        if (existing != null) return existing
        return generateKeyPairInternal(fullAlias(keyId))
    }

    override fun generateCandidateKeyPair(keyId: String): String {
        val alias = fullAlias(keyId)
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (ks.containsAlias(alias)) {
            ks.deleteEntry(alias)
        }
        return generateKeyPairInternal(alias)
    }

    override fun deleteKeyPair(keyId: String) {
        try {
            val alias = fullAlias(keyId)
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (ks.containsAlias(alias)) {
                ks.deleteEntry(alias)
            }
        } catch (_: Exception) {
            // Best-effort cleanup of unused candidate or retired keys
        }
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

    override fun inspectKeyHardwareStatus(keyId: String): KeyHardwareStatus {
        return try {
            val alias = fullAlias(keyId)
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val privateKey = ks.getKey(alias, null) as? PrivateKey
                ?: return KeySecurityLevelMapper.mapKeyInfoSecurityLevel(
                    keyId = keyId,
                    exists = false,
                    sdkInt = Build.VERSION.SDK_INT,
                    securityLevelConst = null,
                    isInsideSecureHardwareFlag = null
                )

            val factory = KeyFactory.getInstance(privateKey.algorithm, ANDROID_KEYSTORE)
            val keyInfo = factory.getKeySpec(privateKey, KeyInfo::class.java)
            val levelConst = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                keyInfo.securityLevel
            } else {
                null
            }
            @Suppress("DEPRECATION")
            val insideHardware = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                keyInfo.isInsideSecureHardware
            } else {
                null
            }

            KeySecurityLevelMapper.mapKeyInfoSecurityLevel(
                keyId = keyId,
                exists = true,
                sdkInt = Build.VERSION.SDK_INT,
                securityLevelConst = levelConst,
                isInsideSecureHardwareFlag = insideHardware
            )
        } catch (_: Exception) {
            KeySecurityLevelMapper.mapKeyInfoSecurityLevel(
                keyId = keyId,
                exists = true,
                sdkInt = Build.VERSION.SDK_INT,
                securityLevelConst = null,
                isInsideSecureHardwareFlag = null
            )
        }
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
        const val DEFAULT_LEGACY_KEY_ID = "primary_v1"
    }
}
