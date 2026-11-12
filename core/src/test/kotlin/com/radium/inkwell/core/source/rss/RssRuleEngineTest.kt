package com.radium.inkwell.core.source.rss

import com.radium.inkwell.core.model.ContentElement
import com.radium.inkwell.core.source.FetchedPage
import com.radium.inkwell.core.source.PageRenderer
import com.radium.inkwell.core.source.SourceHttpClient
import com.radium.inkwell.core.source.js.RhinoScriptRuntime
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import kotlin.test.Test
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFails

/**
 * 在 feed 上写规则的订阅源（翻译类学术订阅源都是这样）：脚本先把整份 feed 过一遍再 `//item`，
 * 链接取 `link@text`，正文脚本里 `java.ajax` 再去拿别的东西。
 *
 * 三样从前全坏：脚本接 XPath 的列表取到的是纯文本节点；feed 按 HTML 解析，`<link>` 的地址被挤出元素；
 * 订阅引擎没给脚本接网络，`java.ajax` 一律回空。
 */
class RssRuleEngineTest {

    private lateinit var server: MockWebServer
    private lateinit var base: String

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/feed" -> MockResponse().setHeader("Content-Type", "application/rss+xml; charset=utf-8").setBody(
                    """<?xml version="1.0" encoding="UTF-8"?>
                    <rss version="2.0"><channel><title>站</title><link>$base</link>
                    <item><title>第一篇</title><link><![CDATA[$base/p/1]]></link></item>
                    <item><title>第二篇</title><link>$base/p/2</link></item>
                    </channel></rss>""",
                )
                "/p/1" -> MockResponse().setBody("<html><body><div class=\"c\">正文开头</div></body></html>")
                "/part" -> MockResponse().setBody("后面一段")
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        base = server.url("/").toString().removeSuffix("/")
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun engine() = RssEngine(
        SourceHttpClient(OkHttpClient(), retryBaseDelayMs = 1, allowPrivateNetwork = true),
        RhinoScriptRuntime(),
    )

    @Test
    fun `feed 上的规则：脚本接 XPath、取 link，正文脚本能 ajax`() = runBlocking {
        val rule = RssSourceRule(
            id = base,
            name = "测试订阅",
            sorts = listOf(RssSort("全部", "$base/feed")),
            articles = "<js>\nresult;\n</js>\n//item",
            title = "title@text",
            link = "link@text",
            content = "<js>'<p>' + java.getString('.c@text') + '，' + java.ajax('$base/part') + '·' + source.sourceName + '</p>'</js>",
        )
        val eng = engine()
        val page = eng.articles(rule, "$base/feed")
        assertEquals(listOf("第一篇", "第二篇"), page.items.map { it.title })
        assertEquals(listOf("$base/p/1", "$base/p/2"), page.items.map { it.link })

        val content = eng.content(rule, page.items.first())
        assertEquals(listOf(ContentElement.Paragraph("正文开头，后面一段·测试订阅")), content.elements)
    }

    @Test
    fun `header 脚本每次请求现跑，Jsoup 写的正文规则能用`() = runBlocking {
        // 翻译类学术订阅源的样子：header 是脚本、读登录信息（没登录为空）和 source.sourceUrl；正文规则拿 Jsoup 改写页面
        val converted = LegadoRssConverter.convert(
            """[{"sourceName":"学术","sourceUrl":"$base","sortUrl":"全部::$base/feed",
                "header":"@js:
var info = null; try { info = source.getLoginInfoMap(); } catch (e) {}
JSON.stringify({'Referer': String(source.sourceUrl), 'X-Info': String(info)})",
                "ruleArticles":"//item","ruleTitle":"title@text","ruleLink":"link@text",
                "ruleContent":"@css:body@html
<js>var doc = org.jsoup.Jsoup.parse(result); doc.select('.c').first().text('改过'); doc.body().html()</js>"}]""",
        ).converted.single().rule
        assertEquals(null, converted.headers["Referer"])
        val eng = engine()
        val page = eng.articles(converted, "$base/feed")
        val req = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals(base, req.getHeader("Referer"))
        assertEquals("null", req.getHeader("X-Info"))
        val content = eng.content(converted, page.items.first())
        assertEquals(listOf(ContentElement.Paragraph("改过")), content.elements)
        assertEquals(base, server.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Referer"))
    }

    @Test
    fun `地址声明 webView 的交给渲染器，渲染不出来退回静态抓`() = runBlocking {
        val rendered = mutableListOf<String>()
        val renderer = object : PageRenderer {
            override suspend fun render(url: String, headers: Map<String, String>, userAgent: String?): FetchedPage? {
                rendered += url
                if (url.endsWith("/fail")) return null
                return FetchedPage(url, 200, "<ul><li><a href=\"/p/9\">画出来的</a></li></ul>", "UTF-8")
            }
        }
        val eng = RssEngine(
            SourceHttpClient(OkHttpClient(), retryBaseDelayMs = 1, allowPrivateNetwork = true),
            RhinoScriptRuntime(),
            renderer,
        )
        val rule = RssSourceRule(id = base, name = "渲染", articles = "li", title = "a@text", link = "a@href")
        val page = eng.articles(rule, """$base/list,{"webView":true}""")
        assertEquals(listOf("画出来的"), page.items.map { it.title })
        assertEquals(listOf("$base/list"), rendered)
        // 没声明的不渲染；渲染失败的退回静态抓（这里静态抓是 404）
        eng.articles(RssSourceRule(id = base, name = "静态"), "$base/feed")
        assertEquals(1, rendered.size)
        assertFails { eng.articles(rule, """$base/fail,{"webView":true}""") }
        assertEquals(2, rendered.size)
    }
}
