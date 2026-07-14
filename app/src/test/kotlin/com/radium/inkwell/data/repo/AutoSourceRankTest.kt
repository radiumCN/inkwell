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
}
