package com.radium.inkwell.ui.search

import com.radium.inkwell.core.source.SearchResult
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SearchSortParseTest {

    @Test
    fun wordCount_wan_and_plain() {
        assertEquals(120_000L, parseWordCount("12万字"))
        assertEquals(15_000L, parseWordCount("1.5万"))
        assertEquals(12345L, parseWordCount("12345字"))
        assertEquals(-1L, parseWordCount(null))
        assertEquals(-1L, parseWordCount("连载"))
    }

    @Test
    fun updateEpoch_fromKind() {
        val a = searchUpdateEpoch(listOf(book(kind = "连载 · 2024-01-15")))
        val b = searchUpdateEpoch(listOf(book(kind = "2023年12月1日")))
        assertTrue(a > b)
        assertEquals(UNKNOWN_UPDATE_DATE, searchUpdateEpoch(listOf(book(kind = "玄幻"))))
    }

    @Test fun `explicit update wins over legacy hints and ignores arbitrary dates`() {
        val olderExplicit = book(updateTime = "2024-01-01T00:30:00+08:00")
        val newerHint = book(kind = "连载 · 更新时间：2025-02-02", intro = "故事发生于 2025-03-04")
        assertEquals(Instant.parse("2023-12-31T16:30:00Z").toEpochMilli(), searchUpdateEpoch(listOf(olderExplicit, newerHint)))
        assertEquals(Instant.parse("2025-02-02T00:00:00Z").toEpochMilli(), searchUpdateEpoch(listOf(newerHint)))
        assertEquals(UNKNOWN_UPDATE_DATE, searchUpdateEpoch(listOf(
            book(kind = "2025-02-30", intro = "更新于 2025-02-02"),
            book(kind = "首发于 2025-01-01", updateTime = "2099-01-01"),
        )))
    }

    private fun book(kind: String? = null, intro: String? = null, updateTime: String? = null) =
        SearchResult("书", "/book", sourceId = "fixture", kind = kind, intro = intro, updateTime = updateTime)
}
