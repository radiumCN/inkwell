package com.radium.inkwell.data.repo

import com.radium.inkwell.core.model.ChapterContent
import com.radium.inkwell.core.model.ContentElement
import com.radium.inkwell.core.source.BookSourceEngine
import com.radium.inkwell.core.source.BookSourceRule
import com.radium.inkwell.core.source.SourceHttpClient
import com.radium.inkwell.data.db.dao.ChapterDao
import com.radium.inkwell.data.db.entity.ChapterEntity
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NetReaderRefreshTest {
    private val root = Files.createTempDirectory("refresh-test").toFile()
    private val cache = ChapterContentCache(root)
    private val server = MockWebServer().also { it.start() }
    private val url = server.url("/chapter").toString()
    private val old = ChapterContent(listOf(ContentElement.Paragraph("原来的正文")))
    private val marked = mutableListOf<String>()
    private val healthScope = CoroutineScope(Dispatchers.Unconfined)
    private val dao = Proxy.newProxyInstance(
        ChapterDao::class.java.classLoader, arrayOf(ChapterDao::class.java),
    ) { _, method, args ->
        check(method.name == "markCachedByUrl") { "Unexpected DAO call: ${method.name}" }
        marked += args!![1] as String
        Unit
    } as ChapterDao
    private val source by lazy {
        NetReaderBookSource(
            "book", listOf(ChapterEntity("book", 0, "第一章", url)),
            BookSourceRule.fromJson("""{
                "bookSourceUrl":"${server.url("/")}","bookSourceName":"测试",
                "ruleContent":{"content":"id.content@html"}
            }"""),
            BookSourceEngine(SourceHttpClient(OkHttpClient(), retryBaseDelayMs = 1, allowPrivateNetwork = true)),
            cache, dao, SourceReadHealth(InMemorySourceReadHealthDao(), healthScope),
        )
    }

    @AfterTest
    fun close() {
        healthScope.cancel()
        server.shutdown()
        root.deleteRecursively()
    }

    @Test
    fun `手动获取绕过原文缓存且提交之前原文仍可阅读`() = runBlocking {
        cache.write("book", url, old)
        server.enqueue(MockResponse().setBody("<div id='content'><p>更新后的正文</p></div>"))
        assertEquals(old, source.loadChapter(0))
        assertEquals(0, server.requestCount)

        val fresh = source.fetchFreshChapter(0)
        assertEquals(1, server.requestCount)
        assertEquals(listOf(ContentElement.Paragraph("更新后的正文")), fresh.elements)
        assertEquals(old, cache.read("book", url))
        assertTrue(marked.isEmpty())

        source.cacheFreshChapter(url, fresh, source.cacheEpoch())
        assertEquals(fresh, source.loadChapter(0))
        assertEquals(listOf(url), marked)
    }

    @Test
    fun `获取失败不回退成伪成功且原缓存不变`() = runBlocking {
        cache.write("book", url, old)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(404)
        }
        assertFailsWith<Exception> { source.fetchFreshChapter(0) }
        assertEquals(old, cache.read("book", url))
        assertEquals(old, source.loadChapter(0))
        assertTrue(marked.isEmpty())
    }

    @Test
    fun `旧文字首屏和预取不联网，后台补全在响应到达后才更新缓存`() = runBlocking {
        cache.write("book", url, old)
        root.resolve("book").listFiles()!!.single().writeText("\u0001V4\n原来的正文")
        val imageUrl = server.url("/illustration.png").toString()
        val release = holdResponse(
            MockResponse().setBody("<div id='content'><p>原来的正文</p><img src='$imageUrl'></div>"),
        )
        val timing = NetReaderBookSource.LoadTiming()
        assertEquals(old, source.loadChapter(0, timing))
        assertEquals("text_only_hit", timing.cacheState)
        source.prefetchChapter(0)
        assertEquals(0, server.requestCount)

        val upgrade = async(Dispatchers.IO) { source.upgradeTextOnlyChapter(0) {} }
        try {
            assertTrue(server.takeRequest(5, TimeUnit.SECONDS) != null)
            assertEquals(old, source.loadChapter(0))
            assertEquals("deferred", source.upgradeTextOnlyChapter(0) {})
            assertEquals(1, server.requestCount)
        } finally {
            release.countDown()
        }
        assertEquals("ready", upgrade.await())
        assertTrue(source.loadChapter(0).elements.any { it is ContentElement.Image && it.resourceId == imageUrl })
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `换源期间不能提交旧源的新正文`() = runBlocking {
        cache.write("book", url, old)
        source.cacheWritesHeld = true
        assertFailsWith<IllegalStateException> { source.cacheFreshChapter(url, old, source.cacheEpoch()) }
        assertEquals(old, cache.read("book", url))
        assertTrue(marked.isEmpty())
    }
    @Test
    fun `正文请求期间清空缓存不会重新创建旧缓存或标记`() = runBlocking {
        val release = holdResponse(MockResponse().setBody("<div id='content'><p>延迟正文</p></div>"))
        val pending = async(Dispatchers.IO) { source.loadChapter(0) }
        assertTrue(server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS) != null)
        cache.clear("book")
        release.countDown()
        pending.await()
        assertEquals(null, cache.read("book", url))
        assertTrue(marked.isEmpty())
    }

    @Test
    fun `图片请求期间清空缓存不重新写回图片`() = runBlocking {
        val image = server.url("/image").toString()
        val release = holdResponse(MockResponse().setBody(okio.Buffer().write(byteArrayOf(1, 2, 3))))
        val pending = async(Dispatchers.IO) { source.loadImage(image) }
        assertTrue(server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS) != null)
        cache.clearAll()
        release.countDown()
        assertEquals(listOf<Byte>(1, 2, 3), pending.await()?.toList())
        assertEquals(null, cache.readImage("book", image))
    }

    @Test
    fun `手动刷新开始后清理缓存则拒绝提交且不打标记`() = runBlocking {
        val epoch = source.cacheEpoch()
        cache.clearAll()
        assertFailsWith<IllegalStateException> { source.cacheFreshChapter(url, old, epoch) }
        assertEquals(null, cache.read("book", url))
        assertTrue(marked.isEmpty())
    }


    @Test
    fun `预取期间清理缓存不写回旧正文`() = runBlocking {
        val release = holdResponse(MockResponse().setBody("<div id='content'><p>预取正文</p></div>"))
        val pending = async(Dispatchers.IO) { source.prefetchChapter(0) }
        assertTrue(server.takeRequest(5, TimeUnit.SECONDS) != null)
        cache.clearAll()
        release.countDown()
        pending.await()
        assertEquals(null, cache.read("book", url))
        assertTrue(marked.isEmpty())
    }

    @Test
    fun `清理后的新请求不合并进旧请求且旧响应不能覆盖新缓存`() = runBlocking {
        val release = CountDownLatch(1)
        val requests = java.util.concurrent.atomic.AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val first = requests.incrementAndGet() == 1
                if (first) check(release.await(5, TimeUnit.SECONDS))
                return MockResponse().setBody("<div id='content'><p>${if (first) "旧响应" else "新响应"}</p></div>")
            }
        }
        val first = async(Dispatchers.IO) { source.loadChapter(0) }
        assertTrue(server.takeRequest(5, TimeUnit.SECONDS) != null)
        cache.clear("book")
        val second = source.loadChapter(0)
        release.countDown()
        first.await()
        assertEquals(listOf(ContentElement.Paragraph("新响应")), second.elements)
        assertEquals(second, cache.read("book", url))
        assertEquals(2, requests.get())
        assertEquals(listOf(url), marked)
    }

    private fun holdResponse(response: MockResponse): CountDownLatch {
        val release = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                check(release.await(5, TimeUnit.SECONDS)) { "测试没有放行响应" }
                return response
            }
        }
        return release
    }

}
