package com.eachother.app.core.di

import android.content.Context
import com.eachother.app.BuildConfig
import com.eachother.app.core.network.EachOtherApi
import com.eachother.app.core.network.NetworkModule
import com.eachother.app.core.security.AdminKeySigner
import com.eachother.app.core.security.AndroidKeystoreAdminKeyManager
import com.eachother.app.core.security.DataStoreSharingSettingsStore
import com.eachother.app.core.security.KeystoreSessionStore
import com.eachother.app.core.security.SessionStore
import com.eachother.app.core.security.SharingSettingsStore
import com.eachother.app.core.time.Clock
import com.eachother.app.core.time.SystemClock
import com.eachother.app.data.repository.AuthRepository
import com.eachother.app.platform.permissions.AndroidPermissionChecker
import com.eachother.app.platform.permissions.PermissionChecker

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val clock: Clock = SystemClock()
    val sessionStore: SessionStore = KeystoreSessionStore(appContext)
    val sharingSettingsStore: SharingSettingsStore = DataStoreSharingSettingsStore(appContext)
    val adminKeySigner: AdminKeySigner = AndroidKeystoreAdminKeyManager()
    val api: EachOtherApi = NetworkModule.createApi(BuildConfig.API_BASE_URL, sessionStore)
    val authRepository: AuthRepository = AuthRepository(
        api = api,
        sessionStore = sessionStore,
        sharingSettingsStore = sharingSettingsStore,
        adminKeySigner = adminKeySigner,
        appVersion = BuildConfig.VERSION_NAME
    )
    val permissionChecker: PermissionChecker = AndroidPermissionChecker(appContext)
}
