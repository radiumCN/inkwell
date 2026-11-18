package com.radium.inkwell.data.prefs

import androidx.datastore.preferences.core.mutablePreferencesOf
import com.radium.inkwell.reader.api.ReaderTheme
import kotlin.test.Test
import kotlin.test.assertEquals

class ReaderPaperPrefsTest {
    @Test fun `returning to custom restores the palette for each mode`() {
        val p = mutablePreferencesOf()
        val day = ReaderTheme.custom(0xFFEEEEDD, 0xFF232323)
        val night = ReaderTheme.custom(0xFF121427, 0xFFEEEEEE)
        ReaderPaperPrefs.saveCustom(p, dark = false, day)
        ReaderPaperPrefs.saveCustom(p, dark = true, night)
        ReaderPaperPrefs.select(p, dark = false, ReaderTheme.CUSTOM_ID)
        ReaderPaperPrefs.select(p, dark = true, ReaderTheme.CUSTOM_ID)

        ReaderPaperPrefs.select(p, dark = false, ReaderTheme.PAPER.id)
        assertEquals(ReaderTheme.PAPER, ReaderPaperPrefs.active(p, dark = false))
        assertEquals(day, ReaderPaperPrefs.custom(p, dark = false))
        ReaderPaperPrefs.select(p, dark = false, ReaderTheme.CUSTOM_ID)
        assertEquals(day, ReaderPaperPrefs.active(p, dark = false))
        assertEquals(night, ReaderPaperPrefs.active(p, dark = true))
    }

    @Test fun `first custom selection defaults to warm paper and legacy colors stay in day`() {
        val keys = ReaderPrefs.Keys
        val bg = 0xFFEEDAC5
        val text = 0xFF231C18
        val p = mutablePreferencesOf(keys.CUSTOM_BG to bg, keys.CUSTOM_TEXT to text)
        ReaderPaperPrefs.select(p, dark = false, ReaderTheme.CUSTOM_ID)
        ReaderPaperPrefs.select(p, dark = true, ReaderTheme.CUSTOM_ID)

        assertEquals(ReaderTheme.custom(bg, text), ReaderPaperPrefs.active(p, dark = false))
        assertEquals(ReaderTheme.DEFAULT_CUSTOM, ReaderPaperPrefs.active(p, dark = true))
        assertEquals(bg, p[keys.CUSTOM_DAY_BG])
        assertEquals(ReaderTheme.PAPER.background, p[keys.CUSTOM_NIGHT_BG])
        assertEquals(ReaderTheme.PAPER.textColor, p[keys.CUSTOM_NIGHT_TEXT])
    }
}
