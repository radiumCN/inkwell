package com.radium.inkwell.core.webdav

import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.io.path.createTempFile
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebDavClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: WebDavClient

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = WebDavClient(server.url("/dav").toString(), "user", "pass")
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `put sends basic auth and body`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201))
        client.put("inkwell/backup.json.gz", byteArrayOf(1, 2, 3))
        val recorded = server.takeRequest()
        assertEquals("PUT", recorded.method)
        assertEquals("/dav/inkwell/backup.json.gz", recorded.path)
        assertTrue(recorded.getHeader("Authorization")!!.startsWith("Basic "))
        assertContentEquals(byteArrayOf(1, 2, 3), recorded.body.readByteArray())
    }

    @Test
    fun `book file retry is conditional and reports exhausted quota`() = runTest {
        val file = createTempFile().toFile()
        try {
            file.writeText("book content")
            server.enqueue(MockResponse().setResponseCode(412))
            client.putFileIfAbsent("inkwell/books/book.txt", file)
            val request = server.takeRequest()
            assertEquals("*", request.getHeader("If-None-Match"))
            assertEquals("book content", request.body.readUtf8())

            server.enqueue(MockResponse().setResponseCode(507))
            val error = assertFailsWith<WebDavClient.WebDavException> {
                client.putFileIfAbsent("inkwell/books/book.txt", file)
            }
            assertEquals(507, error.code)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `book download enforces declared size even for chunked responses`() = runTest {
        val file = createTempFile().toFile()
        try {
            val path = "inkwell/books/book.txt"
            server.enqueue(MockResponse().setChunkedBody("book", 2))
            client.downloadFile(path, file, 4)
            assertEquals("book", file.readText())

            server.enqueue(MockResponse().setChunkedBody("book", 2))
            assertFailsWith<IOException> { client.downloadFile(path, file, 3) }

            server.enqueue(MockResponse().setChunkedBody("boo", 2))
            assertFailsWith<IOException> { client.downloadFile(path, file, 4) }
        } finally {
            file.delete()
        }
    }

    @Test
    fun `cancelling download interrupts a stalled response body`() = runBlocking {
        val dir = Files.createTempDirectory("dav-download").toFile()
        val file = dir.resolve("partial")
        val callCancelled = AtomicBoolean()
        val http = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun canceled(call: Call) {
                callCancelled.set(true)
            }
        }).build()
        val client = WebDavClient(server.url("/dav").toString(), "user", "pass", http)
        try {
            server.enqueue(MockResponse().setBody("book").setBodyDelay(5, TimeUnit.SECONDS))
            val download = launch { client.downloadFile("inkwell/books/book.txt", file, 4) }
            withTimeout(5_000) {
                // 暂存文件创建于收到响应头之后、第一次读正文之前。
                while (!file.exists()) delay(10)
            }
            withTimeout(3_000) { download.cancelAndJoin() }
            assertTrue(download.isCancelled)
            assertTrue(callCancelled.get(), "收到响应头后取消仍应中断 OkHttp Call")
            assertTrue(file.delete(), "取消完成后暂存文件已关闭")
        } finally {
            file.delete()
            dir.delete()
        }
    }

    @Test
    fun `get returns null on 404`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        assertNull(client.get("inkwell/none.gz"))
    }

    /**
     * 坚果云对「父目录不存在」的 GET 回 409 而不是 404。首次同步时 inkwell/ 还没建，
     * 于是读远端备份直接抛 `GET 失败: 409`，同步一次都成不了 —— 用户看到的就是这个。
     */
    @Test
    fun `get returns null on 409 (parent collection missing)`() = runTest {
        server.enqueue(MockResponse().setResponseCode(409))
        assertNull(client.get("inkwell/backup.json.gz"))
    }

    @Test
    fun `get returns bytes on success`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("hello"))
        assertContentEquals("hello".toByteArray(), client.get("inkwell/backup.json.gz"))
    }

    @Test
    fun `mkcol treats 405 as already exists`() = runTest {
        server.enqueue(MockResponse().setResponseCode(405))
        client.mkcol("inkwell") // 不抛异常即通过
        assertEquals("MKCOL", server.takeRequest().method)
    }

    @Test
    fun `auth failure raises WebDavException`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        val e = assertFailsWith<WebDavClient.WebDavException> {
            client.put("inkwell/x", byteArrayOf(0))
        }
        assertEquals(401, e.code)
    }

    @Test
    fun `reads quota at the DAV root with the sync credentials`() = runTest {
        server.enqueue(MockResponse().setResponseCode(207).setBody("""
            <multistatus xmlns="DAV:">
              <response>
                <href>/dav/</href>
                <propstat>
                  <prop><quota-used-bytes>11534336</quota-used-bytes><quota-available-bytes>5357174784</quota-available-bytes></prop>
                  <status>HTTP/1.1 200 OK</status>
                </propstat>
              </response>
            </multistatus>
        """.trimIndent()))
        assertEquals(WebDavQuota(11534336L, 5368709120L), client.quota())
        val request = server.takeRequest()
        assertEquals("PROPFIND", request.method)
        assertEquals("/dav/", request.path)
        assertEquals("0", request.getHeader("Depth"))
        assertEquals(okhttp3.Credentials.basic("user", "pass", Charsets.UTF_8), request.getHeader("Authorization"))
        val body = request.body.readUtf8()
        assertTrue(body.contains("quota-used-bytes") && body.contains("quota-available-bytes"))
    }

    @Test
    fun `missing quota properties allow legacy fallback but invalid DAV credentials surface`() = runTest {
        server.enqueue(MockResponse().setResponseCode(207).setBody("""
            <D:multistatus xmlns:D="DAV:"><D:response><D:href>/dav/</D:href>
              <D:propstat><D:prop><D:quota-used-bytes/><D:quota-available-bytes/></D:prop>
              <D:status>HTTP/1.1 404 Not Found</D:status></D:propstat>
            </D:response></D:multistatus>
        """.trimIndent()))
        assertNull(client.quota())
        server.enqueue(MockResponse().setResponseCode(401))
        assertEquals(401, assertFailsWith<WebDavClient.WebDavException> { client.quota() }.code)
    }

    @Test
    fun `root collection keeps trailing slash`() = runTest {
        server.enqueue(MockResponse().setResponseCode(207).setBody("""<?xml version="1.0"?>
            <d:multistatus xmlns:d="DAV:"></d:multistatus>"""))
        client.list("")
        assertEquals("/dav/", server.takeRequest().path)
    }

    @Test
    fun `propfind parses multistatus children`() = runTest {
        val xml = """<?xml version="1.0"?>
            <d:multistatus xmlns:d="DAV:">
              <d:response><d:href>/dav/inkwell/</d:href></d:response>
              <d:response><d:href>/dav/inkwell/backup.json.gz</d:href></d:response>
              <d:response><d:href>/dav/inkwell/backup-2.json.gz</d:href></d:response>
            </d:multistatus>"""
        server.enqueue(MockResponse().setResponseCode(207).setBody(xml))
        val names = client.list("inkwell")
        assertEquals(listOf("backup.json.gz", "backup-2.json.gz"), names)
        assertEquals("1", server.takeRequest().getHeader("Depth"))
    }
}
