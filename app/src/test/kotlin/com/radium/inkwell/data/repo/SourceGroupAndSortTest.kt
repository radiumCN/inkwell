package com.radium.inkwell.data.repo

import com.radium.inkwell.core.source.legado.LegadoConverter
import com.radium.inkwell.data.db.entity.BookSourceEntity
import com.radium.inkwell.data.db.entity.CheckStatus
import com.radium.inkwell.ui.sourcemanage.SourceSort
import com.radium.inkwell.ui.sourcemanage.sourceComparator
import kotlin.test.Test
import kotlin.test.assertEquals

class SourceGroupAndSortTest {

    @Test
    fun `legado 的 bookSourceGroup 被带进书源`() {
        val legado = """
        [{
          "bookSourceUrl": "https://a.com",
          "bookSourceName": "站A",
          "bookSourceGroup": "精品,漫画",
          "searchUrl": "/s?q={{key}}",
          "ruleSearch": {"bookList": "class.item", "name": "tag.h3@text", "bookUrl": "tag.a@href"},
          "ruleToc": {"chapterList": "tag.dd@tag.a", "chapterName": "text", "chapterUrl": "href"},
          "ruleContent": {"content": "id.content@html"}
        }]
        """.trimIndent()
        val rule = LegadoConverter.convert(legado).converted.single().rule
        assertEquals("精品,漫画", rule.group)
    }

    /**
     * 按响应速度排序时，未校验(-1)和失效(-1)的书源必须沉到最后。
     * 如果直接拿 respondTime 排，-1 会冒充"最快"，一堆死源排在最前面 —— 正好排反了。
     */
    @Test
    fun `按响应速度排序：未校验与失效的沉到最后`() {
        val list = listOf(
            entity("慢源", respondTime = 5000, status = CheckStatus.OK),
            entity("未校验", respondTime = -1, status = CheckStatus.UNCHECKED),
            entity("快源", respondTime = 300, status = CheckStatus.OK),
            entity("失效", respondTime = -1, status = CheckStatus.FAILED),
        )
        val sorted = list.sortedWith(sourceComparator(SourceSort.RESPOND_TIME))
        assertEquals(listOf("快源", "慢源", "失效", "未校验"), sorted.map { it.name })
    }

    private fun entity(name: String, respondTime: Long, status: Int) = BookSourceEntity(
        id = "https://$name", name = name, json = "{}", updatedAt = 0,
        respondTime = respondTime, checkStatus = status,
    )
}
