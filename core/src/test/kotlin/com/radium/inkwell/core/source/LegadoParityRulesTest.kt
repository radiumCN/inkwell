package com.radium.inkwell.core.source

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 照 Legado 补齐的几处规则语义。一份 89 个来源的合集导进来，六十多个被判失效，挨个查下来是这些引擎差异：
 * 脚本里的 `src`、JSON 上的单花括号内嵌规则、单斜杠 XPath、`tag.x.y`、整体正则的分组引用、
 * `java.getElement` 拿全部、脚本里的 ArrayList。每条都是「阅读里能用、我们这边取空」。
 */
class LegadoParityRulesTest {

    private val ev = RuleEvaluator(com.radium.inkwell.core.source.js.RhinoScriptRuntime())

    private fun page(text: String) = EvalContext.page(text, "https://ex.com/book/1/")

    private fun items(listRule: String, c: EvalContext) = ev.evalToNodes(LegadoRuleAnalyzer.analyze(listRule), c)

    private fun str(rule: String, c: EvalContext) = ev.evalToString(LegadoRuleAnalyzer.analyze(rule), c)

    // ---- src ----

    @Test
    fun `选择器后接脚本时 src 是这一条目录项，不是上一步取到的文字`() {
        // 废纸文学这一类站：href 是占位，真地址 base64 藏在目录项的属性里，脚本从 src（那一条的 HTML）里挖出来
        val c = page("""<ul><li><a class="g" data-u="L2NoYXB0ZXIvMS5odG1s" href="javascript:">第一章</a></li></ul>""")
        val item = items("tag.li@tag.a", c).single()
        val rule = "@href\n<js>/\\.html/.test(result) ? result : java.base64Decode(String(src).match(/data-u=\"([^\"]+)\"/)[1])</js>"
        assertEquals("/chapter/1.html", str(rule, item))
    }

    @Test
    fun `页面一级的脚本里 src 是整页原文`() {
        // 果文学网的下一页：先取「下一页」三个字，脚本再从整页的 script 里抠真地址
        val c = page("""<html><body><a href="#">下一页</a><script>var nextUrl="/c/2.html";</script></body></html>""")
        assertEquals("/c/2.html", str("text.下一页@text\n@js:src.match(/var nextUrl=\"(.*?)\"/)[1]", c))
    }

    @Test
    fun `运行时 eval 进来的函数里读 src 也拿得到，脚本能改写它`() {
        // 公用函数是运行时才拿到的（`eval(String(source.loginUrl))`、远程库）：这里拼一个字符串顶替，
        // 规则原文里一个「src」都没有 —— 按字面判断要不要给 src 的话，函数里读到的是空串
        val c = page("""<html><body><script>var nextUrl="/c/3.html";</script></body></html>""")
        assertEquals("/c/3.html", str("@js:eval('function next(){ return s' + 'rc.match(/nextUrl=\"(.*?)\"/)[1] }'); next()", c))
        assertEquals("改过", str("@js:src = '改过'; src", c))
    }

    // ---- {$.x} ----

    @Test
    fun `JSON 条目上的单花括号内嵌规则换成那个字段的值`() {
        val c = page("""{"list":[{"bookId":"47873","md5":"ab","tag":["言情","古代"]}]}""")
        val item = items("$.list[*]", c).single()
        assertEquals("/books?bookId=47873", str("/books?bookId={$.bookId}", item))
        assertEquals("https://b.com/47873.txt?md5=ab", str("https://b.com/{$.bookId}.txt?md5={$.md5}", item))
        // `@json:` 打头的一样认；多条结果用换行连起来（同 Legado 的 getString）
        assertEquals("言情\n古代", str("@json:{$.tag[*]}", item))
    }

