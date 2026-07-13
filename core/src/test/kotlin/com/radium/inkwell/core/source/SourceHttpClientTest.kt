package com.radium.inkwell.core.source

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Before
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SourceHttpClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client() = SourceHttpClient(OkHttpClient(), retryBaseDelayMs = 1)

    private fun gbkBody(html: String): Buffer =
        Buffer().write(html.toByteArray(charset("GBK")))

    @Test
    fun `charset from content type header, gbk decoded as gb18030`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setBody(gbkBody("<html><body>你好世界</body></html>"))
                .setHeader("Content-Type", "text/html; charset=gbk")
        )
        val page = client().fetch(server.url("/").toString())
        assertEquals("GB18030", page.detectedCharset)
        assertTrue(page.bodyText.contains("你好世界"))
    }

    @Test
    fun `charset sniffed from meta when header has no charset`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setBody(gbkBody("<html><head><meta charset=\"gbk\"></head><body>中文嗅探测试</body></html>"))
                .setHeader("Content-Type", "text/html")
        )
        val page = client().fetch(server.url("/").toString())
        assertEquals("GB18030", page.detectedCharset)
        assertTrue(page.bodyText.contains("中文嗅探测试"))
    }

    @Test
    fun `source declared charset wins over wrong header`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setBody(gbkBody("<html><body>声明优先</body></html>"))
                .setHeader("Content-Type", "text/html; charset=utf-8")
        )
        val page = client().fetch(server.url("/").toString(), charsetOverride = "gbk")
        assertTrue(page.bodyText.contains("声明优先"))
    }

    @Test
    fun `defaults to utf8 when nothing declared`() = runBlocking {
        server.enqueue(MockResponse().setBody("<html><body>默认utf8</body></html>"))
        val page = client().fetch(server.url("/").toString())
        assertEquals("UTF-8", page.detectedCharset)
        assertTrue(page.bodyText.contains("默认utf8"))
    }

    @Test
    fun `retries on 429 then succeeds`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(MockResponse().setBody("ok"))
        val page = client().fetch(server.url("/").toString())
        assertEquals(200, page.statusCode)
        assertEquals("ok", page.bodyText)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `gives up after two retries on 403`() = runBlocking {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(403)) }
        assertFailsWith<IOException> { client().fetch(server.url("/").toString()) }
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `default mobile UA is added when absent`() = runBlocking {
        server.enqueue(MockResponse().setBody("ok"))
        server.enqueue(MockResponse().setBody("ok"))
        val c = client()
        c.fetch(server.url("/").toString())
        val ua = server.takeRequest().getHeader("User-Agent")!!
        assertTrue(ua.contains("Mobile"))
        // 显式 UA 不被覆盖
        c.fetch(server.url("/").toString(), headers = mapOf("User-Agent" to "custom-ua"))
        assertEquals("custom-ua", server.takeRequest().getHeader("User-Agent"))
    }

    @Test
    fun `cookies persist across requests to same host`() = runBlocking {
        server.enqueue(MockResponse().setBody("ok").addHeader("Set-Cookie", "sid=abc123; Path=/"))
        server.enqueue(MockResponse().setBody("ok"))
        val c = client()
        c.fetch(server.url("/login").toString())
        c.fetch(server.url("/next").toString())
        server.takeRequest()
        val cookie = server.takeRequest().getHeader("Cookie")
        assertTrue(cookie != null && cookie.contains("sid=abc123"))
    }

    @Test
    fun `post sends body with content type`() = runBlocking {
        server.enqueue(MockResponse().setBody("ok"))
        client().fetch(
            server.url("/api").toString(),
            method = "POST",
            body = "q=测试",
            headers = mapOf("Content-Type" to "application/x-www-form-urlencoded"),
        )
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("q=测试", req.body.readUtf8())
    }

    @Test
    fun `rate limit spaces out requests`() = runBlocking {
        repeat(3) { server.enqueue(MockResponse().setBody("ok")) }
        val c = client()
        val limit = RateLimitRule(intervalMs = 60, burst = 1)
        val start = System.currentTimeMillis()
        repeat(3) { c.fetch(server.url("/").toString(), rateLimit = limit) }
        // burst=1：第 2、3 次各需等待约 60ms
        assertTrue(System.currentTimeMillis() - start >= 100)
    }

    @Test
    fun `final url reflects redirect`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(302)
                .setHeader("Location", server.url("/target").toString())
        )
        server.enqueue(MockResponse().setBody("landed"))
        val page = client().fetch(server.url("/from").toString())
        assertTrue(page.finalUrl.endsWith("/target"))
        assertEquals("landed", page.bodyText)
    }
}
