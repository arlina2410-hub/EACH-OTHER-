package com.eachother.app.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class AppRole {
    UNSELECTED,
    ADMIN,
    CONNECTED
}

/**
 * Remembers which Administrator account/device corresponds to this phone's Keystore key
 * across local Sign Out so the owner can sign back in with challenge-response without
 * losing their local hardware key identity. Cleared on app reinstall or recovery.
 */
@Serializable
data class LocalAdminIdentity(
    val email: String,
    val adminDeviceId: String,
    val groupName: String? = null,
    val activeKeyId: String = "primary_v1"
)

@Serializable
data class AuthSession(
    val role: AppRole = AppRole.UNSELECTED,
    val accountId: String? = null,
    val adminDeviceId: String? = null,
    val adminEmail: String? = null,
    val activeAdminKeyId: String? = null,
    val pendingAdminKeyId: String? = null,
    val deviceId: String? = null,
    val groupId: String? = null,
    val groupName: String? = null,
    val displayName: String? = null,
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val isRevoked: Boolean = false,
    val localAdminIdentity: LocalAdminIdentity? = null
) {
    val isAuthenticated: Boolean
        get() = !accessToken.isNullOrBlank() && !isRevoked && role != AppRole.UNSELECTED
}
