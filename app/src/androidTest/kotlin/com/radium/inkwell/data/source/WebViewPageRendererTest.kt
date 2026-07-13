package com.radium.inkwell.data.source

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.radium.inkwell.core.model.ContentElement
import com.radium.inkwell.core.source.BookSourceEngine
import com.radium.inkwell.core.source.BookSourceRule
import com.radium.inkwell.core.source.SourceException
import com.radium.inkwell.core.source.SourceHttpClient
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * JS 渲染回退的落地验证：WebView 必须真的执行页面脚本，纯 JVM 单测覆盖不到这一层。
 *
 * fixture 复刻创世中文网那类站点：静态 HTML 里 `<div id="article">` 是空壳，
 * 正文由脚本在加载后塞进去 —— 只用 OkHttp + Jsoup 无论规则怎么写都拿不到。
 */
@RunWith(AndroidJUnit4::class)
class WebViewPageRendererTest {

    private lateinit var server: MockWebServer
    private lateinit var base: String

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        base = server.url("/").toString().removeSuffix("/")
    }

    @After
    fun tearDown() = server.shutdown()

    private fun context() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun jsRenderedChapter() = MockResponse()
        .setHeader("Content-Type", "text/html; charset=utf-8")
        .setBody(
            """
            <html><body>
              <div id="article"></div>
              <script>
                document.getElementById('article').innerHTML =
                  '<p>脚本注入的正文第一段。</p><p>脚本注入的正文第二段。</p>';
              </script>
            </body></html>
            """.trimIndent()
        )

    @Test
    fun 渲染后能拿到脚本注入的正文() = runBlocking {
        server.enqueue(jsRenderedChapter())
        val page = WebViewPageRenderer(context()).render("$base/chap/1")

        assertNotNull("渲染返回 null，说明加载或超时失败", page)
        assertTrue(
            "渲染后的 HTML 里应当有脚本注入的正文",
            page!!.bodyText.contains("脚本注入的正文第一段。"),
        )
    }

    @Test
    fun 静态抓取拿不到而渲染回退能拿到() = runBlocking {
        val rule = BookSourceRule.fromJson(
            """{"id":"js.test","name":"JS渲染站","baseUrl":"$base",
                "content":{"content":"css:#article@html"}}"""
        )

        // 无渲染器：静态 HTML 里 #article 是空的 → 规则匹配不到内容
        server.enqueue(jsRenderedChapter())
        val plain = BookSourceEngine(SourceHttpClient())
        try {
            plain.getContent(rule, "$base/chap/1")
            throw AssertionError("静态抓取本该失败：#article 静态是空壳")
        } catch (_: SourceException) {
            // 预期
        }

        // 有渲染器：先静态空跑一次，再 WebView 渲染重试 → 拿到正文
        server.enqueue(jsRenderedChapter()) // 静态那一次
        server.enqueue(jsRenderedChapter()) // 渲染那一次
        val rendered = BookSourceEngine(SourceHttpClient(), renderer = WebViewPageRenderer(context()))
            .getContent(rule, "$base/chap/1")

        assertEquals(
            listOf("脚本注入的正文第一段。", "脚本注入的正文第二段。"),
            rendered.elements.filterIsInstance<ContentElement.Paragraph>().map { it.text },
        )
    }
}
