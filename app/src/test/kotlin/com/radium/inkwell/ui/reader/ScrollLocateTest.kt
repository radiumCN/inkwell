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
                startLine = 0,
                endLine = 0,
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

    @Test
    fun `flatIndex 是 locate 的反函数`() {
        assertEquals(1, flatIndexOf(chapters, 3, 0))
        assertEquals(4, flatIndexOf(chapters, 3, 3))
        assertEquals(5, flatIndexOf(chapters, 4, 0))
        assertEquals(6, flatIndexOf(chapters, 4, 1))
        assertNull(flatIndexOf(chapters, 9, 0))
    }

    @Test
    fun `字符偏移落到对应段，MAX_VALUE 落章末`() {
        val chapter = chapter(3, 4).let {
            ScrollChapter(
                chapterIndex = it.chapterIndex,
                title = it.title,
                items = it.items,
                measured = it.measured,
                charOffsets = mapOf(0 to 0, 1 to 10, 2 to 25, 3 to 40),
            )
        }
        assertEquals(0, elementIndexForOffset(chapter, 0))
        assertEquals(1, elementIndexForOffset(chapter, 10))
        assertEquals(1, elementIndexForOffset(chapter, 18))
        assertEquals(3, elementIndexForOffset(chapter, 40))
        assertEquals(3, elementIndexForOffset(chapter, Int.MAX_VALUE))
    }

    @Test
    fun `前面插入上一章时下标要加上去`() {
        val current = listOf(chapter(5, 3))
        val withPrev = listOf(chapter(4, 2), chapter(5, 3))
        assertEquals(2, leadingItemDelta(current, withPrev))
        assertEquals(0, leadingItemDelta(emptyList(), current))
    }

    @Test
    fun `前面裁掉上一章时下标要减`() {
        val window = listOf(chapter(4, 2), chapter(5, 3), chapter(6, 1))
        val trimmed = listOf(chapter(5, 3), chapter(6, 1))
        assertEquals(-2, leadingItemDelta(window, trimmed))
    }

    @Test
    fun `屏顶还是上一章时锚点取视口三成处`() {
        val visible = listOf(
            VisibleSlot(4, -200, 250),
            VisibleSlot(5, 50, 80),
            VisibleSlot(6, 130, 400),
            VisibleSlot(7, 530, 400),
        )
        assertEquals(6, pickAnchorIndex(visible, 0, 800))
    }

    @Test
    fun `锚点仍在第一项时不要跳到下一项`() {
        val visible = listOf(
            VisibleSlot(4, -50, 500),
            VisibleSlot(5, 450, 400),
        )
        assertEquals(4, pickAnchorIndex(visible, 0, 800))
    }

    @Test
    fun `空可见列表没有锚点`() {
        assertNull(pickAnchorIndex(emptyList(), 0, 800))
    }

    @Test
    fun `屏顶上一章、正文已是下一章时进度跟下一章`() {
        val window = listOf(chapter(41, 2), chapter(42, 4), chapter(43, 4))
        val visible = listOf(
            VisibleSlot(6, -100, 150),
            VisibleSlot(7, 50, 80),
            VisibleSlot(8, 130, 400),
            VisibleSlot(9, 530, 300),
        )
        val report = visibleReport(window, visible, 0, 800)
        assertEquals(ScrollVisibleReport(43, 1, 42, 43), report)
    }

    @Test
    fun `屏底留白算窗口最后一章`() {
        val window = listOf(chapter(41, 2), chapter(42, 2), chapter(43, 2))
        val visible = listOf(
            VisibleSlot(5, 0, 400),
            VisibleSlot(6, 400, 200),
            VisibleSlot(7, 600, 80),
        )
        val report = visibleReport(window, visible, 0, 800)
        assertEquals(43, report?.lastVisibleChapter)
        assertEquals(43, report?.chapterIndex)
    }

    @Test
    fun `屏底压在窗口末章且下一章未缓存时预排这一章`() {
        assertEquals(
            43,
            scrollPrefetchCenter(
                firstVisibleChapter = 42,
                lastVisibleChapter = 43,
                windowFirst = 41,
                windowLast = 43,
                chapterCount = 80,
                nextCached = false,
                prevCached = true,
            ),
        )
    }

    @Test
    fun `下一章已经在窗口里就不要再预排`() {
        assertNull(
            scrollPrefetchCenter(
                firstVisibleChapter = 42,
                lastVisibleChapter = 43,
                windowFirst = 42,
                windowLast = 44,
                chapterCount = 80,
                nextCached = true,
                prevCached = true,
            ),
        )
    }

    @Test
    fun `书末一章没有下一章可预排`() {
        assertNull(
            scrollPrefetchCenter(
                firstVisibleChapter = 78,
                lastVisibleChapter = 79,
                windowFirst = 77,
                windowLast = 79,
                chapterCount = 80,
                nextCached = false,
                prevCached = true,
            ),
        )
    }

    @Test
    fun `屏顶压在窗口首章且上一章未缓存时预排这一章`() {
        assertEquals(
            42,
            scrollPrefetchCenter(
                firstVisibleChapter = 42,
                lastVisibleChapter = 43,
                windowFirst = 42,
                windowLast = 44,
                chapterCount = 80,
                nextCached = true,
                prevCached = false,
            ),
        )
    }
}
