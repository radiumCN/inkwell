package com.radium.inkwell.data.repo

import com.radium.inkwell.data.prefs.FeedbackReceipt
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
import java.io.File

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
    fun `反馈进度和讨论经中转读取 旧收据也无需回复凭证`() = runBlocking {
        server.enqueue(MockResponse().setBody(
            """{"state":"closed","state_reason":"not_planned","comments":[{"id":5000000000,"body":"开发者回复","user":{"login":"maintainer"}}],"next_cursor":"cursor+page="}""",
        ))
        val receipt = FeedbackReceipt(123, "https://github.com/radiumCN/inkwell/issues/123", "测试")
        val discussion = requireNotNull(repo.discussion(receipt))
        assertEquals(FeedbackIssueStatus.NOT_PLANNED, discussion.status)
        assertEquals("开发者回复", discussion.comments.single().message)
        assertEquals("maintainer", discussion.comments.single().authorLabel)
        assertEquals(5000000000L, discussion.comments.single().id)
        val request = server.takeRequest()
        assertEquals("/api/v1/feedback/123?repo=radiumCN%2Finkwell", request.path)
        assertNull(request.getHeader("Authorization"))

        server.enqueue(MockResponse().setBody("""{"state":"closed","comments":[]}"""))
        assertNull(requireNotNull(repo.discussion(receipt, discussion.nextCursor)).nextCursor)
        assertEquals("/api/v1/feedback/123?repo=radiumCN%2Finkwell&after=cursor%2Bpage%3D", server.takeRequest().path)
    }

    @Test
    fun `反馈进度忽略非 GitHub 地址和编号不匹配的收据`() = runBlocking {
        assertNull(repo.discussion(FeedbackReceipt(123, "https://example.com/owner/project/issues/123", "测试")))
        assertNull(repo.discussion(FeedbackReceipt(123, "https://github.com/owner/project/issues/456", "测试")))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `应用回复只把凭证发送给反馈中转服务`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setBody(
            """{"id":42,"body":"**反馈提交者（来自 Inkwell）**\n\n已验证","user":{"login":"relay"}}""",
        ))
        val receipt = FeedbackReceipt(123, "https://github.com/radiumCN/inkwell/issues/123", "测试", "private-token")
        assertNull(repo.reply(receipt.copy(replyToken = null), "已验证"))
        val reply = requireNotNull(repo.reply(receipt, "已验证"))
        assertEquals(42L, reply.id)
        assertEquals("已验证", reply.message)
        assertEquals("反馈提交者", reply.authorLabel)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/feedback/123/comments", request.path)
        assertEquals("Bearer private-token", request.getHeader("Authorization"))
        assertEquals("已验证", (Json.parseToJsonElement(request.body.readUtf8()) as JsonObject)["body"]?.jsonPrimitive?.content)
    }

    @Test
    fun `追加日志使用回复凭证并上传原始 ZIP 请求体`() = runBlocking {
        val receipt = FeedbackReceipt(123, "https://github.com/radiumCN/inkwell/issues/123", "测试", "private-token")
        val uploadId = "11111111-2222-3333-4444-555555555555"
        val logs = File.createTempFile("feedback-log-upload", ".zip")
        try {
            logs.writeText("private-logs")
            for (code in listOf(201, 204)) {
                server.enqueue(MockResponse().setResponseCode(code))
                assertEquals(FeedbackLogUploadResult.SUCCESS, repo.appendLogs(receipt, uploadId, logs))
                val request = server.takeRequest()
                assertEquals("PUT", request.method)
                assertEquals("/api/v1/feedback/123/logs/$uploadId", request.path)
                assertEquals("Bearer private-token", request.getHeader("Authorization"))
                assertEquals("application/zip", request.getHeader("Content-Type"))
                assertEquals("private-logs", request.body.readUtf8())
            }
        } finally {
            logs.delete()
        }
    }

    @Test
    fun `追加日志需要匹配的反馈收据`() = runBlocking {
        val receipt = FeedbackReceipt(123, "https://github.com/radiumCN/inkwell/issues/123", "测试", "private-token")
        val logs = File("unused.zip")
        assertEquals(FeedbackLogUploadResult.FORBIDDEN, repo.appendLogs(receipt.copy(replyToken = null), "id", logs))
        assertEquals(FeedbackLogUploadResult.FORBIDDEN, repo.appendLogs(receipt.copy(issue = 456), "id", logs))
        assertEquals(0, server.requestCount)
    }

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
    fun `附带日志使用 multipart 并发送同一个提交编号`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"issue":1,"url":"u"}"""))
        val logs = File.createTempFile("feedback-test", ".zip")
        try {
            logs.writeText("test-archive-marker")
            repo.submit(content = "正文", requestId = "stable-submission-id", logs = logs)
            val req = server.takeRequest()
            assertTrue(req.getHeader("Content-Type")!!.startsWith("multipart/form-data;"))
            val body = req.body.readUtf8()
            assertTrue("name=\"payload\"" in body)
            assertTrue("\"request_id\":\"stable-submission-id\"" in body)
            assertTrue("name=\"logs\"; filename=\"logs.zip\"" in body)
            assertTrue("test-archive-marker" in body)
        } finally {
            logs.delete()
        }
    }

    @Test
    fun `附件单独使用 multipart 无需附带日志`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"issue":1,"url":"u"}"""))
        val files = File.createTempFile("feedback-attachments-test", ".zip")
        try {
            files.writeText("attachments-marker")
            assertEquals(
                FeedbackResult.Success(1, "u"),
                repo.submit(content = "正文", requestId = "stable-submission-id", attachments = files),
            )
            val req = server.takeRequest()
            assertTrue(req.getHeader("Content-Type")!!.startsWith("multipart/form-data;"))
            val body = req.body.readUtf8()
            assertTrue("name=\"payload\"" in body)
            assertTrue("name=\"attachments\"; filename=\"attachments.zip\"" in body)
            assertTrue("attachments-marker" in body)
            assertTrue("name=\"logs\"" !in body)
        } finally {
            files.delete()
        }
    }

    @Test
    fun `不附带日志保留 JSON 且发送提交编号`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"issue":1,"url":"u"}"""))
        repo.submit(content = "正文", requestId = "stable-submission-id")
        val req = server.takeRequest()
        assertTrue(req.getHeader("Content-Type")!!.startsWith("application/json"))
        val body = Json.parseToJsonElement(req.body.readUtf8()) as JsonObject
        assertEquals("stable-submission-id", body["request_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `结果待确认不提示重新提交`() = runBlocking {
        for (code in listOf(409, 502, 503)) {
            server.enqueue(MockResponse().setResponseCode(code).setBody("""{"code":"result_pending"}"""))
            assertEquals(FeedbackResult.Pending, repo.submit("正文"))
        }
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
