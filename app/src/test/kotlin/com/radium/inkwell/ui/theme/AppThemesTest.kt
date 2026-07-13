package com.radium.inkwell.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppThemesTest {

    /** WCAG 对比度 */
    private fun contrast(a: Color, b: Color): Float {
        val l1 = maxOf(a.luminance(), b.luminance()) + 0.05f
        val l2 = minOf(a.luminance(), b.luminance()) + 0.05f
        return l1 / l2
    }

    @Test
    fun `derived schemes keep body text readable`() {
        // 任意自定义组合下正文对比度必须 >= 4.5:1（含极端浅背景与纯黑背景）
        val cases = listOf(
            Triple(Color(0xFF92400E), Color(0xFFFFFBEB), false),
            Triple(Color(0xFF2B3A55), Color(0xFFFFFFFF), false),
            Triple(Color(0xFFE8A33D), Color(0xFF17140F), true),
            Triple(Color(0xFF9E9E9E), Color(0xFF000000), true),
            Triple(Color(0xFF00BCD4), Color(0xFFF0FDFF), false),
        )
        cases.forEach { (seed, bg, dark) ->
            val scheme = AppThemes.schemeFrom(seed, bg, dark)
            assertTrue(
                contrast(scheme.onBackground, scheme.background) >= 4.5f,
                "onBackground/background 对比不足: seed=$seed bg=$bg dark=$dark",
            )
            assertTrue(
                contrast(scheme.onPrimary, scheme.primary) >= 3.0f,
                "onPrimary/primary 对比不足: seed=$seed bg=$bg dark=$dark",
            )
        }
    }

    @Test
    fun `resolve honors mode and presets`() {
        val config = ThemeConfig(
            mode = ThemeMode.DARK,
            darkPreset = AppThemes.DARK_BLACK,
        )
        val (scheme, dark) = AppThemes.resolve(config, systemDark = false)
        assertTrue(dark)
        assertEquals(Color(0xFF000000), scheme.background)

        val (lightScheme, lightDark) =
            AppThemes.resolve(config.copy(mode = ThemeMode.LIGHT), systemDark = true)
        assertEquals(false, lightDark)
        assertEquals(Color(0xFFFFFBEB), lightScheme.background)
    }

    @Test
    fun `custom preset uses stored colors`() {
        val config = ThemeConfig(
            mode = ThemeMode.LIGHT,
            lightPreset = AppThemes.CUSTOM,
            customLightSeed = 0xFF006064,
            customLightBg = 0xFFF5FDFD,
        )
        val (scheme, _) = AppThemes.resolve(config, systemDark = false)
        assertEquals(Color(0xFF006064), scheme.primary)
        assertEquals(Color(0xFFF5FDFD), scheme.background)
    }
}
