package com.radium.inkwell.core.source

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 入库的书名作者：列表上有就用列表的，详情页只补空缺；书源开了 canReName 才让详情页盖过列表。
 *
 * 起因：搜「天蚕土豆」点「大主宰」的「加入」，提示已加入书架，那一行却还是「加入」——
 * 入库取的是详情页的写法，和列表上那一对对不上，搜索页就认不出它在架上。
 */
class BookNamingTest {

    private fun listed(title: String = "大主宰", author: String? = "天蚕土豆") =
        SearchResult(title = title, bookUrl = "/b/1", author = author, sourceId = "s")

    private fun detail(title: String, author: String?, canReName: Boolean = false) =
        RemoteBookDetail(title = title, author = author, coverUrl = null, intro = null, tocUrl = "/t", canReName = canReName)

    @Test
    fun `详情页写法不一样 —— 照列表上的存`() {
        assertEquals("大主宰" to "天蚕土豆", bookNameAndAuthor(detail("大主宰（全本）", "天蚕土豆 著"), listed()))
    }

    @Test
    fun `详情页作者取出空串 —— 不会把作者存成空的`() {
        assertEquals("大主宰" to "天蚕土豆", bookNameAndAuthor(detail("大主宰", ""), listed()))
        assertEquals("大主宰" to "天蚕土豆", bookNameAndAuthor(detail("  ", "  "), listed()))
    }

    @Test
    fun `列表上缺的由详情页补`() {
        assertEquals("大主宰" to "天蚕土豆", bookNameAndAuthor(detail("大主宰", "天蚕土豆"), listed(author = null)))
        assertEquals("大主宰" to "天蚕土豆", bookNameAndAuthor(detail("大主宰", " 天蚕土豆 "), listed(author = "")))
    }

    @Test
    fun `书源开了 canReName —— 详情页盖过列表，但空的照旧不盖`() {
        val renamed = detail("大主宰（全本）", "天蚕土豆 著", canReName = true)
        assertEquals("大主宰（全本）" to "天蚕土豆 著", bookNameAndAuthor(renamed, listed()))
        assertEquals("大主宰" to "天蚕土豆", bookNameAndAuthor(detail("", null, canReName = true), listed()))
    }

    @Test
    fun `没有列表那一条 —— 只能用详情页的`() {
        assertEquals("大主宰" to "天蚕土豆", bookNameAndAuthor(detail(" 大主宰 ", "天蚕土豆"), null))
        assertEquals("" to "", bookNameAndAuthor(detail("", null), null))
    }
}
