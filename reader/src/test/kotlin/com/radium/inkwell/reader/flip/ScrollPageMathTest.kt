package com.radium.inkwell.reader.flip

import com.radium.inkwell.reader.api.FlipDirection
import com.radium.inkwell.reader.paginate.LayoutSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ScrollPageMathTest {

    @Test
    fun `上滑越过当前页高就翻下一页`() {
        val step = applyScrollDrag(
            pageOffset = -90f,
            dragDelta = -20f,
            currentHeight = 100f,
            hasPrev = true,
            hasNext = true,
            viewportHeight = 800f,
        )
        assertEquals(FlipDirection.FORWARD, step.flip)
        assertEquals(-10f, carryScrollOffset(step, 100f, 80f), 0.01f)
    }

    @Test
    fun `下滑越过 0 就翻上一页`() {
        val step = applyScrollDrag(
            pageOffset = -5f,
            dragDelta = 10f,
            currentHeight = 100f,
            hasPrev = true,
            hasNext = true,
            viewportHeight = 800f,
        )
        assertEquals(FlipDirection.BACKWARD, step.flip)
        assertEquals(-75f, carryScrollOffset(step, 100f, 80f), 0.01f)
    }

    @Test
    fun `书开头不能再往上滑`() {
        val step = applyScrollDrag(
            pageOffset = 0f,
            dragDelta = 20f,
            currentHeight = 100f,
            hasPrev = false,
            hasNext = true,
            viewportHeight = 800f,
        )
        assertNull(step.flip)
        assertEquals(0f, step.pageOffset)
    }

    @Test
    fun `书末短页不能再往下空滑`() {
        val step = applyScrollDrag(
            pageOffset = -50f,
            dragDelta = -400f,
            currentHeight = 100f,
            hasPrev = true,
            hasNext = false,
            viewportHeight = 800f,
        )
        assertNull(step.flip)
        assertEquals(0f, step.pageOffset)
    }

    @Test
    fun `书末长页最多滑到页底对齐视口底`() {
        val step = applyScrollDrag(
            pageOffset = -500f,
            dragDelta = -400f,
            currentHeight = 1200f,
            hasPrev = true,
            hasNext = false,
            viewportHeight = 800f,
        )
        assertNull(step.flip)
        assertEquals(-400f, step.pageOffset)
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
