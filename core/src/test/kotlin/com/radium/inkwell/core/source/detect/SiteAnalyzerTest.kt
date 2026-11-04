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

    private val GBK = java.nio.charset.Charset.forName("GBK")

    private val http get() = SourceHttpClient(OkHttpClient(), retryBaseDelayMs = 1, allowPrivateNetwork = true)

    private fun engine() = BookSourceEngine(http, { emptyList() })

    /** 假渲染器：/js/1234/ 渲染后才是完整的目录页 */
    private val renderer = object : com.radium.inkwell.core.source.PageRenderer {
        override suspend fun render(url: String, headers: Map<String, String>, userAgent: String?) =
            if (url.contains("/js/1234/")) {
                com.radium.inkwell.core.source.FetchedPage(url, 200, DetectFixtures.biqugeToc(), "utf-8")
            } else {
                null
            }
    }

    /** 两个站：PC 笔趣阁式（目录即详情页、正文分两页）、手机站（目录在另一页、目录分页） */
    private inner class Site : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path.orEmpty()
            val body = when {
                path == "/book/1234/" -> DetectFixtures.biqugeToc(chapters = 30, latest = 6)
                // 目录要等脚本画出来的站：静态页是个空壳，见 [renderer]
                path == "/js/1234/" -> "<html><head><title>加载中</title></head><body><div id=\"app\">加载中…</div></body></html>"
                path == "/forbidden/" -> return MockResponse().setResponseCode(403)
                path == "/book/1234/100001.html" -> DetectFixtures.brContent(paragraphs = 4, nextPage = "/book/1234/100001_2.html")
                path == "/book/1234/100001_2.html" -> DetectFixtures.brContent(paragraphs = 3).replace("这是第", "后半页第")
                path.startsWith("/book/1234/") -> DetectFixtures.brContent()
                path == "/book/88/" -> DetectFixtures.mobileDetail(base)
                path.startsWith("/book/88/all_") -> DetectFixtures.mobileTocPage(path.substringAfter("all_").substringBefore('.').toInt())
                path.startsWith("/book/88/") -> DetectFixtures.pContent()
                path.startsWith("/search.php") ->
                    DetectFixtures.searchList(request.requestUrl?.queryParameter("q").orEmpty())
                // 老站的 GBK POST 搜索：按 GBK 解 body、按 GBK 回页面。引擎要是按 UTF-8 发，这里解出来的是乱码词
                path.startsWith("/modules/article/search.php") && request.method == "POST" -> {
                    val form = String(request.body.readByteArray(), GBK)
                    val key = form.split('&').map { it.split('=', limit = 2) }
                        .firstOrNull { it[0] == "searchkey" }?.getOrNull(1)
                        ?.let { java.net.URLDecoder.decode(it, GBK) }.orEmpty()
                    return MockResponse()
                        .setHeader("Content-Type", "text/html; charset=gbk")
                        .setBody(okio.Buffer().write(DetectFixtures.searchTable(key).toByteArray(GBK)))
                }
                else -> return MockResponse().setResponseCode(404)
            }
            return MockResponse().setHeader("Content-Type", "text/html; charset=utf-8").setBody(body)
        }
    }

    private suspend fun buildSource(bookUrl: String, search: SearchSetup? = null): Pair<BookPageAnalysis, BookSourceRule> {
        val analyzer = SiteAnalyzer(http)
        val book = analyzer.analyzeBook(bookUrl)
        val toc = book.tocCandidates.first()
        val chapter = analyzer.analyzeChapter(toc.chapters.first().url)
        val rule = SourceDraftBuilder.build(
            "测试站", book, toc, chapter, chapter.contentCandidates.first(), group = "自建", search = search,
        )
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

    // ---- 试抓（高级页） ----

    @Test
    fun `dry run reports what each field picked up`() = runBlocking {
        val (book, rule) = buildSource("$base/book/1234/")
        val run = SiteAnalyzer(http).dryRun(rule, book.bookUrl, keyword = null)
        assertTrue(run.failures.isEmpty(), run.failures.toString())
        assertEquals("示例之书", run.samples[RuleField.BookName]?.output)
        assertEquals("某作者", run.samples[RuleField.BookAuthor]?.output)
        assertEquals(30, run.chapterCount)
        assertEquals("第1章 山门之外（1）", run.samples[RuleField.ChapterName]?.output)
        assertEquals("第1章 山门之外（1）", run.sampleChapterTitle)
        // 正文一栏是净化之后的正文开头
        assertTrue(run.samples[RuleField.Content]?.output.orEmpty().contains("这是第1段正文"))
        assertNull(run.searchCount)
    }

    @Test
    fun `dry run shows where a broken rule stops the chain`() = runBlocking {
        val (book, rule) = buildSource("$base/book/1234/")
        val broken = RuleField.Content.set(rule, "@css:#nope@html")
        val run = SiteAnalyzer(http).dryRun(broken, book.bookUrl, keyword = null)
        assertEquals(30, run.chapterCount)
        assertNotNull(run.failures[STAGE_CONTENT])
        // 替换规则生效与否看正文预览：把「正文」两个字换掉
        val purified = RuleField.ReplaceRegex.set(rule, "##正文##内容")
        val clean = SiteAnalyzer(http).dryRun(purified, book.bookUrl, keyword = null)
        assertTrue(clean.samples[RuleField.Content]?.output.orEmpty().contains("这是第1段内容"))
    }

    @Test
    fun `dry run includes search when the source has it`() = runBlocking {
        val template = assertNotNull(
            SearchTemplates.fromForm("$base/search.php", "get", null, listOf(FormField("q", "剑", "search"))),
        )
        val search = SiteAnalyzer(http).analyzeSearch(template, "剑", "$base/book/1234/").setup
        val (book, rule) = buildSource("$base/book/1234/", search = search)
        val run = SiteAnalyzer(http).dryRun(rule, book.bookUrl, keyword = "剑")
        assertEquals(5, run.searchCount)
        assertEquals("剑之书1", run.samples[RuleField.SearchName]?.output)
    }

    /**
     * 目录要渲染的站：引擎先静态抓一次（空的）、再渲染。从前每栏取第一次的记录 ——
     * 概览说「目录 30 章」，章节列表那栏却是「0 个节点」，诱人去改一条本来没毛病的规则。
     */
    @Test
    fun `dry run shows the rendered result on script drawn pages`() = runBlocking {
        val (_, rule) = buildSource("$base/book/1234/")
        val run = SiteAnalyzer(http, renderer).dryRun(rule, "$base/js/1234/", keyword = null)
        assertEquals(30, run.chapterCount)
        assertEquals("示例之书", run.samples[RuleField.BookName]?.output)
        assertEquals("30 个节点", run.samples[RuleField.ChapterList]?.output)
    }

    /** 试抓要和正式引擎一样跑脚本、套全局净化：否则写 @js: 的规则试抓说不行，存进去却能用 */
    @Test
    fun `dry run runs scripts and global purify like the app engine`() = runBlocking {
        val (book, rule) = buildSource("$base/book/1234/")
        val scripted = RuleField.BookName.set(rule, "@js:'脚本书名'")
        val analyzer = SiteAnalyzer(
            http,
            scriptRuntime = com.radium.inkwell.core.source.js.RhinoScriptRuntime(),
            globalPurify = { listOf(com.radium.inkwell.core.source.PurifyRule("正文", "内容", isRegex = false)) },
        )
        val run = analyzer.dryRun(scripted, book.bookUrl, keyword = null)
        assertEquals("脚本书名", run.samples[RuleField.BookName]?.output)
        assertTrue(run.samples[RuleField.Content]?.output.orEmpty().contains("这是第1段内容"))
    }

    /** 被拒（403、人机验证）不是网址填错了：要说清楚，别让用户去换网址 */
    @Test
    fun `blocked pages say why instead of blaming the url`() = runBlocking {
        val e = kotlin.test.assertFailsWith<com.radium.inkwell.core.source.SourceException> {
            SiteAnalyzer(http).analyzeBook("$base/forbidden/")
        }
        assertTrue(e.message.orEmpty().contains("拒绝"), e.message)
    }

    /** 点选围绕刚认过的那份页面重认，不再下载一遍（必要时还要排队等渲染） */
    @Test
    fun `picking does not download the page again`() = runBlocking {
        val analyzer = SiteAnalyzer(http)
        val book = analyzer.analyzeBook("$base/book/1234/")
        val chapter = analyzer.analyzeChapter(book.tocCandidates.first().sampleChapter.url)
        val before = server.requestCount
        assertNotNull(analyzer.pickToc(book.tocUrl, book.tocCandidates.first().chapters[3].url))
        assertNotNull(analyzer.pickContent(chapter.url, DetectFixtures.paragraph(2)))
        assertEquals(before, server.requestCount)
    }

    // ---- 搜索 ----

    @Test
    fun `get form search round trips through the engine`() = runBlocking {
        val template = assertNotNull(
            SearchTemplates.fromForm("$base/search.php", "get", "UTF-8", listOf(FormField("q", "剑", "search"))),
        )
        val analysis = SiteAnalyzer(http).analyzeSearch(template, "剑", "$base/book/1234/")
        // 侧栏六本「热门推荐」没混进来
        assertEquals(5, analysis.results.size)
        val first = analysis.results.first()
        assertEquals("剑之书1", first.title)
        assertEquals("写手1", first.author)
        assertEquals("$base/cover/2001.jpg", first.coverUrl)
        assertEquals("$base/book/2001/", first.bookUrl)

        // 存进书源（走一遍 JSON）后换个词照样能搜
        val (_, rule) = buildSource("$base/book/1234/", search = analysis.setup)
        val again = engine().search(rule, "刀")
        assertEquals("刀之书1", again.items.first().title)
    }

    @Test
    fun `gbk post search round trips through the engine`() = runBlocking {
        val template = assertNotNull(
            SearchTemplates.fromForm(
                "$base/modules/article/search.php", "post", "GBK",
                listOf(FormField("searchtype", "articlename", "hidden"), FormField("searchkey", "剑", "text")),
            ),
        )
        val analysis = SiteAnalyzer(http).analyzeSearch(template, "剑", "$base/book/1234/")
        assertEquals(4, analysis.results.size)
        val first = analysis.results.first()
        assertEquals("剑表1", first.title)
        assertEquals("表作者1", first.author)
        assertEquals("第1章 开篇1", first.latestChapter)
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
