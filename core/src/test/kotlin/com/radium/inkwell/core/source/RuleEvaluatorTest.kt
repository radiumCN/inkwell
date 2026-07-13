package com.radium.inkwell.core.source

import org.jsoup.Jsoup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RuleEvaluatorTest {

    private val html = """
        <html><body>
        <div class="book" data-id="42">
          <h3><a href="/book/1">斗破星空</a></h3>
          <span class="author">作者：天蚕</span>
          <img src="/img/1.jpg">
          <p class="intro">简介 <b>加粗</b> 文字</p>
        </div>
        <div class="book" data-id="43">
          <h3><a href="/book/2">凡人修仙</a></h3>
          <span class="author">作者：忘语</span>
        </div>
        <ul><li>甲</li><li>乙</li><li>丙</li></ul>
        </body></html>
    """.trimIndent()

    private val ev = RuleEvaluator()

    private fun ctx(vars: Map<String, String> = emptyMap()) =
        EvalContext(Jsoup.parse(html, "https://ex.com/s?q=1"), null, "https://ex.com/", vars)

    private fun str(rule: String, c: EvalContext = ctx()) = ev.evalToString(RuleParser.parse(rule), c)
    private fun strs(rule: String, c: EvalContext = ctx()) = ev.evalToStrings(RuleParser.parse(rule), c)

    @Test
    fun `css text and href extraction`() {
        assertEquals(listOf("斗破星空", "凡人修仙"), strs("css:div.book h3 a"))
        assertEquals(listOf("/book/1", "/book/2"), strs("css:h3 a@href"))
        assertEquals(listOf("/img/1.jpg"), strs("css:img@src"))
        assertEquals(listOf("42", "43"), strs("css:div.book@attr(data-id)"))
    }

    @Test
    fun `ownText vs text vs html`() {
        assertEquals("简介 加粗 文字", str("css:p.intro@text"))
        assertEquals("简介 文字", str("css:p.intro@ownText"))
        assertTrue(str("css:p.intro@html")!!.contains("<b>加粗</b>"))
        assertTrue(str("css:p.intro@outerHtml")!!.startsWith("<p class=\"intro\">"))
    }

    @Test
    fun `pipe regex replace and match`() {
        assertEquals("天蚕", str("css:span.author@text | first | regex:作者[：:]"))
        assertEquals("天蚕", str("css:span.author@text | first | match:作者[：:](\\S+)"))
        // $1 反向引用替换
        assertEquals("第12章", str("text:12 | regex:(\\d+) 第$1章"))
    }

    @Test
    fun `pipe trim stripTags first last index join`() {
        assertEquals("x", str("text:  x  | trim"))
        assertEquals("加粗文本", str("text:<b>加粗</b>文本 | stripTags"))
        assertEquals("甲", str("css:ul li@text | first"))
        assertEquals("丙", str("css:ul li@text | last"))
        assertEquals(listOf("乙"), strs("css:ul li@text | index:1"))
        assertEquals("甲,乙,丙", str("css:ul li@text | join:,"))
    }

    @Test
    fun `pipe prepend append expand vars`() {
        val c = ctx(mapOf("baseUrl" to "https://ex.com"))
        assertEquals("https://ex.com/book/1", str("css:h3 a@href | first | prepend:{{baseUrl}}", c))
        assertEquals("甲!", str("css:ul li@text | first | append:!", c))
    }

    @Test
    fun `fallback takes first non-empty`() {
        assertEquals("甲", str("css:div.missing@text || css:ul li@text | first"))
        assertEquals("兜底", str("css:div.missing@text || css:span.nope@text || text:兜底"))
        assertNull(str("css:div.missing@text || css:span.nope@text"))
    }

    @Test
    fun `concat merges results`() {
        assertEquals("甲\n丙", str("css:ul li@text | first && css:ul li@text | last"))
        assertEquals(listOf("甲", "丙"), strs("css:ul li@text | first && css:ul li@text | last"))
    }

    @Test
    fun `regex rule extracts capture group from element html`() {
        assertEquals(listOf("42", "43"), strs("regex:data-id=\"(\\d+)\""))
    }

    @Test
    fun `json path evaluation`() {
        val json = """{"code":0,"data":{"books":[{"name":"书一","author":"甲"},{"name":"书二","author":"乙"}]}}"""
        val c = EvalContext(null, json, "https://ex.com/", emptyMap())
        assertEquals(listOf("书一", "书二"), strs("json:$.data.books[*].name", c))
        assertEquals("0", str("json:$.code", c))
        // list 规则 → 节点 → 字段
        val nodes = ev.evalToNodes(RuleParser.parse("json:$.data.books[*]"), c)
        assertEquals(2, nodes.size)
        assertEquals("书一", str("json:$.name", nodes[0]))
        assertEquals("乙", str("json:$.author", nodes[1]))
        // 不存在的路径 → 空
        assertNull(str("json:$.data.missing", c))
    }

    @Test
    fun `evalToNodes with css and nested field eval`() {
        val nodes = ev.evalToNodes(RuleParser.parse("css:div.book"), ctx())
        assertEquals(2, nodes.size)
        assertEquals("斗破星空", str("css:h3 a@text", nodes[0]))
        assertEquals("42", str("css:@attr(data-id)", nodes[0]))
        assertEquals("凡人修仙", str("css:h3 a@text", nodes[1]))
    }

    @Test
    fun `evalToNodes supports first last index pipes`() {
        val first = ev.evalToNodes(RuleParser.parse("css:div.book | first"), ctx())
        assertEquals(1, first.size)
        assertEquals("42", str("css:@attr(data-id)", first[0]))
        val idx = ev.evalToNodes(RuleParser.parse("css:div.book | index:1"), ctx())
        assertEquals("43", str("css:@attr(data-id)", idx[0]))
    }

    @Test
    fun `template variables in text rule`() {
        val c = ctx(mapOf("keyword" to "斗破", "page" to "2"))
        assertEquals("斗破-2", str("text:{{keyword}}-{{page}}", c))
        // 未知变量替换为空
        assertEquals("-2", str("text:{{nope}}-{{page}}", c))
    }

    @Test
    fun `template encode pipe`() {
        assertEquals("%E4%B8%AD%E6%96%87", expandTemplate("{{kw|encode}}", mapOf("kw" to "中文")))
        // gbk 按 GB18030 编码
        assertEquals("%D6%D0%CE%C4", expandTemplate("{{kw|encode:gbk}}", mapOf("kw" to "中文")))
        assertEquals(
            "/search?q=%D6%D0%CE%C4&p=1",
            expandTemplate("/search?q={{kw|encode:gbk}}&p={{page}}", mapOf("kw" to "中文", "page" to "1")),
        )
    }
}
