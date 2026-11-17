package com.eachother.app.platform.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.eachother.app.EachOtherApplication
import com.eachother.app.MainActivity
import com.eachother.app.platform.location.LocationSharingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object SharingNotificationManager {
    const val CHANNEL_ID_SHARING = "each_other_active_sharing"
    const val NOTIFICATION_ID_SHARING = 1001
    const val ACTION_PAUSE_SHARING = "com.eachother.app.ACTION_PAUSE_SHARING"

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID_SHARING,
                "Active Location Sharing",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shown while you have explicitly enabled persistent location sharing."
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    fun buildActiveSharingNotification(context: Context, groupName: String): Notification {
        ensureChannels(context)
        val openAppIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val pauseIntent = PendingIntent.getBroadcast(
            context,
            1,
            Intent(context, PauseSharingReceiver::class.java).apply {
                action = ACTION_PAUSE_SHARING
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(context, CHANNEL_ID_SHARING)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("EACH OTHER: Location sharing active")
            .setContentText("Sharing periodic updates with $groupName. Tap to manage.")
            .setOngoing(true)
            .setContentIntent(openAppIntent)
            .addAction(
                android.R.drawable.ic_media_pause,
                "Stop sharing",
                pauseIntent
            )
            .build()
    }
}

class PauseSharingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SharingNotificationManager.ACTION_PAUSE_SHARING) return
        context.stopService(Intent(context, LocationSharingService::class.java))

        val app = context.applicationContext as? EachOtherApplication ?: return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val current = app.container.sharingSettingsStore.getSettings()
                val paused = current.copy(
                    globalPaused = true,
                    persistentLocationFgsEnabled = false
                )
                val permissions = app.container.permissionChecker.snapshot()
                app.container.authRepository.reconcileAndSyncSharingSettings(paused, permissions)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
