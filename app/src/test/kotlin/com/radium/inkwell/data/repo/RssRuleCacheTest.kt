package com.radium.inkwell.data.repo

import com.radium.inkwell.core.source.rss.RssSourceRule
import com.radium.inkwell.data.db.entity.RssSourceEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class RssRuleCacheTest {
    private fun row(id: String = "s") = RssSourceEntity(id = id, name = id, json = "{}")

    @Test fun `group sort name and enable changes reuse parsing while rule content and version invalidate`() {
        var parsed = 0
        val cache = RssRuleCache { e -> parsed++; RssSourceRule(id = e.id, name = e.name) }
        val original = row()
        val first = cache.ruleOf(original)!!
        assertSame(first, cache.ruleOf(original.copy(groupName = "新组", sortOrder = 3, updatedAt = 100)))
        assertEquals(false, cache.ruleOf(original.copy(enabled = false))!!.enabled)
        assertEquals(1, parsed)
        cache.ruleOf(original.copy(json = "{\"singleUrl\":true}"))
        cache.ruleOf(original.copy(sourceJson = "新原文"))
        cache.ruleOf(original.copy(converterVersion = 1))
        assertEquals(4, parsed)
    }

    @Test fun `capacity and input size bounds avoid unbounded retained rules and failures are cached`() {
        var parsed = 0
        val cache = RssRuleCache(maxEntries = 2, maxChars = 5) { parsed++; null }
        assertNull(cache.ruleOf(row("a")))
        assertNull(cache.ruleOf(row("a")))
        assertEquals(1, parsed)
        cache.ruleOf(row("b")); cache.ruleOf(row("c")); cache.ruleOf(row("a"))
        assertEquals(4, parsed)
        repeat(2) { cache.ruleOf(row("large").copy(json = "123456")) }
        assertEquals(6, parsed)
    }

    @Test fun `default decoder retains single page and falls back from broken legacy input`() {
        val cache = RssRuleCache()
        val rule = cache.ruleOf(row().copy(json = """{"id":"s","name":"订阅","singleUrl":true}""", sourceJson = "bad"))!!
        assertEquals(true, rule.singleUrl)
        assertEquals("订阅", rule.name)
    }
    @Test fun `LRU evicts old rules and does not retain oversized source content`() {
        var parsed = 0
        val cache = RssRuleCache(maxEntries = 2, maxChars = 10) { e ->
            parsed++; RssSourceRule(id = e.id, name = e.name)
        }
        cache.ruleOf(row("a")); cache.ruleOf(row("b")); cache.ruleOf(row("a")); cache.ruleOf(row("c"))
        assertEquals(3, parsed)
        cache.ruleOf(row("b"))
        assertEquals(4, parsed)
        val oversized = row("large").copy(json = "x".repeat(11))
        cache.ruleOf(oversized); cache.ruleOf(oversized)
        assertEquals(6, parsed)
    }

    @Test fun `broken source result is cached until its content changes`() {
        var parsed = 0
        val cache = RssRuleCache { parsed++; null }
        assertNull(cache.ruleOf(row()))
        assertNull(cache.ruleOf(row().copy(updatedAt = 200, enabled = false)))
        assertEquals(1, parsed)
        cache.ruleOf(row().copy(json = "changed"))
        assertEquals(2, parsed)
    }

}
