package com.radium.inkwell.core.source

import org.jsoup.Jsoup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 原生 Legado 规则串直接编译 + 用真实 [RuleEvaluator] 求值。
 * 覆盖分派各分支：Jsoup 默认/@css/层级/索引、JSONPath、正则、模板、@js 后缀、<js> 流水、
 * ##替换、||回退、&&拼接、@put/@get 传值。
 */
class LegadoRuleAnalyzerTest {

    private val html = """
        <html><body>
        <div class="book" data-id="42">
          <h3><a href="/book/1">斗破星空</a></h3>
          <span class="author">作者：天蚕土豆</span>
          <img src="/img/1.jpg">
        </div>
        <div class="book" data-id="43">
          <h3><a href="/book/2">凡人修仙</a></h3>
          <span class="author">作者：忘语</span>
        </div>
        </body></html>
    """.trimIndent()

    private val ev = RuleEvaluator(com.radium.inkwell.core.source.js.RhinoScriptRuntime())

    private fun ctx(vars: Map<String, String> = emptyMap()) =
        EvalContext(Jsoup.parse(html, "https://ex.com/s?q=1"), null, "https://ex.com/", vars)

    private fun str(rule: String, c: EvalContext = ctx()) =
        ev.evalToString(LegadoRuleAnalyzer.analyze(rule), c)

    private fun strs(rule: String, c: EvalContext = ctx()) =
        ev.evalToStrings(LegadoRuleAnalyzer.analyze(rule), c)

    private fun jsonCtx(json: String, vars: Map<String, String> = emptyMap()) =
        EvalContext(null, json, "https://ex.com/", vars)

    // ---- 默认 Jsoup ----

    @Test fun `default jsoup layered rule with extractor`() {
        assertEquals(listOf("斗破星空", "凡人修仙"), strs("class.book@tag.h3@tag.a@text"))
        assertEquals(listOf("/book/1", "/book/2"), strs("class.book@tag.a@href"))
    }

    @Test fun `css prefix uses native css, index applies to default jsoup`() {
        // @css: 走纯 CSS（索引用 :eq），默认语法的 .n 索引作用于匹配集
        assertEquals(listOf("斗破星空", "凡人修仙"), strs("@css:div.book h3 a"))
        assertEquals("凡人修仙", str("@css:div.book:eq(1) h3 a"))
        assertEquals("斗破星空", str("class.book.0@tag.a@text"))
    }

    // ---- JSONPath ----

    @Test fun `jsonpath dollar and at-json`() {
        val c = jsonCtx("""{"data":[{"n":"甲"},{"n":"乙"}]}""")
        assertEquals(listOf("甲", "乙"), ev.evalToStrings(LegadoRuleAnalyzer.analyze("$.data[*].n"), c))
        assertEquals(listOf("甲", "乙"), ev.evalToStrings(LegadoRuleAnalyzer.analyze("@json:$.data[*].n"), c))
    }

    // ---- XPath ----

    @Test fun `xpath prefix and leading slashes`() {
        assertEquals(listOf("斗破星空", "凡人修仙"), strs("@XPath://div[@class='book']//h3/a"))
        assertEquals(listOf("斗破星空", "凡人修仙"), strs("//div[@class='book']//h3/a"))
    }

    // ---- 正则 ----

    @Test fun `leading colon is regex over context text`() {
        // 元素上下文取 outerHtml，正则捕获组 1 优先
        assertEquals("42", str(":data-id=\"(\\d+)\"", EvalContext(
            Jsoup.parse(html, "https://ex.com/").selectFirst("div.book")!!, null, "https://ex.com/")))
    }

    // ---- 模板 ----

    @Test fun `template with baseUrl and arithmetic`() {
        assertEquals("https://ex.com/list/3", str("{{baseUrl}}list/{{page}}",
            ctx(mapOf("baseUrl" to "https://ex.com/", "page" to "3"))))
    }

    /** {{baseUrl}} 是当前页地址（Legado 语义），不是引擎塞进 vars 的站点根 */
    @Test fun `template baseUrl prefers the page url over the site root in vars`() {
        val page = EvalContext(
            Jsoup.parse(html, "https://ex.com/book/1"), null, "https://ex.com/book/1",
            mapOf("baseUrl" to "https://ex.com/"),
        )
        assertEquals("https://ex.com/book/1", str("{{baseUrl}}", page))
    }

    // ---- ## 替换 ----

    @Test fun `hash regex replace on result`() {
        assertEquals(listOf("天蚕土豆", "忘语"), strs("class.author@text##作者：##"))
    }

    // ---- || 回退 / && 拼接 ----

    @Test fun `fallback picks first non-empty across modes`() {
        // 第一分支（不存在的 class）为空 → 回退到第二分支
        assertEquals("斗破星空", str("class.nope@text || class.book.0@tag.a@text"))
    }

