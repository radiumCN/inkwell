package com.radium.inkwell.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.radium.inkwell.ui.theme.AppThemes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 底部面板里的分组卡（[sheetCardColor]）：要和面板底分得开，卡上的摘要小字、正文要读得清。
 *
 * Miuix 默认配色里面板底 `background` 和卡片色 `surfaceContainer` 是同一个色 —— 卡直接放进面板就看不见，
 * 从前面板里一条一卡的行就是这样。Miuix 示例 App 在面板里给卡换成 `secondaryContainer`，但深色下它是 #434343，
 * 摘要色（半透明白）压在上面只有约 3.8:1；一律取 `surfaceContainerHighest` 的话，几套深色品牌预设上又掉到 4.4:1。
 * 规则见 [sheetCardColor]，这里把每套预设都过一遍。
 */
class SheetCardTest {

    private fun contrast(a: Color, b: Color): Float {
        val l1 = maxOf(a.luminance(), b.luminance()) + 0.05f
        val l2 = minOf(a.luminance(), b.luminance()) + 0.05f
        return l1 / l2
    }

    /** 半透明前景叠到不透明底色上的实际颜色（HyperOS 的摘要色带透明度） */
    private fun over(fg: Color, bg: Color): Color = Color(
        red = fg.red * fg.alpha + bg.red * (1 - fg.alpha),
        green = fg.green * fg.alpha + bg.green * (1 - fg.alpha),
        blue = fg.blue * fg.alpha + bg.blue * (1 - fg.alpha),
    )

    @Test
    fun `面板里的卡与面板底分得开、卡上的字读得清`() {
        (AppThemes.lightPresets + AppThemes.darkPresets).forEach { preset ->
            val s = preset.scheme
            val card = sheetCardColor(s, s.background)
            assertTrue(contrast(card, s.background) >= 1.08f, "卡和面板底太近: ${preset.id} card=$card sheet=${s.background}")
            assertTrue(
                contrast(over(s.onSurfaceVariantSummary, card), card) >= 4.5f,
                "卡上的摘要字对比不足: ${preset.id} card=$card",
            )
            assertTrue(contrast(s.onSurface, card) >= 4.5f, "卡上的正文对比不足: ${preset.id} card=$card")
        }
    }

    @Test
    fun `面板底本来就和卡片色不同时（阅读器浮层）照旧用卡片色`() {
        val s = AppThemes.lightPresets.first().scheme
        val overlay = Color(0xFF808080)
        assertEquals(s.surfaceContainer, sheetCardColor(s, overlay))
    }
}
