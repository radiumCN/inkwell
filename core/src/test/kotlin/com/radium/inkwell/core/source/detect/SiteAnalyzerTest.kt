package com.radium.inkwell.core.source.detect

import com.radium.inkwell.core.model.ContentElement
import com.radium.inkwell.core.source.BookSourceEngine
import com.radium.inkwell.core.source.BookSourceRule
import com.radium.inkwell.core.source.SourceHttpClient
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 识别 → 生成书源 → **交给现有引擎**跑详情 / 目录 / 正文。
 *
 * 识别器自己说「认出了 30 章」不算数，引擎按生成的规则真抓出 30 章才算：两边对规则串的理解
 * （`@css:` 怎么切、`text`/`href` 取什么、`-` 倒序、目录翻页、去重保留哪一条）任何一处不一致，
 * 用户建完源打开书就是空的。
 */
class SiteAnalyzerTest {

    private lateinit var server: MockWebServer
    private lateinit var base: String

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = Site()
        server.start()
        base = server.url("/").toString().removeSuffix("/")
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private val http get() = SourceHttpClient(OkHttpClient(), retryBaseDelayMs = 1, allowPrivateNetwork = true)

    private fun engine() = BookSourceEngine(http, { emptyList() })

    /** 两个站：PC 笔趣阁式（目录即详情页、正文分两页）、手机站（目录在另一页、目录分页） */
    private inner class Site : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path.orEmpty()
            val body = when {
                path == "/book/1234/" -> DetectFixtures.biqugeToc(chapters = 30, latest = 6)
                path == "/book/1234/100001.html" -> DetectFixtures.brContent(paragraphs = 4, nextPage = "/book/1234/100001_2.html")
                path == "/book/1234/100001_2.html" -> DetectFixtures.brContent(paragraphs = 3).replace("这是第", "后半页第")
                path.startsWith("/book/1234/") -> DetectFixtures.brContent()
                path == "/book/88/" -> DetectFixtures.mobileDetail(base)
                path.startsWith("/book/88/all_") -> DetectFixtures.mobileTocPage(path.substringAfter("all_").substringBefore('.').toInt())
                path.startsWith("/book/88/") -> DetectFixtures.pContent()
                else -> return MockResponse().setResponseCode(404)
            }
            return MockResponse().setHeader("Content-Type", "text/html; charset=utf-8").setBody(body)
        }
    }

    private suspend fun buildSource(bookUrl: String): Pair<BookPageAnalysis, BookSourceRule> {
        val analyzer = SiteAnalyzer(http)
        val book = analyzer.analyzeBook(bookUrl)
        val toc = book.tocCandidates.first()
        val chapter = analyzer.analyzeChapter(toc.chapters.first().url)
        val rule = SourceDraftBuilder.build("测试站", book, toc, chapter, chapter.contentCandidates.first(), group = "自建")
        // 走一遍 JSON：存库、导出、同步都是这份文本，别只在内存对象上测
        return book to BookSourceRule.fromJson(rule.toJson())
    }

    @Test
    fun `pc site round trips through the engine`() = runBlocking {
        val (book, rule) = buildSource("$base/book/1234/")
        assertEquals(base, rule.bookSourceUrl)
        assertNull(book.tocLink)
        assertNull(rule.searchUrl)

        val eng = engine()
        val detail = eng.getDetail(rule, book.bookUrl)
        assertEquals("示例之书", detail.title)
        assertEquals("某作者", detail.author)
        assertEquals(book.bookUrl, detail.tocUrl)

        val toc = eng.getToc(rule, detail.tocUrl)
        assertEquals(30, toc.size)
        // 最新章节块没混进开头
        assertEquals("第1章 山门之外（1）", toc.first().title)
        assertEquals(book.tocCandidates.first().chapters.map { it.url }, toc.map { it.url })

        // 正文两页拼成一章；「下一章」没被当成下一页，广告块随正文一起来（交给净化规则）
        val content = eng.getContent(rule, toc.first().url, toc.mapTo(HashSet()) { it.url })
        val paragraphs = content.elements.filterIsInstance<ContentElement.Paragraph>().map { it.text }
        assertTrue(paragraphs.first().contains("这是第1段正文"), paragraphs.first())
        assertTrue(paragraphs.any { it.contains("后半页第1段正文") })
    }

    /**
     * 用户粘的是正在读的那一章：要跳到目录页再认。从前直接在章节页上认 —— 唯一的 h1 是章节名，
     * 被当成了书名；章节地址当书的地址存下来，详情、目录全从这一章去认。
     */
    @Test
    fun `chapter page url resolves to the book page`() = runBlocking {
        val chapterUrl = "$base/book/1234/100002.html"
        val (book, rule) = buildSource(chapterUrl)
        assertEquals("$base/book/1234/", book.bookUrl)
        assertEquals(chapterUrl, book.fromChapterUrl)
        assertEquals("示例之书", book.info.name?.value)
        assertEquals(30, book.tocCandidates.first().chapters.size)

        val detail = engine().getDetail(rule, book.bookUrl)
        assertEquals("示例之书", detail.title)

        // 「用已有书源打开」走的那条：同样换成书页地址
        val peek = SiteAnalyzer(http).peekBook(chapterUrl)
        assertEquals("$base/book/1234/", peek.bookUrl)
        assertEquals("示例之书", peek.info.name?.value)
    }

    @Test
    fun `mobile site follows the toc link and paginates`() = runBlocking {
        val (book, rule) = buildSource("$base/book/88/")
        assertNotNull(book.tocLink)
        assertEquals("$base/book/88/all_1.html", book.tocUrl)
        assertEquals("@css:a:contains(下一页)@href", rule.ruleToc?.nextTocUrl)

        val eng = engine()
        val detail = eng.getDetail(rule, book.bookUrl)
        assertEquals("手机之书", detail.title)
        assertEquals("$base/book/88/all_1.html", detail.tocUrl)

        // 第一页 20 章 + 第二页 7 章；末页的「下一页」是 javascript:，翻到这里就停
        val toc = eng.getToc(rule, detail.tocUrl)
        assertEquals((1..27).map { "第${it}章 山门之外（$it）" }, toc.map { it.title })

        val content = eng.getContent(rule, toc.first().url)
        val first = content.elements.filterIsInstance<ContentElement.Paragraph>().first().text
        assertTrue(first.contains("这是第1段正文"), first)
    }
}
