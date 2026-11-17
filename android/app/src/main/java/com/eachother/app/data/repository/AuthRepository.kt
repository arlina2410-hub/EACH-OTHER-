package com.eachother.app.data.repository

import com.eachother.app.core.network.AdminChallengeRequest
import com.eachother.app.core.network.AdminDeviceEnrollRequest
import com.eachother.app.core.network.AdminDevicesResponse
import com.eachother.app.core.network.AdminDisconnectDeviceResponse
import com.eachother.app.core.network.AdminPairingInviteRequest
import com.eachother.app.core.network.AdminPairingInviteResponse
import com.eachother.app.core.network.AdminRecoverRequest
import com.eachother.app.core.network.AdminRegisterRequest
import com.eachother.app.core.network.AdminVerifyRequest
import com.eachother.app.core.network.ApiErrorEnvelope
import com.eachother.app.core.network.CreateInvitationRequest
import com.eachother.app.core.network.CreateInvitationResponse
import com.eachother.app.core.network.DeletionRequestDto
import com.eachother.app.core.network.DeletionResponseDto
import com.eachother.app.core.network.EachOtherApi
import com.eachother.app.core.network.EnrollDeviceRequest
import com.eachother.app.core.network.InvitationsListResponse
import com.eachother.app.core.network.NetworkModule
import com.eachother.app.core.network.RegisteredAdminDevicesResponse
import com.eachother.app.core.network.RevokeAdminDeviceResponse
import com.eachother.app.core.network.SettingsConflictEnvelope
import com.eachother.app.core.network.StorageQuotaResponse
import com.eachother.app.core.network.UpdateSharingSettingsRequest
import com.eachother.app.core.network.UpdateSharingSettingsResponse
import com.eachother.app.core.network.ValidateInvitationRequest
import com.eachother.app.core.network.ValidateInvitationResponse
import com.eachother.app.core.security.AdminKeySigner
import com.eachother.app.core.security.AndroidKeystoreAdminKeyManager
import com.eachother.app.core.security.KeyHardwareStatus
import com.eachother.app.core.security.SessionStore
import com.eachother.app.core.security.SharingSettingsStore
import com.eachother.app.domain.model.AppRole
import com.eachother.app.domain.model.AuthSession
import com.eachother.app.domain.model.LocalAdminIdentity
import com.eachother.app.domain.model.PermissionSnapshot
import com.eachother.app.domain.model.SharingCategorySettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException
import java.util.UUID

sealed interface RepoResult<out T> {
    data class Success<T>(val value: T) : RepoResult<T>
    data class Failure(val code: String, val message: String) : RepoResult<Nothing>
}

data class OfflineDisconnectResult(
    val serverRevocationConfirmed: Boolean,
    val message: String
)

