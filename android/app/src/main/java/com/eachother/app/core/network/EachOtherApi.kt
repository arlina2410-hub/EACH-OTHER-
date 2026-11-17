package com.eachother.app.core.network

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path

interface EachOtherApi {
    @POST("/v1/auth/admin/register")
    suspend fun registerAdmin(@Body request: AdminRegisterRequest): AdminRegisterResponse

    @POST("/v1/auth/admin/challenge")
    suspend fun requestAdminChallenge(@Body request: AdminChallengeRequest): AdminChallengeResponse

    @POST("/v1/auth/admin/verify")
    suspend fun verifyAdminChallenge(@Body request: AdminVerifyRequest): AdminAuthResponse

    @POST("/v1/auth/admin/recover")
    suspend fun recoverAdmin(@Body request: AdminRecoverRequest): AdminAuthResponse

    @POST("/v1/auth/admin/devices/enroll")
    suspend fun enrollAdditionalAdminDevice(@Body request: AdminDeviceEnrollRequest): AdminAuthResponse

    @POST("/v1/auth/admin/recovery-codes/regenerate")
    suspend fun regenerateRecoveryCodes(@Body body: Map<String, String> = emptyMap()): RecoveryCodesResponse

    @POST("/v1/auth/refresh")
    suspend fun refreshToken(@Body request: RefreshRequest): Response<RefreshResponse>

    @POST("/v1/invitations/validate")
    suspend fun validateInvitation(@Body request: ValidateInvitationRequest): ValidateInvitationResponse

    @POST("/v1/devices/enroll")
    suspend fun enrollDevice(@Body request: EnrollDeviceRequest): EnrollDeviceResponse

    @PUT("/v1/devices/me/sharing-settings")
    suspend fun updateSharingSettings(@Body request: UpdateSharingSettingsRequest): UpdateSharingSettingsResponse

    @POST("/v1/devices/revoke")
    suspend fun revokeOwnDevice(): DeviceRevokeResponse

    @POST("/v1/devices/me/deletions")
    suspend fun deleteOwnServerData(@Body request: DeletionRequestDto): DeletionResponseDto

    @GET("/v1/admin/admin-devices")
    suspend fun listRegisteredAdminDevices(): RegisteredAdminDevicesResponse

    @POST("/v1/admin/admin-devices/invite")
    suspend fun createAdminPairingInvite(@Body request: AdminPairingInviteRequest): AdminPairingInviteResponse

    @POST("/v1/admin/admin-devices/{adminDeviceId}/revoke")
    suspend fun revokeAdminDevice(@Path("adminDeviceId") adminDeviceId: String): RevokeAdminDeviceResponse

    @GET("/v1/admin/devices")
    suspend fun listAdminDevices(): AdminDevicesResponse

    @POST("/v1/admin/invitations")
    suspend fun createInvitation(@Body request: CreateInvitationRequest): CreateInvitationResponse

    @GET("/v1/admin/invitations")
    suspend fun listInvitations(): InvitationsListResponse

    @POST("/v1/admin/invitations/{invitationId}/revoke")
    suspend fun revokeInvitation(@Path("invitationId") invitationId: String)

    @GET("/v1/admin/storage")
    suspend fun getStorageStatus(): StorageQuotaResponse

    @POST("/v1/admin/devices/{deviceId}/disconnect")
    suspend fun disconnectDevice(
        @Path("deviceId") deviceId: String,
        @Body body: Map<String, Boolean>
    )
}
