package com.radium.inkwell.reader.render

import com.radium.inkwell.reader.paginate.PageItem
import com.radium.inkwell.reader.paginate.PageSpec
import kotlin.test.Test
import kotlin.test.assertEquals

class FlattenPagesForScrollTest {

    private fun slice(element: Int, y: Float, height: Float) = PageItem.TextSlice(
        elementIndex = element,
        startLine = 0,
        endLine = 0,
        yTopInPage = y,
        height = height,
        isTitle = element == 0,
    )

    @Test
    fun `单页原样返回`() {
        val page = PageSpec(0, 0, listOf(slice(0, 0f, 40f), slice(1, 50f, 80f)), 0, 10)
        assertEquals(page.items, flattenPagesForScroll(listOf(page)))
    }

    @Test
    fun `多页拼成一列并接上 y`() {
        val page0 = PageSpec(0, 0, listOf(slice(0, 0f, 40f), slice(1, 50f, 80f)), 0, 10)
        val page1 = PageSpec(0, 1, listOf(slice(2, 0f, 60f)), 10, 20)
        val flat = flattenPagesForScroll(listOf(page0, page1))
        assertEquals(3, flat.size)
        assertEquals(0f, (flat[0] as PageItem.TextSlice).yTopInPage)
        assertEquals(50f, (flat[1] as PageItem.TextSlice).yTopInPage)
        assertEquals(130f, (flat[2] as PageItem.TextSlice).yTopInPage)
    }
}
