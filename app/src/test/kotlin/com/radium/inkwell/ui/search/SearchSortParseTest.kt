package com.radium.inkwell.ui.search

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
        val a = parseUpdateEpoch("连载 · 2024-01-15")
        val b = parseUpdateEpoch("2023年12月1日")
        assertTrue(a > b)
        assertEquals(Long.MIN_VALUE, parseUpdateEpoch("玄幻"))
    }
}
