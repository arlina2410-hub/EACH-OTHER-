package com.eachother.app.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.eachother.app.core.di.AppContainer
import com.eachother.app.domain.model.AppRole
import com.eachother.app.ui.admin.AdminAuthScreen
import com.eachother.app.ui.admin.AdminDashboardScreen
import com.eachother.app.ui.connected.ConnectedHomeScreen
import com.eachother.app.ui.connected.JoinGroupScreen
import com.eachother.app.ui.onboarding.PrivacyOverviewScreen
import com.eachother.app.ui.onboarding.RoleSelectionScreen
import kotlinx.coroutines.launch

object Routes {
    const val ROLE_SELECT = "role_select"
    const val PRIVACY_OVERVIEW = "privacy_overview"
    const val ADMIN_AUTH = "admin_auth"
    const val ADMIN_DASHBOARD = "admin_dashboard"
    const val CONNECTED_JOIN = "connected_join"
    const val CONNECTED_HOME = "connected_home"
}

@Composable
fun EachOtherNavGraph(container: AppContainer) {
    val navController = rememberNavController()
    val session by container.authRepository.sessionFlow.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    var pendingRole by remember { mutableStateOf(AppRole.UNSELECTED) }

    val currentSession = session
    if (currentSession == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val startDestination = when {
        currentSession.isAuthenticated && currentSession.role == AppRole.ADMIN -> Routes.ADMIN_DASHBOARD
        currentSession.isAuthenticated && currentSession.role == AppRole.CONNECTED -> Routes.CONNECTED_HOME
        else -> Routes.ROLE_SELECT
    }

    NavHost(navController = navController, startDestination = startDestination) {
        composable(Routes.ROLE_SELECT) {
            RoleSelectionScreen(
                onRoleSelected = { role ->
                    pendingRole = role
                    scope.launch { container.authRepository.selectRole(role) }
                    navController.navigate(Routes.PRIVACY_OVERVIEW)
                }
            )
        }

        composable(Routes.PRIVACY_OVERVIEW) {
            val activeRole = if (pendingRole != AppRole.UNSELECTED) pendingRole else currentSession.role
            PrivacyOverviewScreen(
                role = activeRole,
                onContinue = {
                    val target = if (activeRole == AppRole.ADMIN) Routes.ADMIN_AUTH else Routes.CONNECTED_JOIN
                    navController.navigate(target)
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.ADMIN_AUTH) {
            AdminAuthScreen(
                session = currentSession,
                authRepository = container.authRepository,
                onAuthenticated = {
                    navController.navigate(Routes.ADMIN_DASHBOARD) {
                        popUpTo(Routes.ROLE_SELECT) { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.ADMIN_DASHBOARD) {
            AdminDashboardScreen(
                session = currentSession,
                authRepository = container.authRepository,
                onSignedOut = {
                    navController.navigate(Routes.ROLE_SELECT) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.CONNECTED_JOIN) {
            JoinGroupScreen(
                authRepository = container.authRepository,
                onEnrolled = {
                    navController.navigate(Routes.CONNECTED_HOME) {
                        popUpTo(Routes.ROLE_SELECT) { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.CONNECTED_HOME) {
            ConnectedHomeScreen(
                session = currentSession,
                permissionChecker = container.permissionChecker,
                authRepository = container.authRepository,
                onDisconnected = {
                    navController.navigate(Routes.ROLE_SELECT) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }
    }
}
