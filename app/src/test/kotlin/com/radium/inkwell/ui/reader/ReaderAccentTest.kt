package com.radium.inkwell.ui.reader

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.radium.inkwell.reader.api.ReaderTheme
import com.radium.inkwell.ui.theme.AppThemes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 阅读浮层的强调色：App 任一预设的主色、放在任一款纸上，都得够 4.5:1 ——
 * 它当文字色用（文字按钮），也是实心按钮的底（上面的字是纸色）。不够线就是阅读设置里看不清选中的是哪一档。
 * 有色的纸不再用 App 主色，而是从纸的色相里取（[paperAccent]）：HyperOS 蓝压在米色纸上不协调。
 */
class ReaderAccentTest {

    private fun contrast(a: Color, b: Color): Float {
        val hi = maxOf(a.luminance(), b.luminance()) + 0.05f
        val lo = minOf(a.luminance(), b.luminance()) + 0.05f
        return hi / lo
    }

    @Test
    fun `每一款纸配每一个 App 主色，强调色都够 4_5 比 1`() {
        val primaries = (AppThemes.lightPresets + AppThemes.darkPresets).map { it.label to it.scheme.primary }
        for (paper in ReaderTheme.ALL) {
            val bg = Color(paper.background)
            for ((name, primary) in primaries) {
                val accent = readableOn(primary, bg)
                val ratio = contrast(accent, bg)
                assertTrue(ratio >= 4.5f, "${paper.label} × $name：%.2f:1".format(ratio))
            }
        }
    }

    @Test
    fun `有色的纸取纸的色相，换成哪个 App 主色都一样`() {
        val tinted = listOf(ReaderTheme.PAPER, ReaderTheme.RICE, ReaderTheme.KRAFT, ReaderTheme.IVORY, ReaderTheme.GREEN, ReaderTheme.INKBLUE)
        val hyperOsBlue = Color(0xFF3482FF)
        for (paper in tinted) {
            val bg = Color(paper.background)
            val accent = paperAccent(bg, hyperOsBlue)
            assertNotEquals(hyperOsBlue, accent, paper.label)
            assertEquals(accent, paperAccent(bg, Color(0xFF92400E)), "${paper.label}：强调色不该再跟 App 主色走")
            val ratio = contrast(readableOn(accent, bg), bg)
            assertTrue(ratio >= 4.5f, "${paper.label}：%.2f:1".format(ratio))
        }
    }

    @Test
    fun `米色一族都落在褐色上，不是压浓的土黄`() {
        for (paper in listOf(ReaderTheme.PAPER, ReaderTheme.RICE, ReaderTheme.KRAFT, ReaderTheme.IVORY)) {
            val accent = paperAccent(Color(paper.background), Color(0xFF3482FF))
            // 褐：红 > 绿 > 蓝，且红绿拉得开（土黄的红绿几乎相等）
            assertTrue(accent.red > accent.green && accent.green > accent.blue, paper.label)
            assertTrue(accent.red - accent.green > 0.1f, "${paper.label}：$accent")
        }
    }

    @Test
    fun `无色的纸沿用 App 主色`() {
        val appAccent = Color(0xFF3482FF)
        val neutral = listOf(ReaderTheme.WHITE, ReaderTheme.STONE, ReaderTheme.NIGHT, ReaderTheme.CHARCOAL, ReaderTheme.OBSIDIAN, ReaderTheme.OLED)
        for (paper in neutral) {
            assertEquals(appAccent, paperAccent(Color(paper.background), appAccent), paper.label)
        }
    }

    @Test
    fun `本来就够线的颜色原样不动`() {
        val dark = Color(0xFF2B3A55)
        assertEquals(dark, readableOn(dark, Color(ReaderTheme.WHITE.background)))
    }
}
