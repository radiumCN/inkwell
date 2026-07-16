package com.radium.inkwell.data.repo

import com.radium.inkwell.core.source.BookSourceRule
import com.radium.inkwell.core.source.SearchRuleSet
import com.radium.inkwell.data.db.entity.CheckStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 自动换源的试源顺序。
 *
 * 并发只有 8 个名额，几百个源排队 —— 谁先occupy这几个名额，直接决定用户是等 3 秒还是等 3 分钟。
 * 这是我们比 Legado 多出来的一手（它没有校验数据，只能按库里的原顺序硬试）。
 */
class AutoSourceRankTest {

    private fun rule(id: String, hasSearch: Boolean = true) = BookSourceRule(
        bookSourceUrl = id,
        bookSourceName = "源$id",
        searchUrl = if (hasSearch) "/s?q={{key}}" else null,
        ruleSearch = if (hasSearch) SearchRuleSet(bookList = ".item", name = ".t", bookUrl = ".u") else null,
    )

    private fun src(
        id: String,
        status: Int = CheckStatus.UNCHECKED,
        respondTime: Long = -1,
        sortOrder: Int = 0,
        hasSearch: Boolean = true,
    ) = BookSourceRepository.EnabledSource(
        rule = rule(id, hasSearch),
        checkStatus = status,
        respondTime = respondTime,
        sortOrder = sortOrder,
    )

    @Test
    fun `校验通过的先试，失效的垫底`() {
        val ranked = AutoSourceSwitcher.rank(
            listOf(
                src("failed", CheckStatus.FAILED, respondTime = 100),
                src("unchecked", CheckStatus.UNCHECKED),
                src("ok", CheckStatus.OK, respondTime = 3000),
            ),
            exclude = null,
        )
        assertEquals(listOf("ok", "unchecked", "failed"), ranked.map { it.id })
    }

    /** 「书源网络很差」正是要解决的问题之一 —— 快的先上 */
    @Test
    fun `同为校验通过时，响应快的先试`() {
        val ranked = AutoSourceSwitcher.rank(
            listOf(
                src("slow", CheckStatus.OK, respondTime = 5000),
                src("fast", CheckStatus.OK, respondTime = 300),
                src("mid", CheckStatus.OK, respondTime = 1200),
            ),
            exclude = null,
        )
        assertEquals(listOf("fast", "mid", "slow"), ranked.map { it.id })
    }

    /** -1 = 没测过。当成 0 的话它会插到所有源前面，把真正快的挤掉 */
    @Test
    fun `没测过耗时的排在测过的后面，而不是当成最快`() {
        val ranked = AutoSourceSwitcher.rank(
            listOf(
                src("untimed", CheckStatus.OK, respondTime = -1),
                src("timed", CheckStatus.OK, respondTime = 4000),
            ),
            exclude = null,
        )
        assertEquals(listOf("timed", "untimed"), ranked.map { it.id })
    }

    @Test
    fun `校验失效的不剔除，只是垫底`() {
        // 校验结果会过期（站点当时抽风、后来恢复）。这已经是读不了书的兜底路径，
        // 宁可多试一个也不要空手而归
        val ranked = AutoSourceSwitcher.rank(
            listOf(src("failed", CheckStatus.FAILED)),
            exclude = null,
        )
        assertEquals(listOf("failed"), ranked.map { it.id })
    }

    @Test
    fun `当前源被排除 —— 它已经证明自己不行了`() {
        val ranked = AutoSourceSwitcher.rank(
            listOf(src("cur", CheckStatus.OK, respondTime = 1), src("other")),
            exclude = "cur",
        )
        assertEquals(listOf("other"), ranked.map { it.id })
    }

    @Test
    fun `没有搜索规则的源直接出局`() {
        val ranked = AutoSourceSwitcher.rank(
            listOf(src("nosearch", hasSearch = false), src("ok")),
            exclude = null,
        )
        assertEquals(listOf("ok"), ranked.map { it.id })
    }

    @Test
    fun `一个候选都没有时返回空，而不是崩`() {
        assertTrue(AutoSourceSwitcher.rank(emptyList(), exclude = null).isEmpty())
    }

    // ---------- 按书维度：这个源有没有「这本书」 ----------

    /**
     * 这是全局健康度回答不了的问题：一个又快又校验通过的源，照样可能根本没有这本书。
     * 所以「以前搜到过」必须压过 checkStatus/respondTime —— 否则前几个并发名额
     * 全被"健康但没这本书"的源占着，用户还是得干等。
     */
    @Test
    fun `以前搜到过这本书的源，压过又快又健康但没搜过的`() {
        val ranked = AutoSourceSwitcher.rank(
            listOf(
                src("fastButUnknown", CheckStatus.OK, respondTime = 100),
                src("slowButHasBook", CheckStatus.OK, respondTime = 9000),
            ),
            exclude = null,
            bookHits = mapOf("slowButHasBook" to true),
        )
        assertEquals(listOf("slowButHasBook", "fastButUnknown"), ranked.map { it.id })
    }

    /** 搜过、确实没这本书的垫底 —— 但排在"没搜过"的后面，而不是被当成未知 */
    @Test
    fun `搜过没这本书的垫底，没搜过的居中`() {
        val ranked = AutoSourceSwitcher.rank(
            listOf(
                src("miss", CheckStatus.OK, respondTime = 100),
                src("unknown", CheckStatus.OK, respondTime = 200),
                src("hit", CheckStatus.OK, respondTime = 300),
            ),
            exclude = null,
            bookHits = mapOf("hit" to true, "miss" to false),
        )
        assertEquals(listOf("hit", "unknown", "miss"), ranked.map { it.id })
    }

    /**
     * 同 `校验失效的不剔除` 那套理由：站点会上新书，今天没有不代表下周没有；
     * 而且用户点换源本就是想看全部候选，凭空少几个源只会让人纳闷。
     */
    @Test
    fun `搜过没这本书的源不剔除，只是垫底`() {
        val ranked = AutoSourceSwitcher.rank(
            listOf(src("miss")),
            exclude = null,
            bookHits = mapOf("miss" to false),
        )
        assertEquals(listOf("miss"), ranked.map { it.id })
    }

    /** 同为"搜到过"时，退回原来那套全局健康度比较 */
    @Test
    fun `按书信号打平时，仍按校验状态和响应快慢排`() {
        val ranked = AutoSourceSwitcher.rank(
            listOf(
                src("hitSlow", CheckStatus.OK, respondTime = 5000),
                src("hitFast", CheckStatus.OK, respondTime = 300),
                src("hitFailed", CheckStatus.FAILED, respondTime = 1),
            ),
            exclude = null,
            bookHits = mapOf("hitSlow" to true, "hitFast" to true, "hitFailed" to true),
        )
        assertEquals(listOf("hitFast", "hitSlow", "hitFailed"), ranked.map { it.id })
    }

    /** 新用户/新书没有任何记忆，必须原样退回旧行为，而不是乱序 */
    @Test
    fun `没有任何按书记忆时，行为与从前完全一致`() {
        val sources = listOf(
            src("failed", CheckStatus.FAILED, respondTime = 100),
            src("unchecked", CheckStatus.UNCHECKED),
            src("ok", CheckStatus.OK, respondTime = 3000),
        )
        assertEquals(
            AutoSourceSwitcher.rank(sources, exclude = null).map { it.id },
            AutoSourceSwitcher.rank(sources, exclude = null, bookHits = emptyMap()).map { it.id },
        )
    }
}
