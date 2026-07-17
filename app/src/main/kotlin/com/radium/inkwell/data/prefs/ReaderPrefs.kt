package com.radium.inkwell.data.prefs

import android.content.Context
import android.content.res.Configuration
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.radium.inkwell.reader.api.FlipAnimation
import com.radium.inkwell.reader.api.ReaderSettings
import com.radium.inkwell.reader.api.ReaderTheme
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

private val Context.readerDataStore by preferencesDataStore(name = "reader_settings")

class ReaderPrefs(private val context: Context) {

    private object Keys {
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
        val VOLUME_KEY_FLIP = booleanPreferencesKey("volume_key_flip")
        val FLIP_HAPTIC = booleanPreferencesKey("flip_haptic")
        val AUTO_FLIP_SECONDS = intPreferencesKey("auto_flip_seconds")
        val PRELOAD_CHAPTERS = intPreferencesKey("preload_chapters")
        val FONT_ID = stringPreferencesKey("font_id")
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
        ReaderSettings(
            fontSizeSp = p[Keys.FONT_SIZE] ?: 18f,
            titleScale = p[Keys.TITLE_SCALE] ?: 1.4f,
            titleTopSpacingDp = p[Keys.TITLE_TOP_SPACING] ?: 24f,
            headerTopSpacingDp = p[Keys.HEADER_TOP_SPACING] ?: 24f,
            fontId = p[Keys.FONT_ID] ?: ReaderSettings.FONT_SYSTEM,
            lineSpacingMult = p[Keys.LINE_SPACING] ?: 1.6f,
            paragraphSpacingEm = p[Keys.PARA_SPACING] ?: 0.6f,
            marginHorizontalDp = p[Keys.MARGIN_H] ?: 24f,
            marginVerticalDp = p[Keys.MARGIN_V] ?: 28f,
            theme = resolveActiveTheme(p, dark),
            flipAnimation = p[Keys.FLIP]?.let { runCatching { FlipAnimation.valueOf(it) }.getOrNull() }
                ?: FlipAnimation.COVER,
            brightnessOverride = p[Keys.BRIGHTNESS]?.takeIf { it >= 0f },
            keepScreenOn = p[Keys.KEEP_SCREEN_ON] ?: true,
            volumeKeyFlip = p[Keys.VOLUME_KEY_FLIP] ?: true,
            flipHaptic = p[Keys.FLIP_HAPTIC] ?: false,
            autoFlipSeconds = p[Keys.AUTO_FLIP_SECONDS] ?: 15,
            preloadChapters = p[Keys.PRELOAD_CHAPTERS] ?: 3,
        )
    }.stateIn(
        scope,
        SharingStarted.Eagerly,
        // 首个真实值到达前的占位：按系统日夜给深/浅底，绝不给浅色默认，杜绝进书闪一帧浅色
        ReaderSettings(theme = if (isSystemDark()) ReaderTheme.NIGHT else ReaderTheme.PAPER),
    )

    suspend fun update(settings: ReaderSettings) {
        context.readerDataStore.edit { p ->
            p[Keys.UPDATED_AT] = System.currentTimeMillis()
            p[Keys.FONT_SIZE] = settings.fontSizeSp
            p[Keys.TITLE_SCALE] = settings.titleScale
            p[Keys.TITLE_TOP_SPACING] = settings.titleTopSpacingDp
            p[Keys.HEADER_TOP_SPACING] = settings.headerTopSpacingDp
            p[Keys.FONT_ID] = settings.fontId
            p[Keys.LINE_SPACING] = settings.lineSpacingMult
            p[Keys.PARA_SPACING] = settings.paragraphSpacingEm
            p[Keys.MARGIN_H] = settings.marginHorizontalDp
            p[Keys.MARGIN_V] = settings.marginVerticalDp
            // 主题落到当前生效的槽（日/夜），另一个槽不动 —— 这就是"分别记住"
            if (darkActive.value) {
                p[Keys.THEME_NIGHT] = settings.theme.id
                if (settings.theme.id == ReaderTheme.CUSTOM_ID) {
                    p[Keys.CUSTOM_NIGHT_BG] = settings.theme.background
                    p[Keys.CUSTOM_NIGHT_TEXT] = settings.theme.textColor
                }
            } else {
                p[Keys.THEME_DAY] = settings.theme.id
                if (settings.theme.id == ReaderTheme.CUSTOM_ID) {
                    p[Keys.CUSTOM_DAY_BG] = settings.theme.background
                    p[Keys.CUSTOM_DAY_TEXT] = settings.theme.textColor
                }
            }
            p[Keys.FLIP] = settings.flipAnimation.name
            p[Keys.BRIGHTNESS] = settings.brightnessOverride ?: -1f
            p[Keys.KEEP_SCREEN_ON] = settings.keepScreenOn
            p[Keys.VOLUME_KEY_FLIP] = settings.volumeKeyFlip
            p[Keys.FLIP_HAPTIC] = settings.flipHaptic
            p[Keys.AUTO_FLIP_SECONDS] = settings.autoFlipSeconds
            p[Keys.PRELOAD_CHAPTERS] = settings.preloadChapters
        }
    }

