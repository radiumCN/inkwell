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
        val AUTO_CHANGE_SOURCE = booleanPreferencesKey("auto_change_source")
        val EXPLORE_ENABLED = booleanPreferencesKey("explore_enabled")
        val HIDDEN_REQUIRE_AUTH = booleanPreferencesKey("hidden_require_auth")
        /** 长按书籍的动作面板里是否出现「从书架隐藏」。默认关 —— 见 AppPrefs.hideBooksEnabled */
        val HIDE_BOOKS_ENABLED = booleanPreferencesKey("hide_books_enabled")
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
                "auto_change_source" to (p[Keys.AUTO_CHANGE_SOURCE] ?: true).toString(),
                "explore_enabled" to (p[Keys.EXPLORE_ENABLED] ?: true).toString(),
                "hide_books_enabled" to (p[Keys.HIDE_BOOKS_ENABLED] ?: false).toString(),
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
            v["auto_change_source"]?.toBooleanStrictOrNull()
                ?.let { p[Keys.AUTO_CHANGE_SOURCE] = it }
            v["explore_enabled"]?.toBooleanStrictOrNull()
                ?.let { p[Keys.EXPLORE_ENABLED] = it }
            v["hide_books_enabled"]?.toBooleanStrictOrNull()
                ?.let { p[Keys.HIDE_BOOKS_ENABLED] = it }
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

    /**
     * 长按书籍时是否出现「从书架隐藏」。**默认关**。
     *
     * 开着的话，任何人长按一本书都会看到这一项 —— 于是他知道了「这个 App 能藏书」，
     * 进而知道该去找藏起来的东西。隐藏功能的价值恰恰在于别人想不到去找。
     *
     * 开关本身也不能放设置里（那等于换个地方泄密），它住在隐藏区内部 ——
     * 长按书架标题进去才看得到。那个手势是唯一的信任根，一切从它长出来。
     */
    val hideBooksEnabled: Flow<Boolean> = context.appDataStore.data.map { p ->
        p[Keys.HIDE_BOOKS_ENABLED] ?: false
    }

    suspend fun setHideBooksEnabled(on: Boolean) {
        context.appDataStore.edit { it[Keys.HIDE_BOOKS_ENABLED] = on }
        touch()
    }

    /** 正文读不出来时自动换一个能读的源。默认开 —— 与 Legado 原生一致 */
    val autoChangeSource: Flow<Boolean> = context.appDataStore.data.map { p ->
        p[Keys.AUTO_CHANGE_SOURCE] ?: true
    }

    suspend fun setAutoChangeSource(on: Boolean) {
        context.appDataStore.edit { it[Keys.AUTO_CHANGE_SOURCE] = on }
        touch()
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

    /**
     * 查看隐藏书籍是否需要系统验证（指纹/面容/设备密码）。
     *
     * **不跨设备同步**：换台没录指纹的设备，同步过去就把人锁在自己的书外面了。
     * 这是个没有找回途径的锁 —— 书在本地，我们不做账号。
     */
    val hiddenRequireAuth: Flow<Boolean> = context.appDataStore.data.map { p ->
        p[Keys.HIDDEN_REQUIRE_AUTH] ?: false
    }

    suspend fun setHiddenRequireAuth(on: Boolean) {
        context.appDataStore.edit { it[Keys.HIDDEN_REQUIRE_AUTH] = on }
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
