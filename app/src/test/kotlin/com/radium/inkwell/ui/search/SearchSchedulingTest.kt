package com.radium.inkwell.ui.search

import com.radium.inkwell.core.source.BookSourceRule
import com.radium.inkwell.core.source.SearchRuleSet
import com.radium.inkwell.data.db.entity.CheckStatus
import com.radium.inkwell.data.repo.BookSourceRepository.EnabledSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 搜索调度。几百个源只有 8 个并发名额，谁先占住名额、每个名额被占多久，直接决定第一条结果是
 * 3 秒出来还是 3 分钟出来 —— 坏源排在前面、统一等满 30 秒，就是从前「书源一多搜索就慢」的主要原因。
 */
class SearchSchedulingTest {

    private fun src(
        id: String,
        status: Int = CheckStatus.UNCHECKED,
        respondTime: Long = -1,
        sortOrder: Int = 0,
        searchMs: Long = -1,
        searchFails: Int = 0,
        searchAt: Long = if (searchMs >= 0 || searchFails > 0) System.currentTimeMillis() else 0,
    ) = EnabledSource(
        rule = BookSourceRule(
            bookSourceUrl = id,
            bookSourceName = "源$id",
            searchUrl = "/s?q={{key}}",
            ruleSearch = SearchRuleSet(bookList = ".item", name = ".t", bookUrl = ".u"),
        ),
        checkStatus = status,
        respondTime = respondTime,
        sortOrder = sortOrder,
        searchMs = searchMs,
        searchFails = searchFails,
        searchAt = searchAt,
    )

    private fun ids(list: List<EnabledSource>) = list.map { it.rule.id }

    @Test
    fun `快的先发，没测过的排在快慢之间，连续失败的垫底`() {
        val ordered = SearchScheduling.order(
            listOf(
                src("broken", searchMs = 800, searchFails = 3),
                src("slow", searchMs = 12_000),
                src("unknown"),
                src("fast", searchMs = 600),
            ),
        )
        assertEquals(listOf("fast", "unknown", "slow", "broken"), ids(ordered))
    }

    @Test
    fun `没搜过时看校验结论，搜过之后只认搜索自己的数据`() {
        val ordered = SearchScheduling.order(
            listOf(
                // 校验失败、也从没搜过：排最后
                src("checkFailed", status = CheckStatus.FAILED),
                // 校验失败（败在正文那一步），但搜索一直好好的：不该受牵连
                src("searchFine", status = CheckStatus.FAILED, searchMs = 900),
                // 校验通过、没搜过：借全链路耗时当估计
                src("checkedOk", status = CheckStatus.OK, respondTime = 3_000),
            ),
        )
        assertEquals(listOf("searchFine", "checkedOk", "checkFailed"), ids(ordered))
    }

    @Test
    fun `同档保持书源管理里的手动顺序`() {
        val ordered = SearchScheduling.order(listOf(src("b", sortOrder = 2), src("a", sortOrder = 1), src("c", sortOrder = 2)))
        assertEquals(listOf("a", "b", "c"), ids(ordered))
    }

    @Test
    fun `超时按历史耗时的三倍，夹在上下限之间`() {
        assertEquals(SearchScheduling.UNKNOWN_TIMEOUT_MS, SearchScheduling.timeoutMs(null))
        assertEquals(SearchScheduling.UNKNOWN_TIMEOUT_MS, SearchScheduling.timeoutMs(src("x")))
        assertEquals(SearchScheduling.MIN_TIMEOUT_MS, SearchScheduling.timeoutMs(src("x", searchMs = 500)))
        assertEquals(15_000L, SearchScheduling.timeoutMs(src("x", searchMs = 5_000)))
        assertEquals(SearchScheduling.MAX_TIMEOUT_MS, SearchScheduling.timeoutMs(src("x", searchMs = 20_000)))
    }

    @Test
    fun `成功取滑动平均并清零失败，失败只累加次数`() {
        val first = SearchScheduling.next(src("x"), SearchOutcome.Ok(1_000))
        assertEquals(1_000, first.searchMs)

        val smoothed = SearchScheduling.next(src("x", searchMs = 1_000, searchFails = 1), SearchOutcome.Ok(2_000))
        assertEquals(1_300, smoothed.searchMs)
        assertEquals(0, smoothed.searchFails)

        val failed = SearchScheduling.next(src("x", searchMs = 1_000), SearchOutcome.Failed)
        assertEquals(1_000, failed.searchMs)
        assertEquals(1, failed.searchFails)
    }

