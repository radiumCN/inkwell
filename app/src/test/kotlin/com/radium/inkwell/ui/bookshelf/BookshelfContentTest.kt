package com.radium.inkwell.ui.bookshelf

import com.radium.inkwell.data.db.entity.BookEntity
import com.radium.inkwell.data.db.entity.BookType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BookshelfContentTest {
    @Test
    fun `首批书和继续阅读使用同一个可见顺序`() {
        val unread = book("未读", "随笔")
        val recent = book("正在读", "小说", openedAt = 10)
        val snapshot = bookshelfContent(listOf(unread, recent), null, showHidden = false, frozenOrder = null)

        assertEquals(listOf(unread, recent), snapshot.books)
        assertEquals(listOf("小说", "随笔"), snapshot.groups)
        assertEquals(recent, snapshot.continuingBook)
        assertEquals(listOf(unread), snapshot.booksBelowContinueCard)
    }

    @Test
    fun `收起隐藏区时分组和继续阅读同时恢复到公开书架`() {
        val hidden = book("私密读物", "私密", openedAt = 10, hidden = true)
        val public = book("公开读物", "公开", openedAt = 5)
        val all = listOf(hidden, public)
        val revealed = bookshelfContent(all, "私密", showHidden = true, frozenOrder = null)
        assertEquals(listOf(hidden), revealed.books)
        assertNull(revealed.continuingBook)

        val collapsed = bookshelfContent(all, "私密", showHidden = false, frozenOrder = null)
        assertEquals(listOf(public), collapsed.books)
        assertEquals(listOf("公开"), collapsed.groups)
        assertNull(collapsed.group)
        assertEquals(public, collapsed.continuingBook)
        assertFalse(collapsed.allHidden)
        assertTrue(bookshelfContent(listOf(hidden), null, showHidden = false, frozenOrder = null).allHidden)
    }

    @Test
    fun `阅读退出期间冻结书位也冻结继续阅读的书`() {
        val original = book("上一轮在读", "小说", openedAt = 5)
        val opened = book("刚打开", "小说", openedAt = 10)
        val books = listOf(opened, original)
        val frozen = bookshelfContent(books, null, showHidden = false, frozenOrder = listOf(original.id, opened.id))

        assertEquals(listOf(original, opened), frozen.books)
        assertEquals(original, frozen.continuingBook)
        assertEquals(opened, bookshelfContent(books, null, showHidden = false, frozenOrder = null).continuingBook)
    }

    private fun book(title: String, group: String, openedAt: Long = 0, hidden: Boolean = false) = BookEntity(
        id = title,
        type = BookType.LOCAL_TXT,
        title = title,
        author = "",
        addedAt = 0,
        updatedAt = 0,
        groupName = group,
        openedAt = openedAt,
        hidden = hidden,
    )
}
