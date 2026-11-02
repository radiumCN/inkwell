package com.radium.inkwell.data.prefs

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.radium.inkwell.ui.bookshelf.BookshelfLayout
import com.radium.inkwell.ui.theme.AppThemes
import com.radium.inkwell.ui.theme.ThemeConfig
import com.radium.inkwell.ui.theme.ThemeMode
import com.radium.inkwell.update.UpdateChannel
import com.radium.inkwell.update.UpdateSource
import com.radium.inkwell.core.webdav.BackupSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

private val Context.appDataStore by preferencesDataStore(
    name = "app_prefs",
    // 文件损坏（写到一半被杀、存储出错）时 DataStore 抛 CorruptionException，而设置流是启动就收集的：
    // 不兜的话应用每次启动都崩，唯一的出路是清除数据 —— 连同书架数据库一起没了。退回默认值
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

class AppPrefs(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private object Keys {
        val UPDATE_CHANNEL = stringPreferencesKey("update_channel")
        val UPDATE_SOURCE = stringPreferencesKey("update_source")
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
        val BOOKSHELF_LAYOUT = stringPreferencesKey("bookshelf_layout")
        val HIDDEN_REQUIRE_AUTH = booleanPreferencesKey("hidden_require_auth")
        val SEARCH_HISTORY_ENABLED = booleanPreferencesKey("search_history_enabled")
        /** 搜索历史，新的在前，换行分隔（关键词入库前已把换行压成空格，见 [pushSearchHistory]） */
        val SEARCH_HISTORY = stringPreferencesKey("search_history")
        /**
         * 已读章节正文缓存保留天数。0 = 关闭自动清理；合法值 0/7/14/30，默认 14。
         * 只清进度之前且超过该天数未碰过的缓存，当前章与其后不动。
         */
        val CACHE_AUTO_PRUNE_DAYS = intPreferencesKey("cache_auto_prune_days")
        val DEVELOPER_MODE = booleanPreferencesKey("developer_mode")
        val LOG_RECORDING = booleanPreferencesKey("log_recording")
        /** 最后一次改动的时间戳；WebDAV 整块 LWW 靠它裁决 */
        val UPDATED_AT = longPreferencesKey("settings_updated_at")
    }

    /**
     * 改设置并盖时间戳，**同一次** edit。从前是先 edit、再 touch() 单独写一遍时间戳：
     * 每拨一下开关整份文件写两遍，每条派生流（主题、各开关……）各发射两次。
     */
    private suspend fun editTouched(block: (MutablePreferences) -> Unit) {
        context.appDataStore.edit { p ->
            block(p)
            p[Keys.UPDATED_AT] = System.currentTimeMillis()
        }
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
                "update_source" to (p[Keys.UPDATE_SOURCE] ?: UpdateSource.GITHUB.name),
                "change_source_check_author" to (p[Keys.CHANGE_SOURCE_CHECK_AUTHOR] ?: true).toString(),
                "text_selection_enabled" to (p[Keys.TEXT_SELECTION] ?: true).toString(),
                "auto_change_source" to (p[Keys.AUTO_CHANGE_SOURCE] ?: true).toString(),
                "explore_enabled" to (p[Keys.EXPLORE_ENABLED] ?: true).toString(),
                "search_history_enabled" to (p[Keys.SEARCH_HISTORY_ENABLED] ?: true).toString(),
                "bookshelf_layout" to (p[Keys.BOOKSHELF_LAYOUT] ?: BookshelfLayout.GRID.name),
                "cache_auto_prune_days" to (p[Keys.CACHE_AUTO_PRUNE_DAYS] ?: DEFAULT_CACHE_AUTO_PRUNE_DAYS).toString(),
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
            v["update_source"]
                ?.takeIf { name -> UpdateSource.entries.any { it.name == name } }
                ?.let { p[Keys.UPDATE_SOURCE] = it }
            v["change_source_check_author"]?.toBooleanStrictOrNull()
                ?.let { p[Keys.CHANGE_SOURCE_CHECK_AUTHOR] = it }
            v["text_selection_enabled"]?.toBooleanStrictOrNull()
                ?.let { p[Keys.TEXT_SELECTION] = it }
            v["auto_change_source"]?.toBooleanStrictOrNull()
                ?.let { p[Keys.AUTO_CHANGE_SOURCE] = it }
            v["explore_enabled"]?.toBooleanStrictOrNull()
                ?.let { p[Keys.EXPLORE_ENABLED] = it }
            v["search_history_enabled"]?.toBooleanStrictOrNull()?.let { on ->
                p[Keys.SEARCH_HISTORY_ENABLED] = on
                // 与本机关掉开关同一个语义：关 = 不留记录。别让别的设备关了、这台还攒着一串旧词
                if (!on) p.remove(Keys.SEARCH_HISTORY)
            }
            v["bookshelf_layout"]
                ?.takeIf { name -> BookshelfLayout.entries.any { it.name == name } }
                ?.let { p[Keys.BOOKSHELF_LAYOUT] = it }
            v["cache_auto_prune_days"]?.toIntOrNull()
                ?.takeIf { it in CACHE_AUTO_PRUNE_DAYS_OPTIONS }
                ?.let { p[Keys.CACHE_AUTO_PRUNE_DAYS] = it }
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
        editTouched { it[Keys.CHANGE_SOURCE_CHECK_AUTHOR] = on }
    }

    /**
     * 阅读页是否允许长按选字。默认开。
     * 关掉的理由是实打实的：长按选字要占用长按手势，翻页时手指停顿久一点就会误触选中。
     */
    val textSelectionEnabled: Flow<Boolean> = context.appDataStore.data.map { p ->
        p[Keys.TEXT_SELECTION] ?: true
    }

    /** 正文读不出来时自动换一个能读的源。默认开 —— 与 Legado 原生一致 */
    val autoChangeSource: Flow<Boolean> = context.appDataStore.data.map { p ->
        p[Keys.AUTO_CHANGE_SOURCE] ?: true
    }

    suspend fun setAutoChangeSource(on: Boolean) {
        editTouched { it[Keys.AUTO_CHANGE_SOURCE] = on }
    }

    suspend fun setTextSelectionEnabled(on: Boolean) {
        editTouched { it[Keys.TEXT_SELECTION] = on }
    }

    /** 书架顶栏是否显示「发现」入口。不看发现页的人，那个图标只是碍事 */
    val exploreEnabled: Flow<Boolean> = context.appDataStore.data.map { p ->
        p[Keys.EXPLORE_ENABLED] ?: true
    }

    suspend fun setExploreEnabled(on: Boolean) {
        editTouched { it[Keys.EXPLORE_ENABLED] = on }
    }

    /**
     * 是否记录搜索历史。默认开。
     *
     * 开关进备份、随设备同步；**历史本身不进备份**：搜过什么是这台设备上的事，
     * 同步过去等于把别人在另一台设备上搜的词摊在这台的搜索页上。
     */
    val searchHistoryEnabled: Flow<Boolean> = context.appDataStore.data.map { p ->
        p[Keys.SEARCH_HISTORY_ENABLED] ?: true
    }.distinctUntilChanged()

    /** 关掉时连同已有历史一起清掉 —— 用户关它多半就是不想让人看见搜过什么，只停止记录不够 */
    suspend fun setSearchHistoryEnabled(on: Boolean) {
        editTouched { p ->
            p[Keys.SEARCH_HISTORY_ENABLED] = on
            if (!on) p.remove(Keys.SEARCH_HISTORY)
        }
    }

    /**
     * 搜索历史，新的在前；最多 [SEARCH_HISTORY_MAX] 条。
     * 先比原始字符串再拆：同一个 DataStore 里任何一项设置变了（含刷时间戳）整份 data 都会再发一次，
     * 不去重的话每次都重拆一遍、再推给搜索页重组
     */
    val searchHistory: Flow<List<String>> = context.appDataStore.data
        .map { p -> p[Keys.SEARCH_HISTORY] }
        .distinctUntilChanged()
        .map(::decodeSearchHistory)

    /**
     * 记一条。开关的判断放在同一次 edit 里：先读开关、再另起一次写的话，
     * 中间用户关了开关、清了历史，这条又会被写回去。
     * 历史不刷时间戳（不走 editTouched）：它不进备份，刷时间戳只会让 WebDAV 以为设置变了、白推一次。
     */
    suspend fun addSearchHistory(keyword: String) {
        context.appDataStore.edit { p ->
            if (p[Keys.SEARCH_HISTORY_ENABLED] == false) return@edit
            val next = pushSearchHistory(decodeSearchHistory(p[Keys.SEARCH_HISTORY]), keyword)
            p[Keys.SEARCH_HISTORY] = next.joinToString("\n")
        }
    }

    suspend fun removeSearchHistory(keyword: String) {
        context.appDataStore.edit { p ->
            val next = decodeSearchHistory(p[Keys.SEARCH_HISTORY]) - keyword
            if (next.isEmpty()) p.remove(Keys.SEARCH_HISTORY)
            else p[Keys.SEARCH_HISTORY] = next.joinToString("\n")
        }
    }

    suspend fun clearSearchHistory() {
        context.appDataStore.edit { it.remove(Keys.SEARCH_HISTORY) }
    }

    /** 书架展示：网格（封面优先）或列表（一行一书，方便扫最新章）。默认网格。 */
    val bookshelfLayout: Flow<BookshelfLayout> = context.appDataStore.data.map { p ->
        p[Keys.BOOKSHELF_LAYOUT]
            ?.let { runCatching { BookshelfLayout.valueOf(it) }.getOrNull() }
            ?: BookshelfLayout.GRID
    }

    suspend fun setBookshelfLayout(layout: BookshelfLayout) {
        editTouched { it[Keys.BOOKSHELF_LAYOUT] = layout.name }
    }

    /**
     * 已读章节正文缓存自动清理保留天数。
     *
     * 0 = 关闭；默认 14。只清「当前进度之前」且超过该天数未读过的缓存，
     * 当前章与预取章不受影响。合法值见 [CACHE_AUTO_PRUNE_DAYS_OPTIONS]。
     */
    val cacheAutoPruneDays: Flow<Int> = context.appDataStore.data.map { p ->
        p[Keys.CACHE_AUTO_PRUNE_DAYS]
            ?.takeIf { it in CACHE_AUTO_PRUNE_DAYS_OPTIONS }
            ?: DEFAULT_CACHE_AUTO_PRUNE_DAYS
    }

    suspend fun setCacheAutoPruneDays(days: Int) {
        require(days in CACHE_AUTO_PRUNE_DAYS_OPTIONS) { "非法保留天数: $days" }
        editTouched { it[Keys.CACHE_AUTO_PRUNE_DAYS] = days }
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

    /**
     * 开发者模式：关于页连点版本号打开。默认关。
     *
     * 与下面的日志开关一样**不进备份、不刷时间戳**：这是一台设备上为定位问题临时开的，
     * 同步过去就成了「另一台设备莫名其妙一直在录日志」。
     */
    val developerMode: Flow<Boolean> = context.appDataStore.data.map { p ->
        p[Keys.DEVELOPER_MODE] ?: false
    }

    /** 关开发者模式时连带关掉日志记录 —— 入口都藏起来了，录制却还在后台跑，用户关不掉 */
    suspend fun setDeveloperMode(on: Boolean) {
        context.appDataStore.edit { p ->
            p[Keys.DEVELOPER_MODE] = on
            if (!on) p[Keys.LOG_RECORDING] = false
        }
    }

    /** 运行日志记录，见 [com.radium.inkwell.util.RunLogRecorder]。只在开发者模式下生效 */
    val logRecording: Flow<Boolean> = context.appDataStore.data.map { p ->
        (p[Keys.DEVELOPER_MODE] ?: false) && (p[Keys.LOG_RECORDING] ?: false)
    }

    suspend fun setLogRecording(on: Boolean) {
        context.appDataStore.edit { it[Keys.LOG_RECORDING] = on }
    }

    val updateChannel: Flow<UpdateChannel> = context.appDataStore.data.map { p ->
        p[Keys.UPDATE_CHANNEL]
            ?.let { runCatching { UpdateChannel.valueOf(it) }.getOrNull() }
            ?: UpdateChannel.STABLE
    }

    suspend fun setUpdateChannel(channel: UpdateChannel) {
        editTouched { it[Keys.UPDATE_CHANNEL] = channel.name }
    }

    /** 更新源：GitHub（默认）或中转服务器 */
    val updateSource: Flow<UpdateSource> = context.appDataStore.data.map { p ->
        p[Keys.UPDATE_SOURCE]
            ?.let { runCatching { UpdateSource.valueOf(it) }.getOrNull() }
            ?: UpdateSource.GITHUB
    }

    suspend fun setUpdateSource(source: UpdateSource) {
        editTouched { it[Keys.UPDATE_SOURCE] = source.name }
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

    /**
     * [themeConfig] 的预热版，null = 磁盘那一读还没回来。
     *
     * 界面要的是「第一帧就是对的主题」：拿 `collectAsState(initialValue = ThemeConfig())` 收冷流的话，
     * 强制夜间、纯黑、自定义强调色的用户每次冷启动都先按默认暖纸画一帧，读到配置再整树换色。
     * InkwellApp 冷启动就 get 一次 AppPrefs，让这一读和 ReaderPrefs 的预热一起提前；
     * MainActivity 在它到之前拦住首帧（见 MainActivity.holdFirstFrameUntilThemed）。
     * 之后进的页面（阅读页、主题设置）直接读 .value，就不必各自再闪一次默认值。
     */
    val themeState: StateFlow<ThemeConfig?> = themeConfig
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, null)

    suspend fun setThemeConfig(config: ThemeConfig) {
        editTouched { p ->
            p[Keys.THEME_MODE] = config.mode.name
            p[Keys.THEME_LIGHT] = config.lightPreset
            p[Keys.THEME_DARK] = config.darkPreset
            p[Keys.CUSTOM_LIGHT_SEED] = config.customLightSeed
            p[Keys.CUSTOM_LIGHT_BG] = config.customLightBg
            p[Keys.CUSTOM_DARK_SEED] = config.customDarkSeed
            p[Keys.CUSTOM_DARK_BG] = config.customDarkBg
        }
    }

    // ---- 设置页读的预热版 ----
    //
    // null = 磁盘那一读还没回来。设置页从前拿 `collectAsState(initialValue = 默认值)` 收冷流：
    // 第一帧按默认值画、下一帧才是真值，关过「自动换源」的人每次进页都看着开关从「开」滑到「关」。
    // 这些流从 App 启动起就在收（AppPrefs 在 InkwellApp 里预热），进设置页时 .value 早已是真值。
    // 原来的 Flow 一个不动：别处用 .first() 读的是真值，不能被这里的占位顶替。

    private fun <T> Flow<T>.warm(): StateFlow<T?> = stateIn(scope, SharingStarted.Eagerly, null)

    val changeSourceCheckAuthorState: StateFlow<Boolean?> = changeSourceCheckAuthor.warm()
    val autoChangeSourceState: StateFlow<Boolean?> = autoChangeSource.warm()
    val exploreEnabledState: StateFlow<Boolean?> = exploreEnabled.warm()
    val searchHistoryEnabledState: StateFlow<Boolean?> = searchHistoryEnabled.warm()
    val bookshelfLayoutState: StateFlow<BookshelfLayout?> = bookshelfLayout.warm()
    val cacheAutoPruneDaysState: StateFlow<Int?> = cacheAutoPruneDays.warm()
    val developerModeState: StateFlow<Boolean?> = developerMode.warm()
    val updateChannelState: StateFlow<UpdateChannel?> = updateChannel.warm()
    val updateSourceState: StateFlow<UpdateSource?> = updateSource.warm()

    companion object {
        const val DEFAULT_CACHE_AUTO_PRUNE_DAYS = 14
        /** 再多就得在搜索页里滚着找了，那还不如重新输 */
        const val SEARCH_HISTORY_MAX = 20
        /** 关闭 / 7 / 14 / 30 天 */
        val CACHE_AUTO_PRUNE_DAYS_OPTIONS = setOf(0, 7, 14, 30)
    }
}

private fun decodeSearchHistory(raw: String?): List<String> =
    raw?.split('\n')?.filter { it.isNotBlank() }.orEmpty()

/**
 * 搜索关键词的规范形：去首尾空白，中间连续空白（含粘贴进来的换行）压成一个空格。
 *
 * 搜索与历史**必须**用同一个：历史按换行分隔存，词里留着换行会被拆成两条；而搜索若只 trim，
 * 「斗破  苍穹」搜的是两个空格、历史记的是一个，点历史重搜就成了另一个词，书源返回的结果可能不同。
 */
fun normalizeSearchKeyword(raw: String): String = raw.trim().replace(WHITESPACE_RUN, " ")

/** 每搜一次要用两三回（搜索本身、记历史），别每回现编一个 */
private val WHITESPACE_RUN = Regex("\\s+")

/** 把 [keyword] 放到历史最前：已有的同一个词挪上来而不是重复一条，超出 [max] 的旧词丢掉 */
internal fun pushSearchHistory(
    history: List<String>,
    keyword: String,
    max: Int = AppPrefs.SEARCH_HISTORY_MAX,
): List<String> {
    val k = normalizeSearchKeyword(keyword)
    if (k.isEmpty()) return history
    return (listOf(k) + history.filter { it != k }).take(max)
}
