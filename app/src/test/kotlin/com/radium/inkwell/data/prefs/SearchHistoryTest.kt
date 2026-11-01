package com.radium.inkwell.data.prefs

import kotlin.test.Test
import kotlin.test.assertEquals

class SearchHistoryTest {

    @Test
    fun `新词放最前，重复的词挪上来而不是多一条`() {
        val h = pushSearchHistory(listOf("凡人修仙传", "诡秘之主"), "诡秘之主")
        assertEquals(listOf("诡秘之主", "凡人修仙传"), h)
    }

    @Test
    fun `超过上限丢掉最旧的`() {
        val full = List(3) { "词$it" }
        assertEquals(listOf("新", "词0", "词1"), pushSearchHistory(full, "新", max = 3))
    }

    @Test
    fun `连续空白和换行压成一个空格，空词不记`() {
        assertEquals(listOf("斗破 苍穹"), pushSearchHistory(emptyList(), "  斗破 \n 苍穹 "))
        assertEquals(listOf("斗破 苍穹"), pushSearchHistory(listOf("斗破 苍穹"), "斗破  苍穹"))
        assertEquals(listOf("已有"), pushSearchHistory(listOf("已有"), "  \n "))
    }

    @Test
    fun `搜索与历史同一个规范形`() {
        val typed = "  斗破  苍穹\n"
        assertEquals(normalizeSearchKeyword(typed), pushSearchHistory(emptyList(), typed).single())
    }
}
