package com.radium.inkwell.core.source.rss

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 订阅源导出（多选 →「导出」）：阅读来的原文照搬、只换开关和分组；粘地址订的写一份最简的 */
class RssSourceExportTest {

    private val legado = """
        {"sourceName":"晋江书单","sourceUrl":"https://www.jjwxc.net/rss","sourceGroup":"小说",
         "sortUrl":"新书::https://a/new\n完结::https://a/done","ruleArticles":"class.item","ruleTitle":"tag.h3@text",
         "loginUrl":"https://login","enabled":true}
    """.trimIndent()

    @Test
    fun `阅读来的：原文字段一个不丢，开关和分组换成现在的`() {
        val out = RssSourceExport.toJson(
            listOf(
                ExportRssSource(
                    url = "https://www.jjwxc.net/rss",
                    name = "晋江书单",
                    icon = "",
                    group = "追更",
                    enabled = false,
                    sourceJson = legado,
                ),
            ),
        )
        val obj = Json.parseToJsonElement(out).jsonArray.single().jsonObject
        // Inkwell 认不了的字段（登录）也在：导到阅读里照样能用
        assertEquals("https://login", obj["loginUrl"]!!.jsonPrimitive.content)
        assertEquals("class.item", obj["ruleArticles"]!!.jsonPrimitive.content)
        assertEquals("追更", obj["sourceGroup"]!!.jsonPrimitive.content)
        assertEquals("false", obj["enabled"]!!.jsonPrimitive.content)

        // 导回 Inkwell：规则、分组、开关都是导出时的样子
        val rule = LegadoRssConverter.convert(out).converted.single().rule
        assertEquals("https://www.jjwxc.net/rss", rule.id)
        assertEquals("追更", rule.group)
        assertFalse(rule.enabled)
        assertEquals(2, rule.sorts.size)
    }

    @Test
    fun `粘地址订的：只有地址和名字，导回来还是一个 RSS 源`() {
        val out = RssSourceExport.toJson(
            listOf(
                ExportRssSource(
                    url = "https://sspai.com/feed",
                    name = "少数派",
                    icon = "",
                    group = "",
                    enabled = true,
                    sourceJson = "",
                ),
            ),
        )
        assertTrue(LegadoRssConverter.looksLikeLegadoRss(out))
        val obj = Json.parseToJsonElement(out).jsonArray.single().jsonObject
        // 没有图标就不写空串
        assertNull(obj["sourceIcon"])
        val rule = LegadoRssConverter.convert(out).converted.single().rule
        assertEquals("https://sspai.com/feed", rule.id)
        assertEquals("少数派", rule.name)
        assertTrue(rule.enabled)
        // 没有文章规则 = 按 RSS/Atom 解析，同直接粘地址
        assertNull(rule.articles)
    }

    @Test
    fun `原文坏了或者对不上这个地址，退回最简的`() {
        listOf("{broken", """{"sourceUrl":"https://other.example/rss","sourceName":"别的源"}""").forEach { bad ->
            val out = RssSourceExport.toJson(
                listOf(ExportRssSource("https://sspai.com/feed", "少数派", "", "", true, sourceJson = bad)),
            )
            val rule = LegadoRssConverter.convert(out).converted.single().rule
            assertEquals("https://sspai.com/feed", rule.id, bad)
            assertEquals("少数派", rule.name, bad)
        }
    }
}
