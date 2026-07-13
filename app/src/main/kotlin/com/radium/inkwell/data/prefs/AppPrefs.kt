package com.radium.inkwell.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.radium.inkwell.update.UpdateChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.appDataStore by preferencesDataStore(name = "app_prefs")

class AppPrefs(private val context: Context) {

    private object Keys {
        val UPDATE_CHANNEL = stringPreferencesKey("update_channel")
    }

    val updateChannel: Flow<UpdateChannel> = context.appDataStore.data.map { p ->
        p[Keys.UPDATE_CHANNEL]
            ?.let { runCatching { UpdateChannel.valueOf(it) }.getOrNull() }
            ?: UpdateChannel.STABLE
    }

    suspend fun setUpdateChannel(channel: UpdateChannel) {
        context.appDataStore.edit { it[Keys.UPDATE_CHANNEL] = channel.name }
    }
}
