package com.radium.inkwell.data.repo

import com.radium.inkwell.core.source.BookSourceRule
import com.radium.inkwell.core.source.SearchResult
import com.radium.inkwell.data.db.dao.SourceReadHealthDao
import com.radium.inkwell.data.db.entity.CheckStatus
import com.radium.inkwell.data.db.entity.SourceReadHealthEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SourceReadHealthTest {
    private val rule = BookSourceRule(bookSourceUrl = "https://source.example", bookSourceName = "来源")

    @Test fun `actual outcomes are sampled by stage while cancellations are ignored`() = runTest {
        val dao = InMemorySourceReadHealthDao()
        val health = SourceReadHealth(dao, backgroundScope)
        health.measure(rule, ReadStage.DETAIL) { "detail" }
        health.measure(rule, ReadStage.TOC, usable = List<String>::isNotEmpty) { emptyList<String>() }
        assertFailsWith<IllegalStateException> { health.measure(rule, ReadStage.CONTENT) { error("failed") } }
        assertFailsWith<CancellationException> {
            health.measure(rule, ReadStage.DETAIL) { throw CancellationException("user left") }
        }
        runCurrent()
        assertEquals(listOf("DETAIL" to 1000, "TOC" to 0, "CONTENT" to 0), dao.rows.map { it.stage to it.score })
    }

    @Test fun `recent successful reading outranks historical checks and expires after rule change or time`() {
        val now = 2_000_000_000_000L
        val old = rule.copy(bookSourceUrl = "https://old.example")
        val recent = listOf(
            SourceReadHealthEntity(rule.id, "DETAIL", rule.hashCode(), 900, 4, now),
            SourceReadHealthEntity(rule.id, "CONTENT", rule.hashCode(), 900, 4, now),
        )
        val score = readingHealthScore(rule, recent, now)
        assertTrue(score > 0)
        assertEquals(0, readingHealthScore(rule.copy(searchUrl = "/new"), recent, now))
        assertEquals(0, readingHealthScore(rule, recent, now + 8 * 86_400_000L))
        val candidates = listOf(
            BookSourceRepository.EnabledSource(old, CheckStatus.OK, 10, 0),
            BookSourceRepository.EnabledSource(rule, CheckStatus.UNCHECKED, -1, 1, readHealthScore = score),
        )
        val results = candidates.map { SearchResult("书", "/book", sourceId = it.rule.id) }
        assertEquals(rule.id, AutoSourceSwitcher.rankSearchResults(results, candidates).first().sourceId)
    }
}

internal class InMemorySourceReadHealthDao : SourceReadHealthDao {
    val rows = mutableListOf<SourceReadHealthEntity>()
    override suspend fun recent(since: Long) = rows.filter { it.observedAt >= since }
    override suspend fun prune(since: Long) { rows.removeAll { it.observedAt < since } }
    override suspend fun record(sourceId: String, stage: String, ruleHash: Int, score: Int, now: Long, since: Long) {
        rows += SourceReadHealthEntity(sourceId, stage, ruleHash, score, 1, now)
    }
}