    /**
     * 按当前日夜取生效主题。日间槽为空时回落到老的单一 THEME 键（老用户升级迁移）；
     * 夜间槽为空时回落到「夜色」预设，而不是暖纸 —— 夜里默认给深色纸才合理。
     */
    private fun resolveActiveTheme(p: Preferences, dark: Boolean): ReaderTheme = if (dark) {
        resolveTheme(
            p[Keys.THEME_NIGHT] ?: ReaderTheme.NIGHT.id,
            p[Keys.CUSTOM_NIGHT_BG], p[Keys.CUSTOM_NIGHT_TEXT],
            fallback = ReaderTheme.NIGHT,
        )
    } else {
        resolveTheme(
            p[Keys.THEME_DAY] ?: p[Keys.THEME],                       // 迁移：老单槽 → 日间
            p[Keys.CUSTOM_DAY_BG] ?: p[Keys.CUSTOM_BG],
            p[Keys.CUSTOM_DAY_TEXT] ?: p[Keys.CUSTOM_TEXT],
            fallback = ReaderTheme.PAPER,
        )
    }

    /** 备份用：读出日/夜两槽的原值（不是当前生效那套），两台设备日/夜配色都同步 */
    suspend fun themeBackupValues(): Map<String, String> {
        val p = context.readerDataStore.data.first()
        return mapOf(
            "theme_day" to (p[Keys.THEME_DAY] ?: p[Keys.THEME] ?: ReaderTheme.PAPER.id),
            "theme_night" to (p[Keys.THEME_NIGHT] ?: ReaderTheme.NIGHT.id),
            "custom_day_bg" to (p[Keys.CUSTOM_DAY_BG] ?: p[Keys.CUSTOM_BG])?.toString().orEmpty(),
            "custom_day_text" to (p[Keys.CUSTOM_DAY_TEXT] ?: p[Keys.CUSTOM_TEXT])?.toString().orEmpty(),
            "custom_night_bg" to p[Keys.CUSTOM_NIGHT_BG]?.toString().orEmpty(),
            "custom_night_text" to p[Keys.CUSTOM_NIGHT_TEXT]?.toString().orEmpty(),
        )
    }

    /** 从备份写回日/夜两槽。兼容老备份（只有单一 "theme" → 归到日间槽） */
    suspend fun importThemeSlots(v: Map<String, String>) {
        context.readerDataStore.edit { p ->
            (v["theme_day"] ?: v["theme"])?.let { p[Keys.THEME_DAY] = it }
            v["theme_night"]?.let { p[Keys.THEME_NIGHT] = it }
            (v["custom_day_bg"] ?: v["theme_custom_bg"])?.toLongOrNull()?.let { p[Keys.CUSTOM_DAY_BG] = it }
            (v["custom_day_text"] ?: v["theme_custom_text"])?.toLongOrNull()?.let { p[Keys.CUSTOM_DAY_TEXT] = it }
            v["custom_night_bg"]?.toLongOrNull()?.let { p[Keys.CUSTOM_NIGHT_BG] = it }
            v["custom_night_text"]?.toLongOrNull()?.let { p[Keys.CUSTOM_NIGHT_TEXT] = it }
        }
    }

    /** 最后一次改动时间；WebDAV 整块 LWW 用 */
    suspend fun updatedAt(): Long = context.readerDataStore.data.first()[Keys.UPDATED_AT] ?: 0L

    /** 从远端导入设置后，把时间戳按远端的写回，避免两台设备来回覆盖 */
    suspend fun stampUpdatedAt(at: Long) {
        context.readerDataStore.edit { it[Keys.UPDATED_AT] = at }
    }

}

