package com.eachother.app

import android.app.Application
import com.eachother.app.core.di.AppContainer
import com.eachother.app.platform.notifications.SharingNotificationManager

class EachOtherApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        SharingNotificationManager.ensureChannels(this)
    }
}
