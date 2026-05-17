package com.yuwanadev.mdm.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "mdm_config")

/**
 * ConfigStore handles persistent storage of server settings and device identity.
 */
class ConfigStore(private val context: Context) {

    companion object {
        private val SERVER_URL = stringPreferencesKey("server_url")
        private val AUTH_TOKEN = stringPreferencesKey("auth_token")
        private val DEVICE_ID = stringPreferencesKey("device_id")
    }

    val deviceId: Flow<String?> = context.dataStore.data.map { it[DEVICE_ID] }

    suspend fun saveConfig(url: String, token: String) {
        context.dataStore.edit { prefs ->
            prefs[SERVER_URL] = url
            prefs[AUTH_TOKEN] = token
        }
    }

    suspend fun saveDeviceId(id: String) {
        context.dataStore.edit { prefs ->
            prefs[DEVICE_ID] = id
        }
    }

    suspend fun getServerUrl(): String? = context.dataStore.data.first()[SERVER_URL]
    suspend fun getToken(): String? = context.dataStore.data.first()[AUTH_TOKEN]
    suspend fun getDeviceId(): String? = context.dataStore.data.first()[DEVICE_ID]

    suspend fun isReady(): Boolean {
        val prefs = context.dataStore.data.first()
        return prefs[SERVER_URL] != null && prefs[AUTH_TOKEN] != null
    }

    suspend fun clear() {
        context.dataStore.edit { it.clear() }
    }
}
