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
            // 摘要小字（设置行副标题、空态提示）与分组小标题既坐在页面底上，也坐在卡片上：两处都得够 4.5:1
            for ((name, text) in listOf(
                "onSurfaceVariantSummary" to scheme.onSurfaceVariantSummary,
                "onBackgroundVariant" to scheme.onBackgroundVariant,
            )) {
                assertTrue(
                    contrast(text, scheme.surface) >= 4.5f,
                    "$name/surface 对比不足: seed=$seed bg=$bg dark=$dark",
                )
                assertTrue(
                    contrast(text, scheme.surfaceContainer) >= 4.5f,
                    "$name/surfaceContainer 对比不足: seed=$seed bg=$bg dark=$dark",
                )
            }
            // 下拉菜单选中项、选中 chip：淡强调底上的强调色字
            assertTrue(
                contrast(scheme.onTertiaryContainer, scheme.tertiaryContainer) >= 4.5f,
                "onTertiaryContainer/tertiaryContainer 对比不足: seed=$seed bg=$bg dark=$dark",
            )
        }
    }

    @Test
    fun `HyperOS 预设的正文、小标题与选中色都够 4点5 比 1`() {
        // Miuix 默认配色原样搬来的槽位里，有带透明度的摘要色（0x99000000 / 0x80FFFFFF）：
        // 先叠到底色上再算，直接拿 luminance 会把 60% 黑当成纯黑
        listOf(AppThemes.lightPresets, AppThemes.darkPresets).forEach { presets ->
            val s = presets.first { it.id == AppThemes.HYPEROS }.scheme
            for (bg in listOf(s.surface, s.surfaceContainer)) {
                assertTrue(contrast(over(s.onSurfaceVariantSummary, bg), bg) >= 4.5f, "摘要字对比不足 bg=$bg")
                assertTrue(contrast(s.onBackgroundVariant, bg) >= 4.5f, "分组小标题对比不足 bg=$bg")
                assertTrue(contrast(s.onSurface, bg) >= 4.5f, "正文对比不足 bg=$bg")
            }
            assertTrue(contrast(s.onTertiaryContainer, s.tertiaryContainer) >= 4.5f, "淡强调底上的强调字对比不足")
            assertTrue(contrast(s.onPrimary, s.primary) >= 3.0f, "按钮字对比不足")
        }
    }

    /** 半透明前景叠到不透明底色上的实际颜色 */
    private fun over(fg: Color, bg: Color): Color = Color(
        red = fg.red * fg.alpha + bg.red * (1 - fg.alpha),
        green = fg.green * fg.alpha + bg.green * (1 - fg.alpha),
        blue = fg.blue * fg.alpha + bg.blue * (1 - fg.alpha),
    )

    @Test
    fun `默认是 HyperOS 配色`() {
        val (light, _) = AppThemes.resolve(ThemeConfig(mode = ThemeMode.LIGHT), systemDark = false)
        assertEquals(Color(0xFFF7F7F7), light.surface)
        assertEquals(Color(0xFF3482FF), light.primary)
        val (dark, _) = AppThemes.resolve(ThemeConfig(mode = ThemeMode.DARK), systemDark = false)
        assertEquals(Color.Black, dark.surface)
    }

    @Test
    fun `提示胶囊上的正文与操作键都读得清`() {
        // AppSnackbar：onSecondaryVariant 底、secondaryVariant 字，操作键是推过的强调色。
        // 浅色主题的强调色本身就是深色，原样放在近黑的胶囊上只有约 1.9:1
        (AppThemes.lightPresets + AppThemes.darkPresets).forEach { preset ->
            val s = preset.scheme
            val container = s.onSecondaryVariant
            assertTrue(
                contrast(s.secondaryVariant, container) >= 4.5f,
                "提示正文对比不足: ${preset.id}",
            )
            val action = AppThemes.readableOn(s.primary, container)
            assertTrue(
                contrast(action, container) >= 4.5f,
                "提示操作键对比不足: ${preset.id} action=$action container=$container",
            )
        }
    }

    @Test
    fun `浅色灰底白卡、深色黑底亮卡`() {
        // Miuix 的分层：页面底 surface、卡片 surfaceContainer。浅色卡就是预设的纸色、页面压深一档；
        // 深色页面就是预设的底色、卡片往亮里抬 —— 两边都得是「卡比页面更像纸」
        AppThemes.lightPresets.forEach { preset ->
            val s = preset.scheme
            assertEquals(preset.previewBg, s.surfaceContainer, "浅色卡片应是纸色本身: ${preset.id}")
            assertTrue(s.surface.luminance() < s.surfaceContainer.luminance(), "浅色页面应比卡片深: ${preset.id}")
        }
        AppThemes.darkPresets.forEach { preset ->
            val s = preset.scheme
            assertEquals(preset.previewBg, s.surface, "深色页面应是底色本身: ${preset.id}")
            assertTrue(s.surfaceContainer.luminance() > s.surface.luminance(), "深色卡片应比页面亮: ${preset.id}")
        }
    }

    @Test
    fun `dark list cards stay distinct from page background`() {
        // 卡片（surfaceContainer）坐在页面底（surface）上；纯黑预设上若只提亮 3%，
        // 卡片会糊进页面，夜间「看不见容器」、日间却正常 —— 就是这个不对称（所以台阶走 sRGB 混合而非 Oklab）。
        AppThemes.darkPresets.forEach { preset ->
            val scheme = preset.scheme
            assertTrue(
                contrast(scheme.surfaceContainer, scheme.surface) >= 1.12f,
                "surfaceContainer 相对 surface 太近: preset=${preset.id} " +
                    "card=${scheme.surfaceContainer} page=${scheme.surface}",
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
        assertEquals(Color(0xFF000000), scheme.surface)

        val (lightScheme, lightDark) =
            AppThemes.resolve(config.copy(mode = ThemeMode.LIGHT, lightPreset = AppThemes.LIGHT_PAPER), systemDark = true)
        assertEquals(false, lightDark)
        assertEquals(Color(0xFFFFFBEB), lightScheme.surfaceContainer)
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
        assertEquals(Color(0xFFF5FDFD), scheme.surfaceContainer)
    }

    @Test
    fun `跟随壁纸按日夜各取系统给的那套`() {
        val wallpaperLight = AppThemes.schemeFrom(Color(0xFF00897B), Color(0xFFFAFAFA), dark = false)
        val wallpaperDark = AppThemes.schemeFrom(Color(0xFF80CBC4), Color(0xFF101414), dark = true)
        val config = ThemeConfig(lightPreset = AppThemes.DYNAMIC, darkPreset = AppThemes.DYNAMIC)
        val provider = { dark: Boolean -> if (dark) wallpaperDark else wallpaperLight }
        assertEquals(wallpaperLight to false, AppThemes.resolve(config, systemDark = false, dynamicScheme = provider))
        assertEquals(wallpaperDark to true, AppThemes.resolve(config, systemDark = true, dynamicScheme = provider))
    }

    @Test
    fun `只有一个槽选了壁纸时另一个槽照常用预设`() {
        val config = ThemeConfig(lightPreset = AppThemes.DYNAMIC, darkPreset = AppThemes.DARK_BLACK)
        val wallpaper = AppThemes.schemeFrom(Color(0xFF00897B), Color(0xFFFAFAFA), dark = false)
        val (dark, _) = AppThemes.resolve(config, systemDark = true) { wallpaper }
        assertEquals(AppThemes.darkPresets.first { it.id == AppThemes.DARK_BLACK }.scheme, dark)
    }

    @Test
    fun `拿不到壁纸配色时回落第一个预设，不崩`() {
        val config = ThemeConfig(lightPreset = AppThemes.DYNAMIC)
        val (scheme, _) = AppThemes.resolve(config, systemDark = false)
        assertEquals(AppThemes.lightPresets.first().scheme, scheme)
    }
}
