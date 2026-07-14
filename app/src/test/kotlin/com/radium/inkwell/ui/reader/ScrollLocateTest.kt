package com.radium.inkwell.ui.reader

import com.radium.inkwell.reader.paginate.PageItem
import com.radium.inkwell.reader.render.ScrollChapter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 列表扁平下标 → (章, 元素) 的换算。
 *
 * 这里差一格，阅读进度就整体错位 —— 而且是**静默**错位：书照样能读，只是每次重进
 * 都从上一段开头开始，用户只会觉得"进度好像不太准"，根本报不出可复现的 bug。
 */
class ScrollLocateTest {

    private fun chapter(index: Int, elementCount: Int) = ScrollChapter(
        chapterIndex = index,
        title = "第 $index 章",
        items = (0 until elementCount).map { e ->
            PageItem.TextSlice(
                elementIndex = e,
                lineRange = 0..0,
                yTopInPage = e * 50f,
                height = 40f,
                isTitle = e == 0,
            )
        },
        measured = emptyMap(),
    )

    private val chapters = listOf(chapter(3, 4), chapter(4, 2))

    @Test
    fun `顶部留白也算第一段，不返回 null`() {
        // 下标 0 是顶部留白的 Spacer
        assertEquals(3 to 0, locate(chapters, 0))
    }

    @Test
    fun `第一章的元素`() {
        assertEquals(3 to 0, locate(chapters, 1)) // 留白之后的第一个元素
        assertEquals(3 to 3, locate(chapters, 4))
    }

    /** 跨章边界最容易错：第 5 位应该落进下一章的第 0 个元素，而不是上一章的第 4 个 */
    @Test
    fun `跨章边界`() {
        assertEquals(4 to 0, locate(chapters, 5))
        assertEquals(4 to 1, locate(chapters, 6))
    }

    @Test
    fun `末尾留白落在所有元素之后，返回 null`() {
        assertNull(locate(chapters, 7))
    }
}