/** WebDAV 备份用：设置 ↔ 字符串表。键名与 DataStore 保持一致，读到不认识的键忽略。 */
suspend fun ReaderPrefs.exportForBackup(): BackupSettings {
    val s = settings.first()
    return BackupSettings(
        updatedAt = updatedAt(),
        values = themeBackupValues() + mapOf(
            "font_size_sp" to s.fontSizeSp.toString(),
            "title_scale" to s.titleScale.toString(),
            "title_top_spacing" to s.titleTopSpacingDp.toString(),
            "header_top_spacing" to s.headerTopSpacingDp.toString(),
            "font_id" to s.fontId,
            "line_spacing" to s.lineSpacingMult.toString(),
            "para_spacing" to s.paragraphSpacingEm.toString(),
            "margin_h" to s.marginHorizontalDp.toString(),
            "margin_v" to s.marginVerticalDp.toString(),
            "flip" to s.flipAnimation.name,
            "brightness" to (s.brightnessOverride ?: -1f).toString(),
            "keep_screen_on" to s.keepScreenOn.toString(),
            "volume_key_flip" to s.volumeKeyFlip.toString(),
            "flip_haptic" to s.flipHaptic.toString(),
            "auto_flip_seconds" to s.autoFlipSeconds.toString(),
            "preload_chapters" to s.preloadChapters.toString(),
        ),
    )
}

/**
 * id → 主题。自定义主题不在 ReaderTheme.ALL 那张表上（它的颜色是用户存的），
 * 所以按 id 查表这一步必须先把它摘出来 —— 否则自定义会被静默降级成"暖纸"。
 *
 * 读取、备份导入两处共用：各写一份的话，WebDAV 同步回来的自定义主题会丢色。
 */
private fun resolveTheme(
    id: String?, bg: Long?, text: Long?,
    fallback: ReaderTheme = ReaderTheme.PAPER,
): ReaderTheme = when {
    id == ReaderTheme.CUSTOM_ID && bg != null && text != null -> ReaderTheme.custom(bg, text)
    else -> ReaderTheme.ALL.firstOrNull { it.id == id } ?: fallback
}

suspend fun ReaderPrefs.importFromBackup(backup: BackupSettings) {
    val v = backup.values
    val base = settings.first()
    update(
        base.copy(
            fontSizeSp = v["font_size_sp"]?.toFloatOrNull() ?: base.fontSizeSp,
            titleScale = v["title_scale"]?.toFloatOrNull() ?: base.titleScale,
            titleTopSpacingDp = v["title_top_spacing"]?.toFloatOrNull() ?: base.titleTopSpacingDp,
            headerTopSpacingDp = v["header_top_spacing"]?.toFloatOrNull() ?: base.headerTopSpacingDp,
            fontId = v["font_id"] ?: base.fontId,
            lineSpacingMult = v["line_spacing"]?.toFloatOrNull() ?: base.lineSpacingMult,
            paragraphSpacingEm = v["para_spacing"]?.toFloatOrNull() ?: base.paragraphSpacingEm,
            marginHorizontalDp = v["margin_h"]?.toFloatOrNull() ?: base.marginHorizontalDp,
            marginVerticalDp = v["margin_v"]?.toFloatOrNull() ?: base.marginVerticalDp,
            // 主题不走 copy —— 它现在是日/夜双槽，由下面的 importThemeSlots 单独写回
            flipAnimation = v["flip"]?.let { runCatching { FlipAnimation.valueOf(it) }.getOrNull() }
                ?: base.flipAnimation,
            // 键缺失 → 保留本地值；键存在（含 -1=跟随系统）→ 用远端值
            brightnessOverride = if ("brightness" in v) v["brightness"]?.toFloatOrNull()?.takeIf { it >= 0f }
            else base.brightnessOverride,
            keepScreenOn = v["keep_screen_on"]?.toBooleanStrictOrNull() ?: base.keepScreenOn,
            volumeKeyFlip = v["volume_key_flip"]?.toBooleanStrictOrNull() ?: base.volumeKeyFlip,
            flipHaptic = v["flip_haptic"]?.toBooleanStrictOrNull() ?: base.flipHaptic,
            autoFlipSeconds = v["auto_flip_seconds"]?.toIntOrNull() ?: base.autoFlipSeconds,
            preloadChapters = v["preload_chapters"]?.toIntOrNull() ?: base.preloadChapters,
        )
    )
    importThemeSlots(v)
    // update() 会把时间戳刷成"现在"，但这份设置其实是远端的 —— 保留远端的时间戳，
    // 否则下次同步时本地看起来更新，会把这份设置又推回去，两台设备来回打架。
    stampUpdatedAt(backup.updatedAt)
}
