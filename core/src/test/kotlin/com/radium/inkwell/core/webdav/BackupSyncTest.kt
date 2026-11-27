package com.radium.inkwell.core.webdav

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.OkHttpClient
import okio.Buffer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackupSyncTest {
    private val server = MockWebServer().also { it.start() }
    private val path = "inkwell/backup.json.gz"
    private fun client() = WebDavClient(server.url("/dav").toString(), "user", "pass")
    private fun payload(id: String) = BackupPayload(
        deviceId = id, exportedAt = 1,
        books = listOf(BackupBook(id = id, type = 1, title = id, updatedAt = 1)),
    )
    private fun response(payload: BackupPayload, etag: String? = "\"v1\"") =
        MockResponse().setBody(Buffer().write(BackupCodec.encode(payload))).apply {
            etag?.let { setHeader("ETag", it) }
        }

    @AfterTest fun close() = server.shutdown()

    @Test fun `two devices reading same version preserve both updates after conditional conflict`() = runBlocking {
        val firstReads = CountDownLatch(2)
        val reads = AtomicInteger()
        var remote = BackupPayload(deviceId = "remote", exportedAt = 1)
        var revision = 1
        val lock = Any()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method == "GET") {
                    val result = synchronized(lock) { response(remote, "\"v$revision\"") }
                    if (reads.incrementAndGet() <= 2) {
                        firstReads.countDown()
                        check(firstReads.await(5, TimeUnit.SECONDS))
                    }
                    return result
                }
                check(request.method == "PUT")
                return synchronized(lock) {
                    if (request.getHeader("If-Match") != "\"v$revision\"") {
                        MockResponse().setResponseCode(412)
                    } else {
                        remote = BackupCodec.decode(request.body.readByteArray())
                        revision++
                        MockResponse().setResponseCode(204).setHeader("ETag", "\"v$revision\"")
                    }
                }
            }
        }
        suspend fun sync(id: String) {
            var local = payload(id)
            BackupSync(client()).run(path, { local }) { merged ->
                local = local.copy(books = merged.books)
            }
        }
        val a = async(Dispatchers.IO) { sync("a") }
        val b = async(Dispatchers.IO) { sync("b") }
        a.await()
        b.await()
        assertEquals(setOf("a", "b"), remote.books.map { it.id }.toSet())
        assertEquals(3, revision)
        assertEquals(3, reads.get())
    }

    @Test fun `first creation uses create-only condition and retries a racing creation`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(412))
        server.enqueue(response(payload("b"), "\"b\""))
        server.enqueue(MockResponse().setResponseCode(204).setHeader("ETag", "\"merged\""))
        val result = BackupSync(client()).run(path, { payload("a") }, {})
        assertEquals("etag:\"merged\"", result.remoteVersion)
        assertEquals("GET", server.takeRequest().method)
        val create = server.takeRequest()
        assertEquals("*", create.getHeader("If-None-Match"))
        assertNull(create.getHeader("If-Match"))
        server.takeRequest()
        val retry = server.takeRequest()
        assertEquals("\"b\"", retry.getHeader("If-Match"))
        assertEquals(setOf("a", "b"), BackupCodec.decode(retry.body.readByteArray()).books.map { it.id }.toSet())
    }

    @Test fun `version mark comes from PUT response without a later HEAD`() = runBlocking {
        server.enqueue(response(payload("b")))
        server.enqueue(MockResponse().setResponseCode(204).setHeader("ETag", "\"mine\""))
        val result = BackupSync(client()).run(path, { payload("a") }, {})
        assertEquals("etag:\"mine\"", result.remoteVersion)
        assertEquals(2, server.requestCount)
    }

    @Test fun `lost put response can be confirmed by reading the committed backup`() = runBlocking {
        val newBackup = payload("a")
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(response(newBackup, "\"confirmed\""))
        val http = OkHttpClient.Builder().retryOnConnectionFailure(false).build()
        val client = WebDavClient(server.url("/dav").toString(), "user", "pass", http)
        assertEquals("etag:\"confirmed\"", BackupSync(client).run(path, { newBackup }, {}).remoteVersion)
        assertEquals(listOf("GET", "PUT", "GET"), List(3) { server.takeRequest().method })
    }

    @Test fun `PUT without ETag does not claim a remote version`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(201))
        assertNull(BackupSync(client()).run(path, { payload("a") }, {}).remoteVersion)
        assertEquals(2, server.requestCount)
    }

    @Test fun `missing or weak ETag permits pull but never blind overwrite`() = runBlocking {
        for (etag in listOf(null, "W/\"v1\"")) {
            server.enqueue(response(payload("b"), etag))
            var applied = false
            val e = assertFailsWith<IOException> {
                BackupSync(client()).run(path, { payload("a") }, { applied = true })
            }
            assertTrue(e.message!!.contains("未覆盖上传"))
            assertTrue(applied)
            assertEquals("GET", server.takeRequest().method)
        }
        assertEquals(2, server.requestCount)
    }

    @Test fun `unchanged backup requires no PUT and uses the GET version`() = runBlocking {
        val same = payload("a")
        server.enqueue(response(same, "\"same\""))
        assertEquals("etag:\"same\"", BackupSync(client()).run(path, { same }, {}).remoteVersion)
        assertEquals(1, server.requestCount)
    }

    @Test fun `corrupt and future backups remain untouched`() = runBlocking {
        server.enqueue(MockResponse().setBody("truncated gzip"))
        server.enqueue(response(payload("future").copy(version = 99)))
        repeat(2) {
            assertFailsWith<IOException> {
                BackupSync(client()).run(path, { error("must not read local") }, { error("must not apply") })
            }
            assertEquals("GET", server.takeRequest().method)
        }
        assertEquals(2, server.requestCount)
    }

    @Test fun `repeated conflicts stop after three attempts`() = runBlocking {
        repeat(3) {
            server.enqueue(response(payload("b")))
            server.enqueue(MockResponse().setResponseCode(412))
        }
        assertFailsWith<IOException> { BackupSync(client()).run(path, { payload("a") }, {}) }
        assertEquals(6, server.requestCount)
    }
    @Test fun `conflict retry keeps remote deletion and progress while refreshing local snapshot`() = runBlocking {
        val original = BackupBook(id = "shared", type = 3, title = "共享书", updatedAt = 100, readAt = 100)
        val first = payload("remote").copy(books = listOf(original))
        val changed = first.copy(books = listOf(original.copy(
            deleted = true, updatedAt = 300, readAt = 200, readChapterIndex = 42, readCharOffset = 128,
        )))
        server.enqueue(response(first))
        server.enqueue(MockResponse().setResponseCode(412))
        server.enqueue(response(changed, "\"new\""))
        server.enqueue(MockResponse().setResponseCode(204))
        var local = payload("local").copy(books = listOf(original, payload("local").books.single()))
        var snapshots = 0
        BackupSync(client()).run(path, { snapshots++; local }) { merged ->
            local = local.copy(books = merged.books)
            if (snapshots == 1) local = local.copy(books = local.books + payload("just-added").books.single())
        }
        repeat(3) { server.takeRequest() }
        val uploaded = BackupCodec.decode(server.takeRequest().body.readByteArray())
        assertEquals(2, snapshots)
        assertEquals(setOf("shared", "local", "just-added"), uploaded.books.map { it.id }.toSet())
        val shared = uploaded.books.single { it.id == "shared" }
        assertTrue(shared.deleted)
        assertEquals(42, shared.readChapterIndex)
        assertEquals(128, shared.readCharOffset)
    }

    @Test fun `persistent conflicts stop after three attempts`() = runBlocking {
        repeat(3) {
            server.enqueue(response(payload("b")))
            server.enqueue(MockResponse().setResponseCode(412))
        }
        val e = assertFailsWith<IOException> { BackupSync(client()).run(path, { payload("a") }, {}) }
        assertTrue(e.message!!.contains("稍后重试"))
        assertEquals(6, server.requestCount)
    }

    @Test fun `authentication error is not retried as a conflict`() = runBlocking {
        server.enqueue(response(payload("b")))
        server.enqueue(MockResponse().setResponseCode(401))
        val e = assertFailsWith<WebDavClient.WebDavException> {
            BackupSync(client()).run(path, { payload("a") }, {})
        }
        assertEquals(401, e.code)
        assertEquals(2, server.requestCount)
    }

}
