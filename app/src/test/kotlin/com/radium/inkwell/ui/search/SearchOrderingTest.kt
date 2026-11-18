package com.radium.inkwell.ui.search

import com.radium.inkwell.core.source.SearchResult
import kotlin.test.Test
import kotlin.test.assertEquals

class SearchOrderingTest {
    private fun result(id: String, title: String = id, words: String? = null, date: String? = null) =
        SearchResult(title = title, bookUrl = id, sourceId = id, wordCount = words, kind = date)

    @Test fun `merging a new source replaces cached count and date without mutating published hits`() {
        val hits = linkedMapOf<Pair<String, String>, SearchHit>()
        mergeSearchResults(hits, listOf(result("s1", "书", "1万", "2020-01-01")))
        val old = hits.values.single()
        assertEquals(10000L, old.wordCount)
        val oldDate = old.updateEpoch
        mergeSearchResults(hits, listOf(result("s2", "书", "3万", "2026-01-01")))
        val updated = hits.values.single()
        assertEquals(30000L, updated.wordCount)
        assertEquals(searchUpdateEpoch(listOf(result("s2", date = "2026-01-01"))), updated.updateEpoch)
        assertEquals(10000L, old.wordCount)
        assertEquals(oldDate, old.updateEpoch)
    }

    @Test fun `numeric sorts match original comparison and retain tie order and missing values`() {
        val input = listOf(
            SearchHit(listOf(result("a", words = "1.5万", date = "2020-01-01"), result("a2", words = "2万", date = "2024-01-01"))),
            SearchHit(listOf(result("b", words = "2万", date = "2024-01-01"))),
            SearchHit(listOf(result("c"))),
            SearchHit(listOf(result("d", words = "1亿", date = "2026-01-01"))),
        )
        for (sort in listOf(SearchSort.WORD_COUNT, SearchSort.UPDATE_TIME)) {
            val collator = java.text.Collator.getInstance(java.util.Locale.CHINA).apply { strength = java.text.Collator.PRIMARY }
            val original = input.sortedWith(compareByDescending<SearchHit> { hit ->
                if (sort == SearchSort.WORD_COUNT) hit.results.maxOf { parseWordCount(it.wordCount) } else searchUpdateEpoch(hit.results)
            }.thenBy(collator) { it.result.title })
            assertEquals(original, orderSearchHits(input, "", sort))
            assertEquals(original, orderSearchHits(input, "", sort))
        }
    }
}