    @Test fun `concat merges parts`() {
        assertEquals(listOf("斗破星空", "凡人修仙"), strs("class.book.0@tag.a@text && class.book.1@tag.a@text"))
    }

    // ---- @js 后缀 / <js> 流水 ----

    @Test fun `at-js suffix runs script on rule result`() {
        assertEquals("斗破星空-x", str("class.book.0@tag.a@text@js:result+\"-x\""))
    }

    @Test fun `js block then tail rule on script output`() {
        // 脚本吐出一段 HTML，尾规则在其产物上再抽取
        val rule = "<js>\"<a href=\\\"/z\\\">末</a>\"</js>@tag.a@href"
        assertEquals("/z", str(rule))
    }

    // ---- @put / @get ----

    @Test fun `put stores variable as side effect`() {
        // 目录→正文传参：@put 把当前项上求得的值存进变量表，供后续 @get:{k}/java.get(k) 取用
        val item = EvalContext(
            Jsoup.parse(html, "https://ex.com/").selectFirst("div.book")!!, null, "https://ex.com/",
            js = JsContext())
        ev.evalToString(LegadoRuleAnalyzer.analyze("class.book@data-id@put:{\"bid\":\"@data-id\"}"), item)
        assertEquals("42", item.js.scriptVars["bid"])
        // 整段 `@get:{k}` 是取值，不是选择器
        assertEquals("42", ev.evalToString(LegadoRuleAnalyzer.analyze("@get:{bid}"), item))
    }

    // ---- 降级：不认识的东西不抛错 ----

    @Test fun `unknown selector degrades to empty not throw`() {
        assertNull(str("class.doesNotExist@text"))
    }

    // ---- 回归：## 剥离先于 <js> 切分曾把脚本体腰斩 ----

    @Test fun `hashes inside js body are not truncated`() {
        assertEquals("a##b", str("<js>var s=\"a##b\"; s</js>"))
    }

    // ---- 回归：列表规则 头<js>过滤</js> 的管道曾被 evalToNodes 静默丢弃 ----

    @Test fun `list rule with head and js pipe is not dropped`() {
        val nodes = ev.evalToNodes(LegadoRuleAnalyzer.analyze("class.book@html<js>result</js>"), ctx())
        assertEquals(2, nodes.size)
        assertEquals("斗破星空", ev.evalToString(LegadoRuleAnalyzer.analyze("tag.a@text"), nodes[0]))
    }

    // ---- ### 结尾 = replaceFirst（抽取语义），未匹配则空串 ----

    @Test fun `triple hash is replaceFirst extraction`() {
        // 仅在首个匹配区间内替换（其余文本被丢弃）
        assertEquals("<星空>", str("class.book.0@tag.a@text##(星空)##<\$1>###"))
        // 未匹配 → 空串（不是原文）
        assertNull(str("class.book.0@tag.a@text##(无此串)##x###"))
        // 对照：无 ### 是全局替换，保留其余文本
        assertEquals("斗破<星空>", str("class.book.0@tag.a@text##(星空)##<\$1>"))
    }

    // ---- @put spec 含嵌套花括号（{{page}}）不被截断 ----

    @Test fun `put spec with nested braces is not truncated`() {
        val item = EvalContext(
            Jsoup.parse(html, "https://ex.com/").selectFirst("div.book")!!, null, "https://ex.com/",
            vars = mapOf("page" to "3"), js = JsContext())
        ev.evalToString(
            LegadoRuleAnalyzer.analyze("class.book@data-id@put:{\"p\":\"{{page}}\"}"), item)
        assertEquals("3", item.js.scriptVars["p"])
    }

    // ---- @put 的 Legado 文档写法：键不加引号 / 单引号 ----

    @Test fun `put spec with unquoted keys or single quotes`() {
        fun item() = EvalContext(
            Jsoup.parse(html, "https://ex.com/").selectFirst("div.book")!!, null, "https://ex.com/",
            js = JsContext())
        val unquoted = item()
        ev.evalToString(LegadoRuleAnalyzer.analyze("class.book@data-id@put:{bid:\"@data-id\"}"), unquoted)
        assertEquals("42", unquoted.js.scriptVars["bid"])
        val single = item()
        ev.evalToString(LegadoRuleAnalyzer.analyze("class.book@data-id@put:{'bid':'@data-id'}"), single)
        assertEquals("42", single.js.scriptVars["bid"])
    }

    // ---- 先切脚本段、再在规则段里解析 ## ----

    @Test fun `hash replace before at-js applies to the head, script still runs`() {
        assertEquals("天蚕土豆!", str("class.author.0@text##作者：@js:result+'!'"))
    }

    @Test fun `hash replace before js block applies to the head, script still runs`() {
        assertEquals("天蚕土豆?", str("class.author.0@text##作者：<js>result+'?'</js>"))
    }

    @Test fun `hashes inside at-js body do not cut the script`() {
        assertEquals("a##b", str("@js:var s='a##b';s"))
    }

