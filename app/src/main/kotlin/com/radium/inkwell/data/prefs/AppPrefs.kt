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
import com.radium.inkwell.core.webdav.BackupSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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
        val TEXT_SELECTION = booleanPreferencesKey("text_selection_enabled")
        val EXPLORE_ENABLED = booleanPreferencesKey("explore_enabled")
        /** 最后一次改动的时间戳；WebDAV 整块 LWW 靠它裁决 */
        val UPDATED_AT = longPreferencesKey("settings_updated_at")
    }

    private suspend fun touch() {
        context.appDataStore.edit { it[Keys.UPDATED_AT] = System.currentTimeMillis() }
    }

    suspend fun updatedAt(): Long = context.appDataStore.data.first()[Keys.UPDATED_AT] ?: 0L

    /** 从远端导入后按远端的时间戳落章，免得两台设备来回覆盖 */
    suspend fun stampUpdatedAt(at: Long) {
        context.appDataStore.edit { it[Keys.UPDATED_AT] = at }
    }

    /** WebDAV 备份用：设置 ↔ 字符串表。读到不认识的键忽略，新旧版本能共用同一份备份 */
    suspend fun exportForBackup(): BackupSettings {
        val p = context.appDataStore.data.first()
        val theme = themeConfig.first()
        return BackupSettings(
            updatedAt = p[Keys.UPDATED_AT] ?: 0L,
            values = mapOf(
                "update_channel" to (p[Keys.UPDATE_CHANNEL] ?: UpdateChannel.STABLE.name),
                "change_source_check_author" to (p[Keys.CHANGE_SOURCE_CHECK_AUTHOR] ?: true).toString(),
                "text_selection_enabled" to (p[Keys.TEXT_SELECTION] ?: true).toString(),
                "explore_enabled" to (p[Keys.EXPLORE_ENABLED] ?: true).toString(),
                "theme_mode" to theme.mode.name,
                "theme_light" to theme.lightPreset,
                "theme_dark" to theme.darkPreset,
                "custom_light_seed" to theme.customLightSeed.toString(),
                "custom_light_bg" to theme.customLightBg.toString(),
                "custom_dark_seed" to theme.customDarkSeed.toString(),
                "custom_dark_bg" to theme.customDarkBg.toString(),
            ),
        )
    }

    suspend fun importFromBackup(backup: BackupSettings) {
        val v = backup.values
        val base = themeConfig.first()
        context.appDataStore.edit { p ->
            v["update_channel"]
                ?.takeIf { name -> UpdateChannel.entries.any { it.name == name } }
                ?.let { p[Keys.UPDATE_CHANNEL] = it }
            v["change_source_check_author"]?.toBooleanStrictOrNull()
                ?.let { p[Keys.CHANGE_SOURCE_CHECK_AUTHOR] = it }
            v["text_selection_enabled"]?.toBooleanStrictOrNull()
                ?.let { p[Keys.TEXT_SELECTION] = it }
            v["explore_enabled"]?.toBooleanStrictOrNull()
                ?.let { p[Keys.EXPLORE_ENABLED] = it }
            v["theme_mode"]
                ?.takeIf { name -> ThemeMode.entries.any { it.name == name } }
                ?.let { p[Keys.THEME_MODE] = it }
            p[Keys.THEME_LIGHT] = v["theme_light"] ?: base.lightPreset
            p[Keys.THEME_DARK] = v["theme_dark"] ?: base.darkPreset
            p[Keys.CUSTOM_LIGHT_SEED] = v["custom_light_seed"]?.toLongOrNull() ?: base.customLightSeed
            p[Keys.CUSTOM_LIGHT_BG] = v["custom_light_bg"]?.toLongOrNull() ?: base.customLightBg
            p[Keys.CUSTOM_DARK_SEED] = v["custom_dark_seed"]?.toLongOrNull() ?: base.customDarkSeed
            p[Keys.CUSTOM_DARK_BG] = v["custom_dark_bg"]?.toLongOrNull() ?: base.customDarkBg
            // 这份设置来自远端，时间戳照抄远端的
            p[Keys.UPDATED_AT] = backup.updatedAt
        }
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
        touch()
    }

    /**
     * 阅读页是否允许长按选字。默认开。
     * 关掉的理由是实打实的：长按选字要占用长按手势，翻页时手指停顿久一点就会误触选中。
     */
    val textSelectionEnabled: Flow<Boolean> = context.appDataStore.data.map { p ->
        p[Keys.TEXT_SELECTION] ?: true
    }

    suspend fun setTextSelectionEnabled(on: Boolean) {
        context.appDataStore.edit { it[Keys.TEXT_SELECTION] = on }
        touch()
    }

    /** 书架顶栏是否显示「发现」入口。不看发现页的人，那个图标只是碍事 */
    val exploreEnabled: Flow<Boolean> = context.appDataStore.data.map { p ->
        p[Keys.EXPLORE_ENABLED] ?: true
    }

    suspend fun setExploreEnabled(on: Boolean) {
        context.appDataStore.edit { it[Keys.EXPLORE_ENABLED] = on }
        touch()
    }

    val updateChannel: Flow<UpdateChannel> = context.appDataStore.data.map { p ->
        p[Keys.UPDATE_CHANNEL]
            ?.let { runCatching { UpdateChannel.valueOf(it) }.getOrNull() }
            ?: UpdateChannel.STABLE
    }

    suspend fun setUpdateChannel(channel: UpdateChannel) {
        context.appDataStore.edit { it[Keys.UPDATE_CHANNEL] = channel.name }
        touch()
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
        touch()
    }
}
