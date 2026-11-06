package com.radium.inkwell.ui.bookshelf

import com.radium.inkwell.core.source.SearchResult
import com.radium.inkwell.data.db.entity.BookEntity
import com.radium.inkwell.data.db.entity.BookType
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 搜索/发现/预览认「已在书架」时，隐藏的书算不算。
 *
 * 起因：隐藏区上了锁，搜一下书名，结果行上照样亮着「已加入」、预览页写着「已在书架」，
 * 点「开始阅读」还不经验证直接打开 —— 一次搜索就绕开了那把锁。
 */
class HiddenShelfTest {

    private fun book(title: String, hidden: Boolean = false, author: String = "作者") = BookEntity(
        id = title,
        type = BookType.NET,
        title = title,
        author = author,
        sourceId = "源",
        bookUrl = "/book/$title",
        addedAt = 0,
        updatedAt = 0,
        hidden = hidden,
    )

    /** 列表上的一条：默认书名作者都和书架上那本一样、但来自别的书源 */
    private fun listed(title: String, author: String = "作者", sourceId: String = "别的源", bookUrl: String = "/other") =
        SearchResult(title = title, bookUrl = bookUrl, author = author, sourceId = sourceId)

    private val shelf = listOf(book("看得见的书"), book("藏起来的书", hidden = true))

    @Test
    fun `上了锁 —— 隐藏的书不算在架`() {
        val index = searchableShelf(shelf, hiddenLocked = true)
        assertTrue(listed("看得见的书") in index)
        assertFalse(listed("藏起来的书") in index)
        // 按地址认也一样拦着，别从这条缝里漏出去
        assertFalse(listed("藏起来的书", author = "", sourceId = "源", bookUrl = "/book/藏起来的书") in index)
    }

    @Test
    fun `没上锁 —— 隐藏只是收起来，照旧算在架`() {
        val index = searchableShelf(shelf, hiddenLocked = false)
        assertTrue(listed("看得见的书") in index)
        assertTrue(listed("藏起来的书") in index)
        assertFalse(listed("不在架上的书") in index)
    }

    /**
     * 书架上那本的作者存歪了（老版本取详情页的写法，作者规则取出空串）：
     * 列表上就是加它的那一行，书名作者对不上也得认出来，否则一直亮着「加入」
     */
    @Test
    fun `书名作者对不上 —— 同一个书源同一个地址照样认得`() {
        val index = searchableShelf(listOf(book("大主宰", author = "")), hiddenLocked = false)
        assertTrue(listed("大主宰", author = "天蚕土豆", sourceId = "源", bookUrl = "/book/大主宰") in index)
        assertFalse(listed("大主宰", author = "天蚕土豆") in index)
    }

    @Test
    fun `本地书没有书源和地址 —— 只按书名作者认`() {
        val local = book("本地的书").copy(type = BookType.LOCAL_TXT, sourceId = null, bookUrl = null)
        val index = searchableShelf(listOf(local), hiddenLocked = false)
        assertTrue(listed("本地的书") in index)
        assertFalse(listed("别的书", sourceId = "源", bookUrl = "/book/本地的书") in index)
    }

    @Test
    fun `上了锁 —— 搜书名片段、整个书名加作者、作者名都算暴露`() {
        assertTrue(revealsHiddenBook("藏起来", shelf, hiddenLocked = true))
        assertTrue(revealsHiddenBook("藏起来的书 作者", shelf, hiddenLocked = true))
        assertTrue(revealsHiddenBook("作者", shelf, hiddenLocked = true))
        assertFalse(revealsHiddenBook("看得见的书", listOf(book("看得见的书")), hiddenLocked = true))
    }

    @Test
    fun `没上锁或空词不拦；隐藏书没填作者也不会把所有词都拦下`() {
        assertFalse(revealsHiddenBook("藏起来", shelf, hiddenLocked = false))
        assertFalse(revealsHiddenBook("  ", shelf, hiddenLocked = true))
        val noAuthor = listOf(book("藏起来的书", hidden = true).copy(author = ""))
        assertFalse(revealsHiddenBook("随便什么", noAuthor, hiddenLocked = true))
    }
}
