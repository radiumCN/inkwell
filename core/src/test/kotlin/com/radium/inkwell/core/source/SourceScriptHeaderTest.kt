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
import kotlin.test.assertTrue

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
        // 没有源级 UA 了：发这个来源的默认 UA（阅读导入的，桌面 Chrome）
        assertEquals(SourceHttpClient.LEGADO_UA, req.getHeader("User-Agent"))
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
    fun `规则里 putLoginHeader 存下的头，这个来源后面的请求都带上`() = runBlocking {
        // 一读这类站：先回一段脚本挑战，规则算出 Cookie 存进登录头，后面的请求靠它过
        val src = source(
            header = """{"User-Agent":"src-ua"}""",
            name = "h3@text@js:source.putLoginHeader(JSON.stringify({'Cookie': 't=1', 'User-Agent': 'login-ua'})); result",
        )
        val eng = engine()
        val hit = eng.search(src, "斗破").items.single()
        eng.getDetail(src, hit.bookUrl, hit.variables)
        assertNull(nextRequest().getHeader("Cookie"))
        val detail = nextRequest()
        assertEquals("t=1", detail.getHeader("Cookie"))
        // 登录头盖过源级请求头（同阅读的 getHeaderMap）
        assertEquals("login-ua", detail.getHeader("User-Agent"))
    }

    @Test
    fun `proxy 头是阅读设代理用的，不发给站点`() = runBlocking {
        val src = source("@js:JSON.stringify({'X-A': '1', 'proxy': ''})")
        engine().search(src, "斗破")
        val req = nextRequest()
        assertEquals("1", req.getHeader("X-A"))
        assertNull(req.getHeader("proxy"))
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

    @Test
    fun `单引号写的请求头同阅读的宽松解析也认`() {
        assertEquals(
            mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android 9) Mobile", "client-device" to "2d37"),
            parseHeaderJson("{ 'User-Agent': 'Mozilla/5.0 (Linux; Android 9) Mobile','client-device': '2d37'}"),
        )
        // 单引号串里的双引号、\' 转义，双引号串里的单引号，都照字面
        assertEquals(
            mapOf("X-A" to "say \"hi\"", "X-B" to "it's", "X-C" to "don't"),
            parseHeaderJson("""{'X-A': 'say "hi"', 'X-B': 'it\'s', "X-C": "don't"}"""),
        )
        // 解析不了的照旧当没有
        assertEquals(emptyMap(), parseHeaderJson("'User-Agent': 'x'"))
    }

    // ---- 默认 UA：阅读导入的、没写 UA 的来源同阅读发桌面 Chrome；向导做的发我们的手机 UA ----

    /** 搜索结果的书名经脚本 `java.ajax` 再抓一次详情页：页面请求、脚本请求、各自的 UA 都看得到 */
    private val ajaxName = "h3@text@js:java.ajax(baseUrl + '/b/1'); result"

    @Test
    fun `没写 UA 的阅读来源：页面、详情、脚本请求一律发阅读的桌面 UA`() = runBlocking {
        val src = source(header = null, name = ajaxName)
        val eng = engine()
        val item = eng.search(src, "斗破").items.single()
        eng.getDetail(src, item.bookUrl)
        val uas = List(3) { nextRequest().getHeader("User-Agent") }
        assertEquals(List(3) { SourceHttpClient.LEGADO_UA }, uas)
    }

    @Test
    fun `来源自己写了 UA：页面照它发，脚本请求也照它发`() = runBlocking {
        val src = source(header = """{"User-Agent":"src-ua"}""", name = ajaxName)
        engine().search(src, "斗破")
        assertEquals("src-ua", nextRequest().getHeader("User-Agent"))
        assertEquals("src-ua", nextRequest().getHeader("User-Agent"))
    }

    @Test
    fun `请求头是脚本的来源：脚本请求和页面发同一个 UA`() = runBlocking {
        // 晋江、太极这类：请求头脚本给 java.getWebViewUA()（手机），页面按它发；脚本请求从前却落到了桌面 UA
        val src = source(header = "@js:JSON.stringify({'User-Agent': java.getWebViewUA()})", name = ajaxName)
        engine().search(src, "斗破")
        assertEquals(SourceHttpClient.DEFAULT_UA, nextRequest().getHeader("User-Agent"))
        assertEquals(SourceHttpClient.DEFAULT_UA, nextRequest().getHeader("User-Agent"))
    }

    @Test
    fun `请求头脚本自己发请求取 token，不会一层套一层地重跑自己`() = runBlocking {
        // 脚本请求没写 UA 时补的是「请求头脚本给的 UA」，可请求头脚本里的请求也要补 UA：从前就这样绕回来，
        // 一直套到栈溢出，退栈时每一层各发一次请求
        val src = source(header = "@js:java.ajax(baseUrl + '/token'); JSON.stringify({'X-T': 'ok'})")
        assertEquals(listOf("斗破苍穹"), engine().search(src, "斗破").items.map { it.title })
        val tokenHits = List(server.requestCount) { nextRequest() }.count { it.path == "/token" }
        // 第一次要先跑一遍请求头脚本算出 UA（那一遍里的请求发默认 UA），之后就是每个页面请求跑一回
        assertTrue(tokenHits <= 2, "token 请求了 $tokenHits 次")
    }

    @Test
    fun `脚本请求自己带了 UA 的不覆盖`() = runBlocking {
        val src = source(
            header = null,
            name = "h3@text@js:java.ajax(baseUrl + '/b/1,' + JSON.stringify({headers: {'User-Agent': 'ajax-ua'}})); result",
        )
        engine().search(src, "斗破")
        assertEquals(SourceHttpClient.LEGADO_UA, nextRequest().getHeader("User-Agent"))
        assertEquals("ajax-ua", nextRequest().getHeader("User-Agent"))
    }

    @Test
    fun `向导做的来源发手机 UA：新的写在请求头里，之前做的靠备注认`() = runBlocking {
        // 新做的：SourceDraftBuilder 把手机 UA 写进了请求头
        val drafted = source(header = com.radium.inkwell.core.source.detect.SourceDraftBuilder.NATIVE_HEADER)
        engine().search(drafted, "斗破")
        assertEquals(SourceHttpClient.DEFAULT_UA, nextRequest().getHeader("User-Agent"))
        // 之前做的：请求头里没有 UA，备注是向导写的（改名前叫「新建书源」）
        for (comment in listOf("由「新建来源」根据 https://a.com/book/1/ 自动识别生成", "由「新建书源」根据 https://a.com/b/ 自动识别生成")) {
            val old = source(header = null, comment = comment, name = ajaxName)
            assertEquals(SourceHttpClient.DEFAULT_UA, old.defaultUserAgent)
            engine().search(old, "斗破")
            assertEquals(SourceHttpClient.DEFAULT_UA, nextRequest().getHeader("User-Agent"))
            assertEquals(SourceHttpClient.DEFAULT_UA, nextRequest().getHeader("User-Agent"))
        }
    }

    @Test
    fun `登录网页用的 UA 和抓取的同一个`() = runBlocking {
        val eng = engine()
        assertEquals(SourceHttpClient.LEGADO_UA, eng.userAgentOf(source(header = null)))
        assertEquals("src-ua", eng.userAgentOf(source(header = """{"user-agent":"src-ua"}""")))
        // 请求头是脚本的也跑一遍；跑坏了退回默认的
        assertEquals("sign-ua", eng.userAgentOf(source(header = "@js:JSON.stringify({'User-Agent': 'sign-ua'})")))
        assertEquals(SourceHttpClient.LEGADO_UA, eng.userAgentOf(source(header = "@js:throw new Error('坏了')")))
        assertEquals(
            SourceHttpClient.DEFAULT_UA,
            eng.userAgentOf(source(header = null, comment = "由「新建来源」根据 https://a.com/book/1/ 自动识别生成")),
        )
    }

    @Test
    fun `渲染页面交给渲染器的也是来源的默认 UA`() = runBlocking {
        var renderedWith: String? = null
        val renderer = object : PageRenderer {
            override suspend fun render(url: String, headers: Map<String, String>, userAgent: String?): FetchedPage {
                renderedWith = userAgent
                return FetchedPage(
                    url, 200,
                    """<html><body><div class="r"><h3>斗破苍穹</h3><a href="/b/1">详情</a></div></body></html>""",
                    "UTF-8",
                )
            }
        }
        val eng = BookSourceEngine(
            SourceHttpClient(OkHttpClient(), retryBaseDelayMs = 1, allowPrivateNetwork = true),
            { _, _ -> emptyList() },
            scriptRuntime = com.radium.inkwell.core.source.js.RhinoScriptRuntime(),
            renderer = renderer,
        )
        val src = source(header = null).copy(searchUrl = "/s?q={{key}},{\"webView\":true}")
        assertEquals(listOf("斗破苍穹"), eng.search(src, "斗破").items.map { it.title })
        assertEquals(SourceHttpClient.LEGADO_UA, renderedWith)
    }
}
