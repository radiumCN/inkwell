package com.radium.inkwell.data.repo

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 反馈提交的状态码分支。
 *
 * 每个码对应用户完全不同的下一步（改内容 / 等一会儿 / 别再点了），压成一个笼统的
 * 「提交失败」就等于什么都没告诉他 —— 所以这些分支值得逐个钉住。
 */
class FeedbackRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var repo: FeedbackRepository

    @BeforeTest
    fun setUp() {
        server = MockWebServer().also { it.start() }
        repo = FeedbackRepository(OkHttpClient(), server.url("/").toString().removeSuffix("/"))
    }

    @AfterTest
    fun tearDown() = server.shutdown()

    @Test
    fun `201 返回 issue 号与地址`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(201).setBody(
                """{"status":"ok","issue":123,"url":"https://github.com/radiumCN/inkwell/issues/123"}""",
            ),
        )
        val r = repo.submit("打开就闪退")
        assertEquals(
            FeedbackResult.Success(123, "https://github.com/radiumCN/inkwell/issues/123"),
            r,
        )
    }

    /** 服务端只认 snake_case，字段名写错的话 issue 里就没有环境信息，白填 */
    @Test
    fun `请求体用 snake_case 且打到正确路径`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"issue":1,"url":"u"}"""))
        repo.submit(
            content = "正文",
            contact = "a@b.com",
            appVersion = "v0.1.5-beta.1",
            device = "Pixel 7",
            osVersion = "Android 14",
            channel = "beta",
        )
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/v1/feedback", req.path)
        val body = Json.parseToJsonElement(req.body.readUtf8()) as JsonObject
        assertEquals("正文", body["content"]?.jsonPrimitive?.content)
        assertEquals("a@b.com", body["contact"]?.jsonPrimitive?.content)
        assertEquals("v0.1.5-beta.1", body["app_version"]?.jsonPrimitive?.content)
        assertEquals("Pixel 7", body["device"]?.jsonPrimitive?.content)
        assertEquals("Android 14", body["os_version"]?.jsonPrimitive?.content)
        assertEquals("beta", body["channel"]?.jsonPrimitive?.content)
    }

    /** 空白联系方式不该占着字段发出去 —— 那会在公开 issue 里留一行空的「联系方式：」 */
    @Test
    fun `空白联系方式不发送该字段`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"issue":1,"url":"u"}"""))
        repo.submit(content = "正文", contact = "   ")
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()) as JsonObject
        assertTrue("contact" !in body)
    }

    @Test
    fun `400 是内容非法`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400))
        assertEquals(FeedbackResult.InvalidContent, repo.submit(""))
    }

    @Test
    fun `413 是内容超长`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(413))
        assertEquals(FeedbackResult.TooLong, repo.submit("x"))
    }

    /** 429 要把 Retry-After 读出来，否则只能干说「稍后再试」，用户不知道等多久 */
    @Test
    fun `429 读出 Retry-After 秒数`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "1800"))
        assertEquals(FeedbackResult.RateLimited(1800), repo.submit("x"))
    }

    /** 服务端不给 Retry-After 也不能崩 —— 限流本身仍要如实告诉用户 */
    @Test
    fun `429 没有 Retry-After 时仍是限流`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429))
        val r = repo.submit("x")
        assertTrue(r is FeedbackResult.RateLimited)
        assertNull(r.retryAfterSec)
    }

    @Test
    fun `502 与 503 都算服务端故障`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(502))
        assertEquals(FeedbackResult.ServerDown, repo.submit("x"))
        server.enqueue(MockResponse().setResponseCode(503))
        assertEquals(FeedbackResult.ServerDown, repo.submit("x"))
    }

    /** 没预料到的码不能冒充成功 */
    @Test
    fun `未知状态码归为网络错误而不是成功`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(418))
        val r = repo.submit("x")
        assertTrue(r is FeedbackResult.NetworkError)
    }
}
