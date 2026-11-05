package com.radium.inkwell.ui.components

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.radium.inkwell.ui.theme.AppThemes
import top.yukonga.miuix.kmp.theme.Colors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 封面毛玻璃上的字读不读得清。
 *
 * 糊得再狠，一张几乎纯色的封面糊开还是那个颜色，所以按**最坏的封面**算：浅色主题的字是深色，最坏是一张纯黑封面；
 * 深色主题反过来是纯白。只算薄底叠在封面上，不算 Colored Thick 混色 —— 那组混色在浅色下只会把黑往亮处提、
 * 深色下只会把白往暗处压，都是帮字的，不算它是往保守里算。
 */
class CoverGlassTest {

    private fun contrast(a: Color, b: Color): Float {
        val l1 = maxOf(a.luminance(), b.luminance()) + 0.05f
        val l2 = minOf(a.luminance(), b.luminance()) + 0.05f
        return l1 / l2
    }

    /** 半透明前景叠到不透明底色上的实际颜色（sRGB 里混，和 Compose 画出来的一样） */
    private fun over(fg: Color, bg: Color): Color = Color(
        red = fg.red * fg.alpha + bg.red * (1 - fg.alpha),
        green = fg.green * fg.alpha + bg.green * (1 - fg.alpha),
        blue = fg.blue * fg.alpha + bg.blue * (1 - fg.alpha),
    )

    private fun worstCover(dark: Boolean) = if (dark) Color.White else Color.Black

    /** 预设 + 几组极端的自定义配色（与 AppThemesTest 同一批） */
    private val schemes = buildList {
        AppThemes.lightPresets.forEach { add(it.scheme to false) }
        AppThemes.darkPresets.forEach { add(it.scheme to true) }
        listOf(
            Triple(Color(0xFF92400E), Color(0xFFFFFBEB), false),
            Triple(Color(0xFF2B3A55), Color(0xFFFFFFFF), false),
            Triple(Color(0xFFE8A33D), Color(0xFF17140F), true),
            Triple(Color(0xFF9E9E9E), Color(0xFF000000), true),
            Triple(Color(0xFF00BCD4), Color(0xFFF0FDFF), false),
        ).forEach { (seed, bg, dark) -> add(AppThemes.schemeFrom(seed, bg, dark) to dark) }
    }

    /** [text] 压在「[base] 色以 alpha 叠在最坏封面上」的底上，够不够 4.5:1 */
    private fun readable(text: Color, base: Color, alpha: Float, dark: Boolean): Boolean {
        val bg = over(base.copy(alpha = alpha), worstCover(dark))
        return contrast(over(text, bg), bg) >= 4.5f
    }

    /** 这套配色里，薄底最少要多厚这几种字才都读得清（二分到千分之一） */
    private fun minAlpha(base: Color, texts: List<Color>, dark: Boolean): Float {
        var lo = 0f
        var hi = 1f
        repeat(10) {
            val mid = (lo + hi) / 2
            if (texts.all { readable(it, base, mid, dark) }) hi = mid else lo = mid
        }
        return hi
    }

    /** 浅色、深色各自最难的那套配色要多厚；常量必须不比它薄。失败信息里直接报出要改成多少 */
    private fun assertVeil(name: String, light: Float, dark: Float, need: (Colors, Boolean) -> Float) {
        for ((isDark, actual) in listOf(false to light, true to dark)) {
            val worst = schemes.filter { it.second == isDark }.maxOf { (s, d) -> need(s, d) }
            assertTrue(
                actual >= worst,
                "$name（${if (isDark) "深色" else "浅色"}）薄底 $actual 不够，最难的配色要 ${"%.3f".format(worst)}",
            )
        }
    }

    @Test
    fun `玻璃卡上的字压在最坏的封面上也够 4点5 比 1`() {
        assertVeil("玻璃卡正文", CARD_VEIL_LIGHT, CARD_VEIL_DARK) { s, dark ->
            minAlpha(s.surfaceContainer, listOf(s.onSurface), dark)
        }
        // coverGlassVeil 用的就是这两个常量
        schemes.forEach { (s, dark) ->
            assertTrue(readable(s.onSurface, s.surfaceContainer, coverGlassVeil(s.surfaceContainer, dark).alpha, dark))
        }
    }

    @Test
    fun `详情页头部最淡的小字压在最坏的封面上也够 4点5 比 1`() {
        // 书名 onSurface、作者与章数 onSurfaceVariantSummary、简介 onSurfaceSecondary
        assertVeil("详情页头部", HEADER_VEIL_LIGHT, HEADER_VEIL_DARK) { s, dark ->
            minAlpha(s.surface, listOf(s.onSurface, s.onSurfaceVariantSummary, s.onSurfaceSecondary), dark)
        }
        schemes.forEach { (s, dark) ->
            val alpha = coverHeaderVeil(s.surface, dark).alpha
            assertTrue(readable(s.onSurfaceVariantSummary, s.surface, alpha, dark))
        }
    }

