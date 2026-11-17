package com.eachother.app

import com.eachother.app.core.network.NetworkModule
import com.eachother.app.core.security.AdminKeySigner
import com.eachother.app.core.security.KeyHardwareStatus
import com.eachother.app.core.security.KeySecurityLevel
import com.eachother.app.core.security.SessionStore
import com.eachother.app.core.security.SharingSettingsStore
import com.eachother.app.data.repository.AuthRepository
import com.eachother.app.data.repository.RepoResult
import com.eachother.app.domain.model.AppRole
import com.eachother.app.domain.model.AuthSession
import com.eachother.app.domain.model.PermissionSnapshot
import com.eachother.app.domain.model.SharingCategorySettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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
    val storedKeys = mutableMapOf<String, String>()
    var lastSignedPayload: String? = null
    var lastSignedKeyId: String? = null
    var candidateGeneratedCount: Int = 0
    var deletedKeys = mutableListOf<String>()
    var throwMissingKey: Boolean = false

    override fun ensureKeyPair(keyId: String): String =
        storedKeys.getOrPut(keyId) {
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEFakeSpkiKey_$keyId"
        }

    override fun generateCandidateKeyPair(keyId: String): String {
        candidateGeneratedCount++
        val spki = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEFakeSpkiKey_$keyId"
        storedKeys[keyId] = spki
        return spki
    }

    override fun deleteKeyPair(keyId: String) {
        storedKeys.remove(keyId)
        deletedKeys.add(keyId)
    }

    override fun getPublicKeySpki(keyId: String): String? = storedKeys[keyId]

    override fun signCanonicalChallenge(keyId: String, canonicalPayload: String): String {
        if (throwMissingKey || !storedKeys.containsKey(keyId)) {
            error("Administrator Keystore key '$keyId' not found on this device.")
        }
        lastSignedKeyId = keyId
        lastSignedPayload = canonicalPayload
        return "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=="
    }

    override fun inspectKeyHardwareStatus(keyId: String): KeyHardwareStatus {
        val exists = storedKeys.containsKey(keyId)
        return KeyHardwareStatus(
            keyId = keyId,
            exists = exists,
            securityLevel = if (exists) KeySecurityLevel.TEE_HARDWARE else KeySecurityLevel.UNKNOWN,
            isInsideSecureHardware = exists,
            summary = if (exists) "Hardware-backed (TEE) ECDSA P-256 key" else "Missing key"
        )
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
        val api = NetworkModule.createApi(server.url("/").toString(), store, sharingStore)
        repo = AuthRepository(api, store, sharingStore, signer, "0.2.0")
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `registerAdmin saves activeKeyId and preserves localAdminIdentity across signOutLocally`() = runTest {
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
        assertNotNull(saved.activeAdminKeyId)
        assertEquals(saved.activeAdminKeyId, saved.localAdminIdentity?.activeKeyId)
        assertTrue(signer.storedKeys.containsKey(saved.activeAdminKeyId!!))

        repo.signOutLocally()
        val afterSignOut = store.getSession()
        assertFalse(afterSignOut.isAuthenticated)
        assertEquals("ad-1", afterSignOut.localAdminIdentity?.adminDeviceId)
        assertEquals("owner@example.com", afterSignOut.localAdminIdentity?.email)
        assertEquals(saved.activeAdminKeyId, afterSignOut.localAdminIdentity?.activeKeyId)
    }

    @Test
    fun `failed recoverAdmin preserves existing active Keystore key and deletes unused candidate key`() = runTest {
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
                      "accessToken":"jwt-orig",
                      "refreshToken":"ref-orig",
                      "expiresInSec":900,
                      "recoveryCodes":["A1B2-C3D4-E5F6","G7H8-J9K0-M1N2","1111-2222-3333","4444-5555-6666","7777-8888-9999","AAAA-BBBB-CCCC","DDDD-EEEE-FFFF","GGGG-HHHH-JJJJ","KKKK-MMMM-NNNN","PPPP-QQQQ-RRRR"]
                    }
                    """.trimIndent()
                )
        )
        assertTrue(repo.registerAdmin("owner@example.com", "Family", "Original Phone") is RepoResult.Success)
        val originalKeyId = store.getSession().activeAdminKeyId!!

        // Recovery fails on server (invalid recovery code)
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("""{"error":{"code":"RECOVERY_FAILED","message":"Invalid recovery credentials."}}""")
        )
        val failedRecovery = repo.recoverAdmin("owner@example.com", "WRONG-CODE-1234", "New Phone")
        assertTrue(failedRecovery is RepoResult.Failure)
        assertEquals("RECOVERY_FAILED", (failedRecovery as RepoResult.Failure).code)

        // Original key and session remain intact and usable!
        assertTrue(signer.storedKeys.containsKey(originalKeyId))
        assertEquals(1, signer.storedKeys.size)
        assertEquals(1, signer.deletedKeys.size)
        assertEquals(originalKeyId, store.getSession().activeAdminKeyId)
        assertEquals("jwt-orig", store.getSession().accessToken)

        // Signing in afterwards uses the preserved original active key
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"challengeId":"c1","canonicalPayload":"eachother:auth:v1|c1|acc-1|ad-1|LOGIN|n|1800000000000","expiresAtMs":1800000000000}""")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"accountId":"acc-1","adminDeviceId":"ad-1","groupId":"grp-1","groupName":"Family","accessToken":"jwt-2","refreshToken":"ref-2","expiresInSec":900}""")
        )
        assertTrue(repo.signInAdminWithKeystore("owner@example.com", "ad-1") is RepoResult.Success)
        assertEquals(originalKeyId, signer.lastSignedKeyId)
    }

    @Test
    fun `successful recoverAdmin swaps to new candidate key ID and cleans up retired key`() = runTest {
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
                      "accessToken":"jwt-orig",
                      "refreshToken":"ref-orig",
                      "expiresInSec":900,
                      "recoveryCodes":["A1B2-C3D4-E5F6","G7H8-J9K0-M1N2","1111-2222-3333","4444-5555-6666","7777-8888-9999","AAAA-BBBB-CCCC","DDDD-EEEE-FFFF","GGGG-HHHH-JJJJ","KKKK-MMMM-NNNN","PPPP-QQQQ-RRRR"]
                    }
                    """.trimIndent()
                )
        )
        repo.registerAdmin("owner@example.com", "Family", "Original")
        val oldKeyId = store.getSession().activeAdminKeyId!!

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
        val newKeyId = store.getSession().activeAdminKeyId!!
        assertNotEquals(oldKeyId, newKeyId)
        assertFalse(signer.storedKeys.containsKey(oldKeyId))
        assertTrue(signer.storedKeys.containsKey(newKeyId))
        assertEquals("ad-replacement", store.getSession().adminDeviceId)
    }

    @Test
    fun `signInAdminWithKeystore maps missing keystore key to KEYSTORE_ERROR and succeeds with valid key`() = runTest {
        val activeKeyId = "primary_v1"
        signer.ensureKeyPair(activeKeyId)

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
    fun `concurrent 401 responses coordinate into a single refresh request and retry both calls`() = runTest {
        val concurrentServer = MockWebServer()
        concurrentServer.start()
        try {
            val refreshCalls = AtomicInteger(0)
            val bothSawExpiredToken = CountDownLatch(2)

            concurrentServer.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    return when (request.path) {
                        "/v1/auth/refresh" -> {
                            val n = refreshCalls.incrementAndGet()
                            if (n == 1) {
                                MockResponse().setResponseCode(200).setBody(
                                    """{"accessToken":"fresh-jwt-1","refreshToken":"ref-fresh-1","expiresInSec":900}"""
                                )
                            } else {
                                MockResponse().setResponseCode(401).setBody(
                                    """{"error":{"code":"REFRESH_TOKEN_REUSED","message":"Reused"}}"""
                                )
                            }
                        }
                        "/v1/devices/me/deletions" -> {
                            val auth = request.getHeader("Authorization")
                            if (auth == "Bearer expired-jwt") {
                                bothSawExpiredToken.countDown()
                                bothSawExpiredToken.await(2, TimeUnit.SECONDS)
                                MockResponse().setResponseCode(401)
                            } else if (auth == "Bearer fresh-jwt-1") {
                                MockResponse().setResponseCode(201).setBody(
                                    """{"commandId":"cmd-sync","serverDeletedCount":1,"deviceDeletionStatus":"PENDING_DEVICE_ACK"}"""
                                )
                            } else {
                                MockResponse().setResponseCode(401)
                            }
                        }
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }

            val concurrentStore = InMemorySessionStore().apply {
                saveSession(
                    AuthSession(
                        role = AppRole.CONNECTED,
                        deviceId = "dev-1",
                        groupId = "grp-1",
                        accessToken = "expired-jwt",
                        refreshToken = "ref-1",
                        isRevoked = false
                    )
                )
            }
            val concurrentApi = NetworkModule.createApi(
                concurrentServer.url("/").toString(),
                concurrentStore,
                sharingStore
            )
            val concurrentRepo = AuthRepository(concurrentApi, concurrentStore, sharingStore, signer, "0.2.0")

            val results = listOf(
                async(Dispatchers.IO) { concurrentRepo.deleteOwnServerHistory() },
                async(Dispatchers.IO) { concurrentRepo.deleteOwnServerHistory() }
            ).awaitAll()

            assertEquals(1, refreshCalls.get())
            assertTrue(results.all { it is RepoResult.Success })
            assertEquals("fresh-jwt-1", concurrentStore.getSession().accessToken)
            assertEquals("ref-fresh-1", concurrentStore.getSession().refreshToken)
        } finally {
            concurrentServer.shutdown()
        }
    }

    @Test
    fun `reconcileAndSyncSharingSettings reconciles 409 conflicts and serializes versions`() = runTest {
        store.saveSession(
            AuthSession(
                role = AppRole.CONNECTED,
                deviceId = "dev-1",
                groupId = "grp-1",
                accessToken = "jwt-dev",
                refreshToken = "ref-dev"
            )
        )

        // Server rejects stale update with 409 and returns authoritative version 5
        server.enqueue(
            MockResponse()
                .setResponseCode(409)
                .setBody(
                    """
                    {
                      "error":{"code":"SETTINGS_VERSION_CONFLICT","message":"Stale version"},
                      "authoritativeSettings":{
                        "deviceId":"dev-1",
                        "deviceStatus":"PAUSED",
                        "globalPaused":true,
                        "locationEnabled":true,
                        "usageEnabled":false,
                        "deviceStatusEnabled":true,
                        "locationPermissionState":"BACKGROUND_GRANTED",
                        "usagePermissionState":"DENIED",
                        "backgroundRestricted":false,
                        "settingsVersion":5,
                        "updatedAtMs":1800000000000
                      }
                    }
                    """.trimIndent()
                )
        )

        val conflictRes = repo.reconcileAndSyncSharingSettings(
            desired = SharingCategorySettings(locationEnabled = true, usageEnabled = false, deviceStatusEnabled = true, settingsVersion = 1),
            permissions = PermissionSnapshot(foregroundLocationGranted = true, backgroundLocationGranted = true)
        )
        assertTrue(conflictRes is RepoResult.Failure)
        assertEquals("SETTINGS_VERSION_CONFLICT", (conflictRes as RepoResult.Failure).code)

        // Local DataStore adopted authoritative server version 5 & paused state, preserving category flags
        val reconciled = sharingStore.getSettings()
        assertEquals(5, reconciled.settingsVersion)
        assertTrue(reconciled.globalPaused)
        assertTrue(reconciled.locationEnabled)
        assertTrue(reconciled.deviceStatusEnabled)

        // Next sync increments from reconciled version 5 -> 6 cleanly
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "deviceId":"dev-1",
                      "deviceStatus":"ACTIVE",
                      "globalPaused":false,
                      "locationEnabled":true,
                      "usageEnabled":false,
                      "deviceStatusEnabled":true,
                      "locationPermissionState":"BACKGROUND_GRANTED",
                      "usagePermissionState":"DENIED",
                      "backgroundRestricted":false,
                      "settingsVersion":6,
                      "updatedAtMs":1800000001000
                    }
                    """.trimIndent()
                )
        )

        val unpauseRes = repo.reconcileAndSyncSharingSettings(
            desired = reconciled.copy(globalPaused = false),
            permissions = PermissionSnapshot(foregroundLocationGranted = true, backgroundLocationGranted = true)
        )
        assertTrue(unpauseRes is RepoResult.Success)
        assertEquals(6, sharingStore.getSettings().settingsVersion)
        assertFalse(sharingStore.getSettings().globalPaused)
        assertTrue(sharingStore.getSettings().locationEnabled)
    }

    @Test
    fun `offline disconnect clears local state with unconfirmed server status while online disconnect revokes cleanly`() = runTest {
        store.saveSession(
            AuthSession(
                role = AppRole.CONNECTED,
                deviceId = "dev-1",
                groupId = "grp-1",
                accessToken = "jwt-dev",
                refreshToken = "ref-dev"
            )
        )
        sharingStore.saveSettings(SharingCategorySettings(locationEnabled = true, settingsVersion = 3))

        val offlineResult = repo.disconnectLocallyWithoutServer()
        assertFalse(offlineResult.serverRevocationConfirmed)
        assertTrue(offlineResult.message.contains("could not be verified", ignoreCase = true))
        assertNull(store.getSession().accessToken)
        assertFalse(sharingStore.getSettings().locationEnabled)

        // Online disconnect via 401 (already revoked by Admin) clears cleanly
        store.saveSession(
            AuthSession(
                role = AppRole.CONNECTED,
                deviceId = "dev-1",
                groupId = "grp-1",
                accessToken = "jwt-dev",
                refreshToken = "ref-dev"
            )
        )
        server.enqueue(MockResponse().setResponseCode(401))
        val onlineRes = repo.disconnectOwnDevice()
        assertTrue(onlineRes is RepoResult.Success)
        assertNull(store.getSession().accessToken)
    }

    @Test
    fun `disconnectConnectedDeviceByAdmin parses honest DEVICE_REVOKED_LOCAL_UNVERIFIED wipe result`() = runTest {
        store.saveSession(
            AuthSession(
                role = AppRole.ADMIN,
                accountId = "acc-1",
                adminDeviceId = "ad-1",
                groupId = "grp-1",
                accessToken = "jwt-admin"
            )
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "deviceId":"dev-1",
                      "status":"REVOKED",
                      "revokedAtMs":1800000000000,
                      "deletionResult":{
                        "commandId":"cmd-99",
                        "serverDeletedCount":12,
                        "deviceDeletionStatus":"DEVICE_REVOKED_LOCAL_UNVERIFIED",
                        "localWipeGuaranteed":false
                      }
                    }
                    """.trimIndent()
                )
        )

        val res = repo.disconnectConnectedDeviceByAdmin("dev-1", true)
        assertTrue(res is RepoResult.Success)
        val body = (res as RepoResult.Success).value
        assertEquals("REVOKED", body.status)
        assertEquals(12, body.deletionResult?.serverDeletedCount)
        assertEquals("DEVICE_REVOKED_LOCAL_UNVERIFIED", body.deletionResult?.deviceDeletionStatus)
        assertEquals(false, body.deletionResult?.localWipeGuaranteed)
    }
}