    /**
     * 一个变慢了的源：从前 2 秒，今天要 10 秒。超时 8 秒 → 估计抬到 8 秒 → 下次等 24 秒，能搜回来。
     * 不抬的话它每次都在同一个 8 秒上超时，永远翻不了身。
     */
    @Test
    fun `超时把估计抬到这次的上限，下次放宽`() {
        val before = src("x", searchMs = 2_000)
        val limit = SearchScheduling.timeoutMs(before)
        val after = SearchScheduling.next(before, SearchOutcome.TimedOut(limit))
        assertEquals(limit, after.searchMs)
        assertEquals(1, after.searchFails)
        assertTrue(SearchScheduling.timeoutMs(after) > limit)
    }

    @Test
    fun `只有成败变了或耗时变化够大才写库`() {
        val base = src("x", searchMs = 2_000)
        assertFalse(SearchScheduling.worthPersisting(base, base.copy(searchMs = 2_100)))
        assertTrue(SearchScheduling.worthPersisting(base, base.copy(searchMs = 2_300)))
        assertTrue(SearchScheduling.worthPersisting(base, base.copy(searchFails = 1)))
        assertTrue(SearchScheduling.worthPersisting(src("x"), src("x", searchMs = 900)))
        assertFalse(SearchScheduling.worthPersisting(src("x"), src("x")))
    }

    @Test
    fun `书名完全一致才算，剥掉书名号与空白`() {
        val kw = com.radium.inkwell.data.repo.TitleMatch.normalize("诡秘之主")
        assertTrue(SearchScheduling.isExactTitle("《诡秘之主》", kw))
        assertTrue(SearchScheduling.isExactTitle(" 诡秘 之主 ", kw))
        assertFalse(SearchScheduling.isExactTitle("诡秘之主同人", kw))
        assertFalse(SearchScheduling.isExactTitle("诡秘之主", ""))
    }

    @Test
    fun `only the oldest failed source gets an early recovery probe and stale checks expire`() {
        val now = 2_000_000_000_000L
        val fast = (1..8).map { src("fast$it", searchMs = 500, searchAt = now) }
        val recent = src("recent", searchMs = 100, searchFails = 2, searchAt = now - 1_000)
        val eligible = src("probe", searchMs = 100, searchFails = 2, searchAt = now - 1_000_000)
        val other = src("other", searchMs = 100, searchFails = 2, searchAt = now - 950_000)
        val stale = src("stale", status = CheckStatus.FAILED, searchMs = 100, searchFails = 8,
            searchAt = now - SearchScheduling.MAX_SAMPLE_AGE_MS).copy(checkedAt = now - SearchScheduling.MAX_SAMPLE_AGE_MS)
        val ranked = SearchScheduling.order(fast + listOf(recent, eligible, other, stale), now)
        assertEquals("probe", ranked[3].rule.id)
        assertTrue(ranked.indexOf(stale) < ranked.indexOf(other))
        val staleTimeout = SearchScheduling.timeoutMs(stale, now)
        assertEquals(SearchScheduling.MIN_TIMEOUT_MS, staleTimeout)
        val timedOut = SearchScheduling.next(stale, SearchOutcome.TimedOut(staleTimeout), now)
        assertTrue(SearchScheduling.timeoutMs(timedOut, now) > staleTimeout)
        val recovered = SearchScheduling.next(eligible, SearchOutcome.Ok(300), now)
        assertEquals(0, recovered.searchFails)
        assertEquals(300L, recovered.searchMs)
        assertEquals(now, recovered.searchAt)
    }

    @Test
    fun `retry-after cools down without increasing failures or renewing stale success`() {
        val now = 2_000_000_000_000L
        val before = src("limited", searchMs = 550, searchFails = 1, searchAt = now - 2_000)
        val limited = SearchScheduling.next(before, SearchOutcome.RateLimited("60"), now)
        assertEquals(before.searchFails, limited.searchFails)
        assertEquals(before.searchAt, limited.searchAt)
        assertEquals(now + 60_000, limited.searchRetryAt)
        assertTrue(SearchScheduling.coolingDown(limited, now + 59_000))
        assertFalse(SearchScheduling.coolingDown(limited, now + 60_000))
        assertTrue(SearchScheduling.worthPersisting(before, limited))
        assertEquals(listOf("normal", "limited"), ids(SearchScheduling.order(listOf(limited, src("normal")), now)))
    }
}
