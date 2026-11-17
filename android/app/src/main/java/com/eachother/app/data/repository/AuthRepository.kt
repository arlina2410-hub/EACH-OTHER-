package com.eachother.app.data.repository

import com.eachother.app.core.network.AdminChallengeRequest
import com.eachother.app.core.network.AdminDeviceEnrollRequest
import com.eachother.app.core.network.AdminDevicesResponse
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
import com.eachother.app.core.network.StorageQuotaResponse
import com.eachother.app.core.network.UpdateSharingSettingsRequest
import com.eachother.app.core.network.UpdateSharingSettingsResponse
import com.eachother.app.core.network.ValidateInvitationRequest
import com.eachother.app.core.network.ValidateInvitationResponse
import com.eachother.app.core.security.AdminKeySigner
import com.eachother.app.core.security.AndroidKeystoreAdminKeyManager
import com.eachother.app.core.security.SessionStore
import com.eachother.app.core.security.SharingSettingsStore
import com.eachother.app.domain.model.AppRole
import com.eachother.app.domain.model.AuthSession
import com.eachother.app.domain.model.LocalAdminIdentity
import com.eachother.app.domain.model.PermissionSnapshot
import com.eachother.app.domain.model.SharingCategorySettings
import kotlinx.coroutines.flow.Flow
import retrofit2.HttpException

sealed interface RepoResult<out T> {
    data class Success<T>(val value: T) : RepoResult<T>
    data class Failure(val code: String, val message: String) : RepoResult<Nothing>
}

