package com.radium.inkwell.core.source

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 源级 header 写成脚本（`@js:` / `<js>`）、规则里读 `source` 的字段：
 * - header 脚本从前一律当没有源级请求头，UA、Referer、签名一个都没发出去；
 * - `eval(String(source.bookSourceComment))` 加载写在来源说明里的公用函数，从前 eval 的是 "undefined"。
 */
class SourceScriptHeaderTest {

    private lateinit var server: MockWebServer
    private lateinit var base: String

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/s") -> MockResponse().setBody(
                        """<html><body><div class="r"><h3>斗破苍穹</h3><a href="/b/1">详情</a></div></body></html>""",
                    )
                    path.startsWith("/b/1") -> MockResponse().setBody("<html><body><h1>斗破苍穹</h1></body></html>")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        base = server.url("/").toString().removeSuffix("/")
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun engine() = BookSourceEngine(
        // MockWebServer 在 127.0.0.1 上，得放行 SSRF 防护
        SourceHttpClient(OkHttpClient(), retryBaseDelayMs = 1, allowPrivateNetwork = true),
        { _, _ -> emptyList() },
        scriptRuntime = com.radium.inkwell.core.source.js.RhinoScriptRuntime(),
    )

    private fun source(
        header: String?,
        url: String = base,
        comment: String = "",
        name: String = "h3@text",
        jsLib: String? = null,
    ) = BookSourceRule(
        bookSourceUrl = url,
        bookSourceName = "签名源",
        bookSourceComment = comment,
        header = header,
        searchUrl = "/s?q={{key}}",
        ruleSearch = SearchRuleSet(bookList = "div.r", name = name, bookUrl = "a@href"),
        ruleBookInfo = BookInfoRuleSet(name = "h1@text"),
        jsLib = jsLib,
    )

    private fun nextRequest(): RecordedRequest = server.takeRequest(5, TimeUnit.SECONDS)!!

    @Test
    fun `header 脚本每次请求现跑 —— 搜索、详情各跑一回，签名不会过期`() = runBlocking {
        val src = source(
            "<js>\n(()=>{\n var n = Number(cache.get('n') || 0) + 1; cache.put('n', String(n));\n" +
                " return JSON.stringify({'X-N': String(n), 'User-Agent': 'sign-ua'});\n})()\n</js>",
        )
        val eng = engine()
        val hit = eng.search(src, "斗破").items.single()
        eng.getDetail(src, hit.bookUrl, hit.variables)

        val search = nextRequest()
        assertEquals("1", search.getHeader("X-N"))
        assertEquals("sign-ua", search.getHeader("User-Agent"))
        assertEquals("2", nextRequest().getHeader("X-N"))
    }

    @Test
    fun `header 脚本里的 baseUrl、source、java 都照阅读`() = runBlocking {
        val src = source(
            "@js:JSON.stringify({\"Referer\": baseUrl, \"X-Src\": source.bookSourceUrl, " +
                "\"User-Agent\": java.getWebViewUA(), \"X-Sign\": java.digestHex(\"abc\", \"md5\")})",
            url = "$base#2",
        )
        engine().search(src, "斗破")
        val req = nextRequest()
        // baseUrl 是剥掉 #后缀的站点根，source.bookSourceUrl 是原样的来源地址
        assertEquals(base, req.getHeader("Referer"))
        assertEquals("$base#2", req.getHeader("X-Src"))
        assertEquals(SourceHttpClient.DEFAULT_UA, req.getHeader("User-Agent"))
        // 阅读的参数顺序：digestHex(数据, 算法)
        assertEquals("900150983cd24fb0d6963f7d28e17f72", req.getHeader("X-Sign"))
    }

    @Test
    fun `header 脚本能调 jsLib 里的函数`() = runBlocking {
        val src = source("@js:JSON.stringify({'User-Agent': libUa()})", jsLib = "function libUa(){ return 'lib-ua' }")
        engine().search(src, "斗破")
        assertEquals("lib-ua", nextRequest().getHeader("User-Agent"))
    }

    @Test
    fun `header 脚本跑坏了照常发请求，只是没有源级请求头`() = runBlocking {
        val src = source("@js:throw new Error('坏了')")
        val items = engine().search(src, "斗破").items
        assertEquals(listOf("斗破苍穹"), items.map { it.title })
        val req = nextRequest()
        assertNull(req.getHeader("X-N"))
        assertEquals(SourceHttpClient.DEFAULT_UA, req.getHeader("User-Agent"))
    }

    @Test
    fun `规则里 eval 来源说明加载公用函数，source 的名字、地址也读得到`() = runBlocking {
        val src = source(
            header = null,
            comment = "function deco(s){ return '《' + s + '》' + source.bookSourceName + '|' + source.key }",
            name = "h3@text@js:eval(String(source.bookSourceComment)); deco(result)",
        )
        assertEquals("《斗破苍穹》签名源|$base", engine().search(src, "斗破").items.single().title)
    }

    @Test
    fun `header 脚本的两种写法都认，JSON 写法不算脚本`() {
        assertEquals("JSON.stringify({})", headerScriptOf("@js:JSON.stringify({})"))
        assertEquals("\nvar h = 1 < 2;\nJSON.stringify({})\n", headerScriptOf("<js>\nvar h = 1 < 2;\nJSON.stringify({})\n</js>"))
        assertEquals("JSON.stringify({})", headerScriptOf("<JS>JSON.stringify({})"))
        assertNull(headerScriptOf("""{"User-Agent":"x"}"""))
        assertNull(headerScriptOf("@js:  "))
        assertNull(headerScriptOf(null))
        // JSON 写法照旧解析
        assertEquals(mapOf("User-Agent" to "x"), source("""{"User-Agent":"x"}""").headers)
        assertNull(source("""{"User-Agent":"x"}""").headerScript)
    }
}
