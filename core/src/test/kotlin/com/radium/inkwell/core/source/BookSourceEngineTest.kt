package com.radium.inkwell.core.source

import com.radium.inkwell.core.model.ContentElement
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 手写小说站 fixture：搜索页 / 详情页 / 目录两页 / 正文两页，跑通全链路 */
class BookSourceEngineTest {

    private lateinit var server: MockWebServer
    private lateinit var base: String

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = FixtureDispatcher()
        server.start()
        base = server.url("/").toString().removeSuffix("/")
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun engine(trace: RuleTraceCollector? = null, globalPurify: List<PurifyRule> = emptyList()) =
        BookSourceEngine(
            SourceHttpClient(OkHttpClient(), retryBaseDelayMs = 1),
            { globalPurify },
            trace,
            scriptRuntime = com.radium.inkwell.core.source.js.RhinoScriptRuntime(),
        )

    private fun source(): BookSourceRule = BookSourceRule.fromJson(sourceJson(base))

    // ---- 全链路 ----

    @Test
    fun `full chain search detail toc content`() = runBlocking {
        val eng = engine()
        val src = source()

        // 搜索
        val result = eng.search(src, "modao", 1)
        assertEquals("/search?q=modao&page=1", server.takeRequest().path)
        assertEquals(2, result.items.size)
        assertTrue(result.hasMore)
        val first = result.items[0]
        assertEquals("魔道修真", first.title)
        assertEquals("$base/book/1", first.bookUrl)
        assertEquals("青衫", first.author)
        assertEquals("$base/img/1.jpg", first.coverUrl)
        assertEquals("一个平凡少年的故事。", first.intro)
        assertEquals("第五章 大结局", first.latestChapter)
        assertEquals(base, first.sourceId)
        assertEquals("仙路漫漫", result.items[1].title)
        assertNull(result.items[1].intro)

        // 详情
        val detail = eng.getDetail(src, first.bookUrl)
        assertEquals("魔道修真", detail.title)
        assertEquals("青衫", detail.author)
        assertEquals("$base/img/cover1.jpg", detail.coverUrl)
        assertEquals("一个平凡少年踏上修真之路。", detail.intro)
        assertEquals("$base/book/1/toc", detail.tocUrl)

        // 目录（两页分页）
        val toc = eng.getToc(src, detail.tocUrl)
        assertEquals(5, toc.size)
        assertEquals(listOf(0, 1, 2, 3, 4), toc.map { it.index })
        assertEquals("第一章 山村少年", toc[0].title)
        assertEquals("$base/chap/1", toc[0].url)
        assertEquals("第四章 秘境", toc[3].title)
        assertEquals("第五章 大结局", toc[4].title)

        // 正文（两页分页 + 净化去广告）
        val content = eng.getContent(src, toc[0].url)
        val paras = content.elements.filterIsInstance<ContentElement.Paragraph>()
        assertEquals(
            listOf(
                "山村的清晨格外宁静。",
                "少年推开柴门，望向远山。",
                "山那边传来一声钟响。",
                "他背起行囊，踏上了修真之路。",
            ),
            paras.map { it.text },
        )
        // 广告行被净化规则删除
        assertTrue(paras.none { it.text.contains("本站广告") })
        // 图片 resourceId 为绝对 URL
        val img = content.elements.filterIsInstance<ContentElement.Image>().single()
        assertEquals("$base/img/scene.jpg", img.resourceId)
    }

    @Test
    fun `toc reverse flips order and reindexes`() = runBlocking {
        val src = source()
        // 原生 Legado：chapterList 前导 `-` 表示整体倒序
        val reversed = src.copy(ruleToc = src.ruleToc!!.copy(chapterList = "-" + src.ruleToc!!.chapterList))
        val toc = engine().getToc(reversed, "$base/book/1/toc")
        assertEquals("第五章 大结局", toc[0].title)
        assertEquals("第一章 山村少年", toc[4].title)
        assertEquals(listOf(0, 1, 2, 3, 4), toc.map { it.index })
    }

