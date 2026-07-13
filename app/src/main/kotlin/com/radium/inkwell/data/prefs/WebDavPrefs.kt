package com.radium.inkwell.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.webDavDataStore by preferencesDataStore(name = "webdav")

data class WebDavConfig(
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val lastSyncAt: Long = 0,
) {
    val isConfigured: Boolean get() = url.isNotBlank() && username.isNotBlank()
}

class WebDavPrefs(private val context: Context) {

    private object Keys {
        val URL = stringPreferencesKey("url")
        val USERNAME = stringPreferencesKey("username")
        val PASSWORD = stringPreferencesKey("password")
        val LAST_SYNC = longPreferencesKey("last_sync")
        val DEVICE_ID = stringPreferencesKey("device_id")
    }

    val config: Flow<WebDavConfig> = context.webDavDataStore.data.map { p ->
        WebDavConfig(
            url = p[Keys.URL] ?: "",
            username = p[Keys.USERNAME] ?: "",
            password = p[Keys.PASSWORD] ?: "",
            lastSyncAt = p[Keys.LAST_SYNC] ?: 0,
        )
    }

    suspend fun save(url: String, username: String, password: String) {
        context.webDavDataStore.edit { p ->
            p[Keys.URL] = url.trim()
            p[Keys.USERNAME] = username.trim()
            p[Keys.PASSWORD] = password
        }
    }

    suspend fun markSynced(at: Long) {
        context.webDavDataStore.edit { it[Keys.LAST_SYNC] = at }
    }

    suspend fun deviceId(): String {
        val existing = context.webDavDataStore.data.first()[Keys.DEVICE_ID]
        if (existing != null) return existing
        val id = UUID.randomUUID().toString()
        context.webDavDataStore.edit { it[Keys.DEVICE_ID] = id }
        return id
    }
}
