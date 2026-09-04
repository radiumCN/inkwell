package com.radium.inkwell.data.net

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OfficialWebDavClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OfficialWebDavClient

    @BeforeTest
    fun setUp() {
        server = MockWebServer().also { it.start() }
        client = OfficialWebDavClient(OkHttpClient(), server.url("/").toString().removeSuffix("/"))
    }

    @AfterTest
    fun tearDown() = server.shutdown()

    @Test
    fun `publicConfig 读出注册开关与 DAV 地址`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"ok":true,"data":{"registration_enabled":true,"webdav_url":"https://webdav-api.skylark.run/dav/"}}""",
            ),
        )
        val cfg = client.publicConfig()
        assertTrue(cfg.registrationEnabled)
        assertEquals("https://webdav-api.skylark.run/dav/", cfg.webdavUrl)
        assertEquals("/api/v1/public/config", server.takeRequest().path)
    }

    @Test
    fun `登录成功解析 tokens`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"ok":true,"data":{"user":{"username":"ada","email":"a@b.c"},"tokens":{"access_token":"acc","refresh_token":"ref","expires_in":3600}}}""",
            ),
        )
        val auth = client.login("ada", "secret")
        assertEquals("ada", auth.user.username)
        assertEquals("acc", auth.tokens.accessToken)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/v1/auth/login", req.path)
        assertTrue(req.body.readUtf8().contains("\"login\":\"ada\""))
    }

    @Test
    fun `信封失败带上服务端原文`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(401).setBody(
                """{"ok":false,"error":{"code":"bad_credentials","message":"用户名或密码错误"}}""",
            ),
        )
        val e = assertFailsWith<OfficialWebDavException> { client.login("ada", "nope") }
        assertEquals(401, e.httpStatus)
        assertEquals("bad_credentials", e.errorCode)
        assertEquals("用户名或密码错误", e.message)
    }

    @Test
    fun `发应用码带 replace 与 Bearer`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"ok":true,"data":{"id":3,"hint":"ab12","secret":"wd_xxx"}}""",
            ),
        )
        val issued = client.createAppPassword("tok", OfficialWebDav.APP_PASSWORD_NAME, replace = true)
        assertEquals("wd_xxx", issued.secret)
        val req = server.takeRequest()
        assertEquals("Bearer tok", req.getHeader("Authorization"))
        val body = req.body.readUtf8()
        assertTrue(body.contains("\"replace\":true"))
        assertTrue(body.contains(OfficialWebDav.APP_PASSWORD_NAME))
    }
}