    @Test
    fun `explore lists category page`() = runBlocking {
        val page = engine().explore(source(), 0, 1)
        assertEquals("/list/1.html", server.takeRequest().path)
        assertEquals(2, page.items.size)
        assertEquals("发现书一", page.items[0].title)
        assertEquals("$base/book/9", page.items[0].bookUrl)
        // exploreUrl 是 /list/{{page}}.html 且这一页有结果 → 还能往下翻。
        // 从前这里恒为 false，发现页的「加载更多」因此永远不触发。
        assertTrue(page.hasMore)
    }

    @Test
    fun `explore index out of bounds throws`() = runBlocking {
        assertFailsWith<SourceException> { engine().explore(source(), 5, 1) }
        Unit
    }

    // ---- 防环与异常 ----

    @Test
    fun `content nextPage cycle A to B to A terminates`() = runBlocking {
        val content = engine().getContent(source(), "$base/loop/a")
        val paras = content.elements.filterIsInstance<ContentElement.Paragraph>()
        // 甲、乙两页各出现一次，不死循环
        assertEquals(listOf("甲页内容。", "乙页内容。"), paras.map { it.text })
        assertEquals(2, server.requestCount)
    }

    /**
     * 分页链接与「下一章」共用一个规则时（不少站点如此），跟到本章最后一页会滑进下一章。
     * 传入目录里的其它章节地址即可止步。不传则一路吃下去，正是线上把十几章连成一章的成因。
     */
    @Test
    fun `content nextPage stops at another chapter`() = runBlocking {
        val eng = engine()
        val bleed = eng.getContent(source(), "$base/bleed/1")
        assertEquals(
            listOf("第一章上半。", "第一章下半。", "第二章内容。"),
            bleed.elements.filterIsInstance<ContentElement.Paragraph>().map { it.text },
        )

        val stopped = eng.getContent(source(), "$base/bleed/1", setOf("$base/bleed/2"))
        assertEquals(
            listOf("第一章上半。", "第一章下半。"),
            stopped.elements.filterIsInstance<ContentElement.Paragraph>().map { it.text },
        )
    }

    /** 页面上下两处导航各有一个「下一页」：URL 字段必须取首个匹配，拼起来会得到必然 404 的废地址 */
    @Test
    fun `url field takes first match instead of joining`() = runBlocking {
        val content = engine().getContent(source(), "$base/dup/1")
        assertEquals(
            listOf("重复导航页。", "第二页内容。"),
            content.elements.filterIsInstance<ContentElement.Paragraph>().map { it.text },
        )
    }

    /**
     * Legado 的搜索/发现没有独立的 nextPage 规则，全靠地址里的 {{page}} 重发请求翻页。
     * 从前 hasMore 只认 nextPage 规则，于是这类书源永远 hasMore=false ——
     * 发现页的「加载更多」成了死代码，搜索干脆连翻页都没有。
     */
    @Test
    fun `没有 nextPage 规则时靠地址里的 page 变量判定还能翻页`() = runBlocking {
        // 原生 Legado 的搜索没有 nextPage 规则，翻页全靠 searchUrl 里的 {{page}}
        val page = engine().search(source(), "修真")
        assertTrue(page.items.isNotEmpty())
        assertTrue(page.hasMore, "searchUrl 里有 {{page}} 且这一页有结果 → 还能往下翻")

        // 地址里没有 page 变量：请求「第 2 页」拿回来的还是第 1 页，不该声称还有更多
        val fixed = BookSourceRule.fromJson(sourceJson(base).replace("&page={{page}}", ""))
        assertFalse(engine().search(fixed, "修真").hasMore)
    }

    /**
     * 书源不配书名规则是常态：JSON API 源的「详情页」往往只是目录接口，压根没有书名，
     * 书名搜索时就给过了。从前一律要求详情页解析出书名，把这一整类书源判了死刑
     * （追书神器等 22 个源都栽在这）。
     */
    @Test
    fun `没有书名规则的详情页不算失败，书名留空交给调用方回落`() = runBlocking {
        val noTitleRule = BookSourceRule.fromJson(
            sourceJson(base).replace(""""name": "h1@text",""", "")
        )
        val detail = engine().getDetail(noTitleRule, "$base/book/1")
        assertEquals("", detail.title, "书名留空，由调用方回落到搜索结果")
        // 其余字段照常解析
        assertEquals("$base/book/1/toc", detail.tocUrl)
        assertEquals("一个平凡少年踏上修真之路。", detail.intro)
    }

