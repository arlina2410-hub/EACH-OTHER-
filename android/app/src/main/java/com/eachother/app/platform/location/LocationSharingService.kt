package com.eachother.app.platform.location

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.eachother.app.platform.notifications.SharingNotificationManager

/**
 * Optional user-started foreground location service (full periodic collection wired in Phase 4).
 * Never auto-started silently or from BOOT_COMPLETED.
 */
class LocationSharingService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val groupName = intent?.getStringExtra(EXTRA_GROUP_NAME) ?: "your group"
        val notification = SharingNotificationManager.buildActiveSharingNotification(this, groupName)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    SharingNotificationManager.NOTIFICATION_ID_SHARING,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
            } else {
                startForeground(SharingNotificationManager.NOTIFICATION_ID_SHARING, notification)
            }
        } catch (_: SecurityException) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val EXTRA_GROUP_NAME = "extra_group_name"
    }
}
