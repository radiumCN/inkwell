package com.radium.inkwell.ui.replace

import com.radium.inkwell.data.db.entity.ReplaceRuleEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/** 净化替换页的搜索 + 筛选（filterRules） */
class ReplaceRuleFilterTest {

    private fun rule(
        id: String,
        name: String,
        pattern: String,
        replacement: String = "",
        scope: String = "",
        bookId: String = "",
        enabled: Boolean = true,
    ) = ReplaceRuleEntity(
        id = id,
        name = name,
        pattern = pattern,
        replacement = replacement,
        scope = scope,
        bookId = bookId,
        enabled = enabled,
    )

    private val rules = listOf(
        rule("a", "去掉网址", "(https?://)?(www\\.)?[a-zA-Z0-9-]+\\.(com|net)"),
        rule("b", "最新网址：www.xiaoshu", "最新网址：www.xiaoshuoge.la", bookId = "book1"),
        rule("c", "小说屋防盗段落", "本站.{0,6}XiaoShuoGe[^\\n]*", enabled = false),
        rule("d", "屏蔽词还原", "ji", replacement = "鸡", scope = "某某文学"),
    )

    private fun ids(list: List<ReplaceRuleEntity>) = list.map { it.id }

    @Test
    fun `站名只在规则内容里也搜得到，不分大小写，顺序不动`() {
        assertEquals(listOf("b", "c"), ids(filterRules(rules, "xiaoshuoge", RuleFilter.ALL)))
    }

    @Test
    fun `替换成什么、作用范围也搜`() {
        assertEquals(listOf("d"), ids(filterRules(rules, "鸡", RuleFilter.ALL)))
        assertEquals(listOf("d"), ids(filterRules(rules, "某某", RuleFilter.ALL)))
    }

    @Test
    fun `空白搜索词当没搜`() {
        assertEquals(ids(rules), ids(filterRules(rules, "  ", RuleFilter.ALL)))
    }

    @Test
    fun `筛选：已启用、已禁用、本书专属，和搜索叠加`() {
        assertEquals(listOf("a", "b", "d"), ids(filterRules(rules, "", RuleFilter.ENABLED)))
        assertEquals(listOf("c"), ids(filterRules(rules, "", RuleFilter.DISABLED)))
        assertEquals(listOf("b"), ids(filterRules(rules, "", RuleFilter.BOOK_ONLY)))
        assertEquals(listOf("b"), ids(filterRules(rules, "xiaoshuoge", RuleFilter.ENABLED)))
    }
}
