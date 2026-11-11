package com.radium.inkwell.ui.rss

import com.radium.inkwell.core.model.ContentElement
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

/**
 * 文章页文末那个按钮：这里看不到正文时「在应用内打开原网页」是主按钮，正文在这儿时降成灰色的「查看原网页」。
 * 从前只有一个「在浏览器中打开原文」，看全文就得离开应用（少数派）。
 */
class RssArticleUiStateTest {

    private val link = "https://sspai.com/post/1"
    private val text = listOf(ContentElement.Paragraph("正文"))

    @Test
    fun `正文在这儿时原网页是次要出口，正在取全文时也是`() {
        assertFalse(RssArticleUiState(link = link, elements = text, loading = false).webPageIsPrimary)
        assertFalse(RssArticleUiState(link = link, elements = text, loading = false, fetchingFullText = true).webPageIsPrimary)
        assertFalse(
            RssArticleUiState(link = link, elements = text, loading = false, fullTextHost = "sspai.com").webPageIsPrimary,
        )
    }

    @Test
    fun `没有正文、整理不出全文、抓取失败时原网页是主出口`() {
        assertTrue(RssArticleUiState(link = link, loading = false).webPageIsPrimary)
        assertTrue(RssArticleUiState(link = link, elements = text, loading = false, fullTextFailure = "整理不出").webPageIsPrimary)
        assertTrue(RssArticleUiState(link = link, loading = false, error = "超时").webPageIsPrimary)
    }

    @Test
    fun `脚本地址、data 地址没有原网页`() {
        for (l in listOf("@js:java.ajax(1)", "data:;base64,MA==", "")) {
            val s = RssArticleUiState(link = l, loading = false)
            assertFalse(s.hasWebPage, l)
            assertFalse(s.webPageIsPrimary, l)
        }
    }
}
