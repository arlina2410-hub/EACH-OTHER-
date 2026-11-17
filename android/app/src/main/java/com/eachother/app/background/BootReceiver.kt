package com.eachother.app.background

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Re-schedules WorkManager sync/collection checks after reboot (wired in Phase 3/4).
 * Strictly never launches a Foreground Service directly from BOOT_COMPLETED per Android 15 rules.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // Phase 3/4 will enqueue WorkManager PeriodicWorkRequest here safely.
        }
    }
}
