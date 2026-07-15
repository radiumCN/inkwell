package com.radium.inkwell.update

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ServerUpdateCheckerTest {

    private lateinit var server: MockWebServer
    private lateinit var checker: ServerUpdateChecker

    @BeforeTest
    fun setUp() {
        server = MockWebServer().also { it.start() }
        checker = ServerUpdateChecker(OkHttpClient(), server.url("/").toString().removeSuffix("/"))
    }

    @AfterTest
    fun tearDown() = server.shutdown()

    private val body = """
        {"channel":"beta","version":"v0.1.0-beta.63","name":"v0.1.0-beta.63",
         "notes":"changelog here","published_at":"x","synced_at":"y","has_update":true,
         "asset":{"filename":"inkwell-v0.1.0-beta.63.apk","size":10012181,
                  "sha256":"ccb8eb91","content_type":"application/vnd.android.package-archive",
                  "download_url":"https://book-server.skylark.run/api/v1/download/beta",
                  "source_url":"https://github.com/radiumCN/inkwell/releases/tag/x"}}
    """.trimIndent()

    @Test
    fun `has_update true returns Available with directInstall, and sends v-prefixed current`() = runBlocking {
        server.enqueue(MockResponse().setBody(body))
        val avail = assertIs<CheckResult.Available>(checker.check(UpdateChannel.BETA, "0.1.0-beta.62"))
        assertEquals("0.1.0-beta.63", avail.info.latestVersion)
        assertTrue(avail.info.isPrerelease)
        assertEquals("ccb8eb91", avail.info.directInstall?.sha256)
        assertEquals(10012181L, avail.info.directInstall?.size)
        // ⚠️ current 必须补 v 前缀，渠道映射到 /beta
        assertEquals("/api/v1/update/beta?current=v0.1.0-beta.62", server.takeRequest().path)
    }

    // 用块体（返回 Unit）：assertIs 会返回值，若做表达式体的末句会让 @Test 方法非 void，JUnit 拒收
    @Test
    fun `has_update false returns UpToDate`() {
        server.enqueue(MockResponse().setBody(body.replace("\"has_update\":true", "\"has_update\":false")))
        runBlocking {
            assertIs<CheckResult.UpToDate>(checker.check(UpdateChannel.BETA, "0.1.0-beta.63"))
        }
    }

    @Test
    fun `404 channel treated as UpToDate not error`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"no release for channel"}"""))
        assertIs<CheckResult.UpToDate>(checker.check(UpdateChannel.STABLE, "0.1.0"))
        assertEquals("/api/v1/update/stable?current=v0.1.0", server.takeRequest().path)
    }
}
