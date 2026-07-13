package com.radium.inkwell.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.radium.inkwell.reader.api.FlipAnimation
import com.radium.inkwell.reader.api.ReaderSettings
import com.radium.inkwell.reader.api.ReaderTheme
import kotlinx.coroutines.flow.Flow
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
}
