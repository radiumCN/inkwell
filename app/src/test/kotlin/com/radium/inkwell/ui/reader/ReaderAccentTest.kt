package com.radium.inkwell.ui.reader

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.radium.inkwell.reader.api.ReaderTheme
import com.radium.inkwell.ui.theme.AppThemes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 阅读浮层的强调色：App 任一预设的主色、放在任一款纸上，都得够 4.5:1 ——
 * 它当文字色用（文字按钮），也是实心按钮的底（上面的字是纸色）。不够线就是阅读设置里看不清选中的是哪一档。
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
    fun `本来就够线的颜色原样不动`() {
        val dark = Color(0xFF2B3A55)
        assertEquals(dark, readableOn(dark, Color(ReaderTheme.WHITE.background)))
    }
}
