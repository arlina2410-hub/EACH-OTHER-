package com.eachother.app.ui.connected

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eachother.app.data.repository.AuthRepository
import com.eachother.app.data.repository.RepoResult
import com.eachother.app.domain.model.AuthSession
import com.eachother.app.domain.model.PermissionSnapshot
import com.eachother.app.domain.model.SharingCategorySettings
import com.eachother.app.platform.location.LocationSharingService
import com.eachother.app.platform.permissions.PermissionChecker
import com.eachother.app.ui.components.BadgeTone
import com.eachother.app.ui.components.InfoBanner
import com.eachother.app.ui.components.QrCodeHelper
import com.eachother.app.ui.components.StatusBadge
import kotlinx.coroutines.launch

private enum class DisclosureCategory {
    LOCATION,
    USAGE,
    DEVICE_STATUS
}

@Composable
fun JoinGroupScreen(
    authRepository: AuthRepository,
    onEnrolled: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var inviteCode by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf(Build.MODEL.take(40).ifBlank { "My Phone" }) }
    var validatedGroupName by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val qrPhotoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            val decoded = QrCodeHelper.decodeQrFromUri(context, uri)
            if (decoded != null) {
                inviteCode = QrCodeHelper.extractCodeFromPayload(decoded)
                validatedGroupName = null
                error = null
            } else {
                error = "No invite QR code was detected in the selected image."
            }
        }
    }

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Join Trusted Group", style = MaterialTheme.typography.headlineMedium)
            InfoBanner(
                title = "One group & consent-first defaults",
                body = "This phone can belong to only one Administrator group at a time. Joining does NOT turn on Location, App Usage, or Device Status automatically — every category starts OFF."
            )

            OutlinedTextField(
                value = inviteCode,
                onValueChange = { inviteCode = it; validatedGroupName = null },
                label = { Text("12-character invite code") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedButton(
                onClick = {
                    qrPhotoPicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Import Invite QR Image (No Camera Needed)")
            }

            OutlinedTextField(
                value = displayName,
                onValueChange = { displayName = it },
                label = { Text("Device name shown to Administrator") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            if (validatedGroupName != null) {
                StatusBadge(
                    label = "Valid invite for: $validatedGroupName",
                    tone = BadgeTone.POSITIVE
                )
            }

            if (error != null) {
                StatusBadge(label = error!!, tone = BadgeTone.ERROR)
            }

            if (validatedGroupName == null) {
                Button(
                    onClick = {
                        scope.launch {
                            loading = true
                            error = null
                            when (val res = authRepository.validateInvitation(inviteCode)) {
                                is RepoResult.Success -> validatedGroupName = res.value.groupName
                                is RepoResult.Failure -> error = res.message
                            }
                            loading = false
                        }
                    },
                    enabled = !loading && inviteCode.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (loading) CircularProgressIndicator() else Text("Verify Invitation")
                }
            } else {
                Button(
                    onClick = {
                        scope.launch {
                            loading = true
                            error = null
                            when (val res = authRepository.enrollDevice(inviteCode, displayName)) {
                                is RepoResult.Success -> onEnrolled()
                                is RepoResult.Failure -> error = res.message
                            }
                            loading = false
                        }
                    },
                    enabled = !loading && displayName.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (loading) CircularProgressIndicator() else Text("Accept & Join Group")
                }
            }

            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text("Back")
            }
        }
    }
}

@Composable
fun ConnectedHomeScreen(
    session: AuthSession,
    permissionChecker: PermissionChecker,
    authRepository: AuthRepository,
    onDisconnected: () -> Unit
) {
    val context = LocalContext.current
    val persistedSettingsState by authRepository.sharingSettingsFlow.collectAsStateWithLifecycle(
        initialValue = null
    )
    var permissions by remember { mutableStateOf(permissionChecker.snapshot()) }
    var pendingDisclosure by remember { mutableStateOf<DisclosureCategory?>(null) }
    var pendingUsageEnableAfterSettings by remember { mutableStateOf(false) }
    var showBackgroundGuideDialog by remember { mutableStateOf(false) }
    var showDisconnectDialog by remember { mutableStateOf(false) }
    var showForceLocalDisconnectDialog by remember { mutableStateOf(false) }
    var showDeleteServerDialog by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var statusTone by remember { mutableStateOf(BadgeTone.NEUTRAL) }
    var hasInitialSyncCompleted by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun syncServiceState(settings: SharingCategorySettings, snap: PermissionSnapshot) {
        val shouldRunFgs = !settings.globalPaused &&
            settings.locationEnabled &&
            settings.persistentLocationFgsEnabled &&
            snap.foregroundLocationGranted

        val serviceIntent = Intent(context, LocationSharingService::class.java).apply {
            putExtra(LocationSharingService.EXTRA_GROUP_NAME, session.groupName ?: "your group")
        }
        if (shouldRunFgs) {
            ContextCompat.startForegroundService(context, serviceIntent)
        } else {
            context.stopService(serviceIntent)
        }
    }

    fun applyAndSyncSettings(next: SharingCategorySettings, snap: PermissionSnapshot) {
        syncServiceState(next, snap)
        scope.launch {
            when (val res = authRepository.reconcileAndSyncSharingSettings(next, snap)) {
                is RepoResult.Success -> {
                    if (statusTone == BadgeTone.ERROR) {
                        statusMessage = null
                        statusTone = BadgeTone.NEUTRAL
                    }
                }
                is RepoResult.Failure -> {
                    if (res.code == "DEVICE_REVOKED") {
                        context.stopService(Intent(context, LocationSharingService::class.java))
                        onDisconnected()
                    } else {
                        statusTone = BadgeTone.ERROR
                        statusMessage = "Server sync failed — saved locally only (${res.message})"
                    }
                }
            }
        }
    }

    // Wait for real DataStore state before rendering or syncing to avoid overwriting saved preferences
    val persistedSettings = persistedSettingsState
    if (persistedSettings == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    // Re-read real DataStore + fresh OS permissions whenever returning from System Settings dialogs
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        scope.launch {
            val updatedSnap = permissionChecker.snapshot()
            permissions = updatedSnap
            val stored = authRepository.getStoredSharingSettings()

            val effectiveDesired = if (pendingUsageEnableAfterSettings) {
                pendingUsageEnableAfterSettings = false
                if (updatedSnap.usageAccessGranted) {
                    statusTone = BadgeTone.NEUTRAL
                    statusMessage = "Usage Access granted. Daily App Usage consent enabled."
                    stored.copy(usageEnabled = true)
                } else {
                    statusTone = BadgeTone.WARNING
                    statusMessage = "Usage Access was not granted. Daily App Usage remains OFF."
                    stored.copy(usageEnabled = false)
                }
            } else {
                val permissionLost =
                    (stored.locationEnabled && !updatedSnap.foregroundLocationGranted) ||
                        (stored.usageEnabled && !updatedSnap.usageAccessGranted)
                if (permissionLost) {
                    statusTone = BadgeTone.WARNING
                    statusMessage = "An Android permission was revoked in Settings. Related sharing was turned OFF."
                }
                stored
            }

            syncServiceState(effectiveDesired, updatedSnap)
            when (val res = authRepository.reconcileAndSyncSharingSettings(effectiveDesired, updatedSnap)) {
                is RepoResult.Success -> hasInitialSyncCompleted = true
                is RepoResult.Failure -> {
                    if (res.code == "DEVICE_REVOKED") {
                        context.stopService(Intent(context, LocationSharingService::class.java))
                        onDisconnected()
                    } else {
                        statusTone = BadgeTone.ERROR
                        statusMessage = "Server sync failed — saved locally only (${res.message})"
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        if (!hasInitialSyncCompleted) {
            val snap = permissionChecker.snapshot()
            permissions = snap
            val stored = authRepository.getStoredSharingSettings()
            applyAndSyncSettings(stored, snap)
            hasInitialSyncCompleted = true
        }
    }

    val fgLocationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val updatedSnap = permissionChecker.snapshot()
        permissions = updatedSnap
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true ||
            updatedSnap.foregroundLocationGranted

        scope.launch {
            val stored = authRepository.getStoredSharingSettings()
            if (granted) {
                applyAndSyncSettings(stored.copy(locationEnabled = true), updatedSnap)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !updatedSnap.backgroundLocationGranted) {
                    showBackgroundGuideDialog = true
                }
            } else {
                statusTone = BadgeTone.WARNING
                statusMessage = "Location permission was denied. Location sharing remains OFF."
                applyAndSyncSettings(stored.copy(locationEnabled = false, persistentLocationFgsEnabled = false), updatedSnap)
            }
        }
    }

    val bgLocationQLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        val updatedSnap = permissionChecker.snapshot()
        permissions = updatedSnap
        scope.launch {
            applyAndSyncSettings(authRepository.getStoredSharingSettings(), updatedSnap)
        }
    }

    val notificationsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        val updatedSnap = permissionChecker.snapshot()
        permissions = updatedSnap
        scope.launch {
            val stored = authRepository.getStoredSharingSettings()
            if (granted || updatedSnap.notificationsGranted) {
                applyAndSyncSettings(stored.copy(persistentLocationFgsEnabled = true), updatedSnap)
            } else {
                statusTone = BadgeTone.WARNING
                statusMessage = "Notifications permission is required before starting visible Foreground Location mode."
            }
        }
    }

    fun openAppDetailsSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null)
        )
        context.startActivity(intent)
    }

    fun openUsageAccessSettings() {
        context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    }

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(session.groupName ?: "Connected Sharing", style = MaterialTheme.typography.headlineMedium)

            val anyCategoryOn = persistedSettings.locationEnabled ||
                persistedSettings.usageEnabled ||
                persistedSettings.deviceStatusEnabled

            StatusBadge(
                label = when {
                    session.isRevoked -> "Disconnected / Revoked"
                    persistedSettings.globalPaused -> "Sharing Paused by You"
                    !anyCategoryOn -> "All Categories Off (Not Sharing)"
                    else -> "Sharing permissions enabled — reporting not yet implemented"
                },
                tone = when {
                    session.isRevoked -> BadgeTone.ERROR
                    persistedSettings.globalPaused -> BadgeTone.NEUTRAL
                    !anyCategoryOn -> BadgeTone.NEUTRAL
                    else -> BadgeTone.POSITIVE
                }
            )

            if (permissions.backgroundExecutionRestricted) {
                InfoBanner(
                    title = "Background execution may be restricted",
                    body = "Android battery optimization may delay ~15-minute best-effort updates while the screen is off."
                )
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Pause All Sharing", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Immediately pauses all collection and uploads without leaving the group.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Switch(
                        checked = persistedSettings.globalPaused,
                        onCheckedChange = { paused ->
                            applyAndSyncSettings(persistedSettings.copy(globalPaused = paused), permissions)
                        }
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Location Sharing", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Permission: ${permissions.locationPermissionStateWire()} • Best-effort ~15 min updates (not guaranteed continuous).",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Switch(
                            checked = persistedSettings.locationEnabled && permissions.foregroundLocationGranted,
                            onCheckedChange = { wantEnable ->
                                if (wantEnable) {
                                    pendingDisclosure = DisclosureCategory.LOCATION
                                } else {
                                    applyAndSyncSettings(
                                        persistedSettings.copy(
                                            locationEnabled = false,
                                            persistentLocationFgsEnabled = false
                                        ),
                                        permissions
                                    )
                                }
                            }
                        )
                    }

                    if (persistedSettings.locationEnabled && permissions.foregroundLocationGranted) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !permissions.backgroundLocationGranted) {
                            OutlinedButton(
                                onClick = { showBackgroundGuideDialog = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Enable Background Location (Optional 2-Step)")
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Visible Foreground Location Mode", style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "Optional user-controlled mode with a persistent notification and Stop button (location capture begins in Phase 4).",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Switch(
                                checked = persistedSettings.persistentLocationFgsEnabled && !persistedSettings.globalPaused,
                                onCheckedChange = { enableFgs ->
                                    if (enableFgs && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !permissions.notificationsGranted) {
                                        notificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    } else {
                                        applyAndSyncSettings(
                                            persistedSettings.copy(persistentLocationFgsEnabled = enableFgs),
                                            permissions
                                        )
                                    }
                                }
                            )
                        }
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Daily App Usage Summaries", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Usage Access: ${permissions.usagePermissionStateWire()} • Daily app totals only (no screen contents or messages).",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Switch(
                            checked = persistedSettings.usageEnabled && permissions.usageAccessGranted,
                            onCheckedChange = { wantEnable ->
                                if (wantEnable) {
                                    pendingDisclosure = DisclosureCategory.USAGE
                                } else {
                                    applyAndSyncSettings(persistedSettings.copy(usageEnabled = false), permissions)
                                }
                            }
                        )
                    }
                    if (!permissions.usageAccessGranted) {
                        OutlinedButton(
                            onClick = { pendingDisclosure = DisclosureCategory.USAGE },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Review Disclosure & Grant Usage Access")
                        }
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Basic Device Status", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Shares battery %, charging state, and Wi-Fi/Cellular type.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(
                        checked = persistedSettings.deviceStatusEnabled,
                        onCheckedChange = { wantEnable ->
                            if (wantEnable) {
                                pendingDisclosure = DisclosureCategory.DEVICE_STATUS
                            } else {
                                applyAndSyncSettings(persistedSettings.copy(deviceStatusEnabled = false), permissions)
                            }
                        }
                    )
                }
            }

            statusMessage?.let {
                StatusBadge(label = it, tone = statusTone)
            }

            OutlinedButton(
                onClick = { showDeleteServerDialog = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Delete My Synchronized History")
            }

            OutlinedButton(
                onClick = { showDisconnectDialog = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Disconnect from Group")
            }
        }
    }

    pendingDisclosure?.let { category ->
        AlertDialog(
            onDismissRequest = { pendingDisclosure = null },
            title = {
                Text(
                    when (category) {
                        DisclosureCategory.LOCATION -> "Location Sharing Disclosure"
                        DisclosureCategory.USAGE -> "Daily App Usage Disclosure"
                        DisclosureCategory.DEVICE_STATUS -> "Basic Device Status Disclosure"
                    }
                )
            },
            text = {
                Text(
                    when (category) {
                        DisclosureCategory.LOCATION ->
                            "If you allow Location access, EACH OTHER periodically records GPS/network coordinates (best-effort every ~15 minutes via Android WorkManager, or continuously when you turn on the visible Foreground Notification mode) and shares them only with ${session.groupName ?: "your Administrator"}. You can pause, disable, or delete history at any time."
                        DisclosureCategory.USAGE ->
                            "If you grant Android Usage Access, EACH OTHER shares daily total foreground duration per app with ${session.groupName ?: "your Administrator"}. It NEVER reads messages, keystrokes, passwords, notifications, or screen contents."
                        DisclosureCategory.DEVICE_STATUS ->
                            "Enabling Basic Device Status shares your battery percentage, charging state, and whether you are on Wi-Fi or Cellular data with ${session.groupName ?: "your Administrator"}."
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDisclosure = null
                    when (category) {
                        DisclosureCategory.LOCATION -> {
                            if (permissions.foregroundLocationGranted) {
                                applyAndSyncSettings(persistedSettings.copy(locationEnabled = true), permissions)
                            } else {
                                fgLocationLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                        Manifest.permission.ACCESS_COARSE_LOCATION
                                    )
                                )
                            }
                        }
                        DisclosureCategory.USAGE -> {
                            if (permissions.usageAccessGranted) {
                                applyAndSyncSettings(persistedSettings.copy(usageEnabled = true), permissions)
                            } else {
                                pendingUsageEnableAfterSettings = true
                                openUsageAccessSettings()
                            }
                        }
                        DisclosureCategory.DEVICE_STATUS -> {
                            applyAndSyncSettings(persistedSettings.copy(deviceStatusEnabled = true), permissions)
                        }
                    }
                }) {
                    Text("I Understand & Enable")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDisclosure = null }) { Text("Keep Off") }
            }
        )
    }

    if (showBackgroundGuideDialog) {
        AlertDialog(
            onDismissRequest = { showBackgroundGuideDialog = false },
            title = { Text("Optional Background Location (Step 2 of 2)") },
            text = {
                Text(
                    "Foreground location works while EACH OTHER is open. For ~15-minute WorkManager updates when the app is closed, Android requires choosing 'Allow all the time' in Permissions -> Location. Only enable this if you want background location updates."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showBackgroundGuideDialog = false
                    if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
                        bgLocationQLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    } else {
                        openAppDetailsSettings()
                    }
                }) {
                    Text(if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) "Request Background Access" else "Open App Settings")
                }
            },
            dismissButton = {
                TextButton(onClick = { showBackgroundGuideDialog = false }) { Text("Not Now") }
            }
        )
    }

    if (showDeleteServerDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteServerDialog = false },
            title = { Text("Delete Your Server-Synchronized History?") },
            text = {
                Text("This permanently deletes all reports uploaded by this device from the server and creates deletion tombstones so pending uploads cannot re-upload deleted items.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteServerDialog = false
                    scope.launch {
                        when (val res = authRepository.deleteOwnServerHistory()) {
                            is RepoResult.Success -> {
                                statusTone = BadgeTone.POSITIVE
                                statusMessage = "Deleted ${res.value.serverDeletedCount} server records."
                            }
                            is RepoResult.Failure -> {
                                statusTone = BadgeTone.ERROR
                                statusMessage = res.message
                            }
                        }
                    }
                }) {
                    Text("Delete History")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteServerDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showDisconnectDialog) {
        AlertDialog(
            onDismissRequest = { showDisconnectDialog = false },
            title = { Text("Disconnect from ${session.groupName ?: "Group"}?") },
            text = {
                Text(
                    "Disconnecting stops all sharing and background uploads immediately and revokes this device's access token. Previously synchronized reports remain visible to the Administrator unless you run 'Delete My Synchronized History' first."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDisconnectDialog = false
                    context.stopService(Intent(context, LocationSharingService::class.java))
                    scope.launch {
                        when (val res = authRepository.disconnectOwnDevice()) {
                            is RepoResult.Success -> onDisconnected()
                            is RepoResult.Failure -> {
                                statusTone = BadgeTone.ERROR
                                statusMessage = res.message
                                showForceLocalDisconnectDialog = true
                            }
                        }
                    }
                }) {
                    Text("Disconnect Now")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDisconnectDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showForceLocalDisconnectDialog) {
        AlertDialog(
            onDismissRequest = { showForceLocalDisconnectDialog = false },
            title = { Text("Server Unreachable — Revocation Unverified") },
            text = {
                Text("Could not reach the server to revoke this device immediately (${statusMessage ?: "offline"}). You can clear local credentials now to stop local sharing, though server-side revocation could not be confirmed until online.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showForceLocalDisconnectDialog = false
                    scope.launch {
                        authRepository.disconnectLocallyWithoutServer()
                        onDisconnected()
                    }
                }) {
                    Text("Clear Local Credentials")
                }
            },
            dismissButton = {
                TextButton(onClick = { showForceLocalDisconnectDialog = false }) { Text("Cancel") }
            }
        )
    }
}
