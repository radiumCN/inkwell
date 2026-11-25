package com.radium.inkwell.ui.reader

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import com.radium.inkwell.reader.api.BatteryDisplayStyle
import com.radium.inkwell.reader.api.ReaderSettings
import com.radium.inkwell.reader.paginate.RaisedReaderHeader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReaderLayoutSpecTest {

    @Test
    fun resetRestoresAllTypesetControlsInTheCurrentScope() {
        val base = ReaderSettings(fontId = "serif", raiseHeader = true, showImages = true)
        val edited = base.copy(
            fontSizeSp = 24f, bodyFontWeight = 700, lineSpacingMult = 2f, letterSpacingEm = 0.1f,
            paragraphSpacingEm = 1f, firstLineIndentEm = 0f, justify = false, titleScale = 2f,
            titleTopSpacingDp = 32f, headerTopSpacingDp = -12f, marginHorizontalDp = 18f, marginVerticalDp = 16f,
        )
        assertEquals(base, edited.resetTypography())
    }

    @Test
    fun tocSelectionFollowsTheSectionWithinOneSpineItem() {
        val entries = listOf(
            TocItem(0, "章", key = 10), TocItem(0, "甲", anchor = "a", key = 11),
            TocItem(0, "乙", anchor = "b", key = 12), TocItem(1, "下一章", key = 13),
        )
        val anchors = mapOf("a" to 2, "b" to 8)
        assertEquals(10, currentTocKey(entries, 0, TocReadingPosition(0, anchors, null)))
        assertEquals(11, currentTocKey(entries, 0, TocReadingPosition(6, anchors, null)))
        assertEquals(12, currentTocKey(entries, 0, TocReadingPosition(9, anchors, null)))
        assertEquals(12, currentTocKey(entries, 0, TocReadingPosition(6, anchors, "b")))
        assertEquals(13, currentTocKey(entries, 1, null))
    }

    private val density = Density(density = 3f, fontScale = 1f)
    private val cutout = IntInsets(0, 90, 0, 0)
    private val statusArea = ReaderStatusArea(90, listOf(IntRect(516, 12, 564, 72)))

    private fun spec(
        settings: ReaderSettings,
        insets: IntInsets = cutout,
        speechInsetPx: Float = 0f,
        status: ReaderStatusArea = statusArea,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
    ) = buildLayoutSpec(IntSize(1080, 2400), settings, density, insets, 18.sp, speechInsetPx, status, layoutDirection)

    @Test
    fun `状态栏开启时页眉和页脚各自预留排版高度`() {
        val s = spec(ReaderSettings(showStatusBar = true))
        assertNull(s.raisedHeader)
        assertEquals(26f * 3, s.headerHeightPx)
        assertEquals(18f * 3, s.footerHeightPx)
    }

    @Test
    fun `隐藏状态栏时页眉用挖孔左侧且正文收回原页眉带`() {
        val settings = ReaderSettings(raiseHeader = true, headerTopSpacingDp = 24f)
        val raised = spec(settings)
        val regular = spec(settings.copy(showStatusBar = true))
        assertEquals(RaisedReaderHeader(18f, 72f, 588f), raised.raisedHeader)
        assertEquals(0f, raised.headerHeightPx)
        assertEquals(regular.marginTopPx, raised.marginTopPx)
        assertEquals(regular.contentHeightPx + regular.headerHeightPx, raised.contentHeightPx)

        val rtl = spec(settings, layoutDirection = LayoutDirection.Rtl)
        assertEquals(RaisedReaderHeader(18f, 588f, 72f), rtl.raisedHeader)
    }

    @Test
    fun `页眉模式决定默认间距且保留用户保存的值`() {
        val regular = ReaderSettings()
        val raised = regular.copy(raiseHeader = true)
        assertNull(spec(regular).raisedHeader)
        assertEquals(126f, spec(regular).marginTopPx)
        assertEquals(36f, spec(regular).titleTopSpacingPx)
        assertEquals(114f, spec(raised).marginTopPx)
        assertEquals(0f, spec(raised).titleTopSpacingPx)
        assertEquals(spec(regular.copy(showStatusBar = true)), spec(raised.copy(showStatusBar = true)))

        for (settings in listOf(regular, raised)) {
            val saved = spec(settings.copy(headerTopSpacingDp = 20f, titleTopSpacingDp = 24f))
            assertEquals(150f, saved.marginTopPx)
            assertEquals(72f, saved.titleTopSpacingPx)
        }
    }

    @Test
    fun `挖孔前空间不足或缺少坐标时用安全区下方的常规页眉`() {
        val settings = ReaderSettings(raiseHeader = true, headerTopSpacingDp = -48f)
        for (status in listOf(ReaderStatusArea(90, listOf(IntRect(132, 12, 182, 72))), ReaderStatusArea(90))) {
            val s = spec(settings, status = status)
            assertNull(s.raisedHeader)
            assertEquals(90f, s.marginTopPx)
            assertEquals(78f, s.headerHeightPx)
        }
    }

    @Test
    fun `顶部放不下放大的页眉时整行留在安全区下方`() {
        val settings = ReaderSettings(raiseHeader = true, headerTopSpacingDp = -48f)
        val s = buildLayoutSpec(IntSize(1080, 2400), settings, density, cutout, 36.sp, 0f, statusArea)
        assertNull(s.raisedHeader)
        assertEquals(90f, s.marginTopPx)
        assertEquals(132f, s.headerHeightPx)
    }

    @Test
    fun `常显状态栏时负留白无法让正文进入系统栏`() {
        val s = spec(
            ReaderSettings(showStatusBar = true, showHeader = false, headerTopSpacingDp = -48f),
            status = ReaderStatusArea(120),
        )
        assertEquals(120f, s.marginTopPx)
        assertEquals(0f, s.headerHeightPx)
        assertNull(s.raisedHeader)
    }

    @Test
    fun `关掉页眉，页眉那条带整个让给正文，头部留白和挖孔照旧`() {
        val shown = spec(ReaderSettings(showStatusBar = true))
        val hidden = spec(ReaderSettings(showStatusBar = true, showHeader = false))
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
    fun `切换电量样式或关闭电量不动排版`() {
        assertEquals(spec(ReaderSettings()), spec(ReaderSettings(showBattery = false)))
        BatteryDisplayStyle.entries.forEach { style ->
            assertEquals(spec(ReaderSettings()), spec(ReaderSettings(batteryDisplayStyle = style)))
        }
        assertTrue(spec(ReaderSettings(showHeader = false, showFooter = false)).contentHeightPx > spec(ReaderSettings()).contentHeightPx)
    }

    @Test
    fun `页眉和状态栏均关闭时负留白抵消挖孔预留但不越过屏幕顶边`() {
        val settings = ReaderSettings(showHeader = false, headerTopSpacingDp = -12f)
        val zero = spec(settings.copy(headerTopSpacingDp = 0f))
        val reduced = spec(settings)
        assertEquals(90f, zero.marginTopPx)
        assertEquals(54f, reduced.marginTopPx)
        assertEquals(zero.contentHeightPx + 36f, reduced.contentHeightPx)
        assertEquals(0f, spec(settings.copy(headerTopSpacingDp = -48f)).marginTopPx)
        assertEquals(0f, spec(settings, IntInsets(0, 0, 0, 0)).marginTopPx)
    }

    @Test
    fun `下边距为零时仍给底部挖孔和朗读控件留位置`() {
        val settings = ReaderSettings(marginVerticalDp = 0f, showFooter = false)
        assertEquals(0f, spec(settings).marginBottomPx)
        val withInsets = spec(settings, IntInsets(0, 90, 0, 42), speechInsetPx = 60f)
        assertEquals(102f, withInsets.marginBottomPx)
        assertEquals(0f, withInsets.footerHeightPx)
    }
}
