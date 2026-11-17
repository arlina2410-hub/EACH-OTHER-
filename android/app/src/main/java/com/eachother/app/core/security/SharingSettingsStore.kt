package com.eachother.app.core.security

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.eachother.app.domain.model.SharingCategorySettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

interface SharingSettingsStore {
    val settingsFlow: Flow<SharingCategorySettings>
    suspend fun getSettings(): SharingCategorySettings
    suspend fun saveSettings(settings: SharingCategorySettings)
    suspend fun clear()
}

private val Context.sharingDataStore by preferencesDataStore(name = "each_other_sharing_settings")

class DataStoreSharingSettingsStore(
    private val context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true }
) : SharingSettingsStore {

    private val key = stringPreferencesKey("sharing_category_settings_v1")

    override val settingsFlow: Flow<SharingCategorySettings> = context.sharingDataStore.data.map { prefs ->
        val raw = prefs[key] ?: return@map SharingCategorySettings()
        try {
            json.decodeFromString<SharingCategorySettings>(raw)
        } catch (_: Exception) {
            SharingCategorySettings()
        }
    }

    override suspend fun getSettings(): SharingCategorySettings = settingsFlow.first()

    override suspend fun saveSettings(settings: SharingCategorySettings) {
        val serialized = json.encodeToString(settings)
        context.sharingDataStore.edit { prefs ->
            prefs[key] = serialized
        }
    }

    override suspend fun clear() {
        context.sharingDataStore.edit { prefs ->
            prefs.remove(key)
        }
    }
}