    /**
     * 详情页解析不出来**不该判死书源**。
     *
     * 书名/作者在搜索结果里早就拿到了，详情页的规则只是「有就覆盖」—— Legado 从不因此判错。
     * 从前我们拿「书名没匹配到」当作「这一页没解析出来」的信号并直接抛错，于是站点改版、
     * 或书名规则只对 PC 版页面有效这类小事，都会让整个书源被判死；而同样的书源在阅读 APP
     * 里好好的。真正决定这本书能不能读的是目录，让目录那步去报错才有信息量。
     */
    @Test
    fun `detail page that parses nothing is not fatal`() = runBlocking {
        val detail = engine().getDetail(source(), "$base/book/empty")
        assertEquals("", detail.title)      // 调用方回落到搜索结果的书名
        assertEquals("$base/book/empty", detail.tocUrl) // 缺省：详情页即目录页
        // 该失败的是目录那步，错误信息也更有指向性
        assertFailsWith<SourceException> { engine().getToc(source(), detail.tocUrl) }
        Unit
    }

    @Test
    fun `missing rule section throws`() = runBlocking {
        val bare = source().copy(searchUrl = null, ruleSearch = null, ruleToc = null, ruleContent = null)
        assertFailsWith<SourceException> { engine().search(bare, "x") }
        assertFailsWith<SourceException> { engine().getToc(bare, "$base/book/1/toc") }
        assertFailsWith<SourceException> { engine().getContent(bare, "$base/chap/1") }
        Unit
    }

    /**
     * JSON API 型书源：详情页返回 JSON，目录地址靠模板拼出来
     * （{{$.x}} 取自当前页 JSON，{{baseUrl}} 是当前页地址而非站点根 —— 与 Legado 一致）。
     */
    @Test
    fun `模板型 tocUrl 从详情页 JSON 取值`() = runBlocking {
        val src = BookSourceRule.fromJson(
            """{"bookSourceUrl":"$base","bookSourceName":"API站",
                "ruleBookInfo":{"name":"${'$'}.name","tocUrl":"/api/book/{{${'$'}.id}}/toc"},
                "ruleToc":{"chapterList":"${'$'}.chapters","chapterName":"${'$'}.t","chapterUrl":"${'$'}.u"}}"""
        )
        val detail = engine().getDetail(src, "$base/api/detail")
        assertEquals("$base/api/book/777/toc", detail.tocUrl)

        val toc = engine().getToc(src, detail.tocUrl)
        assertEquals(listOf("第一章 起", "第二章 承"), toc.map { it.title })
    }

    // ---- 净化 ----

    @Test
    fun `global purify applies after source purify`() = runBlocking {
        val eng = engine(globalPurify = listOf(PurifyRule(pattern = "钟响", replacement = "钟声")))
        val content = eng.getContent(source(), "$base/chap/1")
        val texts = content.elements.filterIsInstance<ContentElement.Paragraph>().map { it.text }
        assertTrue(texts.contains("山那边传来一声钟声。"))
        assertTrue(texts.none { it.contains("本站广告") })
    }

    /**
     * 用户写的净化规则是会写错的。一条坏正则不该让整章正文加载失败 ——
     * 那在阅读页表现为「正文规则未匹配到内容」，用户根本猜不到是自己的净化规则干的。
     */
    @Test
    fun `bad user regex is skipped, not fatal`() = runBlocking {
        val eng = engine(
            globalPurify = listOf(
                PurifyRule(pattern = "([unclosed", replacement = ""),
                PurifyRule(pattern = "钟响", replacement = "钟声"),
            ),
        )
        val content = eng.getContent(source(), "$base/chap/1")
        val texts = content.elements.filterIsInstance<ContentElement.Paragraph>().map { it.text }
        // 坏的那条被跳过，好的那条照常生效
        assertTrue(texts.contains("山那边传来一声钟声。"))
    }