    @Test
    fun `取空的内嵌规则原样留着，HTML 页面上不展开`() {
        val item = items("$.list[*]", page("""{"list":[{"bookId":"1"}]}""")).single()
        assertEquals("/b/1/{$.none}", str("/b/{$.bookId}/{$.none}", item))
        // HTML 上 `{$.` 不是规则语法（Legado 也只在 JSON 模式下认），不该去 JSON 里找东西
        assertEquals(null, str("/b/{$.bookId}", page("<p>{\"bookId\":\"1\"}</p>")))
    }

    // ---- 单斜杠 XPath ----

    @Test
    fun `单斜杠开头的规则在条目上按 XPath 求值`() {
        val c = page("""<div class="hot_sale"><a href="/b/1"><p>书名</p><p>作者</p></a></div>""")
        val item = items("//div[@class='hot_sale']", c).single()
        assertEquals("书名", str("/a/p[1]/text()", item))
        assertEquals("/b/1", str("/a/@href", item))
        // `||` 照常切开，每段各自按 XPath 走
        assertEquals("作者", str("/a/p[9]/text()||/a/p[2]/text()", item))
    }

    // ---- tag.x.y ----

    @Test
    fun `tag 后面多写的一段不算进标签名`() {
        // 瀚海书阁：`tag.div.bg-light-secondary` 在 Legado 里就是 `tag.div`（连条目自己也算进去）
        val c = page("""<div class="bg-light-secondary flex" onclick="window.open('/novel/1.html')"><h3>书</h3></div>""")
        val item = items("@css:div.bg-light-secondary.flex", c).single()
        assertEquals("window.open('/novel/1.html')", str("tag.div.bg-light-secondary@onclick", item))
    }

    // ---- 整体正则 ----

    @Test
    fun `整体正则列表的每一项留着全部分组，字段用 $1 $2 取`() {
        val c = page("""<a href="/book/1.html">第一章</a><a href="/book/2.html">第二章</a>""")
        val list = items(""":href="(/book/[^"]+)"[^>]*>([^<]+)""", c)
        assertEquals(listOf("第一章", "第二章"), list.map { str("\$2", it) })
        assertEquals("https://x.com/book/1.html#第一章", str("https://x.com\$1#\$2", list[0]))
        // 没有那一组的换成空；不用 `$n` 的写法照旧拿到第 1 组
        assertEquals("第一章", str("\$2\$9", list[0]))
        assertEquals("/book/1.html", str("<js>result</js>", list[0]))
    }

    // ---- java.getElement ----

    @Test
    fun `java getElement 拿到的是全部匹配`() {
        // 凡人小说的目录：先拿 .list 下的全部 div，再按 data-id 一块块挑
        val c = page(
            """<div class="list"><div data-id="0"><a href="/1">一</a></div><div data-id="1"><a href="/2">二</a></div><div>尾</div></div>""",
        )
        val script = "<js>var d = java.getElement(\".list > div\"), out = [];" +
            "for (var i = 0; i < d.length - 1; i++) out.push(d.select(\"[data-id=\" + i + \"]\").text()); out.join(\",\")</js>"
        assertEquals("一,二", str(script, c))
    }

    // ---- ArrayList ----

    @Test
    fun `脚本里 new 出来的 ArrayList 就是数组，列表规则照收`() {
        val c = page("""{"list":[{"t":"甲"},{"t":"乙"}]}""")
        val script = "@js:var o = JSON.parse(result).list, out = new Packages.java.util.ArrayList();" +
            "for (var i = 0; i < o.length; i++) out.add(JSON.stringify(o[i])); out"
        assertEquals(listOf("甲", "乙"), items(script, c).map { str("$.t", it) })
    }

    @Test
    fun `ArrayList 的方法不会被 for in 走到`() {
        val script = "<js>var a = new Packages.java.util.ArrayList(); a.add('x'); a.add('y'); var k = [];" +
            "for (var i in a) k.push(i); a.size() + '|' + a.get(1) + '|' + k.join(',')</js>"
        assertEquals("2|y|0,1", str(script, page("")))
    }
}
