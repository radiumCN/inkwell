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

    /**
     * 同 Legado：`key` / `page` 只在拼地址的脚本里（AnalyzeUrl 才绑它们），规则脚本里没有 ——
     * 光遇聚合的列表脚本 `const {key, page} = res` 从前撞上同名变量，Rhino 报「变量被重新声明」，整个搜索为空
     */
    @Test
    fun `key and page only exist in url scripts`() {
        val urlCtx = EvalContext(element = null, json = null, baseUrl = "https://a.com", vars = mapOf("keyword" to "剑", "page" to "2"))
        assertEquals("剑-2", evaluator.evalToString(node("""<js>key + '-' + page</js>"""), urlCtx))
        val rule = """<js>const {key, page} = JSON.parse('{"key":"刀","page":5}'); key + '-' + page</js>"""
        assertEquals("刀-5", evaluator.evalToString(node(rule), htmlCtx("<p>x</p>")))
    }

    /**
     * 同 Legado 的 getElements：元素列表接脚本，`result` 是选出的整组元素（阅读那边是 Java 的 List），
     * 脚本 `toArray()`、下标、`.text()` 着用，交回元素数组就是列表。从前逐个元素转成文字喂进去，`toArray` 是 undefined
     */
    @Test
    fun `element list piped into a script gets the whole list`() {
        val html = """<table><tr class="r"><td>甲</td></tr><tr class="r"><td>广告</td></tr><tr class="r"><td>乙</td></tr></table>"""
        val rule = "class.r\n@js:\nvar list = result.toArray(); var out = [];\n" +
            "for (var i in list) { if (list[i].text().indexOf('广告') < 0) out.push(list[i]); }\nout"
        val nodes = evaluator.evalToNodes(node(rule), htmlCtx(html))
        assertEquals(listOf("甲", "乙"), nodes.map { evaluator.evalToString(node("tag.td@text"), it) })
    }

    /** 列表脚本交回 Jsoup 替身的一组 `<tr>`：元素本身当节点，表格的行、格还在（桔纸书屋的搜索） */
    @Test
    fun `list script returning jsoup rows keeps the table cells`() {
        val html = """<table><tr id="nr"><td><a href="/b/1">书一</a></td><td>作者一</td></tr></table>"""
        val rule = """@js:var J = Packages.org.jsoup.Jsoup; J.parse(result).select('tr#nr')"""
        val nodes = evaluator.evalToNodes(node(rule), htmlCtx(html))
        assertEquals(1, nodes.size)
        assertEquals("书一", evaluator.evalToString(node("td:nth-of-type(1) a@text"), nodes[0]))
        assertEquals("作者一", evaluator.evalToString(node("td:nth-of-type(2)@text"), nodes[0]))
    }

    /**
     * 阅读交给脚本的 JSON 是解析好的对象（Java 的 Map / List）：`result.book_id`、`result.toArray()` 都认（光遇聚合的目录地址、
     * 晋江的搜索列表）；把它当字符串用的（`JSON.parse(result)`、`result.match`）照旧
     */
    @Test
    fun `json result exposes its fields and still works as a string`() {
        val item = EvalContext(element = null, json = mapOf("book_id" to "42", "tab" to "小说"), baseUrl = "https://a.com")
        assertEquals("42|小说", evaluator.evalToString(node("<js>result.book_id + '|' + result.tab</js>"), item))
        assertEquals("42", evaluator.evalToString(node("<js>JSON.parse(result).book_id</js>"), item))
        assertEquals("true", evaluator.evalToString(node("<js>String(/book_id/.test(result) && result.length > 10)</js>"), item))

        val body = """{"items":[{"id":1},{"id":2}]}"""
        val page = EvalContext(element = parsePage(body, "https://a.com/s"), json = body, baseUrl = "https://a.com/s")
        val rule = "\$.items[*]\n<js>result.toArray().map(function (x) { return {n: x.id * 10} })</js>"
        val nodes = evaluator.evalToNodes(node(rule), page)
        assertEquals(listOf("10", "20"), nodes.map { evaluator.evalToString(node("\$.n"), it) })
    }

    /**
     * JSON 内容上不带 `$.` 的组合规则：`||` 照常切开、每段当 JSONPath（同 Legado，jayway 自己补 `$.`）。
     * 从前整串补成一条带着 `||` 的路径，什么都匹配不到（书旗（知秋）的搜索列表）
     */
    @Test
    fun `bare json rule with fallback splits into json paths`() {
        val body = """{"aladdin":{"name":"标签"},"data":[{"sid":1,"title":"甲"},{"sid":2,"title":"乙"}]}"""
        val page = EvalContext(element = parsePage(body, "https://a.com/s"), json = body, baseUrl = "https://a.com/s")
        val nodes = evaluator.evalToNodes(node(".[?(@.bookName)]||.[?(@.title)]"), page)
        assertEquals(listOf("甲", "乙"), nodes.map { evaluator.evalToString(node("title"), it) })
        assertEquals(listOf("甲", "乙"), evaluator.evalToStrings(node("data[*].bookName||data[*].title"), page))
        // 接口直接回一个数组：列表规则就一个 `$`（墨阅·极速）
        val list = """[{"name":"甲"},{"name":"乙"}]"""
        val listPage = EvalContext(element = parsePage(list, "https://a.com/s"), json = list, baseUrl = "https://a.com/s")
        assertEquals(listOf("甲", "乙"), evaluator.evalToNodes(node("$"), listPage).map { evaluator.evalToString(node("\$.name"), it) })
    }

    /** 脚本拼出来的一段 `<tr>`：按表体解析，不被 HTML 解析器拆成散落的字 */
    @Test
    fun `list script returning row html keeps the rows`() {
        val rule = """<js>'<tr><td>甲</td><td>1</td></tr><tr><td>乙</td><td>2</td></tr>'</js>"""
        val nodes = evaluator.evalToNodes(node(rule), htmlCtx("<p>x</p>"))
        assertEquals(listOf("甲", "乙"), nodes.map { evaluator.evalToString(node("td:nth-of-type(1)@text"), it) })
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
    fun `java getString evaluates a rule on the current page`() {
        val html = """<div class="page-link">3/10</div>"""
        val rule = """<js>java.getString('class.page-link@text')</js>"""
        assertEquals("3/10", evaluator.evalToString(node(rule), htmlCtx(html)))
    }

    @Test
    fun `java getElements returns wrappers the script can read`() {
        val html = """<ul class="txt-list"><li><a>甲</a></li><li><a>乙</a></li></ul>"""
        val rule = """<js>java.getElements('class.txt-list@tag.li@tag.a')[0].text()</js>"""
        assertEquals("甲", evaluator.evalToString(node(rule), htmlCtx(html)))
    }

    @Test
    fun `js rule without runtime reports unsupported`() {
        val rule = """<js>result</js>"""
        assertFailsWith<UnsupportedRuleException> {
            noJsEvaluator.evalToString(node(rule), htmlCtx("<p>x</p>"))
        }
    }
}
