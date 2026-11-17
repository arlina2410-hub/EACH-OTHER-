package com.eachother.app

import com.eachother.app.core.network.NetworkModule
import com.eachother.app.core.security.AdminKeySigner
import com.eachother.app.core.security.SessionStore
import com.eachother.app.core.security.SharingSettingsStore
import com.eachother.app.data.repository.AuthRepository
import com.eachother.app.data.repository.RepoResult
import com.eachother.app.domain.model.AppRole
import com.eachother.app.domain.model.AuthSession
import com.eachother.app.domain.model.PermissionSnapshot
import com.eachother.app.domain.model.SharingCategorySettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class InMemorySessionStore : SessionStore {
    private val state = MutableStateFlow(AuthSession())
    override val sessionFlow: Flow<AuthSession> = state
    override suspend fun getSession(): AuthSession = state.value
    override suspend fun saveSession(session: AuthSession) {
        state.value = session
    }
    override suspend fun clearSession() {
        state.value = AuthSession()
    }
}

private class InMemorySharingSettingsStore : SharingSettingsStore {
    private val state = MutableStateFlow(SharingCategorySettings())
    override val settingsFlow: Flow<SharingCategorySettings> = state
    override suspend fun getSettings(): SharingCategorySettings = state.value
    override suspend fun saveSettings(settings: SharingCategorySettings) {
        state.value = settings
    }
    override suspend fun clear() {
        state.value = SharingCategorySettings()
    }
}

private class FakeAdminKeySigner : AdminKeySigner {
    var lastSignedPayload: String? = null
    var rotateCount: Int = 0
    var throwMissingKey: Boolean = false

    override fun ensureKeyPair(keyId: String): String =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEFakeSpkiKeyForJvmUnitTest1234567890"

    override fun rotateKeyPair(keyId: String): String {
        rotateCount++
        return ensureKeyPair(keyId)
    }

    override fun getPublicKeySpki(keyId: String): String = ensureKeyPair(keyId)

    override fun signCanonicalChallenge(keyId: String, canonicalPayload: String): String {
        if (throwMissingKey) {
            error("Administrator Keystore key '$keyId' not found on this device.")
        }
        lastSignedPayload = canonicalPayload
        return "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=="
    }
}

class AuthRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var store: InMemorySessionStore
    private lateinit var sharingStore: InMemorySharingSettingsStore
    private lateinit var signer: FakeAdminKeySigner
    private lateinit var repo: AuthRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        store = InMemorySessionStore()
        sharingStore = InMemorySharingSettingsStore()
        signer = FakeAdminKeySigner()
        val api = NetworkModule.createApi(server.url("/").toString(), store)
        repo = AuthRepository(api, store, sharingStore, signer, "0.2.0")
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `registerAdmin saves ECDSA adminDeviceId and preserves localAdminIdentity across signOutLocally`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setBody(
                    """
                    {
                      "accountId":"acc-1",
                      "adminDeviceId":"ad-1",
                      "groupId":"grp-1",
                      "groupName":"Family",
                      "accessToken":"jwt-1",
                      "refreshToken":"ref-1",
                      "expiresInSec":900,
                      "recoveryCodes":["A1B2-C3D4-E5F6","G7H8-J9K0-M1N2","1111-2222-3333","4444-5555-6666","7777-8888-9999","AAAA-BBBB-CCCC","DDDD-EEEE-FFFF","GGGG-HHHH-JJJJ","KKKK-MMMM-NNNN","PPPP-QQQQ-RRRR"]
                    }
                    """.trimIndent()
                )
        )

        val result = repo.registerAdmin("owner@example.com", "Family", "Pixel 9")
        assertTrue(result is RepoResult.Success)
        assertEquals(10, (result as RepoResult.Success).value.size)

        val saved = store.getSession()
        assertEquals(AppRole.ADMIN, saved.role)
        assertEquals("ad-1", saved.adminDeviceId)
        assertEquals("jwt-1", saved.accessToken)

        repo.signOutLocally()
        val afterSignOut = store.getSession()
        assertFalse(afterSignOut.isAuthenticated)
        assertEquals("ad-1", afterSignOut.localAdminIdentity?.adminDeviceId)
        assertEquals("owner@example.com", afterSignOut.localAdminIdentity?.email)
    }

    @Test
    fun `signInAdminWithKeystore maps missing keystore key to KEYSTORE_ERROR and succeeds with valid key`() = runTest {
        val challengeBody = """
            {
              "challengeId":"chal-1",
              "canonicalPayload":"eachother:auth:v1|chal-1|acc-1|ad-1|LOGIN|nonce|1800000000000",
              "expiresAtMs":1800000000000
            }
        """.trimIndent()

        signer.throwMissingKey = true
        server.enqueue(MockResponse().setResponseCode(200).setBody(challengeBody))
        val missingRes = repo.signInAdminWithKeystore("owner@example.com", "ad-1")
        assertTrue(missingRes is RepoResult.Failure)
        assertEquals("KEYSTORE_ERROR", (missingRes as RepoResult.Failure).code)

        signer.throwMissingKey = false
        server.enqueue(MockResponse().setResponseCode(200).setBody(challengeBody))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "accountId":"acc-1",
                      "adminDeviceId":"ad-1",
                      "groupId":"grp-1",
                      "groupName":"Family",
                      "accessToken":"jwt-2",
                      "refreshToken":"ref-2",
                      "expiresInSec":900
                    }
                    """.trimIndent()
                )
        )

        val result = repo.signInAdminWithKeystore("owner@example.com", "ad-1")
        assertTrue(result is RepoResult.Success)
        assertEquals(
            "eachother:auth:v1|chal-1|acc-1|ad-1|LOGIN|nonce|1800000000000",
            signer.lastSignedPayload
        )
        assertEquals("jwt-2", store.getSession().accessToken)
    }

    @Test
    fun `recoverAdmin rotates Keystore key and reports revokedPriorDevicesCount`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "accountId":"acc-1",
                      "adminDeviceId":"ad-replacement",
                      "groupId":"grp-1",
                      "groupName":"Family",
                      "accessToken":"jwt-rec",
                      "refreshToken":"ref-rec",
                      "expiresInSec":900,
                      "revokedPriorDevicesCount":2
                    }
                    """.trimIndent()
                )
        )

        val res = repo.recoverAdmin("owner@example.com", "A1B2-C3D4-E5F6", "Replacement Phone")
        assertTrue(res is RepoResult.Success)
        assertEquals(2, (res as RepoResult.Success).value)
        assertEquals(1, signer.rotateCount)
        assertEquals("ad-replacement", store.getSession().adminDeviceId)
    }

    @Test
    fun `enrollDevice syncs reconciled sharing settings, handles 401 refresh, and clears cleanly on 401 disconnect`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setBody(
                    """
                    {
                      "deviceId":"dev-1",
                      "groupId":"grp-1",
                      "groupName":"Family",
                      "displayName":"Moto G57",
                      "accessToken":"expired-jwt",
                      "refreshToken":"ref-1",
                      "expiresInSec":900
                    }
                    """.trimIndent()
                )
        )

        val enroll = repo.enrollDevice("ABCD-EFGH-JKLM", "Moto G57")
        assertTrue(enroll is RepoResult.Success)

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "deviceId":"dev-1",
                      "deviceStatus":"ACTIVE",
                      "globalPaused":false,
                      "locationEnabled":false,
                      "usageEnabled":true,
                      "deviceStatusEnabled":false,
                      "locationPermissionState":"DENIED",
                      "usagePermissionState":"GRANTED",
                      "backgroundRestricted":false,
                      "settingsVersion":2,
                      "updatedAtMs":1800000000000
                    }
                    """.trimIndent()
                )
        )

        val syncRes = repo.reconcileAndSyncSharingSettings(
            desired = SharingCategorySettings(locationEnabled = true, usageEnabled = true),
            permissions = PermissionSnapshot(foregroundLocationGranted = false, usageAccessGranted = true)
        )
        assertTrue(syncRes is RepoResult.Success)
        assertFalse(sharingStore.getSettings().locationEnabled)
        assertTrue(sharingStore.getSettings().usageEnabled)

        // TokenAuthenticator refresh flow
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"accessToken":"fresh-jwt","refreshToken":"ref-2","expiresInSec":900}""")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setBody("""{"commandId":"cmd-1","serverDeletedCount":4,"deviceDeletionStatus":"PENDING_DEVICE_ACK"}""")
        )

        val delRes = repo.deleteOwnServerHistory()
        assertTrue(delRes is RepoResult.Success)
        assertEquals(4, (delRes as RepoResult.Success).value.serverDeletedCount)
        assertEquals("fresh-jwt", store.getSession().accessToken)
        assertEquals("ref-2", store.getSession().refreshToken)

        // Already-revoked device (401 on /v1/devices/revoke) still clears local session & settings
        server.enqueue(MockResponse().setResponseCode(401))
        val discRes = repo.disconnectOwnDevice()
        assertTrue(discRes is RepoResult.Success)
        assertNull(store.getSession().accessToken)
        assertFalse(sharingStore.getSettings().usageEnabled)
    }
}
