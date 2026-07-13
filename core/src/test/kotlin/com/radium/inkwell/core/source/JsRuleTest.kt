package com.radium.inkwell.core.source

import com.radium.inkwell.core.source.js.RhinoScriptRuntime
import org.jsoup.Jsoup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JsRuleTest {

    private val evaluator = RuleEvaluator(RhinoScriptRuntime())
    private val noJsEvaluator = RuleEvaluator()

    private fun htmlCtx(html: String) = EvalContext(
        element = Jsoup.parse(html).body(),
        json = null,
        baseUrl = "https://a.com",
        vars = mapOf("keyword" to "剑", "page" to "2"),
    )

    @Test
    fun `js pipe transforms css result`() {
        val html = """<a href="javascript:open('/b/1.html','','')">书名</a>"""
        val rule = "css:a@attr(href) | js:b64:" + b64("""result.match(/'(.*?)'/)[1]""")
        assertEquals("/b/1.html", evaluator.evalToString(RuleParser.parse(rule), htmlCtx(html)))
    }

    @Test
    fun `js atom rule sees page html and vars`() {
        val rule = "js:b64:" + b64("""key + '-' + page""")
        assertEquals("剑-2", evaluator.evalToString(RuleParser.parse(rule), htmlCtx("<p>x</p>")))
    }

    @Test
    fun `js list rule yields elements from generated html`() {
        val rule = "js:b64:" + b64("""'<div><i>a</i><i>b</i></div>'""")
        val nodes = evaluator.evalToNodes(RuleParser.parse(rule), htmlCtx("<p>x</p>"))
        // 生成的 HTML 被解析，取顶层子元素
        assertEquals(1, nodes.size)
        assertEquals("ab", nodes[0].element?.text())
    }

    @Test
    fun `script failure degrades to empty result`() {
        val rule = "js:b64:" + b64("""null.foo()""")
        assertEquals(null, evaluator.evalToString(RuleParser.parse(rule), htmlCtx("<p>x</p>")))
    }

    @Test
    fun `js rule without runtime reports unsupported`() {
        val rule = "js:b64:" + b64("result")
        assertFailsWith<UnsupportedRuleException> {
            noJsEvaluator.evalToString(RuleParser.parse(rule), htmlCtx("<p>x</p>"))
        }
    }

    private fun b64(s: String) =
        java.util.Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))
}
