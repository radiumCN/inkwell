package com.radium.inkwell.ui.search

import com.radium.inkwell.core.source.BookSourceRule
import com.radium.inkwell.core.source.SearchPage
import com.radium.inkwell.core.source.SearchResult
import com.radium.inkwell.core.source.SourceException
import com.radium.inkwell.core.source.SourceHttpClient.HttpStatusException
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SearchRoundRulesTest {
    @Test fun `same URL can return distinct titles and authors across pages`() {
        val history = SearchPageHistory()
        val first = SearchPage(listOf(result("第一本", "/shared", author = "甲")), true)
        val second = SearchPage(listOf(result("第二本", "/shared", author = "甲")), true)
        val third = SearchPage(listOf(result("第二本", "/shared", author = "乙")), true)
        assertTrue(history.canContinue("a", first))
        assertTrue(history.canContinue("a", second))
        assertTrue(history.canContinue("a", third))
        assertFalse(history.canContinue("a", second))
    }

    @Test fun `pagination continues when the page only adds an origin to an existing book`() {
        val hits = linkedMapOf<Pair<String, String>, SearchHit>()
        val history = SearchPageHistory()
        mergeSearchResults(hits, listOf(result("已有书", "/a/2", "a")))
        val first = SearchPage(listOf(result("第一页", "/b/1", "b")), hasMore = true)
        val second = SearchPage(listOf(result("已有书", "/b/2", "b")), hasMore = true)
        val third = SearchPage(listOf(result("第三页的新书", "/b/3", "b")), hasMore = false)

        assertTrue(history.canContinue("b", first))
        mergeSearchResults(hits, first.items)
        assertTrue(history.canContinue("b", second))
        assertTrue(mergeSearchResults(hits, second.items))
        assertEquals(2, hits.size)
        assertEquals(setOf("a", "b"), hits.getValue("已有书" to "甲").origins)
        assertFalse(mergeSearchResults(hits, second.items))

        assertFalse(history.canContinue("b", third))
        mergeSearchResults(hits, third.items)
        assertEquals(3, hits.size)
    }

    @Test fun `repeated pages ignore order and do not block other sources or a fresh search`() {
        val history = SearchPageHistory()
        val items = listOf(result("甲", "/1"), result("乙", "/2"))
        assertTrue(history.canContinue("a", SearchPage(items, true)))
        assertTrue(history.canContinue("a", SearchPage(listOf(result("丙", "/3")), true)))
        assertFalse(history.canContinue("a", SearchPage(items.reversed(), true)))
        assertTrue(history.canContinue("b", SearchPage(items.reversed(), true)))
        assertFalse(history.canContinue("a", SearchPage(emptyList(), true)))
        history.clear()
        assertTrue(history.canContinue("a", SearchPage(items, true)))
    }

    @Test fun `same title by different authors and duplicate rules cannot trigger early stop`() {
        val matches = ExactBookMatches("书名")
        for (site in 1..5) {
            assertFalse(matches.record(rule("site$site.example"), listOf(result("书名", "/1", author = "作者$site"))))
        }
        assertFalse(matches.record(rule("site6.example"), listOf(result("书名", "/1", author = null))))
        assertFalse(matches.record(rule("same.example"), listOf(result("《书名》", "/1", author = "作者：甲"))))
        assertFalse(matches.record(rule("www.same.example#第二套规则"), listOf(result("书名", "/2", author = "甲"))))
        for (site in 7..9) {
            assertFalse(matches.record(rule("site$site.example"), listOf(result("书名", "/1", author = "甲"))))
        }
        assertTrue(matches.record(rule("site10.example"), listOf(result("书名", "/1", author = "甲"))))
    }

    @Test fun `a normalized exact title outranks an exact author with more sources`() {
        val author = SearchHit(listOf(
            result("另一部作品", "/1", "a", author = "同名"),
            result("另一部作品", "/2", "b", author = "同名"),
        ))
        val title = SearchHit(listOf(result("《同 名》", "/3")))
        assertEquals(listOf(title, author), orderSearchHits(listOf(author, title), "同名", SearchSort.RELEVANCE))
    }

    @Test fun `search failures distinguish wrapped HTTP status from timeout and rule errors`() {
        assertEquals(SearchStatus.RATE_LIMITED, searchFailure(SourceException("wrapper", HttpStatusException(429, "url"))))
        assertEquals(SearchStatus.HTTP, searchFailure(HttpStatusException(403, "url")))
        assertEquals(SearchStatus.TIMEOUT, searchFailure(SocketTimeoutException()))
        assertEquals(SearchStatus.NETWORK, searchFailure(IOException()))
        assertEquals(SearchStatus.RULE, searchFailure(SourceException("rule")))
    }

    private fun rule(host: String) = BookSourceRule(bookSourceUrl = "https://$host", bookSourceName = host)
    private fun result(title: String, url: String, source: String = "a", author: String? = "甲") =
        SearchResult(title = title, bookUrl = url, sourceId = source, author = author)
}
