package com.radium.inkwell.core.webdav

import kotlinx.coroutines.test.runTest
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
