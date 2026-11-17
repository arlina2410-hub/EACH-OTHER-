package com.eachother.app.core.network

import kotlinx.serialization.Serializable

@Serializable
data class ApiErrorEnvelope(
    val error: ApiErrorBody
)

@Serializable
data class ApiErrorBody(
    val code: String,
    val message: String
)

@Serializable
data class AdminRegisterRequest(
    val email: String,
    val groupName: String,
    val deviceName: String,
    val publicKeySpki: String
)

@Serializable
data class AdminRegisterResponse(
    val accountId: String,
    val adminDeviceId: String,
    val groupId: String,
    val groupName: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresInSec: Int,
    val recoveryCodes: List<String>
)

@Serializable
data class AdminChallengeRequest(
    val email: String,
    val adminDeviceId: String,
    val operation: String = "LOGIN"
)

@Serializable
data class AdminChallengeResponse(
    val challengeId: String,
    val canonicalPayload: String,
    val expiresAtMs: Long
)

@Serializable
data class AdminVerifyRequest(
    val challengeId: String,
    val signature: String
)

@Serializable
data class AdminAuthResponse(
    val accountId: String,
    val adminDeviceId: String,
    val groupId: String,
    val groupName: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresInSec: Int,
    val revokedPriorDevicesCount: Int? = null
)

@Serializable
data class AdminRecoverRequest(
    val email: String,
    val recoveryCode: String,
    val newDeviceName: String,
    val newPublicKeySpki: String
)

@Serializable
data class AdminDeviceEnrollRequest(
    val pairingCode: String,
    val deviceName: String,
    val publicKeySpki: String
)

@Serializable
data class RecoveryCodesResponse(
    val recoveryCodes: List<String>
)

@Serializable
data class RegisteredAdminDevicesResponse(
    val adminDevices: List<RegisteredAdminDeviceDto>
)

@Serializable
data class RegisteredAdminDeviceDto(
    val id: String,
    val device_name: String,
    val status: String,
    val created_at_ms: Long,
    val last_auth_at_ms: Long? = null,
    val revoked_at_ms: Long? = null
)

@Serializable
data class AdminPairingInviteRequest(
    val ttlMinutes: Int = 15
)

@Serializable
data class AdminPairingInviteResponse(
    val pairingInviteId: String,
    val pairingCode: String,
    val qrPayload: String,
    val expiresAtMs: Long
)

@Serializable
data class RevokeAdminDeviceResponse(
    val adminDeviceId: String,
    val status: String,
    val revokedAtMs: Long
)

@Serializable
data class RefreshRequest(
    val refreshToken: String
)

@Serializable
data class RefreshResponse(
    val accessToken: String,
    val refreshToken: String,
    val expiresInSec: Int
)

@Serializable
data class ValidateInvitationRequest(
    val inviteCode: String
)

@Serializable
data class ValidateInvitationResponse(
    val valid: Boolean,
    val groupName: String,
    val expiresAtMs: Long
)

@Serializable
data class EnrollDeviceRequest(
    val inviteCode: String,
    val displayName: String,
    val appVersion: String
)

@Serializable
data class EnrollDeviceResponse(
    val deviceId: String,
    val groupId: String,
    val groupName: String,
    val displayName: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresInSec: Int
)

@Serializable
data class UpdateSharingSettingsRequest(
    val globalPaused: Boolean,
    val locationEnabled: Boolean,
    val usageEnabled: Boolean,
    val deviceStatusEnabled: Boolean,
    val locationPermissionState: String,
    val usagePermissionState: String,
    val backgroundRestricted: Boolean,
    val settingsVersion: Int
)

@Serializable
data class EffectiveSharingDto(
    val globalPaused: Boolean = false,
    val locationActive: Boolean = false,
    val usageActive: Boolean = false,
    val deviceStatusActive: Boolean = false
)

@Serializable
data class UpdateSharingSettingsResponse(
    val deviceId: String,
    val deviceStatus: String,
    val globalPaused: Boolean,
    val locationEnabled: Boolean,
    val usageEnabled: Boolean,
    val deviceStatusEnabled: Boolean,
    val locationPermissionState: String,
    val usagePermissionState: String,
    val backgroundRestricted: Boolean,
    val settingsVersion: Int,
    val updatedAtMs: Long,
    val effectiveSharing: EffectiveSharingDto? = null
)

@Serializable
data class SettingsConflictEnvelope(
    val error: ApiErrorBody,
    val authoritativeSettings: UpdateSharingSettingsResponse? = null
)

@Serializable
data class CreateInvitationRequest(
    val label: String? = null,
    val ttlMinutes: Int = 60
)

@Serializable
data class CreateInvitationResponse(
    val invitationId: String,
    val inviteCode: String,
    val qrPayload: String,
    val expiresAtMs: Long,
    val label: String? = null
)

@Serializable
data class InvitationsListResponse(
    val invitations: List<InvitationSummaryDto>
)

@Serializable
data class InvitationSummaryDto(
    val id: String,
    val label: String? = null,
    val expires_at_ms: Long,
    val used_at_ms: Long? = null,
    val used_by_device_id: String? = null,
    val revoked_at_ms: Long? = null,
    val created_at_ms: Long
)

@Serializable
data class AdminDevicesResponse(
    val devices: List<AdminDeviceSummaryDto>
)

@Serializable
data class AdminDeviceSummaryDto(
    val id: String,
    val display_name: String,
    val platform: String,
    val app_version: String,
    val status: String,
    val created_at_ms: Long,
    val last_seen_at_ms: Long? = null,
    val last_sync_at_ms: Long? = null,
    val revoked_at_ms: Long? = null,
    val global_paused: Int? = 0,
    val location_enabled: Int? = 0,
    val usage_enabled: Int? = 0,
    val device_status_enabled: Int? = 0,
    val location_permission_state: String? = "UNKNOWN",
    val usage_permission_state: String? = "UNKNOWN",
    val background_restricted: Int? = 0,
    val pending_deletion_count: Int? = 0
)

@Serializable
data class StorageQuotaResponse(
    val storage: StorageSummaryDto,
    val dailyQuotaUtc: DailyQuotaDto
)

@Serializable
data class StorageSummaryDto(
    val estimatedDbBytes: Long,
    val freeTierLimitBytes: Long,
    val softWarningBytes: Long,
    val warningActive: Boolean,
    val autoExpirationEnabled: Boolean
)

@Serializable
data class DailyQuotaDto(
    val dayUtc: String,
    val trackedRequestsToday: Int,
    val freeTierDailyRequestCap: Int,
    val trackedWritesToday: Int,
    val freeTierDailyWriteCap: Int
)

@Serializable
data class DeletionRequestDto(
    val scope: String,
    val category: String,
    val targetRecordId: String? = null,
    val rangeStartMs: Long? = null,
    val rangeEndMs: Long? = null
)

@Serializable
data class DeletionResponseDto(
    val commandId: String,
    val serverDeletedCount: Int,
    val deviceDeletionStatus: String,
    val localWipeGuaranteed: Boolean? = null
)

@Serializable
data class AdminDisconnectDeviceResponse(
    val deviceId: String,
    val status: String,
    val revokedAtMs: Long,
    val deletionResult: DeletionResponseDto? = null
)

@Serializable
data class DeviceRevokeResponse(
    val deviceId: String,
    val status: String,
    val revokedAtMs: Long
)
