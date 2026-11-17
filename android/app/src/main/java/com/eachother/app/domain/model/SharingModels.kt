package com.eachother.app.domain.model

import kotlinx.serialization.Serializable

enum class SharingOverallStatus {
    ACTIVE,
    PAUSED,
    PERMISSION_REQUIRED,
    OFFLINE,
    WAITING_TO_SYNC,
    SYNCING,
    SYNC_ERROR,
    BACKGROUND_RESTRICTED,
    REVOKED
}

enum class DataFreshness {
    RECENT,
    DELAYED,
    STALE
}

@Serializable
data class SharingCategorySettings(
    val globalPaused: Boolean = false,
    val locationEnabled: Boolean = false,
    val persistentLocationFgsEnabled: Boolean = false,
    val usageEnabled: Boolean = false,
    val deviceStatusEnabled: Boolean = false,
    val settingsVersion: Int = 1
)

data class PermissionSnapshot(
    val foregroundLocationGranted: Boolean = false,
    val backgroundLocationGranted: Boolean = false,
    val usageAccessGranted: Boolean = false,
    val notificationsGranted: Boolean = true,
    val backgroundExecutionRestricted: Boolean = false
) {
    fun locationPermissionStateWire(): String = when {
        backgroundLocationGranted -> "BACKGROUND_GRANTED"
        foregroundLocationGranted -> "FOREGROUND_ONLY"
        else -> "DENIED"
    }

    fun usagePermissionStateWire(): String =
        if (usageAccessGranted) "GRANTED" else "DENIED"
}
