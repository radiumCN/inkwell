package com.radium.inkwell.ui.reader

import com.radium.inkwell.data.db.entity.ChapterEntity
import com.radium.inkwell.data.db.entity.TocRow
import com.radium.inkwell.reader.api.ReadPosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 阅读中后台追更换了目录：会话里所有按章号记的东西（当前章、分页缓存、进度）都要按这里换算。
 *
 * 算错一格的后果是**静默**的：目录点第 i 章打开的是别的章，进度存成别的章，用户只会觉得「怎么跳章了」。
 */
class TocRemapTest {

    private fun ch(index: Int, title: String, url: String? = "u/$title") =
        ChapterEntity(bookId = "b", index = index, title = title, url = url)

    private fun toc(vararg titles: String) = titles.mapIndexed { i, t -> ch(i, t) }

    @Test
    fun `前面插入公告章后按 URL 找回原章`() {
        val old = toc("第一章", "第二章", "第三章")
        val new = toc("公告", "第一章", "第二章", "第三章")
        val remap = TocRemap(old, new)
        assertEquals(1, remap.map(0))
        assertEquals(3, remap.map(2))
    }

    @Test
    fun `末尾追加新章时原有章节序号不动`() {
        val old = toc("第一章", "第二章")
        val new = toc("第一章", "第二章", "第三章")
        val remap = TocRemap(old, new)
        assertEquals(0, remap.map(0))
        assertEquals(1, remap.map(1))
    }

    @Test
    fun `URL 换了就按标题就近对齐`() {
        val old = listOf(ch(0, "第一章", "old/1"), ch(1, "第二章", "old/2"))
        val new = listOf(ch(0, "公告", "new/0"), ch(1, "第一章", "new/1"), ch(2, "第二章。", "new/2"))
        // 标题剥标点后相等即可（「第二章。」认作「第二章」）
        assertEquals(2, TocRemap(old, new).map(1))
    }

    @Test
    fun `章节从新目录里消失时返回 null，位置按序号夹取并作废偏移`() {
        val old = toc("第一章", "第二章", "第三章")
        val new = toc("第一章")
        val remap = TocRemap(old, new)
        assertNull(remap.map(2))
        assertEquals(ReadPosition(0, 0), remap.map(ReadPosition(2, 500)))
    }

    @Test
    fun `认出原章时保留章内偏移（含跳到章末的哨兵值）`() {
        val old = toc("第一章", "第二章")
        val new = toc("序", "第一章", "第二章")
        val remap = TocRemap(old, new)
        assertEquals(ReadPosition(2, 1234), remap.map(ReadPosition(1, 1234)))
        assertEquals(ReadPosition(1, Int.MAX_VALUE), remap.map(ReadPosition(0, Int.MAX_VALUE)))
    }

    @Test
    fun `同一 URL 出现两次时取离旧序号最近的`() {
        val old = listOf(ch(0, "a", "x"), ch(1, "b", "y"), ch(2, "c", "z"))
        val new = listOf(ch(0, "a", "x"), ch(1, "b", "y"), ch(2, "c", "z"), ch(3, "dup", "x"))
        assertEquals(0, TocRemap(old, new).map(0))
    }

    @Test
    fun `只有缓存标记变了不算目录变了`() {
        val a = toc("第一章", "第二章")
        val b = a.map { it.copy(isCached = true) }
        assertTrue(sameReadingToc(a, b))
        assertFalse(sameReadingToc(a, toc("第一章", "第二章", "第三章")))
        assertFalse(sameReadingToc(a, listOf(a[0], a[1].copy(variable = "{\"k\":1}"))))
    }

    /**
     * 阅读页拿会话手里的整行目录和库里读回的轻量投影比（ReaderViewModel.observeToc）：
     * 判断标准必须和整行那版一模一样 —— 投影版说「一样」，会话就不会去对齐，追更后点第 i 章打开的是旧目录的第 i 章。
     */
    @Test
    fun `和投影比：标准同整行那版`() {
        val a = toc("第一章", "第二章")
        fun rows(list: List<ChapterEntity>) = list.map { TocRow(it.index, it.title, it.url, it.variable, it.isCached) }

        assertTrue(sameReadingToc(a, rows(a.map { it.copy(isCached = true) })))
        assertFalse(sameReadingToc(a, rows(toc("第一章", "第二章", "第三章"))))
        assertFalse(sameReadingToc(a, rows(listOf(a[0], a[1].copy(variable = "{\"k\":1}")))))
        assertFalse(sameReadingToc(a, rows(listOf(a[0], a[1].copy(url = "u/改了")))))
        assertFalse(sameReadingToc(a, rows(listOf(a[0], a[1].copy(title = "第二章（修订）")))))
    }
}
