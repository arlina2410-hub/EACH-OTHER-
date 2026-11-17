package com.eachother.app.platform.permissions

import android.Manifest
import android.app.ActivityManager
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import androidx.core.content.ContextCompat
import com.eachother.app.domain.model.PermissionSnapshot

interface PermissionChecker {
    fun snapshot(): PermissionSnapshot
}

class AndroidPermissionChecker(
    private val context: Context
) : PermissionChecker {

    override fun snapshot(): PermissionSnapshot {
        val fineGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val coarseGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val bgGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            fineGranted || coarseGranted
        }

        val notificationsGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        val usageAccessGranted = checkUsageStatsPermission()
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val isBgRestricted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            activityManager?.isBackgroundRestricted == true
        } else {
            false
        }
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val ignoringBatteryOpt = powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: true

        return PermissionSnapshot(
            foregroundLocationGranted = fineGranted || coarseGranted,
            backgroundLocationGranted = bgGranted,
            usageAccessGranted = usageAccessGranted,
            notificationsGranted = notificationsGranted,
            backgroundExecutionRestricted = isBgRestricted || !ignoringBatteryOpt
        )
    }

    private fun checkUsageStatsPermission(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }
}
