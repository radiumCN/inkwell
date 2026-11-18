package com.radium.inkwell.ui.search

import com.radium.inkwell.core.source.BookSourceRule
import com.radium.inkwell.data.db.entity.CheckStatus
import com.radium.inkwell.data.repo.BookSourceRepository.EnabledSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 固定时延、使用虚拟时钟的 8 路调度基准；与手机和实际网络的速度无关。 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchNetworkBenchmarkTest {
    private val now = 2_000_000_000_000L
    private data class Reply(val latencyMs: Long, val hit: Boolean)
    private data class Metrics(val firstMs: Long?, val totalMs: Long, val hits: Int, val timeouts: Int, val requests: Int)

    @Test fun `fixed healthy and weak network profiles compare first hit tail and timeouts`() = runTest {
        val fast = sources("fast", latency = 400)
        val slow = sources("slow", latency = 2_500)
        val healthy = fast + slow
        val healthyReplies = healthy.associate { it.rule.id to Reply(it.searchMs, true) }
        val normalBefore = simulate(healthy, healthyReplies, optimized = false)
        val normalAfter = simulate(healthy, healthyReplies, optimized = true)
        println("healthy: before=$normalBefore after=$normalAfter")
        assertEquals(normalBefore, normalAfter)

        val stalled = sources("stale", latency = 100, recordedAt = now - 8 * 86_400_000L)
        val limited = sources("limited", latency = 200).map { it.copy(searchRetryAt = now + 120_000L) }
        val mixed = stalled + limited + healthy
        val weakReplies = stalled.associate { it.rule.id to Reply(60_000, false) } +
            limited.associate { it.rule.id to Reply(250, false) } +
            fast.associate { it.rule.id to Reply(2_400, true) } +
            slow.associate { it.rule.id to Reply(6_500, true) }
        val weakBefore = simulate(mixed, weakReplies, optimized = false)
        val weakAfter = simulate(mixed, weakReplies, optimized = true)
        println("weak: before=$weakBefore after=$weakAfter")
        assertTrue(weakAfter.firstMs!! < weakBefore.firstMs!!)
        assertTrue(weakAfter.totalMs <= weakBefore.totalMs)
        assertEquals(weakBefore.hits, weakAfter.hits)
        assertEquals(weakBefore.timeouts, weakAfter.timeouts)
        assertEquals(weakBefore.requests - limited.size, weakAfter.requests)
    }

    private fun sources(group: String, latency: Long, recordedAt: Long = now) = (1..8).map {
        EnabledSource(
            rule = BookSourceRule(bookSourceUrl = "https://$group-$it.example", bookSourceName = "$group $it"),
            checkStatus = CheckStatus.UNCHECKED, respondTime = -1, sortOrder = it,
            searchMs = latency, searchAt = recordedAt,
        )
    }

    private suspend fun TestScope.simulate(
        sources: List<EnabledSource>,
        replies: Map<String, Reply>,
        optimized: Boolean,
    ): Metrics = coroutineScope {
        val start = testScheduler.currentTime
        var first: Long? = null
        var requests = 0
        var hits = 0
        var timeouts = 0
        val queue = if (optimized) SearchScheduling.order(sources, now) else sources.sortedBy { it.searchMs }
        val limit = Semaphore(SearchScheduling.CONCURRENCY)
        queue.map { source ->
            async {
                limit.withPermit {
                    if (optimized && SearchScheduling.coolingDown(source, now)) return@withPermit
                    requests++
                    val timeoutMs = if (optimized) SearchScheduling.timeoutMs(source, now) else
                        (source.searchMs * 3).coerceIn(SearchScheduling.MIN_TIMEOUT_MS, SearchScheduling.MAX_TIMEOUT_MS)
                    val reply = replies.getValue(source.rule.id)
                    when (withTimeoutOrNull(timeoutMs) { delay(reply.latencyMs); reply.hit }) {
                        true -> {
                            hits++
                            if (first == null) first = testScheduler.currentTime - start
                        }
                        false -> Unit
                        null -> timeouts++
                    }
                }
            }
        }.awaitAll()
        Metrics(first, testScheduler.currentTime - start, hits, timeouts, requests)
    }
}
