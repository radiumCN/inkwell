package com.radium.inkwell.core.source

import com.radium.inkwell.core.source.js.RhinoScriptRuntime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 整页上下文的文档**用到才解析**（[EvalContext.page]）。
 *
 * 钉两件事：JSON 接口的页面从头到尾不碰 Jsoup（搜索几百个接口源、打开书时几 MB 的详情接口，白解析一遍都是实打实的耗时）；
 * 要用文档的规则拿到的结果和从前「抓回来先解析」一模一样。
 */
class LazyPageContextTest {

    private val evaluator = RuleEvaluator(RhinoScriptRuntime())

    private fun node(rule: String) = LegadoRuleAnalyzer.analyze(rule)

    @Test
    fun `JSON 接口页：列表、字段、脚本都取得到，文档一直没解析`() {
        val body = """{"data":{"books":[{"name":"甲","url":"/b/1"},{"name":"乙","url":"/b/2"}]}}"""
        val page = EvalContext.page(body, "https://a.com/s")

        // 不带前缀的规则在 JSON 原文上按 JSONPath 走（同 Legado 的 isJSON），带 $. 的也一样
        val items = evaluator.evalToNodes(node("data.books"), page)
        assertEquals(listOf("甲", "乙"), items.map { evaluator.evalToString(node("name"), it) })
        assertEquals(2, evaluator.evalToNodes(node("$.data.books[*]"), page).size)
        // 页面级脚本的 result 是原文，JSON.parse 得开
        assertEquals("2", evaluator.evalToString(node("<js>JSON.parse(result).data.books.length</js>"), page))

        assertFalse(page.documentParsed)
    }

    @Test
    fun `HTML 页：选择器用到时才解析，结果照旧`() {
        val body = """<html><body><h1 class='t'>A&amp;B</h1><a href="/x">链接</a></body></html>"""
        val page = EvalContext.page(body, "https://a.com/list")
        assertFalse(page.documentParsed)

        assertEquals("A&B", evaluator.evalToString(node("class.t@text"), page))
        assertTrue(page.documentParsed)
        // 正则仍落在原文上（不是 Jsoup 转出来的 outerHtml），见 PageContextTextTest
        assertEquals("A&amp;B", evaluator.evalToString(node(""":<h1 class='t'>([^<]*)"""), page))
    }

    @Test
    fun `copy 不传元素：还没解析的照旧不解析，仍算整页`() {
        val body = """{"n":1}"""
        val page = EvalContext.page(body, "https://a.com/s")
        val copied = page.copy(js = JsContext(sourceKey = "s"))

        assertTrue(copied.isPage)
        assertEquals(body, evaluator.evalToString(node("<js>result</js>"), copied))
        assertFalse(page.documentParsed)
        assertFalse(copied.documentParsed)
    }

    @Test
    fun `copy 传了元素（包括 null）就换成它`() {
        val page = EvalContext.page("<p>甲</p>", "https://a.com/s")
        val cleared = page.copy(element = null, json = "乙")
        assertEquals(null, cleared.element)
        assertFalse(cleared.isPage)
        assertFalse(page.documentParsed)
    }

    @Test
    fun `XML feed：按 XML 解析，link 的地址不丢`() {
        val body = """<?xml version="1.0"?><rss><channel><item><title>甲</title><link>https://a.com/1</link></item></channel></rss>"""
        val page = EvalContext.page(body, "https://a.com/feed")
        val items = evaluator.evalToNodes(node("//item"), page)
        assertEquals(listOf("https://a.com/1"), items.map { evaluator.evalToString(node("link@text"), it) })
    }
}