class AuthRepository(
    private val api: EachOtherApi,
    private val sessionStore: SessionStore,
    private val sharingSettingsStore: SharingSettingsStore,
    private val adminKeySigner: AdminKeySigner,
    private val appVersion: String
) {
    private val settingsSyncMutex = Mutex()

    val sessionFlow: Flow<AuthSession> = sessionStore.sessionFlow
    val sharingSettingsFlow: Flow<SharingCategorySettings> = sharingSettingsStore.settingsFlow

    suspend fun getStoredSharingSettings(): SharingCategorySettings =
        sharingSettingsStore.getSettings()

    fun resolveActiveAdminKeyId(session: AuthSession): String =
        session.localAdminIdentity?.activeKeyId
            ?: session.activeAdminKeyId
            ?: AndroidKeystoreAdminKeyManager.DEFAULT_LEGACY_KEY_ID

    fun getActiveAdminKeyHardwareStatus(session: AuthSession): KeyHardwareStatus =
        adminKeySigner.inspectKeyHardwareStatus(resolveActiveAdminKeyId(session))

    suspend fun selectRole(role: AppRole) {
        val current = sessionStore.getSession()
        sessionStore.saveSession(current.copy(role = role))
    }

    suspend fun registerAdmin(
        email: String,
        groupName: String,
        deviceName: String
    ): RepoResult<List<String>> = safeCall {
        val normalizedEmail = email.trim().lowercase()
        val currentSession = sessionStore.getSession()
        val previousKeyId = currentSession.localAdminIdentity?.activeKeyId ?: currentSession.activeAdminKeyId
        currentSession.pendingAdminKeyId
            ?.takeIf { it != previousKeyId }
            ?.let { adminKeySigner.deleteKeyPair(it) }

        val candidateKeyId = "admin_${UUID.randomUUID()}"
        val publicKeySpki = adminKeySigner.generateCandidateKeyPair(candidateKeyId)
        val res = try {
            api.registerAdmin(
                AdminRegisterRequest(
                    email = normalizedEmail,
                    groupName = groupName.trim(),
                    deviceName = deviceName.trim(),
                    publicKeySpki = publicKeySpki
                )
            )
        } catch (t: Throwable) {
            adminKeySigner.deleteKeyPair(candidateKeyId)
            throw t
        }

        sessionStore.saveSession(
            AuthSession(
                role = AppRole.ADMIN,
                accountId = res.accountId,
                adminDeviceId = res.adminDeviceId,
                adminEmail = normalizedEmail,
                activeAdminKeyId = candidateKeyId,
                pendingAdminKeyId = null,
                groupId = res.groupId,
                groupName = res.groupName,
                accessToken = res.accessToken,
                refreshToken = res.refreshToken,
                isRevoked = false,
                localAdminIdentity = LocalAdminIdentity(
                    email = normalizedEmail,
                    adminDeviceId = res.adminDeviceId,
                    groupName = res.groupName,
                    activeKeyId = candidateKeyId
                )
            )
        )
        if (!previousKeyId.isNullOrBlank() && previousKeyId != candidateKeyId) {
            adminKeySigner.deleteKeyPair(previousKeyId)
        }
        res.recoveryCodes
    }

    suspend fun signInAdminWithKeystore(
        email: String,
        adminDeviceId: String
    ): RepoResult<Unit> = safeCall {
        val normalizedEmail = email.trim().lowercase()
        val currentSession = sessionStore.getSession()
        val activeKeyId = resolveActiveAdminKeyId(currentSession)

        val challenge = api.requestAdminChallenge(
            AdminChallengeRequest(
                email = normalizedEmail,
                adminDeviceId = adminDeviceId.trim(),
                operation = "LOGIN"
            )
        )
        val signature = adminKeySigner.signCanonicalChallenge(
            activeKeyId,
            challenge.canonicalPayload
        )
        val res = api.verifyAdminChallenge(
            AdminVerifyRequest(
                challengeId = challenge.challengeId,
                signature = signature
            )
        )
        sessionStore.saveSession(
            AuthSession(
                role = AppRole.ADMIN,
                accountId = res.accountId,
                adminDeviceId = res.adminDeviceId,
                adminEmail = normalizedEmail,
                activeAdminKeyId = activeKeyId,
                pendingAdminKeyId = null,
                groupId = res.groupId,
                groupName = res.groupName,
                accessToken = res.accessToken,
                refreshToken = res.refreshToken,
                isRevoked = false,
                localAdminIdentity = LocalAdminIdentity(
                    email = normalizedEmail,
                    adminDeviceId = res.adminDeviceId,
                    groupName = res.groupName,
                    activeKeyId = activeKeyId
                )
            )
        )
    }

    suspend fun enrollAdditionalAdminDevice(
        pairingCode: String,
        deviceName: String,
        accountEmailHint: String = ""
    ): RepoResult<Unit> = safeCall {
        val currentSession = sessionStore.getSession()
        val previousKeyId = currentSession.localAdminIdentity?.activeKeyId ?: currentSession.activeAdminKeyId
        currentSession.pendingAdminKeyId
            ?.takeIf { it != previousKeyId }
            ?.let { adminKeySigner.deleteKeyPair(it) }

        val candidateKeyId = "admin_${UUID.randomUUID()}"
        val publicKeySpki = adminKeySigner.generateCandidateKeyPair(candidateKeyId)
        val res = try {
            api.enrollAdditionalAdminDevice(
                AdminDeviceEnrollRequest(
                    pairingCode = pairingCode.trim(),
                    deviceName = deviceName.trim(),
                    publicKeySpki = publicKeySpki
                )
            )
        } catch (t: Throwable) {
            adminKeySigner.deleteKeyPair(candidateKeyId)
            throw t
        }

        val normalizedEmail = accountEmailHint.trim().lowercase()
        sessionStore.saveSession(
            AuthSession(
                role = AppRole.ADMIN,
                accountId = res.accountId,
                adminDeviceId = res.adminDeviceId,
                adminEmail = normalizedEmail.takeIf { it.isNotBlank() },
                activeAdminKeyId = candidateKeyId,
                pendingAdminKeyId = null,
                groupId = res.groupId,
                groupName = res.groupName,
                accessToken = res.accessToken,
                refreshToken = res.refreshToken,
                isRevoked = false,
                localAdminIdentity = LocalAdminIdentity(
                    email = normalizedEmail,
                    adminDeviceId = res.adminDeviceId,
                    groupName = res.groupName,
                    activeKeyId = candidateKeyId
                )
            )
        )
        if (!previousKeyId.isNullOrBlank() && previousKeyId != candidateKeyId) {
            adminKeySigner.deleteKeyPair(previousKeyId)
        }
    }

    suspend fun recoverAdmin(
        email: String,
        recoveryCode: String,
        newDeviceName: String
    ): RepoResult<Int> = safeCall {
        val normalizedEmail = email.trim().lowercase()
        val currentSession = sessionStore.getSession()
        val previousKeyId = currentSession.localAdminIdentity?.activeKeyId ?: currentSession.activeAdminKeyId
        currentSession.pendingAdminKeyId
            ?.takeIf { it != previousKeyId }
            ?.let { adminKeySigner.deleteKeyPair(it) }

        // Keep existing key intact until server recovery commits; register candidate key only
        val candidateKeyId = "admin_${UUID.randomUUID()}"
        val newPublicKeySpki = adminKeySigner.generateCandidateKeyPair(candidateKeyId)
        val res = try {
            api.recoverAdmin(
                AdminRecoverRequest(
                    email = normalizedEmail,
                    recoveryCode = recoveryCode.trim(),
                    newDeviceName = newDeviceName.trim(),
                    newPublicKeySpki = newPublicKeySpki
                )
            )
        } catch (t: Throwable) {
            adminKeySigner.deleteKeyPair(candidateKeyId)
            throw t
        }

        sessionStore.saveSession(
            AuthSession(
                role = AppRole.ADMIN,
                accountId = res.accountId,
                adminDeviceId = res.adminDeviceId,
                adminEmail = normalizedEmail,
                activeAdminKeyId = candidateKeyId,
                pendingAdminKeyId = null,
                groupId = res.groupId,
                groupName = res.groupName,
                accessToken = res.accessToken,
                refreshToken = res.refreshToken,
                isRevoked = false,
                localAdminIdentity = LocalAdminIdentity(
                    email = normalizedEmail,
                    adminDeviceId = res.adminDeviceId,
                    groupName = res.groupName,
                    activeKeyId = candidateKeyId
                )
            )
        )
        if (!previousKeyId.isNullOrBlank() && previousKeyId != candidateKeyId) {
            adminKeySigner.deleteKeyPair(previousKeyId)
        }
        res.revokedPriorDevicesCount ?: 0
    }

    suspend fun regenerateRecoveryCodes(): RepoResult<List<String>> = safeCall {
        api.regenerateRecoveryCodes().recoveryCodes
    }

    suspend fun listRegisteredAdminDevices(): RepoResult<RegisteredAdminDevicesResponse> = safeCall {
        api.listRegisteredAdminDevices()
    }

    suspend fun createAdminPairingInvite(): RepoResult<AdminPairingInviteResponse> = safeCall {
        api.createAdminPairingInvite(AdminPairingInviteRequest(ttlMinutes = 15))
    }

    suspend fun revokeAdminDevice(adminDeviceId: String): RepoResult<RevokeAdminDeviceResponse> = safeCall {
        api.revokeAdminDevice(adminDeviceId)
    }

    suspend fun validateInvitation(inviteCode: String): RepoResult<ValidateInvitationResponse> = safeCall {
        api.validateInvitation(ValidateInvitationRequest(inviteCode.trim()))
    }

    suspend fun enrollDevice(inviteCode: String, displayName: String): RepoResult<AuthSession> = safeCall {
        sharingSettingsStore.clear()
        val res = api.enrollDevice(EnrollDeviceRequest(inviteCode.trim(), displayName.trim(), appVersion))
        val session = AuthSession(
            role = AppRole.CONNECTED,
            deviceId = res.deviceId,
            groupId = res.groupId,
            groupName = res.groupName,
            displayName = res.displayName,
            accessToken = res.accessToken,
            refreshToken = res.refreshToken,
            isRevoked = false
        )
        sessionStore.saveSession(session)
        session
    }

    suspend fun reconcileAndSyncSharingSettings(
        desired: SharingCategorySettings,
        permissions: PermissionSnapshot
    ): RepoResult<UpdateSharingSettingsResponse> = settingsSyncMutex.withLock {
        val latestStored = sharingSettingsStore.getSettings()
        val nextVersion = maxOf(latestStored.settingsVersion, desired.settingsVersion) + 1
        val locationPermitted = desired.locationEnabled && permissions.foregroundLocationGranted
        val reconciled = desired.copy(
            locationEnabled = locationPermitted,
            persistentLocationFgsEnabled = desired.persistentLocationFgsEnabled &&
                locationPermitted &&
                !desired.globalPaused,
            usageEnabled = desired.usageEnabled && permissions.usageAccessGranted,
            settingsVersion = nextVersion
        )
        sharingSettingsStore.saveSettings(reconciled)

        return try {
            val response = api.updateSharingSettings(
                UpdateSharingSettingsRequest(
                    globalPaused = reconciled.globalPaused,
                    locationEnabled = reconciled.locationEnabled,
                    usageEnabled = reconciled.usageEnabled,
                    deviceStatusEnabled = reconciled.deviceStatusEnabled,
                    locationPermissionState = permissions.locationPermissionStateWire(),
                    usagePermissionState = permissions.usagePermissionStateWire(),
                    backgroundRestricted = permissions.backgroundExecutionRestricted,
                    settingsVersion = reconciled.settingsVersion
                )
            )
            sharingSettingsStore.saveSettings(
                reconciled.copy(
                    globalPaused = response.globalPaused,
                    locationEnabled = response.locationEnabled,
                    persistentLocationFgsEnabled = reconciled.persistentLocationFgsEnabled &&
                        !response.globalPaused &&
                        response.locationEnabled,
                    usageEnabled = response.usageEnabled,
                    deviceStatusEnabled = response.deviceStatusEnabled,
                    settingsVersion = response.settingsVersion
                )
            )
            RepoResult.Success(response)
        } catch (e: HttpException) {
            val raw = e.response()?.errorBody()?.string()
            if (e.code() == 409 && raw != null) {
                val conflict = try {
                    NetworkModule.json.decodeFromString<SettingsConflictEnvelope>(raw)
                } catch (_: Exception) {
                    null
                }
                val authoritative = conflict?.authoritativeSettings
                if (authoritative != null) {
                    sharingSettingsStore.saveSettings(
                        SharingCategorySettings(
                            globalPaused = authoritative.globalPaused,
                            locationEnabled = authoritative.locationEnabled,
                            persistentLocationFgsEnabled = reconciled.persistentLocationFgsEnabled &&
                                !authoritative.globalPaused &&
                                authoritative.locationEnabled,
                            usageEnabled = authoritative.usageEnabled,
                            deviceStatusEnabled = authoritative.deviceStatusEnabled,
                            settingsVersion = authoritative.settingsVersion
                        )
                    )
                    return RepoResult.Failure(
                        code = conflict.error.code,
                        message = "Settings were updated out of order and reconciled with server version ${authoritative.settingsVersion}."
                    )
                }
            } else if (e.code() == 401) {
                val current = sessionStore.getSession()
                sharingSettingsStore.clear()
                sessionStore.saveSession(
                    current.copy(
                        accessToken = null,
                        refreshToken = null,
                        isRevoked = true
                    )
                )
            }
            mapHttpFailure(e, raw)
        } catch (e: Exception) {
            RepoResult.Failure(
                code = "NETWORK_ERROR",
                message = e.localizedMessage ?: "Network request failed. Check your connection."
            )
        }
    }

    suspend fun disconnectOwnDevice(): RepoResult<Unit> {
        return try {
            api.revokeOwnDevice()
            sharingSettingsStore.clear()
            sessionStore.clearSession()
            RepoResult.Success(Unit)
        } catch (e: HttpException) {
            if (e.code() == 401) {
                // Device was already revoked by the Administrator; clear local credentials cleanly.
                sharingSettingsStore.clear()
                sessionStore.clearSession()
                RepoResult.Success(Unit)
            } else {
                mapHttpFailure(e)
            }
        } catch (e: Exception) {
            RepoResult.Failure(
                code = "NETWORK_ERROR",
                message = e.localizedMessage ?: "Network request failed. Check your connection."
            )
        }
    }

    suspend fun disconnectLocallyWithoutServer(): OfflineDisconnectResult {
        sharingSettingsStore.clear()
        sessionStore.clearSession()
        return OfflineDisconnectResult(
            serverRevocationConfirmed = false,
            message = "Local sharing state and credentials were cleared on this phone, but server revocation could not be verified while offline."
        )
    }

    suspend fun deleteOwnServerHistory(): RepoResult<DeletionResponseDto> = safeCall {
        api.deleteOwnServerData(
            DeletionRequestDto(scope = "ALL_DEVICE_DATA", category = "ALL")
        )
    }

    suspend fun listAdminDevices(): RepoResult<AdminDevicesResponse> = safeCall {
        api.listAdminDevices()
    }

    suspend fun disconnectConnectedDeviceByAdmin(
        deviceId: String,
        deleteServerData: Boolean
    ): RepoResult<AdminDisconnectDeviceResponse> = safeCall {
        api.disconnectDevice(deviceId, mapOf("deleteServerData" to deleteServerData))
    }

    suspend fun createInvitation(label: String?): RepoResult<CreateInvitationResponse> = safeCall {
        api.createInvitation(CreateInvitationRequest(label = label?.takeIf { it.isNotBlank() }, ttlMinutes = 60))
    }

    suspend fun listInvitations(): RepoResult<InvitationsListResponse> = safeCall {
        api.listInvitations()
    }

    suspend fun revokeInvitation(invitationId: String): RepoResult<Unit> = safeCall {
        api.revokeInvitation(invitationId)
    }

    suspend fun getStorageStatus(): RepoResult<StorageQuotaResponse> = safeCall {
        api.getStorageStatus()
    }

    suspend fun signOutLocally() {
        val current = sessionStore.getSession()
        val preservedIdentity = current.localAdminIdentity
        if (preservedIdentity != null) {
            sessionStore.saveSession(
                AuthSession(
                    role = AppRole.UNSELECTED,
                    activeAdminKeyId = preservedIdentity.activeKeyId,
                    localAdminIdentity = preservedIdentity
                )
            )
        } else {
            sessionStore.clearSession()
        }
    }

    private fun mapHttpFailure(e: HttpException, preReadBody: String? = null): RepoResult.Failure {
        val raw = preReadBody ?: e.response()?.errorBody()?.string()
        val parsed = raw?.let {
            try {
                NetworkModule.json.decodeFromString<ApiErrorEnvelope>(it).error
            } catch (_: Exception) {
                null
            }
        }
        return RepoResult.Failure(
            code = parsed?.code ?: "HTTP_${e.code()}",
            message = parsed?.message ?: "Server returned error ${e.code()}."
        )
    }

    private suspend inline fun <T> safeCall(crossinline block: suspend () -> T): RepoResult<T> {
        return try {
            RepoResult.Success(block())
        } catch (e: HttpException) {
            mapHttpFailure(e)
        } catch (e: IllegalStateException) {
            RepoResult.Failure(
                code = "KEYSTORE_ERROR",
                message = e.message ?: "Android Keystore key unavailable on this device."
            )
        } catch (e: Exception) {
            RepoResult.Failure(
                code = "NETWORK_ERROR",
                message = e.localizedMessage ?: "Network request failed. Check your connection."
            )
        }
    }
}