    /** 净化规则是每次取正文时现读的：引擎是单例，用户改完规则不该要重启 App */
    @Test
    fun `purify rules are read per call, not snapshotted at construction`() = runBlocking {
        var rules = emptyList<PurifyRule>()
        val eng = BookSourceEngine(
            SourceHttpClient(OkHttpClient(), retryBaseDelayMs = 1),
            { rules },
        )
        val before = eng.getContent(source(), "$base/chap/1")
            .elements.filterIsInstance<ContentElement.Paragraph>().map { it.text }
        assertTrue(before.any { it.contains("钟响") })

        rules = listOf(PurifyRule(pattern = "钟响", replacement = "钟声"))

        val after = eng.getContent(source(), "$base/chap/1")
            .elements.filterIsInstance<ContentElement.Paragraph>().map { it.text }
        assertTrue(after.any { it.contains("钟声") }, "改完规则后应立刻生效，实际: $after")
    }

    // ---- GBK 站点 ----

    @Test
    fun `gbk site content decoded via meta sniffing`() = runBlocking {
        val gbkSrc = BookSourceRule.fromJson(
            """{"bookSourceUrl":"$base","bookSourceName":"GBK站",
                "ruleContent":{"content":"div#content@html"}}"""
        )
        val content = engine().getContent(gbkSrc, "$base/gbk/chap")
        val paras = content.elements.filterIsInstance<ContentElement.Paragraph>()
        assertEquals("这是GBK编码的中文正文。", paras.single().text)
    }

    // ---- 调试插桩 ----

    @Test
    fun `trace collector receives fetch and rule events`() = runBlocking {
        val collector = object : RuleTraceCollector {
            val fetches = mutableListOf<FetchTrace>()
            val rules = mutableListOf<RuleTrace>()
            override fun onFetch(trace: FetchTrace) { fetches += trace }
            override fun onRule(trace: RuleTrace) { rules += trace }
        }
        engine(trace = collector).search(source(), "modao", 1)
        assertEquals(1, collector.fetches.size)
        val f = collector.fetches[0]
        assertEquals(200, f.statusCode)
        assertEquals("GET", f.method)
        assertEquals("UTF-8", f.detectedCharset)
        assertTrue(f.bodyPreview.length <= 500)
        assertTrue(collector.rules.any { it.stage == "search" && it.fieldName == "list" })
        assertTrue(collector.rules.any { it.stage == "search" && it.fieldName == "title" && it.output == "魔道修真" })
    }

    // ---- Schema ----

    @Test
    fun `schema parses with unknown keys and defaults`() {
        val src = source()
        assertEquals(0, src.bookSourceType)
        assertEquals(base, src.id)
        assertTrue(src.enabled)
        assertNull(src.charset)
        assertEquals("1", src.headers["X-Test"])
        assertEquals("ul.chapters li a", src.ruleToc!!.chapterList)
        // replaceRegex 解析出一条源级净化
        assertEquals(1, parseReplaceRegex(src.ruleContent!!.replaceRegex).size)
        // 往返序列化（未知键被忽略，不参与相等）
        val roundTrip = BookSourceRule.fromJson(src.toJson())
        assertEquals(src, roundTrip)
    }

    // ---- fixtures ----

