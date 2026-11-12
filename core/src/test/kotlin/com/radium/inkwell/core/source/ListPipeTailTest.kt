package com.radium.inkwell.core.source

import com.radium.inkwell.core.source.js.RhinoScriptRuntime
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 列表规则写成「脚本 + 尾规则」（`<js>…</js>` 换行接一条 CSS / XPath / JSONPath）、在 XML 上写规则、`java.setContent`。
 *
 * 前两种从前都取不到东西：尾规则是 CSS 的列表为空，是 XPath 的取到一串纯文本节点（书名、链接全空）；
 * feed 按 HTML 解析，`<link>` 的地址被挤出元素。书源里三十来个搜索 / 目录这么写，翻译类订阅源全是这样。
 */
class ListPipeTailTest {

    private val ev = RuleEvaluator(scriptRuntime = RhinoScriptRuntime())

    private fun page(body: String) =
        EvalContext(element = parsePage(body, "https://x.com/"), json = body, baseUrl = "https://x.com/")

    private fun list(body: String, listRule: String, field: String): List<String?> =
        ev.evalToNodes(LegadoRuleAnalyzer.analyze(listRule), page(body))
            .map { ev.evalToString(LegadoRuleAnalyzer.analyze(field), it) }

    private val html =
        """<html><body><ul class="B"><li><a href="/b/1">书一</a></li><li><a href="/b/2">书二</a></li></ul></body></html>"""

    private val feed = """<?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0"><channel><title>站</title><link>https://x.com</link>
        <item><title>第一篇</title><link>https://x.com/p/1</link><guid>g1</guid></item>
        <item><title><![CDATA[第二篇]]></title><link><![CDATA[https://x.com/p/2]]></link><guid>g2</guid></item>
        </channel></rss>"""

    @Test
    fun `脚本接 CSS 尾规则：在脚本的产物上取元素`() {
        assertEquals(listOf("/b/1", "/b/2"), list(html, "<js>result</js>\n.B li", "a@href"))
        // 来看文学那种：脚本改写一遍页面再交给选择器
        assertEquals(
            listOf("书一!", "书二!"),
            list(html, "<js>result.replace(/<\\/a>/g, '!</a>')</js>\ntag.li", "a@text"),
        )
    }

    @Test
    fun `脚本接 XPath 尾规则：节点是元素，字段规则取得到`() {
        assertEquals(listOf("/b/1", "/b/2"), list(html, "<js>result</js>\n//li", "a@href"))
    }

    @Test
    fun `脚本接 JSONPath 尾规则照旧`() {
        val json = """{"data":{"list":[{"name":"甲"},{"name":"乙"}]}}"""
        assertEquals(listOf("甲", "乙"), list(json, "<js>result</js>\n$.data.list[*]", "name"))
        assertEquals(listOf("甲", "乙"), list(json, "<js>result</js>\n$.data.list[*]", "$.name"))
    }

    @Test
    fun `feed 按 XML 解析：link 的地址取得到，CDATA 里的也是`() {
        assertEquals(listOf("https://x.com/p/1", "https://x.com/p/2"), list(feed, "item", "link@text"))
        assertEquals(listOf("https://x.com/p/1", "https://x.com/p/2"), list(feed, "//item", "link@text"))
        assertEquals(listOf("https://x.com/p/1", "https://x.com/p/2"), list(feed, "//item", "//link/text()"))
        // 翻译类订阅源：脚本先把整份 feed 过一遍，再 `//item`
        assertEquals(listOf("第一篇", "第二篇"), list(feed, "<js>result</js>\n//item", "title@text"))
        assertEquals(listOf("https://x.com/p/1", "https://x.com/p/2"), list(feed, "<js>result</js>\nitem", "link@text"))
    }

    @Test
    fun `setContent 之后 getString 落在新内容上`() {
        val ctx = page(html)
        fun js(script: String) = ev.evalToString(LegadoRuleAnalyzer.analyze("@js:$script"), ctx)
        // 字符串当新页面（起点、来看文学：重新 ajax 一遍再抽）
        assertEquals(
            "新的",
            js("java.setContent('<div class=\"t\">新的</div>'); java.getString('.t@text')"),
        )
        // 元素：后面的规则落在这个元素上（优特：逐个元素抽字段）
        assertEquals("书二", js("var l = java.getElements('.B li'); java.setContent(l[1]); java.getString('a@text')"))
        // JSON 字符串也认
        assertEquals("7", js("java.setContent('{\"n\":7}'); java.getString('$.n')"))
    }
}
