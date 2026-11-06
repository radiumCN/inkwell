package com.radium.inkwell.ui.reader

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.radium.inkwell.reader.api.ReaderTheme
import com.radium.inkwell.ui.theme.AppThemes
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 阅读玻璃上的强调色字（自动换源胶囊的「撤销」）读不读得清。
 *
 * 底按 [readerGlassFloor] 算：浮层色叠八成压在一整片正文色上 —— 纸面上只有纸和字，糊开的底坏不过这个。
 * 强调色和阅读主题里一样取（[paperAccent] → 纸上够线），再经 [readerGlassAction] 推一档。
 */
class ReaderGlassTest {

    private fun contrast(a: Color, b: Color): Float {
        val hi = maxOf(a.luminance(), b.luminance()) + 0.05f
        val lo = minOf(a.luminance(), b.luminance()) + 0.05f
        return hi / lo
    }

    private val primaries = (AppThemes.lightPresets + AppThemes.darkPresets).map { it.label to it.scheme.primary }

    @Test
    fun `每一款纸配每一个 App 主色，玻璃上的「撤销」都够 4_5 比 1`() {
        for (paper in ReaderTheme.ALL) {
            val bg = Color(paper.background)
            val ink = Color(paper.textColor)
            val tint = readerOverlayOf(bg)
            for ((name, primary) in primaries) {
                val accent = readableOn(paperAccent(bg, primary), bg)
                val action = readerGlassAction(accent, tint, ink)
                val ratio = contrast(action, readerGlassFloor(tint, ink))
                assertTrue(ratio >= 4.5f, "${paper.label} × $name：%.2f:1".format(ratio))
            }
        }
    }

    /**
     * 浅纸的玻璃底透进了深色的字，亮度掉到 0.5 以下：要是按「底色亮不亮过 0.5」定方向，褐会被往白里推、越推越糊。
     * 浅纸上只许往深里推，深纸上只许往亮里推
     */
    @Test
    fun `浅纸上的「撤销」往深里推，深纸上往亮里推`() {
        for (paper in ReaderTheme.ALL) {
            val bg = Color(paper.background)
            val tint = readerOverlayOf(bg)
            for ((name, primary) in primaries) {
                val accent = readableOn(paperAccent(bg, primary), bg)
                val action = readerGlassAction(accent, tint, Color(paper.textColor))
                if (paper.isDark) {
                    assertTrue(action.luminance() >= accent.luminance(), "${paper.label} × $name：深纸上被压暗了")
                } else {
                    assertTrue(action.luminance() <= accent.luminance(), "${paper.label} × $name：浅纸上被提亮了")
                }
            }
        }
    }
}
