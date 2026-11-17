package com.eachother.app.ui.admin

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.eachother.app.core.network.AdminDeviceSummaryDto
import com.eachother.app.core.network.AdminPairingInviteResponse
import com.eachother.app.core.network.CreateInvitationResponse
import com.eachother.app.core.network.InvitationSummaryDto
import com.eachother.app.core.network.RegisteredAdminDeviceDto
import com.eachother.app.core.network.StorageQuotaResponse
import com.eachother.app.data.repository.AuthRepository
import com.eachother.app.data.repository.RepoResult
import com.eachother.app.domain.model.AuthSession
import com.eachother.app.ui.components.BadgeTone
import com.eachother.app.ui.components.InfoBanner
import com.eachother.app.ui.components.QrCodeHelper
import com.eachother.app.ui.components.StatusBadge
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private fun formatEpoch(ms: Long?): String {
    if (ms == null) return "Never"
    return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(ms))
}

@Composable
fun AdminAuthScreen(
    session: AuthSession,
    authRepository: AuthRepository,
    onAuthenticated: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val savedIdentity = session.localAdminIdentity
    var tabIndex by remember { mutableIntStateOf(if (savedIdentity != null) 1 else 0) }
    var email by remember { mutableStateOf(savedIdentity?.email.orEmpty()) }
    var adminDeviceId by remember { mutableStateOf(savedIdentity?.adminDeviceId.orEmpty()) }
    var groupName by remember { mutableStateOf("Private Group") }
    var deviceName by remember { mutableStateOf(Build.MODEL.take(40).ifBlank { "Administrator Phone" }) }
    var recoveryCode by remember { mutableStateOf("") }
    var pairingCode by remember { mutableStateOf("") }
    var pairEmailHint by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var generatedCodes by remember { mutableStateOf<List<String>?>(null) }
    var recoveryCompletedRevokedCount by remember { mutableStateOf<Int?>(null) }
    var savedConfirmed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val pairingQrPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            val decoded = QrCodeHelper.decodeQrFromUri(context, uri)
            if (decoded != null) {
                pairingCode = QrCodeHelper.extractCodeFromPayload(decoded)
                errorMessage = null
            } else {
                errorMessage = "Could not find a valid QR code in that image."
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (generatedCodes != null) {
                Text("Save Your 10 Recovery Codes", style = MaterialTheme.typography.headlineMedium)
                InfoBanner(
                    title = "Shown only once — store outside this phone",
                    body = "Your Administrator sign-in key is stored inside this phone's hardware Android Keystore and cannot be exported. If you lose this phone or clear app data, these 10 one-time codes are the only way to regain control of your group."
                )
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        generatedCodes!!.forEachIndexed { idx, code ->
                            Text("${idx + 1}. $code", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
                OutlinedButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(generatedCodes!!.joinToString("\n")))
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Copy All 10 Codes to Clipboard")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = savedConfirmed, onCheckedChange = { savedConfirmed = it })
                    Text("I have saved these 10 codes somewhere safe outside this phone.")
                }
                Button(
                    onClick = onAuthenticated,
                    enabled = savedConfirmed,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Open Administrator Dashboard")
                }
                return@Scaffold
            }

            Text("Administrator Access", style = MaterialTheme.typography.headlineMedium)
            ScrollableTabRow(selectedTabIndex = tabIndex, edgePadding = 0.dp) {
                Tab(selected = tabIndex == 0, onClick = { tabIndex = 0; errorMessage = null }, text = { Text("Create Group") })
                Tab(selected = tabIndex == 1, onClick = { tabIndex = 1; errorMessage = null }, text = { Text("Sign In") })
                Tab(selected = tabIndex == 2, onClick = { tabIndex = 2; errorMessage = null }, text = { Text("Pair Device") })
                Tab(selected = tabIndex == 3, onClick = { tabIndex = 3; errorMessage = null }, text = { Text("Recover") })
            }

            when (tabIndex) {
                0 -> {
                    InfoBanner(
                        title = "Hardware-key security (No password)",
                        body = "This phone creates an unexportable ECDSA P-256 key inside Android Keystore and gives you 10 one-time recovery codes."
                    )
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text("Account email identifier") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = groupName,
                        onValueChange = { groupName = it },
                        label = { Text("Private group name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = deviceName,
                        onValueChange = { deviceName = it },
                        label = { Text("This phone's label") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                1 -> {
                    InfoBanner(
                        title = "Challenge-Response Keystore Sign-In",
                        body = "Signs a short-lived server challenge using this phone's Android Keystore key. Losing this phone or clearing app data requires Recover or Pair Device."
                    )
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text("Account email identifier") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = adminDeviceId,
                        onValueChange = { adminDeviceId = it },
                        label = { Text("Registered Admin Device ID") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                2 -> {
                    InfoBanner(
                        title = "Add another Administrator device",
                        body = "On an already signed-in Admin phone, create a 15-minute pairing code. Enter it here or import a pairing QR screenshot (no camera access needed)."
                    )
                    OutlinedTextField(
                        value = pairingCode,
                        onValueChange = { pairingCode = it },
                        label = { Text("12-character pairing code") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedButton(
                        onClick = {
                            pairingQrPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Import Pairing QR Image")
                    }
                    OutlinedTextField(
                        value = deviceName,
                        onValueChange = { deviceName = it },
                        label = { Text("New Admin device label") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = pairEmailHint,
                        onValueChange = { pairEmailHint = it },
                        label = { Text("Account email (optional, remembered locally)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                else -> {
                    InfoBanner(
                        title = "Account Recovery Revokes Prior Devices",
                        body = "Using a one-time recovery code registers a new Keystore key on this phone and immediately revokes ALL previously registered Administrator devices and active sessions."
                    )
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text("Account email identifier") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = recoveryCode,
                        onValueChange = { recoveryCode = it },
                        label = { Text("One-time recovery code (XXXX-XXXX-XXXX)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = deviceName,
                        onValueChange = { deviceName = it },
                        label = { Text("Replacement phone label") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            if (errorMessage != null) {
                StatusBadge(label = errorMessage!!, tone = BadgeTone.ERROR)
            }

            val canSubmit = when (tabIndex) {
                0 -> email.isNotBlank() && groupName.isNotBlank() && deviceName.isNotBlank()
                1 -> email.isNotBlank() && adminDeviceId.isNotBlank()
                2 -> pairingCode.isNotBlank() && deviceName.isNotBlank()
                else -> email.isNotBlank() && recoveryCode.isNotBlank() && deviceName.isNotBlank()
            }

            Button(
                onClick = {
                    scope.launch {
                        isLoading = true
                        errorMessage = null
                        when (tabIndex) {
                            0 -> when (val res = authRepository.registerAdmin(email, groupName, deviceName)) {
                                is RepoResult.Success -> generatedCodes = res.value
                                is RepoResult.Failure -> errorMessage = res.message
                            }
                            1 -> when (val res = authRepository.signInAdminWithKeystore(email, adminDeviceId)) {
                                is RepoResult.Success -> onAuthenticated()
                                is RepoResult.Failure -> errorMessage = res.message
                            }
                            2 -> when (val res = authRepository.enrollAdditionalAdminDevice(pairingCode, deviceName, pairEmailHint)) {
                                is RepoResult.Success -> onAuthenticated()
                                is RepoResult.Failure -> errorMessage = res.message
                            }
                            else -> when (val res = authRepository.recoverAdmin(email, recoveryCode, deviceName)) {
                                is RepoResult.Success -> recoveryCompletedRevokedCount = res.value
                                is RepoResult.Failure -> errorMessage = res.message
                            }
                        }
                        isLoading = false
                    }
                },
                enabled = !isLoading && canSubmit,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.height(18.dp))
                } else {
                    Text(
                        when (tabIndex) {
                            0 -> "Create Group & Keystore Key"
                            1 -> "Sign Challenge & Sign In"
                            2 -> "Enroll This Admin Device"
                            else -> "Recover & Revoke Prior Devices"
                        }
                    )
                }
            }

            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text("Back")
            }
        }
    }

    recoveryCompletedRevokedCount?.let { revokedCount ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Account Recovered on This Phone") },
            text = {
                Text(
                    "Registered your replacement Android Keystore key and revoked $revokedCount prior Administrator device(s) and all previous sessions."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    recoveryCompletedRevokedCount = null
                    onAuthenticated()
                }) {
                    Text("Continue")
                }
            }
        )
    }
}

@Composable
fun AdminDashboardScreen(
    session: AuthSession,
    authRepository: AuthRepository,
    onSignedOut: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    var devices by remember { mutableStateOf<List<AdminDeviceSummaryDto>>(emptyList()) }
    var invitations by remember { mutableStateOf<List<InvitationSummaryDto>>(emptyList()) }
    var adminDevices by remember { mutableStateOf<List<RegisteredAdminDeviceDto>>(emptyList()) }
    var storage by remember { mutableStateOf<StorageQuotaResponse?>(null) }
    var latestInvite by remember { mutableStateOf<CreateInvitationResponse?>(null) }
    var latestPairingInvite by remember { mutableStateOf<AdminPairingInviteResponse?>(null) }
    var regeneratedCodes by remember { mutableStateOf<List<String>?>(null) }
    var pendingDisconnectDevice by remember { mutableStateOf<AdminDeviceSummaryDto?>(null) }
    var deleteServerDataOnDisconnect by remember { mutableStateOf(false) }
    var pendingRevokeAdminDevice by remember { mutableStateOf<RegisteredAdminDeviceDto?>(null) }
    var showRegenerateConfirm by remember { mutableStateOf(false) }
    var inviteLabel by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var infoNotice by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun refreshAll() {
        loading = true
        error = null
        when (val dRes = authRepository.listAdminDevices()) {
            is RepoResult.Success -> devices = dRes.value.devices
            is RepoResult.Failure -> error = dRes.message
        }
        when (val iRes = authRepository.listInvitations()) {
            is RepoResult.Success -> invitations = iRes.value.invitations
            is RepoResult.Failure -> if (error == null) error = iRes.message
        }
        when (val aRes = authRepository.listRegisteredAdminDevices()) {
            is RepoResult.Success -> adminDevices = aRes.value.adminDevices
            is RepoResult.Failure -> if (error == null) error = aRes.message
        }
        when (val sRes = authRepository.getStorageStatus()) {
            is RepoResult.Success -> storage = sRes.value
            is RepoResult.Failure -> if (error == null) error = sRes.message
        }
        loading = false
    }

    LaunchedEffect(Unit) {
        refreshAll()
    }

    Scaffold { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(session.groupName ?: "Administrator Group", style = MaterialTheme.typography.headlineMedium)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Connected devices share only categories the owner explicitly turns on after reviewing Android disclosures.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (infoNotice != null) {
                item { StatusBadge(label = infoNotice!!, tone = BadgeTone.POSITIVE) }
            }
            if (error != null) {
                item { StatusBadge(label = error!!, tone = BadgeTone.ERROR) }
            }

            storage?.let { sq ->
                item {
                    val mbUsed = sq.storage.estimatedDbBytes / (1024.0 * 1024.0)
                    val mbLimit = sq.storage.freeTierLimitBytes / (1024.0 * 1024.0)
                    InfoBanner(
                        title = if (sq.storage.warningActive) "Storage Warning" else "Free-Tier Storage & Quota",
                        body = String.format(
                            "Database: %.2f MB / %.0f MB (auto-expiration off). UTC requests today: %d / %d.",
                            mbUsed,
                            mbLimit,
                            sq.dailyQuotaUtc.trackedRequestsToday,
                            sq.dailyQuotaUtc.freeTierDailyRequestCap
                        )
                    )
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Invite a Connected Device (60 min, single-use)", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(
                            value = inviteLabel,
                            onValueChange = { inviteLabel = it },
                            label = { Text("Optional label (e.g. Moto G57 Power)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            onClick = {
                                scope.launch {
                                    when (val res = authRepository.createInvitation(inviteLabel)) {
                                        is RepoResult.Success -> {
                                            latestInvite = res.value
                                            inviteLabel = ""
                                            refreshAll()
                                        }
                                        is RepoResult.Failure -> error = res.message
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Generate 12-Char Invite & QR")
                        }
                        latestInvite?.let { inv ->
                            val qrBitmap = remember(inv.qrPayload) {
                                QrCodeHelper.generateQrBitmap(inv.qrPayload, 420)
                            }
                            StatusBadge(label = "Invite Code: ${inv.inviteCode}", tone = BadgeTone.POSITIVE)
                            OutlinedButton(
                                onClick = {
                                    clipboard.setText(AnnotatedString(inv.inviteCode))
                                    infoNotice = "Invite code copied."
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Copy Invite Code")
                            }
                            if (qrBitmap != null) {
                                Image(
                                    bitmap = qrBitmap.asImageBitmap(),
                                    contentDescription = "Invitation QR Code",
                                    modifier = Modifier
                                        .size(180.dp)
                                        .align(Alignment.CenterHorizontally)
                                )
                            }
                            Text(
                                "Share the code or a screenshot of this QR code. Expires: ${formatEpoch(inv.expiresAtMs)}.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }

                        if (invitations.isNotEmpty()) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                            Text("Recent Invitations", style = MaterialTheme.typography.titleSmall)
                            invitations.take(5).forEach { inv ->
                                val status = when {
                                    inv.revoked_at_ms != null -> "REVOKED"
                                    inv.used_at_ms != null -> "USED"
                                    inv.expires_at_ms <= System.currentTimeMillis() -> "EXPIRED"
                                    else -> "ACTIVE"
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(inv.label ?: "Unlabeled invite", style = MaterialTheme.typography.bodyMedium)
                                        Text("$status • Exp ${formatEpoch(inv.expires_at_ms)}", style = MaterialTheme.typography.bodySmall)
                                    }
                                    if (status == "ACTIVE") {
                                        TextButton(onClick = {
                                            scope.launch {
                                                when (val r = authRepository.revokeInvitation(inv.id)) {
                                                    is RepoResult.Success -> refreshAll()
                                                    is RepoResult.Failure -> error = r.message
                                                }
                                            }
                                        }) {
                                            Text("Revoke")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item {
                Text("Connected Devices (${devices.size})", style = MaterialTheme.typography.titleMedium)
            }

            if (loading) {
                item { CircularProgressIndicator() }
            } else if (devices.isEmpty()) {
                item {
                    InfoBanner(
                        title = "No connected devices yet",
                        body = "Generate a single-use invite above and share it with the person joining your group."
                    )
                }
            } else {
                items(devices, key = { it.id }) { dev ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(dev.display_name, style = MaterialTheme.typography.titleMedium)
                                StatusBadge(
                                    label = dev.status,
                                    tone = when (dev.status) {
                                        "ACTIVE" -> BadgeTone.POSITIVE
                                        "PAUSED" -> BadgeTone.WARNING
                                        else -> BadgeTone.ERROR
                                    }
                                )
                            }
                            Text(
                                "Location: ${if (dev.location_enabled == 1) "ON" else "OFF"} (${dev.location_permission_state ?: "UNKNOWN"})",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                "Daily App Usage: ${if (dev.usage_enabled == 1) "ON" else "OFF"} (${dev.usage_permission_state ?: "UNKNOWN"})",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                "Basic Device Status: ${if (dev.device_status_enabled == 1) "ON" else "OFF"} • BG Restricted: ${if (dev.background_restricted == 1) "Yes" else "No"}",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                "Last seen: ${formatEpoch(dev.last_seen_at_ms)} • Last sync: ${formatEpoch(dev.last_sync_at_ms)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (dev.status != "REVOKED") {
                                OutlinedButton(
                                    onClick = {
                                        deleteServerDataOnDisconnect = false
                                        pendingDisconnectDevice = dev
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Disconnect Connected Device")
                                }
                            }
                        }
                    }
                }
            }

            item {
                Text("Administrator Devices & Recovery", style = MaterialTheme.typography.titleMedium)
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Each Administrator phone uses its own unexportable Keystore key. You can explicitly pair another phone, revoke lost phones, or regenerate your 10 recovery codes.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Button(
                            onClick = {
                                scope.launch {
                                    when (val res = authRepository.createAdminPairingInvite()) {
                                        is RepoResult.Success -> latestPairingInvite = res.value
                                        is RepoResult.Failure -> error = res.message
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Pair Another Admin Device (15 min)")
                        }
                        latestPairingInvite?.let { pair ->
                            val qr = remember(pair.qrPayload) { QrCodeHelper.generateQrBitmap(pair.qrPayload, 360) }
                            StatusBadge(label = "Pairing Code: ${pair.pairingCode}", tone = BadgeTone.POSITIVE)
                            OutlinedButton(
                                onClick = {
                                    clipboard.setText(AnnotatedString(pair.pairingCode))
                                    infoNotice = "Pairing code copied."
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Copy Pairing Code")
                            }
                            if (qr != null) {
                                Image(
                                    bitmap = qr.asImageBitmap(),
                                    contentDescription = "Admin Pairing QR",
                                    modifier = Modifier
                                        .size(160.dp)
                                        .align(Alignment.CenterHorizontally)
                                )
                            }
                        }

                        adminDevices.forEach { ad ->
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    val isCurrent = ad.id == session.adminDeviceId
                                    Text(
                                        "${ad.device_name}${if (isCurrent) " (This Phone)" else ""}",
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                    Text(
                                        "${ad.status} • Registered ${formatEpoch(ad.created_at_ms)} • Last auth ${formatEpoch(ad.last_auth_at_ms)}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                if (ad.status == "ACTIVE") {
                                    TextButton(onClick = { pendingRevokeAdminDevice = ad }) {
                                        Text("Revoke")
                                    }
                                }
                            }
                        }

                        OutlinedButton(
                            onClick = { showRegenerateConfirm = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Regenerate 10 Recovery Codes")
                        }
                    }
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { scope.launch { refreshAll() } }) {
                        Text("Refresh")
                    }
                    OutlinedButton(onClick = {
                        scope.launch {
                            authRepository.signOutLocally()
                            onSignedOut()
                        }
                    }) {
                        Text("Sign Out")
                    }
                }
            }
        }
    }

    pendingDisconnectDevice?.let { dev ->
        AlertDialog(
            onDismissRequest = { pendingDisconnectDevice = null },
            title = { Text("Disconnect ${dev.display_name}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("This immediately revokes the device's session and stops future syncs.")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = deleteServerDataOnDisconnect,
                            onCheckedChange = { deleteServerDataOnDisconnect = it }
                        )
                        Text("Also delete server-stored history for this device")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = dev
                    val wipe = deleteServerDataOnDisconnect
                    pendingDisconnectDevice = null
                    scope.launch {
                        when (val res = authRepository.disconnectConnectedDeviceByAdmin(target.id, wipe)) {
                            is RepoResult.Success -> {
                                infoNotice = "Disconnected ${target.display_name}."
                                refreshAll()
                            }
                            is RepoResult.Failure -> error = res.message
                        }
                    }
                }) {
                    Text("Disconnect")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDisconnectDevice = null }) { Text("Cancel") }
            }
        )
    }

    pendingRevokeAdminDevice?.let { ad ->
        AlertDialog(
            onDismissRequest = { pendingRevokeAdminDevice = null },
            title = { Text("Revoke Admin Device '${ad.device_name}'?") },
            text = {
                Text("This invalidates that phone's ECDSA signing key and all of its active sessions immediately.")
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = ad
                    pendingRevokeAdminDevice = null
                    scope.launch {
                        when (val res = authRepository.revokeAdminDevice(target.id)) {
                            is RepoResult.Success -> {
                                if (target.id == session.adminDeviceId) {
                                    authRepository.signOutLocally()
                                    onSignedOut()
                                } else {
                                    infoNotice = "Revoked ${target.device_name}."
                                    refreshAll()
                                }
                            }
                            is RepoResult.Failure -> error = res.message
                        }
                    }
                }) {
                    Text("Revoke")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRevokeAdminDevice = null }) { Text("Cancel") }
            }
        )
    }

    if (showRegenerateConfirm) {
        AlertDialog(
            onDismissRequest = { showRegenerateConfirm = false },
            title = { Text("Regenerate 10 Recovery Codes?") },
            text = {
                Text("Any unused previous recovery codes will stop working immediately. Store the new 10 codes safely outside this phone.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showRegenerateConfirm = false
                    scope.launch {
                        when (val res = authRepository.regenerateRecoveryCodes()) {
                            is RepoResult.Success -> regeneratedCodes = res.value
                            is RepoResult.Failure -> error = res.message
                        }
                    }
                }) {
                    Text("Regenerate")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRegenerateConfirm = false }) { Text("Cancel") }
            }
        )
    }

    regeneratedCodes?.let { codes ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("New 10 Recovery Codes (Shown Once)") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Save these outside this phone now:")
                    codes.forEachIndexed { idx, code ->
                        Text("${idx + 1}. $code", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(codes.joinToString("\n")))
                        infoNotice = "Recovery codes copied."
                    }) {
                        Text("Copy All")
                    }
                    TextButton(onClick = { regeneratedCodes = null }) {
                        Text("I Saved Them")
                    }
                }
            }
        )
    }
}
