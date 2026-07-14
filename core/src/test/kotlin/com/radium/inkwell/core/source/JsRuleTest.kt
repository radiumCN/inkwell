package com.radium.inkwell.core.source

import com.radium.inkwell.core.source.js.RhinoScriptRuntime
import org.jsoup.Jsoup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** 原生 Legado 的 JS 规则（`<js>…</js>` 后缀 / 整条脚本），经 [LegadoRuleAnalyzer] 编译求值 */
class JsRuleTest {

    private val evaluator = RuleEvaluator(RhinoScriptRuntime())
    private val noJsEvaluator = RuleEvaluator()

    private fun htmlCtx(html: String) = EvalContext(
        element = Jsoup.parse(html).body(),
        json = null,
        baseUrl = "https://a.com",
        vars = mapOf("keyword" to "剑", "key" to "剑", "page" to "2"),
    )

    private fun node(rule: String) = LegadoRuleAnalyzer.analyze(rule)

    @Test
    fun `js suffix transforms rule result`() {
        val html = """<a href="javascript:open('/b/1.html','','')">书名</a>"""
        // 规则结果（href）喂给脚本，脚本用正则抠出真实地址
        val rule = """a@href<js>result.match(/'(.*?)'/)[1]</js>"""
        assertEquals("/b/1.html", evaluator.evalToString(node(rule), htmlCtx(html)))
    }

    @Test
    fun `js atom rule sees page vars`() {
        val rule = """<js>key + '-' + page</js>"""
        assertEquals("剑-2", evaluator.evalToString(node(rule), htmlCtx("<p>x</p>")))
    }

    @Test
    fun `js list rule yields elements from generated html`() {
        val rule = """<js>'<div><i>a</i><i>b</i></div>'</js>"""
        val nodes = evaluator.evalToNodes(node(rule), htmlCtx("<p>x</p>"))
        // 生成的 HTML 被解析，取顶层子元素
        assertEquals(1, nodes.size)
        assertEquals("ab", nodes[0].element?.text())
    }

    @Test
    fun `script failure degrades to empty result`() {
        val rule = """<js>null.foo()</js>"""
        assertEquals(null, evaluator.evalToString(node(rule), htmlCtx("<p>x</p>")))
    }

    @Test
    fun `js rule without runtime reports unsupported`() {
        val rule = """<js>result</js>"""
        assertFailsWith<UnsupportedRuleException> {
            noJsEvaluator.evalToString(node(rule), htmlCtx("<p>x</p>"))
        }
    }
}
