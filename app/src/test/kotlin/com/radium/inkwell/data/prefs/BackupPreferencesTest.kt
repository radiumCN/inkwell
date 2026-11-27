package com.radium.inkwell.data.prefs

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.radium.inkwell.core.webdav.BackupSettings
import com.radium.inkwell.reader.api.BatteryDisplayStyle
import com.radium.inkwell.reader.api.ReaderTheme
import com.radium.inkwell.reader.api.ReaderSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackupPreferencesTest {
    @Test fun `directory display rule is saved by book and excluded from backup`() = runTest {
        val store = object : DataStore<Preferences> {
            override val data = MutableStateFlow<Preferences>(mutablePreferencesOf())
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                transform(data.value).also { data.value = it }
        }
        val prefs = ReaderPrefs(store, initiallyDark = false, fontAvailable = { true }, scope = backgroundScope)
        val before = prefs.backupSnapshot()
        prefs.setBookTocRule("first", BookTocRule.GROUP_VOLUMES)
        assertEquals(BookTocRule.ORIGINAL, prefs.bookTocRule("second"))
        prefs.setBookTocRule("second", BookTocRule.HIDE_VOLUME)
        assertEquals(BookTocRule.GROUP_VOLUMES, prefs.bookTocRule("first"))
        prefs.setBookTocRule("first", BookTocRule.ORIGINAL)
        assertEquals(BookTocRule.ORIGINAL, prefs.bookTocRule("first"))
        assertEquals(BookTocRule.HIDE_VOLUME, prefs.bookTocRule("second"))
        assertEquals(before, prefs.backupSnapshot())
    }

    @Test fun bookOnlyEditDoesNotWinOverNewerSharedSettings() = runTest {
        val store = object : DataStore<Preferences> {
            override val data = MutableStateFlow<Preferences>(mutablePreferencesOf(ReaderPrefs.Keys.UPDATED_AT to 100L))
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                transform(data.value).also { data.value = it }
        }
        val prefs = ReaderPrefs(store, initiallyDark = false, fontAvailable = { true }, scope = backgroundScope)
        prefs.setBookLayoutEnabled("local", true)
        val layout = ReaderSettings(paragraphSpacingEm = 1.2f, showImages = true)
        prefs.update(layout, localBook = true, bookId = "local")
        val beforeSync = prefs.backupSnapshot()
        assertEquals(100L, beforeSync.updatedAt)
        assertEquals("0.6", beforeSync.values["para_spacing"])
        assertTrue(beforeSync.bookLayouts.getValue("local").updatedAt > 100L)
        assertTrue(prefs.restoreBackup(BackupSettings(200, mapOf("font_size_sp" to "22"))))
        assertEquals("22.0", prefs.backupSnapshot().values["font_size_sp"])
        prefs.update(layout.copy(lightweightEffects = true), localBook = true, bookId = "local")
        assertEquals(200L, prefs.backupSnapshot().updatedAt)
        prefs.update(layout.copy(keepScreenOn = false), localBook = true, bookId = "local")
        assertTrue(prefs.backupSnapshot().updatedAt > 200L)
    }

    @Test fun `local reader edit after snapshot wins without changing any field or stamp`() {
        val keys = ReaderPrefs.Keys
        val p = mutablePreferencesOf(keys.UPDATED_AT to 300L, keys.FONT_SIZE to 24f, keys.SHOW_IMAGES to true)
        val before = p.toPreferences()
        assertFalse(ReaderBackupImport.apply(p, BackupSettings(200, mapOf("font_size_sp" to "16"))) { true })
        assertEquals(before, p)
    }

    @Test fun `reader settings themes schemes and remote timestamp change together`() {
        val keys = ReaderPrefs.Keys
        val p = mutablePreferencesOf(keys.UPDATED_AT to 100L, keys.FONT_ID to "file:local.ttf", keys.PRELOAD_CHAPTERS to 5)
        assertTrue(ReaderBackupImport.apply(p, BackupSettings(200, mapOf(
            "font_size_sp" to "22", "show_images" to "true", "theme_day" to ReaderTheme.CUSTOM_ID,
            "custom_day_bg" to "123", "custom_day_text" to "456", "theme_night" to ReaderTheme.NIGHT.id,
            "layout_schemes" to "[]", "font_id" to "file:missing.ttf",
        ))) { false })
        assertEquals(200L, p[keys.UPDATED_AT])
        assertEquals(22f, p[keys.FONT_SIZE])
        assertEquals(true, p[keys.SHOW_IMAGES])
        assertEquals(ReaderTheme.CUSTOM_ID, p[keys.THEME_DAY])
        assertEquals(123L, p[keys.CUSTOM_DAY_BG])
        assertEquals(456L, p[keys.CUSTOM_DAY_TEXT])
        assertEquals(ReaderTheme.NIGHT.id, p[keys.THEME_NIGHT])
        assertEquals("[]", p[keys.LAYOUT_SCHEMES])
        assertEquals("file:local.ttf", p[keys.FONT_ID])
        assertEquals(5, p[keys.PRELOAD_CHAPTERS])
        assertFalse(ReaderBackupImport.apply(p, BackupSettings(200, mapOf("font_size_sp" to "10"))) { true })
    }

    @Test fun `legacy reader theme maps to daytime without changing night or accepting invalid numeric value`() {
        val keys = ReaderPrefs.Keys
        val p = mutablePreferencesOf(keys.THEME_NIGHT to ReaderTheme.NIGHT.id, keys.FONT_SIZE to 21f)
        assertTrue(ReaderBackupImport.apply(p, BackupSettings(100, mapOf(
            "theme" to ReaderTheme.CUSTOM_ID, "theme_custom_bg" to "123", "theme_custom_text" to "456",
            "font_size_sp" to "NaN", "flip" to "UNKNOWN",
        ))) { true })
        assertEquals(ReaderTheme.CUSTOM_ID, p[keys.THEME_DAY])
        assertEquals(ReaderTheme.NIGHT.id, p[keys.THEME_NIGHT])
        assertEquals(21f, p[keys.FONT_SIZE])
        assertEquals(123L, p[keys.CUSTOM_DAY_BG])
    }

    @Test fun `local app edit after snapshot wins including local-only history`() {
        val keys = AppPrefs.Keys
        val p = mutablePreferencesOf(keys.UPDATED_AT to 300L, keys.SEARCH_HISTORY to "本机记录", keys.EXPLORE_ENABLED to true)
        val before = p.toPreferences()
        assertFalse(AppPrefs.BackupImport.applyBackup(p, BackupSettings(200, mapOf("explore_enabled" to "false"))))
        assertEquals(before, p)
    }

    @Test fun `app import preserves absent fields and clears history when remote disables it`() {
        val keys = AppPrefs.Keys
        val p = mutablePreferencesOf(keys.UPDATED_AT to 100L, keys.SEARCH_HISTORY to "旧词", keys.THEME_LIGHT to "custom")
        assertTrue(AppPrefs.BackupImport.applyBackup(p, BackupSettings(200, mapOf("search_history_enabled" to "false"))))
        assertEquals(200L, p[keys.UPDATED_AT])
        assertEquals("custom", p[keys.THEME_LIGHT])
        assertFalse(p.contains(keys.SEARCH_HISTORY))
    }

    @Test fun `local and online image preferences remain independent across backup versions`() {
        val keys = ReaderPrefs.Keys
        val p = mutablePreferencesOf(keys.LOCAL_SHOW_IMAGES to false, keys.SHOW_IMAGES to false)
        assertTrue(ReaderBackupImport.apply(p, BackupSettings(100, mapOf("show_images" to "true"))) { true })
        assertEquals(false, p[keys.LOCAL_SHOW_IMAGES])
        assertEquals(true, p[keys.SHOW_IMAGES])
        assertTrue(ReaderBackupImport.apply(p, BackupSettings(200, mapOf("local_show_images" to "true", "show_images" to "false"))) { true })
        assertEquals(true, p[keys.LOCAL_SHOW_IMAGES])
        assertEquals(false, p[keys.SHOW_IMAGES])
    }

    @Test fun `header and footer switches restore from backup, older backups leave them alone`() {
        val keys = ReaderPrefs.Keys
        val p = mutablePreferencesOf(keys.UPDATED_AT to 100L)
        // 0.6.1 之前的备份没有这两项：照旧不写，读出来是默认的「显示」
        assertTrue(ReaderBackupImport.apply(p, BackupSettings(200, mapOf("show_battery" to "false"))) { true })
        assertFalse(p.contains(keys.SHOW_HEADER))
        assertFalse(p.contains(keys.SHOW_FOOTER))
        assertTrue(ReaderBackupImport.apply(p, BackupSettings(300, mapOf("show_header" to "false", "show_footer" to "false"))) { true })
        assertEquals(false, p[keys.SHOW_HEADER])
        assertEquals(false, p[keys.SHOW_FOOTER])
        assertEquals(false, p[keys.SHOW_BATTERY])
    }

    @Test fun `backup restores a negative header offset without writing negative spacing to the legacy key`() {
        val keys = ReaderPrefs.Keys
        val p = mutablePreferencesOf()
        assertTrue(ReaderBackupImport.apply(p, BackupSettings(100, mapOf(
            "header_top_spacing" to "0", "header_top_offset" to "-16", "margin_v" to "0",
        ))) { true })
        assertEquals(0f, p[keys.HEADER_TOP_SPACING])
        assertEquals(-16f, p[keys.HEADER_TOP_OFFSET])
        assertEquals(0f, p[keys.MARGIN_V])

        assertTrue(ReaderBackupImport.apply(p, BackupSettings(200, mapOf("font_size_sp" to "20"))) { true })
        assertEquals(-16f, p[keys.HEADER_TOP_OFFSET])

        assertTrue(ReaderBackupImport.apply(p, BackupSettings(300, mapOf("header_top_spacing" to "8"))) { true })
        assertEquals(8f, p[keys.HEADER_TOP_SPACING])
        assertEquals(0f, p[keys.HEADER_TOP_OFFSET])
    }

    @Test fun `battery display style restores independently of the old visibility switch`() {
        val keys = ReaderPrefs.Keys
        val p = mutablePreferencesOf(keys.SHOW_BATTERY to false)
        BatteryDisplayStyle.entries.forEachIndexed { index, style ->
            assertTrue(ReaderBackupImport.apply(p, BackupSettings(index + 1L, mapOf("battery_display_style" to style.name))) { true })
            assertEquals(style.name, p[keys.BATTERY_DISPLAY_STYLE])
            assertEquals(false, p[keys.SHOW_BATTERY])
        }
        assertTrue(ReaderBackupImport.apply(p, BackupSettings(100, mapOf("show_battery" to "true"))) { true })
        assertEquals(BatteryDisplayStyle.PERCENT_ONLY.name, p[keys.BATTERY_DISPLAY_STYLE])
        assertEquals(true, p[keys.SHOW_BATTERY])
        assertTrue(ReaderBackupImport.apply(p, BackupSettings(200, mapOf("battery_display_style" to "UNKNOWN"))) { true })
        assertEquals(BatteryDisplayStyle.PERCENT_ONLY.name, p[keys.BATTERY_DISPLAY_STYLE])
    }

    @Test fun `reader status bar imports both values and keeps the local setting for older backups`() {
        val keys = ReaderPrefs.Keys
        val p = mutablePreferencesOf()
        assertTrue(ReaderBackupImport.apply(p, BackupSettings(100, mapOf("show_status_bar" to "true"))) { true })
        assertEquals(true, p[keys.SHOW_STATUS_BAR])
        assertTrue(ReaderBackupImport.apply(p, BackupSettings(200, emptyMap())) { true })
        assertEquals(true, p[keys.SHOW_STATUS_BAR])
        assertTrue(ReaderBackupImport.apply(p, BackupSettings(300, mapOf("show_status_bar" to "false"))) { true })
        assertEquals(false, p[keys.SHOW_STATUS_BAR])
    }

    @Test fun `reader backup distinguishes saved zero spacing, defaults, and omitted values`() {
        val keys = ReaderPrefs.Keys
        val p = mutablePreferencesOf()
        assertTrue(ReaderBackupImport.apply(p, BackupSettings(100, mapOf(
            "raise_header" to "true", "title_top_spacing" to "0", "header_top_spacing" to "0",
        ))) { true })
        assertTrue(ReaderBackupImport.apply(p, BackupSettings(200, emptyMap())) { true })
        assertEquals(true, p[keys.RAISE_HEADER])
        assertEquals(0f, p[keys.TITLE_TOP_SPACING])
        assertEquals(0f, p[keys.HEADER_TOP_SPACING])

        assertTrue(ReaderBackupImport.apply(p, BackupSettings(300, mapOf(
            "raise_header" to "false", "title_top_spacing" to "", "header_top_spacing" to "",
        ))) { true })
        assertEquals(false, p[keys.RAISE_HEADER])
        assertFalse(p.contains(keys.TITLE_TOP_SPACING))
        assertFalse(p.contains(keys.HEADER_TOP_SPACING))
        assertFalse(p.contains(keys.HEADER_TOP_OFFSET))
    }
}