    private class FixtureDispatcher : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path ?: return MockResponse().setResponseCode(404)
            return when {
                path.startsWith("/search") -> html(SEARCH_PAGE)
                path == "/book/1" -> html(DETAIL_PAGE)
                path == "/book/empty" -> html("<html><body><p>无书名页</p></body></html>")
                path == "/book/1/toc" -> html(TOC_PAGE_1)
                path == "/book/1/toc2" -> html(TOC_PAGE_2)
                path == "/chap/1" -> html(CHAP_PAGE_1)
                path == "/chap/1_2" -> html(CHAP_PAGE_2)
                path == "/loop/a" -> html(LOOP_A)
                path == "/loop/b" -> html(LOOP_B)
                path == "/vartoc" -> html(VAR_TOC)
                path.startsWith("/vchap/") -> html("<html><body><p>x</p></body></html>")
                path == "/bleed/1" -> html(BLEED_1)
                path == "/bleed/1_2" -> html(BLEED_1_2)
                path == "/bleed/2" -> html(BLEED_2)
                path == "/api/detail" -> json(API_DETAIL)
                path == "/api/book/777/toc" -> json(API_TOC)
                path == "/dup/1" -> html(DUP_NAV)
                path == "/dup/2" -> html(DUP_LAST)
                path.startsWith("/list/") -> html(EXPLORE_PAGE)
                path == "/gbk/chap" -> MockResponse()
                    .setBody(Buffer().write(GBK_CHAP.toByteArray(charset("GBK"))))
                    .setHeader("Content-Type", "text/html")
                else -> MockResponse().setResponseCode(404)
            }
        }

        private fun html(body: String): MockResponse =
            MockResponse().setBody(body).setHeader("Content-Type", "text/html; charset=utf-8")

        private fun json(body: String): MockResponse =
            MockResponse().setBody(body).setHeader("Content-Type", "application/json; charset=utf-8")
    }


    /**
     * 目录规则用 @put 存的参数，正文规则要用 @get 取得到 —— Legado 靠这条链路传参
     * （变量挂在 chapter 实体上并落库）。我们从前 @put/@get 完全没实现，
     * 这类书源「导入成功、读出来是错的」。
     */
    @Test
    fun `目录 put 的变量传到正文 get`() = runBlocking {
        // 原生 Legado：目录 chapterUrl 用 @put 存 cid，正文用 <js>java.get</js> 取回本章的 cid
        val json = """
            {"bookSourceUrl":"BASE","bookSourceName":"传参站",
             "ruleToc":{"chapterList":"class.chap","chapterName":"tag.a@text",
               "chapterUrl":"tag.a@href@put:{\"cid\":\"tag.a@data-cid\"}"},
             "ruleContent":{"content":"<js>\"章节ID=\"+java.get(\"cid\")</js>"}}
        """.trimIndent().replace("BASE", base)
        val src = BookSourceRule.fromJson(json)

        val toc = engine().getToc(src, "$base/vartoc")
        assertEquals(2, toc.size)
        // 变量随章节被带出来（会落到 ChapterEntity.variable）
        assertTrue(toc[0].variable.contains("c101"), "实际: ${toc[0].variable}")
        assertTrue(toc[1].variable.contains("c202"), "实际: ${toc[1].variable}")

        // 正文规则的 @get:{cid} 取到的是本章的变量，不会串到别的章节
        val c1 = engine().getContent(src, toc[0].url, chapterVariable = toc[0].variable)
        assertEquals("章节ID=c101", (c1.elements.first() as ContentElement.Paragraph).text)
        val c2 = engine().getContent(src, toc[1].url, chapterVariable = toc[1].variable)
        assertEquals("章节ID=c202", (c2.elements.first() as ContentElement.Paragraph).text)
    }

    private companion object {

        // 原生 Legado 格式（字段名与开源阅读一致）；规则由 LegadoRuleAnalyzer 直接求值
        fun sourceJson(base: String) = """
        {
          "bookSourceUrl": "$base",
          "bookSourceName": "测试书站",
          "bookSourceType": 0,
          "header": "{\"X-Test\":\"1\"}",
          "searchUrl": "/search?q={{key}}&page={{page}}",
          "ruleSearch": {
            "bookList": "div.result",
            "name": "h3 a@text",
            "bookUrl": "h3 a@href",
            "author": "span.author@text##作者[：:]",
            "coverUrl": "img@src",
            "intro": "p.intro@text",
            "lastChapter": "span.latest@text"
          },
          "ruleBookInfo": {
            "name": "h1@text",
            "author": "@css:meta[name=author]@content",
            "coverUrl": "div.cover img@src",
            "intro": "div.intro@text",
            "tocUrl": "a.toc@href"
          },
          "ruleToc": {
            "chapterList": "ul.chapters li a",
            "chapterName": "text",
            "chapterUrl": "href",
            "nextTocUrl": "a.nextpage@href"
          },
          "ruleContent": {
            "content": "div#content@html",
            "nextContentUrl": "a#next@href",
            "replaceRegex": "##本站广告.*##"
          },
          "exploreUrl": "玄幻::/list/{{page}}.html",
          "ruleExplore": { "bookList": "div.result", "name": "h3 a@text", "bookUrl": "h3 a@href" },
          "unknownFutureKey": { "x": 1 }
        }
        """

        const val SEARCH_PAGE = """<html><body>
            <div class="result">
              <h3><a href="/book/1">魔道修真</a></h3>
              <span class="author">作者：青衫</span>
              <img src="/img/1.jpg">
              <p class="intro">一个平凡少年的故事。</p>
              <span class="latest">第五章 大结局</span>
            </div>
            <div class="result">
              <h3><a href="/book/2">仙路漫漫</a></h3>
              <span class="author">作者：白衣</span>
            </div>
            <a class="next" href="/search?q=modao&page=2">下一页</a>
            </body></html>"""

        const val DETAIL_PAGE = """<html><head><meta name="author" content="青衫"></head><body>
            <h1>魔道修真</h1>
            <div class="cover"><img src="/img/cover1.jpg"></div>
            <div class="intro">一个平凡少年踏上修真之路。</div>
            <a class="toc" href="/book/1/toc">开始阅读</a>
            </body></html>"""

        const val TOC_PAGE_1 = """<html><body><ul class="chapters">
            <li><a href="/chap/1">第一章 山村少年</a></li>
            <li><a href="/chap/2">第二章 拜师</a></li>
            <li><a href="/chap/3">第三章 下山</a></li>
            </ul><a class="nextpage" href="/book/1/toc2">下一页</a></body></html>"""

        const val TOC_PAGE_2 = """<html><body><ul class="chapters">
            <li><a href="/chap/4">第四章 秘境</a></li>
            <li><a href="/chap/5">第五章 大结局</a></li>
            </ul></body></html>"""

        const val CHAP_PAGE_1 = """<html><body><div id="content">
            <p>　　山村的清晨格外宁静。</p>
            <p>　　少年推开柴门，望向远山。</p>
            <p>本站广告：请收藏 www.ad.example.com</p>
            </div><a id="next" href="/chap/1_2">下一页</a></body></html>"""

        const val CHAP_PAGE_2 = """<html><body><div id="content">
            <p>　　山那边传来一声钟响。</p>
            <p>　　他背起行囊，踏上了修真之路。</p>
            <img src="/img/scene.jpg">
            </div></body></html>"""

        const val VAR_TOC = """<html><body>
            <div class="chap"><a href="/vchap/1" data-cid="c101">第一章</a></div>
            <div class="chap"><a href="/vchap/2" data-cid="c202">第二章</a></div>
            </body></html>"""

        // 章内分页的末页把「下一页」指向下一章 —— 现实中极常见，会把后续章节吃进本章
        const val BLEED_1 = """<html><body><div id="content"><p>第一章上半。</p></div>
            <a id="next" href="/bleed/1_2">下一页</a></body></html>"""

        const val BLEED_1_2 = """<html><body><div id="content"><p>第一章下半。</p></div>
            <a id="next" href="/bleed/2">下一页</a></body></html>"""

        const val BLEED_2 = """<html><body><div id="content"><p>第二章内容。</p></div></body></html>"""

        // 上下两处导航各有一个「下一页」，且指向同一地址
        const val DUP_NAV = """<html><body><a id="next" href="/dup/2">下一页</a>
            <div id="content"><p>重复导航页。</p></div>
            <a id="next" href="/dup/2">下一页</a></body></html>"""

        const val DUP_LAST = """<html><body><div id="content"><p>第二页内容。</p></div></body></html>"""

        const val API_DETAIL = """{"id":777,"name":"接口小说"}"""

        const val API_TOC =
            """{"chapters":[{"t":"第一章 起","u":"/api/c/1"},{"t":"第二章 承","u":"/api/c/2"}]}"""

        const val LOOP_A = """<html><body><div id="content"><p>甲页内容。</p></div>
            <a id="next" href="/loop/b">下一页</a></body></html>"""

        const val LOOP_B = """<html><body><div id="content"><p>乙页内容。</p></div>
            <a id="next" href="/loop/a">下一页</a></body></html>"""

        const val EXPLORE_PAGE = """<html><body>
            <div class="result"><h3><a href="/book/9">发现书一</a></h3></div>
            <div class="result"><h3><a href="/book/10">发现书二</a></h3></div>
            </body></html>"""

        const val GBK_CHAP = """<html><head><meta charset="gbk"></head><body>
            <div id="content"><p>　　这是GBK编码的中文正文。</p></div></body></html>"""
    }
}