    @Test
    fun `毛玻璃提示胶囊上的正文与操作键压在最坏的页面上也够 4点5 比 1`() {
        // 胶囊色是 onSecondaryVariant（浅色主题近黑、深色主题近白），最坏的页面内容是和它反着的那一片纯色。
        // 正文定了叠多厚；操作键的颜色是按这块最坏的底现推的（AppSnackbar），推完也得够
        for (isDark in listOf(false, true)) {
            val worst = schemes.filter { it.second == isDark }.maxOf { (s, _) ->
                val container = s.onSecondaryVariant
                val under = if (container.luminance() < 0.5f) Color.White else Color.Black
                var lo = 0f
                var hi = 1f
                repeat(10) {
                    val mid = (lo + hi) / 2
                    if (contrast(s.secondaryVariant, over(container.copy(alpha = mid), under)) >= 4.5f) hi = mid else lo = mid
                }
                hi
            }
            assertTrue(
                SNACKBAR_GLASS_ALPHA >= worst,
                "提示胶囊正文（${if (isDark) "深色" else "浅色"}）叠色 $SNACKBAR_GLASS_ALPHA 不够，最难的配色要 ${"%.3f".format(worst)}",
            )
        }
        schemes.forEach { (s, dark) ->
            val floor = snackbarGlassFloor(s.onSecondaryVariant)
            val action = AppThemes.readableOn(s.primary, floor)
            assertTrue(contrast(s.secondaryVariant, floor) >= 4.5f, "提示胶囊正文对比不足 dark=$dark")
            assertTrue(contrast(action, floor) >= 4.5f, "提示胶囊操作键推不到 4.5:1 dark=$dark action=$action")
        }
    }

    @Test
    fun `多选操作条的玻璃上正文和删除键压在最坏的页面上也够 4点5 比 1`() {
        // 操作条浮在列表上（书架网格里一排封面），最坏同封面玻璃卡：浅色下面一片纯黑、深色下面一片纯白。
        // 正文 onSurface 由薄底保证；「删除」的 error 红有的配色放在不透明的卡片上都不到 4.5:1，加厚薄底救不回来，
        // 所以是按这块最坏的底现推的（同提示胶囊的操作键），推完也得够。
        // 同一块玻璃（FloatingGlassBar）还托着书架的隐藏区胶囊：「无法上锁」那行说明色也是现推的
        assertVeil("多选操作条", TOOLBAR_VEIL_LIGHT, TOOLBAR_VEIL_DARK) { s, dark ->
            minAlpha(s.surfaceContainer, listOf(s.onSurface), dark)
        }
        schemes.forEach { (s, dark) ->
            val floor = toolbarGlassFloor(s.surfaceContainer, dark)
            val delete = AppThemes.readableOn(s.error, floor)
            val summary = AppThemes.readableOn(s.onSurfaceVariantSummary, floor)
            assertTrue(contrast(delete, floor) >= 4.5f, "操作条删除键推不到 4.5:1 dark=$dark")
            assertTrue(contrast(summary, floor) >= 4.5f, "隐藏区胶囊说明字推不到 4.5:1 dark=$dark")
            assertTrue(contrast(s.onSurface, floor) >= 4.5f, "操作条正文对比不足 dark=$dark")
        }
    }

    @Test
    fun `毛玻璃弹出列表上的正文、说明和选中项压在最坏的页面上也够 4点5 比 1`() {
        // 正文不靠推：叠色厚度本身就要让 onSurfaceContainer 够 4.5:1（推正文会把它推成纯黑 / 纯白，和说明字分不出层级）
        assertVeil("弹出列表", POPUP_GLASS_ALPHA_LIGHT, POPUP_GLASS_ALPHA_DARK) { s, dark ->
            minAlpha(s.surfaceContainer, listOf(s.onSurfaceContainer), dark)
        }
        // 说明小字、选中项的强调色是按这块最坏的底现推的（GlassDropdownPopup），推完必须够
        schemes.forEach { (s, dark) ->
            val floor = popupGlassFloor(s.surfaceContainer, dark)
            listOf(
                "正文" to s.onSurfaceContainer,
                "说明" to s.onSurfaceVariantSummary,
                "选中项" to s.primary,
            ).forEach { (name, color) ->
                val pushed = AppThemes.readableOn(color, floor)
                assertTrue(contrast(pushed, floor) >= 4.5f, "弹出列表$name 推不到 4.5:1 dark=$dark")
            }
        }
    }

    @Test
    fun `玻璃的 squircle 形状把圆角半径交给边缘高光`() {
        // miuix-blur 的边缘高光只从 CornerBasedShape 读圆角半径，认不出的 Shape 按短边的一半画 ——
        // 卡片中间多出一个胶囊形的椭圆（0.5.1-beta.1 的「继续阅读」卡）
        val shape = assertIs<CornerBasedShape>(squircleShape(16.dp))
        val size = Size(900f, 400f)
        val density = Density(3f)
        assertEquals(48f, shape.topStart.toPx(size, density))
        assertEquals(48f, shape.bottomEnd.toPx(size, density))
    }
}
