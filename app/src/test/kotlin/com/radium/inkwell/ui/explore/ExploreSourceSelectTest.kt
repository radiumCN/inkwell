package com.radium.inkwell.ui.explore

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 切书源的两条小规则：同名分类跟着走、最近用过的源置顶。
 * 都是纯函数 —— 拧的是「换源后人停在哪」，写错了测试直接挂，不用手点。
 */
class ExploreSourceSelectTest {

    // ---- matchedCategoryIndex：换源后停在哪个分类 ----

    @Test
    fun `新源有同名分类就停在它上面`() {
        val newCategories = listOf("仙侠小说", "玄幻小说", "都市小说")
        assertEquals(1, matchedCategoryIndex(newCategories, "玄幻小说"))
    }

    @Test
    fun `同名按首尾空白不计来比 —— 规则里多打个空格不该把人扔回第一个`() {
        val newCategories = listOf(" 玄幻小说 ", "都市小说")
        assertEquals(0, matchedCategoryIndex(newCategories, "玄幻小说"))
    }

    @Test
    fun `接不上同名就回第一个分类`() {
        val newCategories = listOf("仙侠小说", "玄幻小说")
        assertEquals(0, matchedCategoryIndex(newCategories, "网游小说"))
    }

    @Test
    fun `没有上一个分类名时回第一个`() {
        assertEquals(0, matchedCategoryIndex(listOf("玄幻小说", "都市小说"), null))
        assertEquals(0, matchedCategoryIndex(listOf("玄幻小说", "都市小说"), "  "))
    }

    // ---- pushRecentSource：最近用过的新→旧、去重、截断 ----

    @Test
    fun `刚用过的源提到最前`() {
        assertEquals(listOf("b", "a"), pushRecentSource(listOf("a"), "b"))
    }

    @Test
    fun `同一个源重复选不列两遍 —— 挪上来而不是再添一条`() {
        assertEquals(listOf("a", "b", "c"), pushRecentSource(listOf("b", "c", "a"), "a"))
    }

    @Test
    fun `最多留三个 —— 「最近」区就三个位置`() {
        val recent = pushRecentSource(listOf("a", "b", "c"), "d")
        assertEquals(listOf("d", "a", "b"), recent)
    }

    @Test
    fun `空列表上记第一个`() {
        assertEquals(listOf("a"), pushRecentSource(emptyList(), "a"))
    }
}
