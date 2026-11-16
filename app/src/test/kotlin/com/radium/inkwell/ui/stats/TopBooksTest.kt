package com.radium.inkwell.ui.stats

import com.radium.inkwell.data.db.entity.StatsBook
import kotlin.test.Test
import kotlin.test.assertEquals

class TopBooksTest {
    @Test fun `hidden and missing books do not consume top five or reduce total reading time`() {
        val summary = ReadingSummary(totalMillis = 999999, topBooks = (0..12).map { "b$it" to (10000L - it) })
        val books = (0..12).filter { it != 2 }.map { StatsBook("b$it", "书$it", "作者", it < 5) }
        assertEquals(listOf("书5", "书6", "书7", "书8", "书9"), topBooks(summary, books).map { it.title })
        assertEquals(999999L, summary.totalMillis)
        assertEquals(10000L - 5, topBooks(summary, books).first().millis)
        assertEquals(listOf("书6", "书7", "书8", "书9", "书10"), topBooks(summary, books.map {
            if (it.id == "b5") it.copy(hidden = true) else it
        }).map { it.title })
    }
}