    @Test fun `hash replace after js block applies to the script output`() {
        assertEquals("正文", str("<js>'广告正文'</js>##广告"))
    }

    // ---- JSON 对象/数组给脚本、正则的是真 JSON，不是 Map.toString ----

    @Test fun `json list item is real json text for js and regex`() {
        val page = jsonCtx("""{"data":{"list":[{"id":1,"title":"甲"},{"id":2,"title":"乙"}]}}""")
        val items = ev.evalToNodes(LegadoRuleAnalyzer.analyze("$.data.list[*]"), page)
        assertEquals(2, items.size)
        assertEquals("甲", ev.evalToString(LegadoRuleAnalyzer.analyze("<js>JSON.parse(result).title</js>"), items[0]))
        assertEquals("乙", ev.evalToString(LegadoRuleAnalyzer.analyze(":\"title\":\"([^\"]+)\""), items[1]))
        // 取整个对象：拿到的是 JSON，不是 {id=1, title=甲}
        assertEquals("""{"id":1,"title":"甲"}""", ev.evalToString(LegadoRuleAnalyzer.analyze("$.data.list[0]"), page))
    }

    // ---- %% 交替合并（JSONPath / 混写）----

    private val twoLists = """{"data":{"recommend":[{"t":"r1"},{"t":"r2"},{"t":"r3"}],"find":[{"t":"f1"}]}}"""

    @Test fun `JSONPath 的 %% 交替合并，短的取完就跳过`() {
        assertEquals(listOf("r1", "f1", "r2", "r3"), strs("$.data.recommend[*].t%%$.data.find[*].t", jsonCtx(twoLists)))
        val nodes = ev.evalToNodes(LegadoRuleAnalyzer.analyze("$.data.recommend[*]%%$.data.find[*]"), jsonCtx(twoLists))
        assertEquals(
            listOf("r1", "f1", "r2", "r3"),
            nodes.map { ev.evalToString(LegadoRuleAnalyzer.analyze("$.t"), it) },
        )
    }

    @Test fun `晋江书单那种写法：前几个分支空，落到 %% 那一支`() {
        val rule = "$.data.data[*]||$.data.list[*]||$.data.a[*]&&$.data.b[*]||$.data.recommend[*]%%$.data.find[*]"
        assertEquals(4, ev.evalToNodes(LegadoRuleAnalyzer.analyze(rule), jsonCtx(twoLists)).size)
    }

    @Test fun `&& 和 %% 混写按先出现的那个切（同 Legado）`() {
        val json = """{"a":["a1"],"b":["b1","b2"],"c":["c1","c2"]}"""
        // a && (b %% c)
        assertEquals(listOf("a1", "b1", "c1", "b2", "c2"), strs("$.a[*]&&$.b[*]%%$.c[*]", jsonCtx(json)))
        // %% 先出现：b %% (c && a) —— b 与「c 接着 a」轮流取
        assertEquals(listOf("b1", "c1", "b2", "c2", "a1"), strs("$.b[*]%%$.c[*]&&$.a[*]", jsonCtx(json)))
    }

    // ---- 晋江书单那类订阅源：模板、脚本、整组管道 ----

    private val item = """{"listid":356320,"recommend_count":"","booknum":12,"fav_count":345,"tags":[{"t":"言情"},{"t":"古代"}]}"""

    @Test fun `模板里的 || 不把整条规则切开，是模板里那条规则的回退`() {
        assertEquals("【12 本】", str("【{{$.recommend_count||$.booknum}} 本】", jsonCtx(item)))
    }

    @Test fun `模板里的 ## 作用在连好的整串上`() {
        // 规则原文里是反斜杠加 n 两个字（正则的换行），不是真的换行
        assertEquals("言情•古代", str("{{\$.tags[*].t##\\n##•}}", jsonCtx(item)))
    }

    @Test fun `脚本里的 {{}} 先按当前这一条展开再跑`() {
        assertEquals(
            "https://a.com/getDetail?listid=356320&p=1",
            str("<js>\"https://a.com/getDetail?listid={{$.listid}}\"+\"&p=1\"</js>", jsonCtx(item)),
        )
    }

    @Test fun `JSON 列表接脚本：脚本拿到整组，后面的 $ 点 星 再展开回逐条`() {
        val page = """{"data":{"recommend":[{"t":"r1"},{"t":"r2"}],"find":[{"t":"f1"}]}}"""
        val rule = "\$.data.recommend[*]%%\$.data.find[*]\n<js>\nif(result ==\"[]\"){ result = '[{\"t\":\"空\"}]' }\nresult\n</js>\n\$.[*]"
        val nodes = ev.evalToNodes(LegadoRuleAnalyzer.analyze(rule), jsonCtx(page))
        assertEquals(listOf("r1", "f1", "r2"), nodes.map { ev.evalToString(LegadoRuleAnalyzer.analyze("\$.t"), it) })
    }
}
