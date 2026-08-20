package com.radium.inkwell.reader.flip

import com.radium.inkwell.reader.api.FlipDirection
import com.radium.inkwell.reader.paginate.LayoutSpec
import com.radium.inkwell.reader.paginate.PageItem
import com.radium.inkwell.reader.paginate.PageSpec
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
    fun `下滑越过 0 就翻上一页`() {
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
        assertEquals(FlipDirection.BACKWARD, step.flip)
        assertEquals(0f, step.drawOffset, 0.01f)
        assertEquals(-75f, step.leftover, 0.01f)
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

    @Test
    fun `没加载的图不撑开滚动页高`() {
        val page = RenderablePage(
            spec = PageSpec(
                chapterIndex = 0,
                pageIndexInChapter = 5,
                items = listOf(
                    PageItem.TextSlice(1, 0, 1, 0f, 80f, isTitle = false),
                    PageItem.ImageBox(2, "img:1", 0f, 80f, 300f, 800f),
                    PageItem.TextSlice(3, 0, 0, 880f, 40f, isTitle = false),
                ),
                startCharOffset = 0,
                endCharOffset = 20,
            ),
            measured = emptyMap(),
        )
        // 800px 空图收掉后，两段字挨在一起
        assertEquals(120f, scrollPageHeight(page), 0.01f)
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
