package com.eachother.app.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.eachother.app.domain.model.AppRole
import com.eachother.app.ui.components.InfoBanner
import com.eachother.app.ui.components.RoleOptionCard

@Composable
fun RoleSelectionScreen(
    onRoleSelected: (AppRole) -> Unit
) {
    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "EACH OTHER",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "Consent-based trusted sharing. Nothing is shared until the phone owner enables a category and grants permission.",
                style = MaterialTheme.typography.bodyLarge
            )

            InfoBanner(
                title = "Clear boundaries by design",
                body = "EACH OTHER never reads messages, passwords, keystrokes, photos, camera, or microphone. Reporting is one-way from Connected User to their group's Administrator."
            )

            Text(
                text = "Choose how you will use this phone:",
                style = MaterialTheme.typography.titleMedium
            )

            RoleOptionCard(
                title = "Administrator (Owner)",
                description = "Create a private group, generate one-time invitations, and view reports that trusted users explicitly share with you.",
                onClick = { onRoleSelected(AppRole.ADMIN) }
            )

            RoleOptionCard(
                title = "Connected User",
                description = "Join one trusted Administrator's group using an invite code, choose individually what to share, and pause or delete anytime.",
                onClick = { onRoleSelected(AppRole.CONNECTED) }
            )
        }
    }
}

@Composable
fun PrivacyOverviewScreen(
    role: AppRole,
    onContinue: () -> Unit,
    onBack: () -> Unit
) {
    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = if (role == AppRole.ADMIN) "Administrator Privacy & Rules" else "Connected User Privacy & Control",
                style = MaterialTheme.typography.headlineMedium
            )
            Text(
                text = if (role == AppRole.ADMIN) {
                    "You can only view devices that join your group with an invite you created. You cannot turn on permissions or sharing remotely on another person's phone."
                } else {
                    "All sharing categories start OFF. You control Location, Daily App Usage, and Basic Device Status individually. You can pause sharing, delete both local and server-synchronized history, or disconnect at any time."
                },
                style = MaterialTheme.typography.bodyLarge
            )
            InfoBanner(
                title = "What is NEVER collected",
                body = "No camera or photos, no audio/call recording, no SMS or chat messages, no passwords, no keystrokes, and no screenshots."
            )
            Spacer(modifier = Modifier.weight(1f))
            Button(
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Continue")
            }
            Button(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Choose Different Role")
            }
        }
    }
}
