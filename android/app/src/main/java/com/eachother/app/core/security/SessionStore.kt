package com.eachother.app.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.eachother.app.domain.model.AuthSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface SessionStore {
    val sessionFlow: Flow<AuthSession>
    suspend fun getSession(): AuthSession
    suspend fun saveSession(session: AuthSession)
    suspend fun clearSession()
}

private val Context.authDataStore by preferencesDataStore(name = "each_other_secure_auth")

class KeystoreSessionStore(
    private val context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true }
) : SessionStore {

    private val sessionKey = stringPreferencesKey("encrypted_session_v1")

    override val sessionFlow: Flow<AuthSession> = context.authDataStore.data.map { prefs ->
        val raw = prefs[sessionKey] ?: return@map AuthSession()
        decryptSession(raw) ?: AuthSession()
    }

    override suspend fun getSession(): AuthSession = sessionFlow.first()

    override suspend fun saveSession(session: AuthSession) {
        val serialized = json.encodeToString(session)
        val encrypted = encryptString(serialized)
        context.authDataStore.edit { prefs ->
            prefs[sessionKey] = encrypted
        }
    }

    override suspend fun clearSession() {
        context.authDataStore.edit { prefs ->
            prefs.remove(sessionKey)
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private fun encryptString(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val ivB64 = Base64.encodeToString(iv, Base64.NO_WRAP)
        val ctB64 = Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        return "$ivB64:$ctB64"
    }

    private fun decryptSession(encoded: String): AuthSession? {
        return try {
            val parts = encoded.split(":")
            if (parts.size != 2) return null
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            val plaintext = cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
            json.decodeFromString<AuthSession>(plaintext)
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "each_other_session_aes_key_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
