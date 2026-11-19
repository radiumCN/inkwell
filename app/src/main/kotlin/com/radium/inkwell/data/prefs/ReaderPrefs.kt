package com.radium.inkwell.data.prefs

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import android.content.Context
import android.content.res.Configuration
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.radium.inkwell.reader.api.FlipAnimation
import com.radium.inkwell.reader.api.ReaderSettings
import com.radium.inkwell.reader.api.ReaderTheme
import com.radium.inkwell.reader.api.TapZones
import com.radium.inkwell.core.webdav.BackupSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

private val Context.readerDataStore by preferencesDataStore(
    name = "reader_settings",
    // 文件损坏（写到一半被杀、存储出错）时 DataStore 抛 CorruptionException，而设置流是启动就收集的：
    // 不兜的话应用每次启动都崩，唯一的出路是清除数据 —— 连同书架数据库一起没了。退回默认值
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * @param fontAvailable 这个字体在本机能不能用（见 ReaderFontStore.isAvailable）。只在导入备份时用：
 * 另一台设备上导入的字体文件不会同步过来，拿它的 fontId 覆盖本地只会让排版静默回落默认字体
 */
class ReaderPrefs(
    private val context: Context,
    private val fontAvailable: (String) -> Boolean = { true },
) {

    internal object Keys {
        val FONT_SIZE = floatPreferencesKey("font_size_sp")
        val TITLE_SCALE = floatPreferencesKey("title_scale")
        val TITLE_TOP_SPACING = floatPreferencesKey("title_top_spacing")
        val HEADER_TOP_SPACING = floatPreferencesKey("header_top_spacing")
        val LINE_SPACING = floatPreferencesKey("line_spacing")
        val PARA_SPACING = floatPreferencesKey("para_spacing")
        val MARGIN_H = floatPreferencesKey("margin_h")
        val MARGIN_V = floatPreferencesKey("margin_v")
        /** 老的单一主题键，仅用于迁移到日间槽 */
        val THEME = stringPreferencesKey("theme")
        val CUSTOM_BG = longPreferencesKey("theme_custom_bg")
        val CUSTOM_TEXT = longPreferencesKey("theme_custom_text")
        // 日/夜双槽：阅读主题跟 App 日夜走，两套配色分别记。自定义纸色/字色也各记一份 ——
        // 换走再换回来还在，白天调的暖纸配色不会被夜里调的覆盖。
        val THEME_DAY = stringPreferencesKey("theme_day")
        val THEME_NIGHT = stringPreferencesKey("theme_night")
        val CUSTOM_DAY_BG = longPreferencesKey("custom_day_bg")
        val CUSTOM_DAY_TEXT = longPreferencesKey("custom_day_text")
        val CUSTOM_NIGHT_BG = longPreferencesKey("custom_night_bg")
        val CUSTOM_NIGHT_TEXT = longPreferencesKey("custom_night_text")
        val FLIP = stringPreferencesKey("flip")
        val BRIGHTNESS = floatPreferencesKey("brightness")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val SHOW_BATTERY = booleanPreferencesKey("show_battery")
        val VOLUME_KEY_FLIP = booleanPreferencesKey("volume_key_flip")
        val FLIP_HAPTIC = booleanPreferencesKey("flip_haptic")
        val SCROLL_CLICK_TO_FLIP = booleanPreferencesKey("scroll_click_to_flip")
        val PINCH_FONT_SIZE = booleanPreferencesKey("pinch_font_size")
        val EDGE_BRIGHTNESS = booleanPreferencesKey("edge_brightness")
        val SHOW_IMAGES = booleanPreferencesKey("show_images")
        val LOCAL_SHOW_IMAGES = booleanPreferencesKey("local_show_images")
        /** 九宫格点击分区，见 [TapZones.encode] */
        val TAP_ZONES = stringPreferencesKey("tap_zones")
        val AUTO_FLIP_SECONDS = intPreferencesKey("auto_flip_seconds")
        val PRELOAD_CHAPTERS = intPreferencesKey("preload_chapters")
        val FONT_ID = stringPreferencesKey("font_id")
        val BODY_FONT_WEIGHT = intPreferencesKey("body_font_weight")
        val FIRST_LINE_INDENT = floatPreferencesKey("first_line_indent")
        val JUSTIFY = booleanPreferencesKey("justify")
        val LETTER_SPACING = floatPreferencesKey("letter_spacing")
        /** 排版方案列表（JSON），见 [LayoutScheme] */
        val LAYOUT_SCHEMES = stringPreferencesKey("layout_schemes")
        /** 最后一次改动的时间戳；WebDAV 整块 LWW 靠它裁决 */
        val UPDATED_AT = longPreferencesKey("updated_at")
    }

    // 单例生命周期作用域：settings 提前热起来，`.value` 可同步读，消除进书时的主题闪烁。
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 系统当前是否夜间（同步可读，不依赖 Compose） */
    private fun isSystemDark(): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    // 当前生效的是日间还是夜间。由 UI 层按 App 主题模式 + 系统日夜算出后喂进来（setDarkActive）。
    // 放内存不落库 —— 它是"此刻系统是不是夜间"，不是用户偏好。
    // 初值直接取系统夜间：UI 事后 setDarkActive 之前，首帧就已解析到正确的日/夜槽，不闪浅色。
    private val darkActive = MutableStateFlow(isSystemDark())

    /** UI 层算出生效日夜后调这个；主题随之切到对应槽 */
    fun setDarkActive(dark: Boolean) { darkActive.value = dark }

    // 热 StateFlow：进阅读页时 `.value` 应已是真实设置，ViewModel 首帧就用它播种。
    // 初始占位也按系统日夜给出深/浅底，避免 DataStore 首次读到之前那一帧仍是浅色默认。
    //
    // **Eagerly 只保证"一旦有人构造了 ReaderPrefs 就立刻开读"，不保证那发生得够早。**
    // Koin 的 single 是懒的，从前全 App 第一个注入它的就是 ReaderViewModel 自己 ——
    // 于是预热永远赢不了自己的构造函数：VM 读到的是下面那个占位值，真实设置 ~90ms 后才到，
    // 每本书每次点开都要按默认边距白排一遍版、再在入场动画中间推翻重排。
    // 真正的预热在 `InkwellApp.onCreate` 的 `koin.get<ReaderPrefs>()`，别把那行删了。
    val settings: StateFlow<ReaderSettings> = combine(context.readerDataStore.data, darkActive) { p, dark ->
        readSettings(p, dark)
    }.stateIn(
        scope,
        SharingStarted.Eagerly,
        // 首个真实值到达前的占位：按系统日夜给深/浅底，绝不给浅色默认，杜绝进书闪一帧浅色
        ReaderSettings(theme = if (isSystemDark()) ReaderTheme.NIGHT else ReaderTheme.PAPER),
    )

    private fun readSettings(p: Preferences, dark: Boolean): ReaderSettings {
        val customPaper = ReaderPaperPrefs.custom(p, dark)
        return ReaderSettings(
            fontSizeSp = p[Keys.FONT_SIZE] ?: 18f,
            titleScale = p[Keys.TITLE_SCALE] ?: 1.4f,
            titleTopSpacingDp = p[Keys.TITLE_TOP_SPACING] ?: 24f,
            headerTopSpacingDp = p[Keys.HEADER_TOP_SPACING] ?: 24f,
            fontId = p[Keys.FONT_ID] ?: ReaderSettings.FONT_SYSTEM,
            bodyFontWeight = p[Keys.BODY_FONT_WEIGHT]?.takeIf { it in ReaderSettings.BODY_FONT_WEIGHTS }
                ?: ReaderSettings.FONT_WEIGHT_NORMAL,
            lineSpacingMult = p[Keys.LINE_SPACING] ?: 1.6f,
            paragraphSpacingEm = p[Keys.PARA_SPACING] ?: 0.6f,
            marginHorizontalDp = p[Keys.MARGIN_H] ?: 24f,
            marginVerticalDp = p[Keys.MARGIN_V] ?: 28f,
            firstLineIndentEm = p[Keys.FIRST_LINE_INDENT] ?: 2f,
            justify = p[Keys.JUSTIFY] ?: true,
            letterSpacingEm = p[Keys.LETTER_SPACING] ?: 0f,
            theme = ReaderPaperPrefs.active(p, dark, customPaper),
            customPaper = customPaper,
            flipAnimation = p[Keys.FLIP]?.let { runCatching { FlipAnimation.valueOf(it) }.getOrNull() }
                ?: FlipAnimation.COVER,
            brightnessOverride = p[Keys.BRIGHTNESS]?.takeIf { it >= 0f },
            keepScreenOn = p[Keys.KEEP_SCREEN_ON] ?: true,
            showBattery = p[Keys.SHOW_BATTERY] ?: true,
            volumeKeyFlip = p[Keys.VOLUME_KEY_FLIP] ?: true,
            flipHaptic = p[Keys.FLIP_HAPTIC] ?: false,
            scrollClickToFlip = p[Keys.SCROLL_CLICK_TO_FLIP] ?: false,
            pinchFontSize = p[Keys.PINCH_FONT_SIZE] ?: true,
            edgeBrightness = p[Keys.EDGE_BRIGHTNESS] ?: true,
            showImages = p[Keys.SHOW_IMAGES] ?: false,
            localShowImages = p[Keys.LOCAL_SHOW_IMAGES] ?: true,
            tapZones = TapZones.decode(p[Keys.TAP_ZONES]),
            autoFlipSeconds = p[Keys.AUTO_FLIP_SECONDS] ?: 15,
            preloadChapters = p[Keys.PRELOAD_CHAPTERS] ?: 3,
        )
    }

    suspend fun update(settings: ReaderSettings, localBook: Boolean = false) {
        context.readerDataStore.edit { p ->
            p[Keys.UPDATED_AT] = maxOf(System.currentTimeMillis(), (p[Keys.UPDATED_AT] ?: 0L) + 1)
            writeTypography(p, settings)
            p[Keys.FLIP] = settings.flipAnimation.name
            p[Keys.BRIGHTNESS] = settings.brightnessOverride ?: -1f
            p[Keys.KEEP_SCREEN_ON] = settings.keepScreenOn
            p[Keys.SHOW_BATTERY] = settings.showBattery
            p[Keys.VOLUME_KEY_FLIP] = settings.volumeKeyFlip
            p[Keys.FLIP_HAPTIC] = settings.flipHaptic
            p[Keys.SCROLL_CLICK_TO_FLIP] = settings.scrollClickToFlip
            p[Keys.PINCH_FONT_SIZE] = settings.pinchFontSize
            p[Keys.EDGE_BRIGHTNESS] = settings.edgeBrightness
            // 按当前书籍类型只写对应槽，避免本地书调整排版时覆盖在线书的图片选择。
            if (localBook) p[Keys.LOCAL_SHOW_IMAGES] = settings.showImages
            else p[Keys.SHOW_IMAGES] = settings.showImages
            p[Keys.TAP_ZONES] = settings.tapZones.encode()
            p[Keys.AUTO_FLIP_SECONDS] = settings.autoFlipSeconds
            p[Keys.PRELOAD_CHAPTERS] = settings.preloadChapters
        }
    }

    /** 连点微调按钮时，每一次都基于 DataStore 里的最新值计算。 */
    suspend fun adjustTypography(change: ReaderSettings.() -> ReaderSettings) {
        context.readerDataStore.edit { p ->
            val before = readSettings(p, darkActive.value)
            val after = before.change()
            if (before == after) return@edit
            writeTypography(p, after)
            p[Keys.UPDATED_AT] = maxOf(System.currentTimeMillis(), (p[Keys.UPDATED_AT] ?: 0L) + 1)
        }
    }

    private fun writeTypography(p: MutablePreferences, settings: ReaderSettings) {
        p[Keys.FONT_SIZE] = settings.fontSizeSp
        p[Keys.TITLE_SCALE] = settings.titleScale
        p[Keys.TITLE_TOP_SPACING] = settings.titleTopSpacingDp
        p[Keys.HEADER_TOP_SPACING] = settings.headerTopSpacingDp
        p[Keys.FONT_ID] = settings.fontId
        p[Keys.BODY_FONT_WEIGHT] = settings.bodyFontWeight
        p[Keys.LINE_SPACING] = settings.lineSpacingMult
        p[Keys.PARA_SPACING] = settings.paragraphSpacingEm
        p[Keys.MARGIN_H] = settings.marginHorizontalDp
        p[Keys.MARGIN_V] = settings.marginVerticalDp
        p[Keys.FIRST_LINE_INDENT] = settings.firstLineIndentEm
        p[Keys.JUSTIFY] = settings.justify
        p[Keys.LETTER_SPACING] = settings.letterSpacingEm
    }

    suspend fun selectPaper(themeId: String) {
        val dark = darkActive.value
        context.readerDataStore.edit { p ->
            ReaderPaperPrefs.select(p, dark, themeId)
            p[Keys.UPDATED_AT] = maxOf(System.currentTimeMillis(), (p[Keys.UPDATED_AT] ?: 0L) + 1)
        }
    }

    suspend fun saveCustomPaper(theme: ReaderTheme) {
        val dark = darkActive.value
        context.readerDataStore.edit { p ->
            ReaderPaperPrefs.saveCustom(p, dark, theme)
            p[Keys.UPDATED_AT] = maxOf(System.currentTimeMillis(), (p[Keys.UPDATED_AT] ?: 0L) + 1)
        }
    }

    /** 排版方案。跟阅读设置同一个 DataStore、同一个时间戳，WebDAV 里也随阅读设置整块走 */
    val layoutSchemes: StateFlow<List<LayoutScheme>> = context.readerDataStore.data
        .map { LayoutSchemeCodec.decode(it[Keys.LAYOUT_SCHEMES]) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** 把当前排版存成方案；同名覆盖 */
    suspend fun saveLayoutScheme(scheme: LayoutScheme) = editSchemes { LayoutSchemeCodec.upsert(it, scheme) }

    suspend fun deleteLayoutScheme(name: String) = editSchemes { list -> list.filterNot { it.name == name } }

    private suspend fun editSchemes(change: (List<LayoutScheme>) -> List<LayoutScheme>) {
        context.readerDataStore.edit { p ->
            p[Keys.LAYOUT_SCHEMES] = LayoutSchemeCodec.encode(change(LayoutSchemeCodec.decode(p[Keys.LAYOUT_SCHEMES])))
            p[Keys.UPDATED_AT] = maxOf(System.currentTimeMillis(), (p[Keys.UPDATED_AT] ?: 0L) + 1)
        }
    }

    /** 最后一次改动时间；WebDAV 整块 LWW 用 */
    suspend fun updatedAt(): Long = context.readerDataStore.data.first()[Keys.UPDATED_AT] ?: 0L

    /** 值与时间戳来自同一份磁盘快照，不依赖可能滞后的热流。 */
    internal suspend fun backupSnapshot(): BackupSettings {
        val p = context.readerDataStore.data.first()
        val s = readSettings(p, false)
        return BackupSettings(
            updatedAt = p[Keys.UPDATED_AT] ?: 0L,
            values = mapOf(
                "theme_day" to (p[Keys.THEME_DAY] ?: p[Keys.THEME] ?: ReaderTheme.PAPER.id),
                "theme_night" to (p[Keys.THEME_NIGHT] ?: ReaderTheme.NIGHT.id),
                "custom_day_bg" to (p[Keys.CUSTOM_DAY_BG] ?: p[Keys.CUSTOM_BG])?.toString().orEmpty(),
                "custom_day_text" to (p[Keys.CUSTOM_DAY_TEXT] ?: p[Keys.CUSTOM_TEXT])?.toString().orEmpty(),
                "custom_night_bg" to p[Keys.CUSTOM_NIGHT_BG]?.toString().orEmpty(),
                "custom_night_text" to p[Keys.CUSTOM_NIGHT_TEXT]?.toString().orEmpty(),
                "font_size_sp" to s.fontSizeSp.toString(),
                "title_scale" to s.titleScale.toString(),
                "title_top_spacing" to s.titleTopSpacingDp.toString(),
                "header_top_spacing" to s.headerTopSpacingDp.toString(),
                "font_id" to s.fontId,
                Keys.BODY_FONT_WEIGHT.name to s.bodyFontWeight.toString(),
                "line_spacing" to s.lineSpacingMult.toString(),
                "para_spacing" to s.paragraphSpacingEm.toString(),
                "margin_h" to s.marginHorizontalDp.toString(),
                "margin_v" to s.marginVerticalDp.toString(),
                "first_line_indent" to s.firstLineIndentEm.toString(),
                "justify" to s.justify.toString(),
                "letter_spacing" to s.letterSpacingEm.toString(),
                "layout_schemes" to p[Keys.LAYOUT_SCHEMES].orEmpty(),
                "flip" to s.flipAnimation.name,
                "brightness" to (s.brightnessOverride ?: -1f).toString(),
                "keep_screen_on" to s.keepScreenOn.toString(),
                "show_battery" to s.showBattery.toString(),
                "volume_key_flip" to s.volumeKeyFlip.toString(),
                "flip_haptic" to s.flipHaptic.toString(),
                "scroll_click_to_flip" to s.scrollClickToFlip.toString(),
                "pinch_font_size" to s.pinchFontSize.toString(),
                "edge_brightness" to s.edgeBrightness.toString(),
                "show_images" to s.showImages.toString(),
                "local_show_images" to s.localShowImages.toString(),
                "tap_zones" to s.tapZones.encode(),
                "auto_flip_seconds" to s.autoFlipSeconds.toString(),
                "preload_chapters" to s.preloadChapters.toString(),
            ),
        )
    }

    internal suspend fun restoreBackup(backup: BackupSettings): Boolean {
        var applied = false
        context.readerDataStore.edit { p ->
            applied = ReaderBackupImport.apply(p, backup, fontAvailable)
        }
        return applied
    }

}

/** 日夜分别记当前主题和自定义配色；旧单槽只迁移到日间。 */
internal object ReaderPaperPrefs {
    private val keys = ReaderPrefs.Keys

    fun custom(p: Preferences, dark: Boolean): ReaderTheme {
        val bg = if (dark) p[keys.CUSTOM_NIGHT_BG] else p[keys.CUSTOM_DAY_BG] ?: p[keys.CUSTOM_BG]
        val text = if (dark) p[keys.CUSTOM_NIGHT_TEXT] else p[keys.CUSTOM_DAY_TEXT] ?: p[keys.CUSTOM_TEXT]
        return if (bg != null && text != null) ReaderTheme.custom(bg, text) else ReaderTheme.DEFAULT_CUSTOM
    }

    fun active(p: Preferences, dark: Boolean, custom: ReaderTheme = custom(p, dark)): ReaderTheme {
        val id = if (dark) p[keys.THEME_NIGHT] else p[keys.THEME_DAY] ?: p[keys.THEME]
        val fallback = if (dark) ReaderTheme.NIGHT else ReaderTheme.PAPER
        if (id == ReaderTheme.CUSTOM_ID) return custom
        return ReaderTheme.ALL.firstOrNull { it.id == id } ?: fallback
    }

    fun select(p: MutablePreferences, dark: Boolean, themeId: String) {
        // 第一次启用时也保存完整配色，保证备份在另一台设备上能恢复同一张纸。
        if (themeId == ReaderTheme.CUSTOM_ID) saveCustom(p, dark, custom(p, dark))
        p[if (dark) keys.THEME_NIGHT else keys.THEME_DAY] = themeId
    }

    fun saveCustom(p: MutablePreferences, dark: Boolean, theme: ReaderTheme) {
        p[if (dark) keys.CUSTOM_NIGHT_BG else keys.CUSTOM_DAY_BG] = theme.background
        p[if (dark) keys.CUSTOM_NIGHT_TEXT else keys.CUSTOM_DAY_TEXT] = theme.textColor
    }
}

/** 备份内容与时间戳来自同一次 DataStore 快照，避免 StateFlow 尚未追上新时间戳。 */
suspend fun ReaderPrefs.exportForBackup(): BackupSettings = backupSnapshot()

suspend fun ReaderPrefs.importFromBackup(backup: BackupSettings): Boolean = restoreBackup(backup)

/** 只转换备份明确提供且可读的值，缺失键保留；调用方在同一次 edit 内执行裁决和提交。 */
internal object ReaderBackupImport {
    private val keys = ReaderPrefs.Keys

    fun apply(
        p: MutablePreferences, backup: BackupSettings, fontAvailable: (String) -> Boolean,
    ): Boolean {
        if (backup.updatedAt <= (p[keys.UPDATED_AT] ?: 0L)) return false
        val v = backup.values
        listOf(keys.FONT_SIZE, keys.TITLE_SCALE, keys.TITLE_TOP_SPACING, keys.HEADER_TOP_SPACING,
            keys.LINE_SPACING, keys.PARA_SPACING, keys.MARGIN_H, keys.MARGIN_V,
            keys.FIRST_LINE_INDENT, keys.LETTER_SPACING, keys.BRIGHTNESS).forEach { key ->
            v[key.name]?.toFloatOrNull()?.takeIf { it.isFinite() }?.let { p[key] = it }
        }
        listOf(keys.JUSTIFY, keys.KEEP_SCREEN_ON, keys.SHOW_BATTERY, keys.VOLUME_KEY_FLIP,
            keys.FLIP_HAPTIC, keys.SCROLL_CLICK_TO_FLIP, keys.PINCH_FONT_SIZE,
            keys.EDGE_BRIGHTNESS, keys.SHOW_IMAGES, keys.LOCAL_SHOW_IMAGES).forEach { key ->
            v[key.name]?.toBooleanStrictOrNull()?.let { p[key] = it }
        }
        v["font_id"]?.takeIf(fontAvailable)?.let { p[keys.FONT_ID] = it }
        v[keys.BODY_FONT_WEIGHT.name]?.toIntOrNull()?.takeIf { it in ReaderSettings.BODY_FONT_WEIGHTS }
            ?.let { p[keys.BODY_FONT_WEIGHT] = it }
        v["flip"]?.takeIf { value -> FlipAnimation.entries.any { it.name == value } }
            ?.let { p[keys.FLIP] = it }
        v["tap_zones"]?.let(TapZones::decodeOrNull)?.let { p[keys.TAP_ZONES] = it.encode() }
        listOf(keys.AUTO_FLIP_SECONDS, keys.PRELOAD_CHAPTERS).forEach { key ->
            v[key.name]?.toIntOrNull()?.let { p[key] = it }
        }
        (v["theme_day"] ?: v["theme"])?.let { p[keys.THEME_DAY] = it }
        v["theme_night"]?.let { p[keys.THEME_NIGHT] = it }
        (v["custom_day_bg"] ?: v["theme_custom_bg"])?.toLongOrNull()?.let { p[keys.CUSTOM_DAY_BG] = it }
        (v["custom_day_text"] ?: v["theme_custom_text"])?.toLongOrNull()?.let { p[keys.CUSTOM_DAY_TEXT] = it }
        v["custom_night_bg"]?.toLongOrNull()?.let { p[keys.CUSTOM_NIGHT_BG] = it }
        v["custom_night_text"]?.toLongOrNull()?.let { p[keys.CUSTOM_NIGHT_TEXT] = it }
        v["layout_schemes"]?.let { p[keys.LAYOUT_SCHEMES] = it }
        p[keys.UPDATED_AT] = backup.updatedAt
        return true
    }
}
