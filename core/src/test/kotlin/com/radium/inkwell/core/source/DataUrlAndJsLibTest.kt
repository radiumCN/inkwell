package com.radium.inkwell.core.source

import com.radium.inkwell.core.source.js.RhinoScriptRuntime
import com.radium.inkwell.core.source.rss.LegadoRssConverter
import com.radium.inkwell.core.source.rss.RssEngine
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 阅读的 `data:` 地址与公共脚本库（jsLib）。源仓库里不少订阅源、书源靠这两样：
 * 分类地址写成 `data:;base64,…`（内容在地址里，不发请求），分类、列表脚本调 jsLib 里定义的函数。
 * 从前前者报「非法 URL」，后者「getMust is not defined」，整个分类一条都没有。
 */
class DataUrlAndJsLibTest {

    private val http = SourceHttpClient(OkHttpClient(), retryBaseDelayMs = 1, allowPrivateNetwork = true)

    // ---- data: ----

    @Test
    fun `data 地址不发请求，内容是解码后字节的十六进制（同阅读）`() = runBlocking {
        // "0" → 30；后面的选项没剥也认
        assertEquals("30", http.fetch("data:;base64,MA==,{\"type\":0}").bodyText)
        // 带 MIME、中文：「你好」的 UTF-8 字节
        assertEquals("e4bda0e5a5bd", http.fetch("data:text/plain;base64,5L2g5aW9").bodyText)
    }

    @Test
    fun `不是 base64 的 data 地址照旧报错`() {
        assertNull(dataUriPage("data:text/plain,hello"))
        assertNull(dataUriPage("https://a.com/data:;base64,MA=="))
    }

    // ---- jsLib：脚本运行时 ----

    private val runtime = RhinoScriptRuntime()

    @Test
    fun `jsLib 里的函数、变量对规则脚本可见，库函数用 this 拿当次的绑定`() {
        val lib = """
            var base = 10;
            function plus(n) { return base + n + Number(this.k); }
            function twice() { return plus(1) * 2; }
        """.trimIndent()
        assertEquals("12", runtime.eval("plus(1)", mapOf("k" to "1"), jsLib = lib))
        // 库函数之间不带 this 互相调用，里面的 this.k 照样是这一次的绑定；绑定换了，结果跟着换
        // （库作用域按线程缓存，绑定每次是新的）
        assertEquals("24", runtime.eval("twice()", mapOf("k" to "1"), jsLib = lib))
        assertEquals("15", runtime.eval("plus(1)", mapOf("k" to "4"), jsLib = lib))
    }

    @Test
    fun `jsLib 本身跑不起来，当没有库，规则脚本照常跑`() {
        // 阅读的远程库写法 {"名字":"地址"}：不下载，也不该让每条规则陪着失败
        assertEquals("3", runtime.eval("1+2", emptyMap(), jsLib = """{"crypto":"https://a.com/crypto.js"}"""))
    }

    @Test
    fun `没有 jsLib 的脚本看不到别的源的库`() {
        runtime.eval("plus(1)", mapOf("k" to "1"), jsLib = "function plus(n){ return n }")
        assertNull(runCatching { runtime.eval("plus(1)", emptyMap()) }.getOrNull())
    }

    // ---- 订阅：data: 分类 + jsLib（AI 风月那种写法，去掉测延迟） ----

    @Test
    fun `订阅的 data 分类交给列表脚本，脚本调 jsLib 里的线路表`() = runBlocking {
        val source = """
            {
              "sourceName": "线路导航",
              "sourceUrl": "https://example.com",
              "sortUrl": "路线列表::data:;base64,MA==,{\"type\":0}\n发布页::data:;base64,MQ==,{\"type\":0}",
              "jsLib": "datas = [[{\"title\":\"甲线路\",\"url\":\"https://a.example\"}],[{\"title\":\"发布页\",\"url\":\"https://b.example\"}]];",
              "ruleArticles": "@js:JSON.stringify(datas[java.hexDecodeToString(src)])",
              "ruleTitle": "title",
              "ruleLink": "url"
            }
        """.trimIndent()
        val rule = LegadoRssConverter.convert(source).converted.single().rule
        val engine = RssEngine(http, RhinoScriptRuntime())
        val first = engine.articles(rule, rule.sorts[0].url).items.single()
        assertEquals("甲线路", first.title)
        assertEquals("https://a.example", first.link)
        assertEquals("发布页", engine.articles(rule, rule.sorts[1].url).items.single().title)
    }

    @Test
    fun `订阅的分类地址展开模板，调得到 jsLib 里的函数，选项拆成请求`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"list":[{"t":"热门一"}]}"""))
        server.start()
        val source = """
            {
              "sourceName": "书单",
              "sourceUrl": "${server.url("/")}",
              "sortUrl": "热门::${server.url("/list")}?kind={{kind()}}&p={{page}},{\"method\":\"POST\",\"body\":\"a=1\"}",
              "jsLib": "function kind(){ return 'hot' }",
              "ruleArticles": "$.list[*]",
              "ruleTitle": "$.t",
              "ruleLink": "$.t"
            }
        """.trimIndent()
        val rule = LegadoRssConverter.convert(source).converted.single().rule
        val items = RssEngine(http, RhinoScriptRuntime()).articles(rule, rule.sorts[0].url, page = 2).items
        val req = server.takeRequest()
        assertEquals("/list?kind=hot&p=2", req.path)
        assertEquals("POST", req.method)
        assertEquals("a=1", req.body.readUtf8())
        assertEquals("热门一", items.single().title)
        server.shutdown()
    }

    @Test
    fun `库函数里裸写的绑定名（src、java）也找得到这一次的值`() {
        val lib = "function who() { return src + '/' + java }"
        assertEquals("abc/x", runtime.eval("who()", mapOf("src" to "abc", "java" to "x"), jsLib = lib))
        assertEquals("def/y", runtime.eval("who()", mapOf("src" to "def", "java" to "y"), jsLib = lib))
    }
}
