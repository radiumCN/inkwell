package com.radium.inkwell.ui.nav

import kotlin.test.Test
import kotlin.test.assertEquals

class DiagnosticRouteTest {
    @Test
    fun `日志路由名不包含书籍标识搜索词或网页参数`() {
        assertEquals("reader", ReaderRoute("private-book-id").diagnosticName())
        assertEquals("search", SearchRoute("private-search-query").diagnosticName())
        assertEquals("rss_article_web", RssArticleWebRoute("private-source", "https://example.org/private", "private-title").diagnosticName())
    }
}
