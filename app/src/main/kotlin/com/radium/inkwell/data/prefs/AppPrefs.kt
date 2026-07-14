package com.radium.inkwell.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.radium.inkwell.ui.theme.AppThemes
import com.radium.inkwell.ui.theme.ThemeConfig
import com.radium.inkwell.ui.theme.ThemeMode
import com.radium.inkwell.update.UpdateChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.appDataStore by preferencesDataStore(name = "app_prefs")

class AppPrefs(private val context: Context) {

    private object Keys {
        val UPDATE_CHANNEL = stringPreferencesKey("update_channel")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val THEME_LIGHT = stringPreferencesKey("theme_light")
        val THEME_DARK = stringPreferencesKey("theme_dark")
        val CUSTOM_LIGHT_SEED = longPreferencesKey("custom_light_seed")
        val CUSTOM_LIGHT_BG = longPreferencesKey("custom_light_bg")
        val CUSTOM_DARK_SEED = longPreferencesKey("custom_dark_seed")
        val CUSTOM_DARK_BG = longPreferencesKey("custom_dark_bg")
        val CHANGE_SOURCE_CHECK_AUTHOR = booleanPreferencesKey("change_source_check_author")
    }

    /**
     * 换源时是否用作者卡人。默认开（同 Legado）。
     * 书源返回的作者字段五花八门（"作者：天蚕土豆"、多作者、干脆为空），
     * 卡得太死会「所有书源都找不到这本书」；关掉就只认书名。
     */
    val changeSourceCheckAuthor: Flow<Boolean> = context.appDataStore.data.map { p ->
        p[Keys.CHANGE_SOURCE_CHECK_AUTHOR] ?: true
    }

    suspend fun setChangeSourceCheckAuthor(on: Boolean) {
        context.appDataStore.edit { it[Keys.CHANGE_SOURCE_CHECK_AUTHOR] = on }
    }

    val updateChannel: Flow<UpdateChannel> = context.appDataStore.data.map { p ->
        p[Keys.UPDATE_CHANNEL]
            ?.let { runCatching { UpdateChannel.valueOf(it) }.getOrNull() }
            ?: UpdateChannel.STABLE
    }

    suspend fun setUpdateChannel(channel: UpdateChannel) {
        context.appDataStore.edit { it[Keys.UPDATE_CHANNEL] = channel.name }
    }

    val themeConfig: Flow<ThemeConfig> = context.appDataStore.data.map { p ->
        val default = ThemeConfig()
        ThemeConfig(
            mode = p[Keys.THEME_MODE]
                ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.SYSTEM,
            lightPreset = p[Keys.THEME_LIGHT] ?: AppThemes.LIGHT_PAPER,
            darkPreset = p[Keys.THEME_DARK] ?: AppThemes.DARK_WARM,
            customLightSeed = p[Keys.CUSTOM_LIGHT_SEED] ?: default.customLightSeed,
            customLightBg = p[Keys.CUSTOM_LIGHT_BG] ?: default.customLightBg,
            customDarkSeed = p[Keys.CUSTOM_DARK_SEED] ?: default.customDarkSeed,
            customDarkBg = p[Keys.CUSTOM_DARK_BG] ?: default.customDarkBg,
        )
    }

    suspend fun setThemeConfig(config: ThemeConfig) {
        context.appDataStore.edit { p ->
            p[Keys.THEME_MODE] = config.mode.name
            p[Keys.THEME_LIGHT] = config.lightPreset
            p[Keys.THEME_DARK] = config.darkPreset
            p[Keys.CUSTOM_LIGHT_SEED] = config.customLightSeed
            p[Keys.CUSTOM_LIGHT_BG] = config.customLightBg
            p[Keys.CUSTOM_DARK_SEED] = config.customDarkSeed
            p[Keys.CUSTOM_DARK_BG] = config.customDarkBg
        }
    }
}
