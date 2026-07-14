package com.radium.inkwell.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.radium.inkwell.reader.api.FlipAnimation
import com.radium.inkwell.reader.api.ReaderSettings
import com.radium.inkwell.reader.api.ReaderTheme
import com.radium.inkwell.core.webdav.BackupSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.readerDataStore by preferencesDataStore(name = "reader_settings")

class ReaderPrefs(private val context: Context) {

    private object Keys {
        val FONT_SIZE = floatPreferencesKey("font_size_sp")
        val LINE_SPACING = floatPreferencesKey("line_spacing")
        val PARA_SPACING = floatPreferencesKey("para_spacing")
        val MARGIN_H = floatPreferencesKey("margin_h")
        val MARGIN_V = floatPreferencesKey("margin_v")
        val THEME = stringPreferencesKey("theme")
        val FLIP = stringPreferencesKey("flip")
        val BRIGHTNESS = floatPreferencesKey("brightness")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val VOLUME_KEY_FLIP = booleanPreferencesKey("volume_key_flip")
        val FONT_ID = stringPreferencesKey("font_id")
        /** 最后一次改动的时间戳；WebDAV 整块 LWW 靠它裁决 */
        val UPDATED_AT = longPreferencesKey("updated_at")
    }

    val settings: Flow<ReaderSettings> = context.readerDataStore.data.map { p ->
        ReaderSettings(
            fontSizeSp = p[Keys.FONT_SIZE] ?: 18f,
            fontId = p[Keys.FONT_ID] ?: ReaderSettings.FONT_SYSTEM,
            lineSpacingMult = p[Keys.LINE_SPACING] ?: 1.6f,
            paragraphSpacingEm = p[Keys.PARA_SPACING] ?: 0.6f,
            marginHorizontalDp = p[Keys.MARGIN_H] ?: 24f,
            marginVerticalDp = p[Keys.MARGIN_V] ?: 28f,
            theme = ReaderTheme.ALL.firstOrNull { it.id == p[Keys.THEME] } ?: ReaderTheme.PAPER,
            flipAnimation = p[Keys.FLIP]?.let { runCatching { FlipAnimation.valueOf(it) }.getOrNull() }
                ?: FlipAnimation.COVER,
            brightnessOverride = p[Keys.BRIGHTNESS]?.takeIf { it >= 0f },
            keepScreenOn = p[Keys.KEEP_SCREEN_ON] ?: true,
            volumeKeyFlip = p[Keys.VOLUME_KEY_FLIP] ?: true,
        )
    }

    suspend fun update(settings: ReaderSettings) {
        context.readerDataStore.edit { p ->
            p[Keys.UPDATED_AT] = System.currentTimeMillis()
            p[Keys.FONT_SIZE] = settings.fontSizeSp
            p[Keys.FONT_ID] = settings.fontId
            p[Keys.LINE_SPACING] = settings.lineSpacingMult
            p[Keys.PARA_SPACING] = settings.paragraphSpacingEm
            p[Keys.MARGIN_H] = settings.marginHorizontalDp
            p[Keys.MARGIN_V] = settings.marginVerticalDp
            p[Keys.THEME] = settings.theme.id
            p[Keys.FLIP] = settings.flipAnimation.name
            p[Keys.BRIGHTNESS] = settings.brightnessOverride ?: -1f
            p[Keys.KEEP_SCREEN_ON] = settings.keepScreenOn
            p[Keys.VOLUME_KEY_FLIP] = settings.volumeKeyFlip
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
        values = mapOf(
            "font_size_sp" to s.fontSizeSp.toString(),
            "font_id" to s.fontId,
            "line_spacing" to s.lineSpacingMult.toString(),
            "para_spacing" to s.paragraphSpacingEm.toString(),
            "margin_h" to s.marginHorizontalDp.toString(),
            "margin_v" to s.marginVerticalDp.toString(),
            "theme" to s.theme.id,
            "flip" to s.flipAnimation.name,
            "brightness" to (s.brightnessOverride ?: -1f).toString(),
            "keep_screen_on" to s.keepScreenOn.toString(),
            "volume_key_flip" to s.volumeKeyFlip.toString(),
        ),
    )
}

suspend fun ReaderPrefs.importFromBackup(backup: BackupSettings) {
    val v = backup.values
    val base = settings.first()
    update(
        base.copy(
            fontSizeSp = v["font_size_sp"]?.toFloatOrNull() ?: base.fontSizeSp,
            fontId = v["font_id"] ?: base.fontId,
            lineSpacingMult = v["line_spacing"]?.toFloatOrNull() ?: base.lineSpacingMult,
            paragraphSpacingEm = v["para_spacing"]?.toFloatOrNull() ?: base.paragraphSpacingEm,
            marginHorizontalDp = v["margin_h"]?.toFloatOrNull() ?: base.marginHorizontalDp,
            marginVerticalDp = v["margin_v"]?.toFloatOrNull() ?: base.marginVerticalDp,
            theme = ReaderTheme.ALL.firstOrNull { it.id == v["theme"] } ?: base.theme,
            flipAnimation = v["flip"]?.let { runCatching { FlipAnimation.valueOf(it) }.getOrNull() }
                ?: base.flipAnimation,
            brightnessOverride = v["brightness"]?.toFloatOrNull()?.takeIf { it >= 0f },
            keepScreenOn = v["keep_screen_on"]?.toBooleanStrictOrNull() ?: base.keepScreenOn,
            volumeKeyFlip = v["volume_key_flip"]?.toBooleanStrictOrNull() ?: base.volumeKeyFlip,
        )
    )
    // update() 会把时间戳刷成"现在"，但这份设置其实是远端的 —— 保留远端的时间戳，
    // 否则下次同步时本地看起来更新，会把这份设置又推回去，两台设备来回打架。
    stampUpdatedAt(backup.updatedAt)
}
