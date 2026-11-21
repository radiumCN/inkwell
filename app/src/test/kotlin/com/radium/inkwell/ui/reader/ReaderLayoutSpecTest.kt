package com.radium.inkwell.ui.reader

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import com.radium.inkwell.reader.api.ReaderSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 页眉 / 页脚开关：关掉的那一边，分页时那条带给 0，正文顺势占用；边距（头部留白里折着挖孔、下边距）不跟着收。
 * 这一步只在 buildLayoutSpec 里，画的那一层（PageInfoBar）只管不画 —— 两边对不上的话，
 * 要么字被藏了却空着一条带，要么带子让出去了字还压在正文上。
 */
class ReaderLayoutSpecTest {

    private val density = Density(density = 3f, fontScale = 1f)
    private val cutout = IntInsets(0, 90, 0, 0)

    private fun spec(settings: ReaderSettings) =
        buildLayoutSpec(IntSize(1080, 2400), settings, density, cutout, 18.sp, speechInsetPx = 0f)

    @Test
    fun `默认页眉页脚都在，两条带是 26 和 18 dp`() {
        val s = spec(ReaderSettings())
        assertEquals(26f * 3, s.headerHeightPx)
        assertEquals(18f * 3, s.footerHeightPx)
    }

    @Test
    fun `关掉页眉，页眉那条带整个让给正文，头部留白和挖孔照旧`() {
        val shown = spec(ReaderSettings())
        val hidden = spec(ReaderSettings(showHeader = false))
        assertEquals(0f, hidden.headerHeightPx)
        assertEquals(shown.marginTopPx, hidden.marginTopPx)
        assertEquals(shown.footerHeightPx, hidden.footerHeightPx)
        assertEquals(shown.contentHeightPx + shown.headerHeightPx, hidden.contentHeightPx)
    }

    @Test
    fun `关掉页脚，页脚那条带让给正文，下边距照旧`() {
        val shown = spec(ReaderSettings())
        val hidden = spec(ReaderSettings(showFooter = false))
        assertEquals(0f, hidden.footerHeightPx)
        assertEquals(shown.marginBottomPx, hidden.marginBottomPx)
        assertEquals(shown.headerHeightPx, hidden.headerHeightPx)
        assertEquals(shown.contentHeightPx + shown.footerHeightPx, hidden.contentHeightPx)
    }

    @Test
    fun `只关电量不动排版`() {
        assertEquals(spec(ReaderSettings()), spec(ReaderSettings(showBattery = false)))
        assertTrue(spec(ReaderSettings(showHeader = false, showFooter = false)).contentHeightPx > spec(ReaderSettings()).contentHeightPx)
    }
}
