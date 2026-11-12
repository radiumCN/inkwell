package com.radium.inkwell.core.source

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 接口源（返回 JSON、请求头签名）与声明 webView 的网页源 —— 七猫小说这两类源在换源里一个都不出现：
 * - 地址选项里值为空的请求头被丢掉，服务端按收到的头验签对不上，整个 401；
 * - 内容是 JSON 时，页面一级不带 `$.` 的规则（`data.books`）被当成 CSS 选择器；
 * - 搜索地址脚本 java.put 的变量（签名用的请求头），结果规则、详情、目录都拿不到；
 * - 书源声明 `webView` 的搜索地址照旧静态抓，拿到的是没有结果的空壳页。
 */
class ApiAndWebViewSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var base: String

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                val json = MockResponse().setHeader("Content-Type", "application/json; charset=utf-8")
                return when {
                    path.startsWith("/api/search") -> json.setBody(
                        """{"data":{"books":[
                            {"id":17,"original_title":"斗破苍穹","original_author":"天蚕土豆"},
                            {"id":18,"original_title":"斗破苍穹之举族飞升","original_author":"又闻秋风起"}
                        ]}}""",
                    )
                    path.startsWith("/api/detail") -> json.setBody(
                        """{"data":{"book":{"id":17,"title":"斗破苍穹","author":"天蚕土豆"}}}""",
                    )
                    path.startsWith("/web/search") -> MockResponse().setBody("<html><body>加载中…</body></html>")
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

    private fun engine(renderer: PageRenderer? = null) = BookSourceEngine(
        // MockWebServer 在 127.0.0.1 上，得放行 SSRF 防护
        SourceHttpClient(OkHttpClient(), retryBaseDelayMs = 1, allowPrivateNetwork = true),
        { _, _ -> emptyList() },
        scriptRuntime = com.radium.inkwell.core.source.js.RhinoScriptRuntime(),
        renderer = renderer,
    )

    /** 照七猫接口源的写法：搜索地址是脚本，把签名用的请求头 java.put 下来；详情、目录地址再 java.get 拼上 */
    private fun apiSource(): BookSourceRule = BookSourceRule.fromJson(
        """
        {
          "bookSourceName": "接口源",
          "bookSourceUrl": "$base",
          "searchUrl": "@js:\nheaders={'app-version':'1','AUTHORIZATION':'','sign':'abc'}\n\"/api/search?wd=\"+key+\",\"+java.put(\"headers\",JSON.stringify({\"headers\":headers}))",
          "ruleSearch": {
            "bookList": "data.books",
            "name": "original_title",
            "author": "original_author",
            "bookUrl": "@js:\"/api/detail?id={{${'$'}.id}},\"+java.get(\"headers\")"
          },
          "ruleBookInfo": {
            "init": "data.book",
            "name": "title",
            "author": "author",
            "tocUrl": "@js:\"/api/toc?id={{${'$'}.id}},\"+java.get(\"headers\")"
          }
        }
        """.trimIndent(),
    )

    @Test
    fun `值为空的请求头照样发 —— 签名把它算进去了，少发一个服务端验签就对不上`() = runBlocking {
        engine().search(apiSource(), "斗破")
        val req = server.takeRequest()
        assertEquals("", req.headers["AUTHORIZATION"], "空值的头被丢了：${req.headers}")
        assertEquals("abc", req.headers["sign"])
    }

    @Test
    fun `内容是 JSON 时页面一级的裸规则按 JSONPath —— data_books 不是 CSS 选择器`() = runBlocking {
        val items = engine().search(apiSource(), "斗破").items
        assertEquals(listOf("斗破苍穹", "斗破苍穹之举族飞升"), items.map { it.title })
        assertEquals("天蚕土豆", items.first().author)

        // 详情的 init 也是裸的 `data.book`
        val detail = engine().getDetail(apiSource(), items.first().bookUrl, items.first().variables)
        assertEquals("斗破苍穹", detail.title)
        assertEquals("天蚕土豆", detail.author)
    }

    @Test
    fun `搜索地址里 java_put 的变量，结果规则拿得到，并随结果带进详情、目录`() = runBlocking {
        val eng = engine()
        val hit = eng.search(apiSource(), "斗破").items.first()
        // 结果规则里 java.get 到了：详情地址带着请求头选项
        assertTrue(hit.bookUrl.contains("\"sign\":\"abc\""), "详情地址没拼上请求头：${hit.bookUrl}")
        assertTrue(hit.variables["headers"].orEmpty().contains("AUTHORIZATION"))

        server.takeRequest() // 搜索那一下
        val detail = eng.getDetail(apiSource(), hit.bookUrl, hit.variables)
        // 详情请求带上了选项里的头（含空值的那个）
        val detailReq = server.takeRequest()
        assertEquals("/api/detail?id=17", detailReq.path)
        assertEquals("", detailReq.headers["AUTHORIZATION"])
        // 详情规则里 java.get 也拿得到：目录地址同样带着请求头，变量随书往下交
        assertTrue(detail.tocUrl.contains("\"sign\":\"abc\""), "目录地址没拼上请求头：${detail.tocUrl}")
        assertTrue(detail.variables.containsKey("headers"))
    }

    @Test
    fun `脚本拼出的地址选项块跨了行，还是一个地址 —— 不按行拆成几段废地址`() = runBlocking {
        // 凤凰网书城的写法：`…/directory,{` 换行 `"body": …,` 换行 `"method": "POST"` 换行 `}`
        val src = BookSourceRule(
            bookSourceUrl = base,
            bookSourceName = "接口源",
            ruleBookInfo = BookInfoRuleSet(
                init = "data.book",
                name = "title",
                tocUrl = "@js:'/api/toc,{\\n  \"body\": \"bookId=17&getNum=10000\",\\n  \"method\": \"POST\"\\n}'",
            ),
        )
        val detail = engine().getDetail(src, "$base/api/detail?id=17")
        val (url, opt) = splitUrlOptions(detail.tocUrl)!!
        assertEquals("$base/api/toc", url)
        assertEquals("POST", opt.method)
        assertEquals("bookId=17&getNum=10000", opt.body)
    }

    @Test
    fun `一条结果规则里 put 的变量不串到下一条`() = runBlocking {
        // 书名规则先读 k、分类规则后存 k：变量表要是各条共用，第二条的书名前面就会粘上第一条存的 k
        val src = BookSourceRule.fromJson(
            """
            {
              "bookSourceName": "接口源",
              "bookSourceUrl": "$base",
              "searchUrl": "/api/search?wd={{key}}",
              "ruleSearch": {
                "bookList": "data.books",
                "name": "original_title@js:java.get('k')+result",
                "kind": "original_author@put:{k:original_title}",
                "bookUrl": "/api/detail?id={{${'$'}.id}}"
              }
            }
            """.trimIndent(),
        )
        val items = engine().search(src, "斗破").items
        assertEquals(listOf("斗破苍穹", "斗破苍穹之举族飞升"), items.map { it.title })
        // 自己这条存的照样带走
        assertEquals("斗破苍穹之举族飞升", items[1].variables["k"])
    }

    private class FakeRenderer(private val html: String) : PageRenderer {
        val calls = java.util.concurrent.CopyOnWriteArrayList<String>()
        override suspend fun render(url: String, headers: Map<String, String>, userAgent: String?): FetchedPage {
            calls += url
            return FetchedPage(url, 200, html, "UTF-8")
        }
    }

    private fun webSource(webView: Boolean): BookSourceRule = BookSourceRule.fromJson(
        """
        {
          "bookSourceName": "网页源",
          "bookSourceUrl": "$base",
          "searchUrl": "/web/search?keyword={{key}}${if (webView) ",{'webView': true}" else ""}",
          "ruleSearch": {
            "bookList": ".qm-pic-txt@li",
            "name": "a.0@text",
            "bookUrl": "a.0@href"
          }
        }
        """.trimIndent(),
    )

    @Test
    fun `书源声明 webView 的搜索交给渲染器 —— 结果是页面脚本拉回来的，静态抓只是空壳`() = runBlocking {
        val renderer = FakeRenderer(
            """<html><body><ul class="qm-pic-txt"><li><a href="/book/1">斗破苍穹</a></li></ul></body></html>""",
        )
        val items = engine(renderer).search(webSource(webView = true), "斗破").items
        assertEquals(listOf("斗破苍穹"), items.map { it.title })
        // 交给渲染器的是剥掉选项的地址
        assertEquals(1, renderer.calls.size)
        assertTrue(renderer.calls.single().startsWith("$base/web/search?keyword="), renderer.calls.single())
        assertTrue(!renderer.calls.single().contains("webView"))
        assertEquals(0, server.requestCount, "声明了 webView 就不该再静态抓一遍")
    }

    @Test
    fun `没声明 webView 的搜索照旧只静态抓，不惊动渲染器`() = runBlocking {
        val renderer = FakeRenderer("<html><body></body></html>")
        engine(renderer).search(webSource(webView = false), "斗破")
        assertTrue(renderer.calls.isEmpty(), "搜索不该自动回退到渲染器：${renderer.calls}")
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `webView 选项的写法同 Legado：false 和空串是不用，其余都算要`() {
        fun opt(v: String) = splitUrlOptions("/s,{\"webView\":$v}")!!.second.webView
        assertTrue(opt("true"))
        assertTrue(opt("\"1\""))
        assertEquals(false, opt("false"))
        assertEquals(false, opt("\"false\""))
        assertEquals(false, opt("\"\""))
        assertEquals(false, opt("null"))
    }
}
