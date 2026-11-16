package com.radium.inkwell.reader.flip

import com.radium.inkwell.core.model.ChapterContent
import com.radium.inkwell.core.model.ContentElement
import com.radium.inkwell.reader.api.FlipDirection
import com.radium.inkwell.reader.paginate.FakeTextMeasureFacade
import com.radium.inkwell.reader.paginate.LayoutSpec
import com.radium.inkwell.reader.paginate.PageItem
import com.radium.inkwell.reader.paginate.PageSpec
import com.radium.inkwell.reader.paginate.Paginator
import com.radium.inkwell.reader.render.RenderablePage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ScrollPageMathTest {

    @Test
    fun `上滑越过当前页高就翻下一页`() {
        val step = consumeScroll(
            pageOffset = -90f,
            dragDelta = -20f,
            currentHeight = 100f,
            prevHeight = 80f,
            nextHeight = 80f,
            hasPrev = true,
            hasNext = true,
            viewportHeight = 800f,
        )
        assertEquals(FlipDirection.FORWARD, step.flip)
        assertEquals(-100f, step.drawOffset, 0.01f)
        assertEquals(-10f, step.leftover, 0.01f)
    }

    @Test
    fun `下滑未超过上一页高只露出上一页`() {
        val step = consumeScroll(
            pageOffset = -5f,
            dragDelta = 10f,
            currentHeight = 100f,
            prevHeight = 80f,
            nextHeight = 80f,
            hasPrev = true,
            hasNext = true,
            viewportHeight = 800f,
        )
        assertNull(step.flip)
        assertEquals(5f, step.drawOffset, 0.01f)
        assertEquals(0f, step.leftover)
    }

    @Test
    fun `下滑越过上一页高才翻上一页`() {
        val step = consumeScroll(
            pageOffset = 70f,
            dragDelta = 20f,
            currentHeight = 100f,
            prevHeight = 80f,
            nextHeight = 80f,
            hasPrev = true,
            hasNext = true,
            viewportHeight = 800f,
        )
        assertEquals(FlipDirection.BACKWARD, step.flip)
        assertEquals(80f, step.drawOffset, 0.01f)
        assertEquals(10f, step.leftover, 0.01f)
    }

    @Test
    fun `书开头不能再往上滑`() {
        val step = consumeScroll(
            pageOffset = 0f,
            dragDelta = 20f,
            currentHeight = 100f,
            prevHeight = 0f,
            nextHeight = 80f,
            hasPrev = false,
            hasNext = true,
            viewportHeight = 800f,
        )
        assertNull(step.flip)
        assertEquals(0f, step.drawOffset)
        assertEquals(0f, step.leftover)
    }

    @Test
    fun `书末短页不能再往下空滑`() {
        val step = consumeScroll(
            pageOffset = -50f,
            dragDelta = -400f,
            currentHeight = 100f,
            prevHeight = 80f,
            nextHeight = 0f,
            hasPrev = true,
            hasNext = false,
            viewportHeight = 800f,
        )
        assertNull(step.flip)
        assertEquals(0f, step.drawOffset)
        assertEquals(0f, step.leftover)
    }

    @Test
    fun `书末长页最多滑到页底对齐视口底`() {
        val step = consumeScroll(
            pageOffset = -500f,
            dragDelta = -400f,
            currentHeight = 1200f,
            prevHeight = 80f,
            nextHeight = 0f,
            hasPrev = true,
            hasNext = false,
            viewportHeight = 800f,
        )
        assertNull(step.flip)
        assertEquals(-400f, step.drawOffset, 0.01f)
        assertEquals(0f, step.leftover)
    }

    @Test
    fun `快滑跨过多页时画布停在下一页顶剩余进 leftover`() {
        val step = consumeScroll(
            pageOffset = 0f,
            dragDelta = -2500f,
            currentHeight = 800f,
            prevHeight = 800f,
            nextHeight = 800f,
            hasPrev = true,
            hasNext = true,
            viewportHeight = 800f,
        )
        assertEquals(FlipDirection.FORWARD, step.flip)
        assertEquals(-800f, step.drawOffset, 0.01f)
        assertEquals(-1700f, step.leftover, 0.01f)
    }

    @Test
    fun `下一页还没画出来时不能把当前页推走留下空洞`() {
        val step = consumeScroll(
            pageOffset = 0f,
            dragDelta = -1500f,
            currentHeight = 800f,
            prevHeight = 800f,
            nextHeight = 0f,
            hasPrev = true,
            hasNext = true,
            viewportHeight = 800f,
        )
        assertEquals(FlipDirection.FORWARD, step.flip)
        assertEquals(0f, step.drawOffset, 0.01f)
        assertEquals(-1500f, step.leftover, 0.01f)
    }

    @Test
    fun `下一页在时页内滑动不翻页`() {
        val step = consumeScroll(
            pageOffset = 0f,
            dragDelta = -200f,
            currentHeight = 800f,
            prevHeight = 800f,
            nextHeight = 800f,
            hasPrev = true,
            hasNext = true,
            viewportHeight = 800f,
        )
        assertNull(step.flip)
        assertEquals(-200f, step.drawOffset, 0.01f)
        assertEquals(0f, step.leftover)
    }

    /**
     * 图的框是按原图比例量好的（见 fitImage），解没解出来都一样高。从前没图的框要折叠掉
     * （那时框是一律 4:3 的占位、图永远不来）；现在再折叠，图解出来那一刻后面的字会整块往下跳
     */
    @Test
    fun `图还没解出来也按框高撑开滚动页高`() {
        val page = RenderablePage(
            spec = PageSpec(
                chapterIndex = 0,
                pageIndexInChapter = 5,
                items = listOf(
                    PageItem.TextSlice(1, 0, 1, 0f, 80f, isTitle = false),
                    PageItem.ImageBox(2, "img:1", 0f, 80f, 300f, 200f),
                    PageItem.TextSlice(3, 0, 0, 280f, 40f, isTitle = false),
                ),
                startCharOffset = 0,
                endCharOffset = 20,
            ),
            measured = emptyMap(),
        )
        assertEquals(320f, scrollPageHeight(page), 0.01f)
    }

    @Test
    fun `滚动页高带上页尾段距`() {
        val page = RenderablePage(
            spec = PageSpec(
                chapterIndex = 0,
                pageIndexInChapter = 0,
                items = listOf(PageItem.TextSlice(1, 0, 1, 0f, 80f, isTitle = false)),
                startCharOffset = 0,
                endCharOffset = 20,
                trailingGapPx = 12f,
            ),
            measured = emptyMap(),
        )
        assertEquals(92f, scrollPageHeight(page), 0.01f)
    }

    /**
     * 端到端：真分页 → 滚动拼页。段尾断页处，下一页首段离上一段末行的距离必须等于页内段距 ——
     * 从前只量到末行的底，两段直接贴在一起。
     */
    @Test
    fun `段尾断页处滚动拼页仍隔着一个段距`() {
        val spec = LayoutSpec(
            viewportWidthPx = 300,
            viewportHeightPx = 400,
            marginLeftPx = 0f, marginTopPx = 0f, marginRightPx = 0f, marginBottomPx = 0f,
            headerHeightPx = 0f, footerHeightPx = 0f,
            fontSizePx = 20f,
            lineHeightPx = 40f,
            paragraphSpacingPx = 20f,
            titleFontScale = 1f,
            firstLineIndentEm = 0f,
        )
        // 每行 15 字：第一段 10 行正好填满一页，第二段被挤到下一页
        val content = ChapterContent(
            listOf(ContentElement.Paragraph("字".repeat(150)), ContentElement.Paragraph("字".repeat(15))),
        )
        val pages = Paginator(FakeTextMeasureFacade()).paginate(0, "", content, spec).chapter.pages
        assertEquals(2, pages.size)
        val first = RenderablePage(pages[0], measured = emptyMap())
        val lastLine = pages[0].items.last() as PageItem.TextSlice
        val nextFirst = pages[1].items.first() as PageItem.TextSlice
        // ScrollPageReader 把下一页接在 scrollPageHeight(当前页) 处
        val gap = scrollPageHeight(first) + nextFirst.yTopInPage - (lastLine.yTopInPage + lastLine.height)
        assertEquals(spec.paragraphSpacingPx, gap, 0.01f)
    }

    @Test
    fun `正文带夹在页眉和页脚之间`() {
        val spec = LayoutSpec(
            viewportWidthPx = 1080,
            viewportHeightPx = 2000,
            marginLeftPx = 40f,
            marginTopPx = 100f,
            marginRightPx = 40f,
            marginBottomPx = 80f,
            headerHeightPx = 26f,
            footerHeightPx = 18f,
            fontSizePx = 20f,
            lineHeightPx = 32f,
            paragraphSpacingPx = 8f,
        )
        assertEquals(126f, scrollContentTop(spec))
        assertEquals(1902f, scrollContentBottom(spec))
        assertEquals(spec.contentHeightPx, scrollContentBottom(spec) - scrollContentTop(spec))
    }
}
