package com.eachother.app

import com.eachother.app.core.security.EcdsaSignatureCodec
import com.eachother.app.core.security.KeySecurityLevel
import com.eachother.app.core.security.KeySecurityLevelMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * Verifies real Java/Android "SHA256withECDSA" ASN.1 DER signatures convert cleanly
 * into fixed 64-byte IEEE P1363 format compatible with Cloudflare Workers Web Crypto
 * and round-trip back to verifiable DER coordinates.
 */
class AdminKeyInteroperabilityTest {

    private fun encodeDerInteger(raw32: ByteArray): ByteArray {
        var start = 0
        while (start < raw32.size - 1 && raw32[start] == 0.toByte()) {
            start++
        }
        val trimmed = raw32.copyOfRange(start, raw32.size)
        val needsZero = (trimmed[0].toInt() and 0x80) != 0
        val out = ByteArray(2 + trimmed.size + if (needsZero) 1 else 0)
        out[0] = 0x02
        out[1] = (trimmed.size + if (needsZero) 1 else 0).toByte()
        if (needsZero) {
            out[2] = 0x00
            System.arraycopy(trimmed, 0, out, 3, trimmed.size)
        } else {
            System.arraycopy(trimmed, 0, out, 2, trimmed.size)
        }
        return out
    }

    private fun p1363ToDer(p1363: ByteArray): ByteArray {
        val rDer = encodeDerInteger(p1363.copyOfRange(0, 32))
        val sDer = encodeDerInteger(p1363.copyOfRange(32, 64))
        val total = rDer.size + sDer.size
        val der = ByteArray(2 + total)
        der[0] = 0x30
        der[1] = total.toByte()
        System.arraycopy(rDer, 0, der, 2, rDer.size)
        System.arraycopy(sDer, 0, der, 2 + rDer.size, sDer.size)
        return der
    }

    @Test
    fun `converts real secp256r1 SHA256withECDSA DER signatures into 64-byte P1363 and round-trips`() {
        val kpg = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }
        val keyPair = kpg.generateKeyPair()

        // Run 50 signatures to hit both 31-byte stripped and 33-byte (0x00 prefixed) DER integers
        for (i in 0 until 50) {
            val payload = "eachother:auth:v1|chal-$i|acc-1|ad-1|LOGIN|nonce-$i|1800000000000"
            val payloadBytes = payload.toByteArray(Charsets.UTF_8)
            val derSig = Signature.getInstance("SHA256withECDSA").run {
                initSign(keyPair.private)
                update(payloadBytes)
                sign()
            }

            val p1363 = EcdsaSignatureCodec.derToP1363(derSig)
            assertNotNull("Signature #$i failed DER->P1363 conversion", p1363)
            assertEquals(64, p1363!!.size)

            val roundTripDer = p1363ToDer(p1363)
            val verified = Signature.getInstance("SHA256withECDSA").run {
                initVerify(keyPair.public)
                update(payloadBytes)
                verify(roundTripDer)
            }
            assertTrue("Round-trip verification failed for signature #$i", verified)
        }
    }

    @Test
    fun `rejects malformed or non-minimal DER signatures`() {
        assertNull(EcdsaSignatureCodec.derToP1363(byteArrayOf()))
        assertNull(EcdsaSignatureCodec.derToP1363(byteArrayOf(0x31, 0x06, 0x02, 0x01, 0x01, 0x02, 0x01, 0x01)))
        val nonMinimal = byteArrayOf(
            0x30, 0x07,
            0x02, 0x02, 0x00, 0x01,
            0x02, 0x01, 0x01
        )
        assertNull(EcdsaSignatureCodec.derToP1363(nonMinimal))
    }

    @Test
    fun `maps KeyInfo security levels accurately without false hardware-backed claims`() {
        val strongBox = KeySecurityLevelMapper.mapKeyInfoSecurityLevel(
            keyId = "k1",
            exists = true,
            sdkInt = 34,
            securityLevelConst = KeySecurityLevelMapper.SECURITY_LEVEL_STRONGBOX,
            isInsideSecureHardwareFlag = null
        )
        assertEquals(KeySecurityLevel.STRONGBOX_HARDWARE, strongBox.securityLevel)
        assertTrue(strongBox.isInsideSecureHardware)

        val tee = KeySecurityLevelMapper.mapKeyInfoSecurityLevel(
            keyId = "k1",
            exists = true,
            sdkInt = 34,
            securityLevelConst = KeySecurityLevelMapper.SECURITY_LEVEL_TRUSTED_ENVIRONMENT,
            isInsideSecureHardwareFlag = null
        )
        assertEquals(KeySecurityLevel.TEE_HARDWARE, tee.securityLevel)
        assertTrue(tee.isInsideSecureHardware)

        val software = KeySecurityLevelMapper.mapKeyInfoSecurityLevel(
            keyId = "k1",
            exists = true,
            sdkInt = 34,
            securityLevelConst = KeySecurityLevelMapper.SECURITY_LEVEL_SOFTWARE,
            isInsideSecureHardwareFlag = null
        )
        assertEquals(KeySecurityLevel.SOFTWARE, software.securityLevel)
        assertFalse(software.isInsideSecureHardware)

        val legacyHardware = KeySecurityLevelMapper.mapKeyInfoSecurityLevel(
            keyId = "k1",
            exists = true,
            sdkInt = 29,
            securityLevelConst = null,
            isInsideSecureHardwareFlag = true
        )
        assertEquals(KeySecurityLevel.TEE_HARDWARE, legacyHardware.securityLevel)
        assertTrue(legacyHardware.isInsideSecureHardware)

        val legacySoftware = KeySecurityLevelMapper.mapKeyInfoSecurityLevel(
            keyId = "k1",
            exists = true,
            sdkInt = 29,
            securityLevelConst = null,
            isInsideSecureHardwareFlag = false
        )
        assertEquals(KeySecurityLevel.SOFTWARE, legacySoftware.securityLevel)
        assertFalse(legacySoftware.isInsideSecureHardware)

        val unknownOrMissing = KeySecurityLevelMapper.mapKeyInfoSecurityLevel(
            keyId = "missing",
            exists = false,
            sdkInt = 34,
            securityLevelConst = null,
            isInsideSecureHardwareFlag = null
        )
        assertEquals(KeySecurityLevel.UNKNOWN, unknownOrMissing.securityLevel)
        assertFalse(unknownOrMissing.exists)
        assertFalse(unknownOrMissing.isInsideSecureHardware)
    }
}