class AuthRepository(
    private val api: EachOtherApi,
    private val sessionStore: SessionStore,
    private val sharingSettingsStore: SharingSettingsStore,
    private val adminKeySigner: AdminKeySigner,
    private val appVersion: String
) {
    val sessionFlow: Flow<AuthSession> = sessionStore.sessionFlow
    val sharingSettingsFlow: Flow<SharingCategorySettings> = sharingSettingsStore.settingsFlow

    suspend fun getStoredSharingSettings(): SharingCategorySettings =
        sharingSettingsStore.getSettings()

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
        val publicKeySpki = adminKeySigner.rotateKeyPair(AndroidKeystoreAdminKeyManager.ACTIVE_ADMIN_KEY_ID)
        val res = api.registerAdmin(
            AdminRegisterRequest(
                email = normalizedEmail,
                groupName = groupName.trim(),
                deviceName = deviceName.trim(),
                publicKeySpki = publicKeySpki
            )
        )
        sessionStore.saveSession(
            AuthSession(
                role = AppRole.ADMIN,
                accountId = res.accountId,
                adminDeviceId = res.adminDeviceId,
                adminEmail = normalizedEmail,
                groupId = res.groupId,
                groupName = res.groupName,
                accessToken = res.accessToken,
                refreshToken = res.refreshToken,
                isRevoked = false,
                localAdminIdentity = LocalAdminIdentity(
                    email = normalizedEmail,
                    adminDeviceId = res.adminDeviceId,
                    groupName = res.groupName
                )
            )
        )
        res.recoveryCodes
    }

    suspend fun signInAdminWithKeystore(
        email: String,
        adminDeviceId: String
    ): RepoResult<Unit> = safeCall {
        val normalizedEmail = email.trim().lowercase()
        val challenge = api.requestAdminChallenge(
            AdminChallengeRequest(
                email = normalizedEmail,
                adminDeviceId = adminDeviceId.trim(),
                operation = "LOGIN"
            )
        )
        val signature = adminKeySigner.signCanonicalChallenge(
            AndroidKeystoreAdminKeyManager.ACTIVE_ADMIN_KEY_ID,
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
                groupId = res.groupId,
                groupName = res.groupName,
                accessToken = res.accessToken,
                refreshToken = res.refreshToken,
                isRevoked = false,
                localAdminIdentity = LocalAdminIdentity(
                    email = normalizedEmail,
                    adminDeviceId = res.adminDeviceId,
                    groupName = res.groupName
                )
            )
        )
    }

    suspend fun enrollAdditionalAdminDevice(
        pairingCode: String,
        deviceName: String,
        accountEmailHint: String = ""
    ): RepoResult<Unit> = safeCall {
        val publicKeySpki = adminKeySigner.rotateKeyPair(AndroidKeystoreAdminKeyManager.ACTIVE_ADMIN_KEY_ID)
        val res = api.enrollAdditionalAdminDevice(
            AdminDeviceEnrollRequest(
                pairingCode = pairingCode.trim(),
                deviceName = deviceName.trim(),
                publicKeySpki = publicKeySpki
            )
        )
        val normalizedEmail = accountEmailHint.trim().lowercase()
        sessionStore.saveSession(
            AuthSession(
                role = AppRole.ADMIN,
                accountId = res.accountId,
                adminDeviceId = res.adminDeviceId,
                adminEmail = normalizedEmail.takeIf { it.isNotBlank() },
                groupId = res.groupId,
                groupName = res.groupName,
                accessToken = res.accessToken,
                refreshToken = res.refreshToken,
                isRevoked = false,
                localAdminIdentity = LocalAdminIdentity(
                    email = normalizedEmail,
                    adminDeviceId = res.adminDeviceId,
                    groupName = res.groupName
                )
            )
        )
    }

    suspend fun recoverAdmin(
        email: String,
        recoveryCode: String,
        newDeviceName: String
    ): RepoResult<Int> = safeCall {
        val normalizedEmail = email.trim().lowercase()
        val newPublicKeySpki = adminKeySigner.rotateKeyPair(AndroidKeystoreAdminKeyManager.ACTIVE_ADMIN_KEY_ID)
        val res = api.recoverAdmin(
            AdminRecoverRequest(
                email = normalizedEmail,
                recoveryCode = recoveryCode.trim(),
                newDeviceName = newDeviceName.trim(),
                newPublicKeySpki = newPublicKeySpki
            )
        )
        sessionStore.saveSession(
            AuthSession(
                role = AppRole.ADMIN,
                accountId = res.accountId,
                adminDeviceId = res.adminDeviceId,
                adminEmail = normalizedEmail,
                groupId = res.groupId,
                groupName = res.groupName,
                accessToken = res.accessToken,
                refreshToken = res.refreshToken,
                isRevoked = false,
                localAdminIdentity = LocalAdminIdentity(
                    email = normalizedEmail,
                    adminDeviceId = res.adminDeviceId,
                    groupName = res.groupName
                )
            )
        )
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
    ): RepoResult<UpdateSharingSettingsResponse> = safeCall {
        val nextVersion = desired.settingsVersion + 1
        val reconciled = desired.copy(
            locationEnabled = desired.locationEnabled && permissions.foregroundLocationGranted,
            persistentLocationFgsEnabled = desired.persistentLocationFgsEnabled &&
                desired.locationEnabled &&
                permissions.foregroundLocationGranted &&
                !desired.globalPaused,
            usageEnabled = desired.usageEnabled && permissions.usageAccessGranted,
            settingsVersion = nextVersion
        )
        sharingSettingsStore.saveSettings(reconciled)
        api.updateSharingSettings(
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

    suspend fun disconnectLocallyWithoutServer() {
        sharingSettingsStore.clear()
        sessionStore.clearSession()
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
    ): RepoResult<Unit> = safeCall {
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
                    localAdminIdentity = preservedIdentity
                )
            )
        } else {
            sessionStore.clearSession()
        }
    }

    private fun mapHttpFailure(e: HttpException): RepoResult.Failure {
        val raw = e.response()?.errorBody()?.string()
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
                message = e.message ?: "Hardware Keystore key unavailable on this device."
            )
        } catch (e: Exception) {
            RepoResult.Failure(
                code = "NETWORK_ERROR",
                message = e.localizedMessage ?: "Network request failed. Check your connection."
            )
        }
    }
}
