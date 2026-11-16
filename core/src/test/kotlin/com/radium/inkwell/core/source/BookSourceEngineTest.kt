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
            // MockWebServer 在 127.0.0.1 上，得放行 SSRF 防护
            SourceHttpClient(OkHttpClient(), retryBaseDelayMs = 1, allowPrivateNetwork = true),
            { _, _ -> globalPurify },
            trace,
            scriptRuntime = com.radium.inkwell.core.source.js.RhinoScriptRuntime(),
        )

    private fun source(): BookSourceRule = BookSourceRule.fromJson(sourceJson(base))

    // ---- jsLib：书源的公共脚本库 ----

    @Test
    fun `搜索地址调得到 jsLib 里定义的函数`() = runBlocking {
        val src = source().copy(
            searchUrl = "/search?q={{key}}&page={{firstPage()}}",
            jsLib = "function firstPage() { return String(this.page) }",
        )
        val result = engine().search(src, "modao", 1)
        assertEquals("/search?q=modao&page=1", server.takeRequest().path)
        assertEquals(2, result.items.size)
    }

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
        // 正文图照收、相对地址按那一页补全（阅读页显示不显示是阅读设置的事），排在它所在的那一段之后
        assertEquals(
            listOf(ContentElement.Image("$base/img/scene.jpg")),
            content.elements.filterIsInstance<ContentElement.Image>(),
        )
        assertTrue(content.elements.last() is ContentElement.Image)
    }

    @Test
    fun `toc reverse flips order and reindexes`() = runBlocking {
        val src = source()
        // 原生 Legado：chapterList 前导 `-` 表示整体倒序
        val tocRule = src.ruleToc!!
        val reversed = src.copy(ruleToc = tocRule.copy(chapterList = "-" + tocRule.chapterList))
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

    /** Legado 编辑器保存时总会写一个空的 ruleExplore；列表规则为空就该回落到搜索规则 */
    @Test
    fun `ruleExplore 为空对象时复用搜索规则`() = runBlocking {
        val json = sourceJson(base).replace(
            """"ruleExplore": { "bookList": "div.result", "name": "h3 a@text", "bookUrl": "h3 a@href" }""",
            """"ruleExplore": {}""",
        )
        assertTrue(json.contains(""""ruleExplore": {}"""))
        val page = engine().explore(BookSourceRule.fromJson(json), 0, 1)
        assertEquals(listOf("发现书一", "发现书二"), page.items.map { it.title })
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
            SourceHttpClient(OkHttpClient(), retryBaseDelayMs = 1, allowPrivateNetwork = true),
            { _, _ -> rules },
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

    // ---- 正文插图 ----

    @Test
    fun `取插图：相对地址按来源补全，带来源请求头`() = runBlocking {
        assertEquals(listOf<Byte>(1, 2, 3), engine().getImage(source(), "/img/scene.jpg").toList())
    }

    /** 防盗链的图床：先不带 Referer，被拒了再带上来源站点取一次 */
    @Test
    fun `取插图被拒时带上来源站点再取一次`() = runBlocking {
        assertEquals(listOf<Byte>(9, 8), engine().getImage(source(), "$base/img/guarded.jpg").toList())
        assertNull(server.takeRequest().getHeader("Referer"))
        assertEquals("$base/", server.takeRequest().getHeader("Referer"))
    }

    /** 阅读的写法：图片地址后面跟 `,{"headers":…}` */
    @Test
    fun `取插图认地址自带的请求头选项`() = runBlocking {
        assertEquals(listOf<Byte>(7), engine().getImage(source(), "$base/img/headers.jpg,{\"headers\":{\"X-Token\":\"t1\"}}").toList())
    }

    @Test
    fun `取不到的插图抛错`() {
        assertFailsWith<java.io.IOException> { runBlocking { engine().getImage(source(), "$base/img/none.jpg") } }
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
                path == "/api/info" -> json(API_INFO)
                path == "/api/book/777/toc" -> json(API_TOC)
                path == "/dup/1" -> html(DUP_NAV)
                path == "/dup/2" -> html(DUP_LAST)
                path.startsWith("/list/") -> html(EXPLORE_PAGE)
                path == "/s" || path.startsWith("/s?") -> html(SEARCH_PAGE)
                path.startsWith("/so/") -> html(SEARCH_PAGE)
                // 静态抓下来正文是空的（要 JS 渲染才有）
                path == "/js/chap" -> html("""<html><body><div id="content"></div></body></html>""")
                path == "/mp/toc" -> html(MP_TOC_1)
                path == "/mp/toc2" -> html(MP_TOC_2)
                path == "/mp/toc3" -> html(MP_TOC_3)
                // 故意慢回来的第二页：并发抓目录时它比第三页晚到，结果仍得按页序拼
                path == "/mp/slowtoc2" -> html(MP_TOC_2).setBodyDelay(300, java.util.concurrent.TimeUnit.MILLISECONDS)
                path == "/selftoc" -> html(SELF_TOC_PAGE)
                path == "/img/scene.jpg" -> MockResponse().setBody(Buffer().write(byteArrayOf(1, 2, 3)))
                // 防盗链图床：不带本站 Referer 一律 403
                path == "/img/guarded.jpg" ->
                    if (request.getHeader("Referer")?.startsWith("http://") == true) {
                        MockResponse().setBody(Buffer().write(byteArrayOf(9, 8)))
                    } else {
                        MockResponse().setResponseCode(403)
                    }
                path == "/img/headers.jpg" ->
                    if (request.getHeader("X-Token") == "t1") MockResponse().setBody(Buffer().write(byteArrayOf(7)))
                    else MockResponse().setResponseCode(401)
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

    @Test
    fun `nextTocUrl 页码列表会抓全部页而不是停在第一个地址`() = runBlocking {
        val json = """
            {"bookSourceUrl":"$base","bookSourceName":"分页目录",
             "ruleToc":{"chapterList":"ul.chapters li a","chapterName":"text","chapterUrl":"href",
               "nextTocUrl":"class.pages@tag.a@href"}}
        """.trimIndent()
        val toc = engine().getToc(BookSourceRule.fromJson(json), "$base/mp/toc")
        assertEquals(listOf("一", "二", "三"), toc.map { it.title })
    }

    @Test
    fun `nextTocUrl 的 JS 数组会拆成多个地址`() = runBlocking {
        val json = """
            {"bookSourceUrl":"$base","bookSourceName":"JS分页",
             "ruleToc":{"chapterList":"ul.chapters li a","chapterName":"text","chapterUrl":"href",
               "nextTocUrl":"<js>['$base/mp/toc2','$base/mp/toc3']</js>"}}
        """.trimIndent()
        val toc = engine().getToc(BookSourceRule.fromJson(json), "$base/mp/toc")
        assertEquals(listOf("一", "二", "三"), toc.map { it.title })
    }

    /** 目录分页并发抓：晚到的那页不能被排到后面去 */
    @Test
    fun `并发抓目录分页时仍按页序拼接`() = runBlocking {
        val json = """
            {"bookSourceUrl":"$base","bookSourceName":"慢分页",
             "ruleToc":{"chapterList":"ul.chapters li a","chapterName":"text","chapterUrl":"href",
               "nextTocUrl":"<js>['$base/mp/slowtoc2','$base/mp/toc3']</js>"}}
        """.trimIndent()
        val toc = engine().getToc(BookSourceRule.fromJson(json), "$base/mp/toc")
        assertEquals(listOf("一", "二", "三"), toc.map { it.title })
    }

    /**
     * 没写 tocUrl 的书源，目录地址就是详情页：先 getDetail 再 getToc 不该把同一页抓两遍
     * （多一个往返、多排一次限速，预览和加书架的超时预算就那么点）。
     */
    @Test
    fun `详情页即目录页时只抓一次`() = runBlocking {
        val json = """
            {"bookSourceUrl":"$base","bookSourceName":"同页站",
             "ruleBookInfo":{"name":"h1@text"},
             "ruleToc":{"chapterList":"ul.chapters li a","chapterName":"text","chapterUrl":"href"}}
        """.trimIndent()
        val src = BookSourceRule.fromJson(json)
        val e = engine()
        val detail = e.getDetail(src, "$base/selftoc")
        assertEquals("同页书", detail.title)
        val toc = e.getToc(src, detail.tocUrl)
        assertEquals(listOf("第一章", "第二章"), toc.map { it.title })
        assertEquals(1, server.requestCount)

        // 缓存只接紧挨着的那一步：之后再取目录（下拉追更、刷新目录）必须真的去抓，别拿刚才那份旧页
        e.getToc(src, detail.tocUrl)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `详情页带上书源的 canReName —— 写了什么都算开，没写就是关`() = runBlocking {
        assertFalse(engine().getDetail(source(), "$base/book/1").canReName)
        val json = sourceJson(base)
            .replace("\"ruleBookInfo\": {", "\"ruleBookInfo\": { \"canReName\": \"1\",")
        assertTrue(engine().getDetail(BookSourceRule.fromJson(json), "$base/book/1").canReName)
    }

    @Test
    fun `详情 init 的 put 能被后续字段 get 到`() = runBlocking {
        val json = sourceJson(base)
            .replace("\"ruleBookInfo\": {", "\"ruleBookInfo\": { \"init\": \"@put:{\\\"n\\\":\\\"h1@text\\\"}\",")
            .replace("\"name\": \"h1@text\",", "\"name\": \"@get:{n}\",")
        val detail = engine().getDetail(BookSourceRule.fromJson(json), "$base/book/1")
        assertEquals("魔道修真", detail.title)
    }

    @Test
    fun `详情 init 切换求值内容 —— JSON 接口源的 data 写法`() = runBlocking {
        val json = """
            {"bookSourceUrl":"$base","bookSourceName":"接口",
             "ruleBookInfo":{"init":"$.data","name":"$.book_name","author":"$.author","tocUrl":"$.toc"}}
        """.trimIndent()
        val detail = engine().getDetail(BookSourceRule.fromJson(json), "$base/api/info")
        assertEquals("接口小说", detail.title)
        assertEquals("某人", detail.author)
        assertEquals("$base/api/book/777/toc", detail.tocUrl)
    }

    @Test
    fun `详情 init 只返回标量时不切换内容`() = runBlocking {
        val json = sourceJson(base)
            .replace("\"ruleBookInfo\": {", "\"ruleBookInfo\": { \"init\": \"<js>'ok'</js>\",")
        val detail = engine().getDetail(BookSourceRule.fromJson(json), "$base/book/1")
        assertEquals("魔道修真", detail.title)
    }

    @Test
    fun `详情后写的规则能读到已经填好的 book name`() = runBlocking {
        val json = sourceJson(base).replace(
            "\"coverUrl\": \"div.cover img@src\",",
            "\"coverUrl\": \"<js>book.name+'.jpg'</js>\",",
        )
        val detail = engine().getDetail(BookSourceRule.fromJson(json), "$base/book/1")
        val cover = java.net.URLDecoder.decode(detail.coverUrl!!, Charsets.UTF_8)
        assertTrue(cover.endsWith("魔道修真.jpg"), "实际: ${detail.coverUrl}")
    }

    @Test
    fun `正文脚本能读到传入的章节标题`() = runBlocking {
        val json = """
            {"bookSourceUrl":"$base","bookSourceName":"章名",
             "ruleContent":{"content":"<js>'标题='+chapter.title</js>"}}
        """.trimIndent()
        val content = engine().getContent(
            BookSourceRule.fromJson(json), "$base/chap/1",
            chapterTitle = "第一章 山村少年",
        )
        assertEquals(
            "标题=第一章 山村少年",
            (content.elements.first() as ContentElement.Paragraph).text,
        )
    }

    @Test
    fun `UTF8 POST 搜索会编码表单值里的特殊字符`() = runBlocking {
        val json = """
            {"bookSourceUrl":"$base","bookSourceName":"POST站",
             "searchUrl":"/s,{\"method\":\"POST\",\"body\":\"q={{key}}\"}",
             "ruleSearch":{"bookList":"div.result","name":"h3 a@text","bookUrl":"h3 a@href"}}
        """.trimIndent()
        engine().search(BookSourceRule.fromJson(json), "A&B")
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("q=A%26B", req.body.readUtf8())
    }

    @Test
    fun `encodeKeyInFormTemplate 给 key 加上 encode 管道`() {
        assertEquals("q={{key|encode}}", encodeKeyInFormTemplate("q={{key}}", null))
        assertEquals("q={{key|encode:gbk}}", encodeKeyInFormTemplate("q={{key}}", "gbk"))
        assertEquals("""{"q":"{{key}}"}""", encodeKeyInFormTemplate("""{"q":"{{key}}"}""", "UTF-8"))
    }

    // ---- 搜索地址里的关键词转义 ----

    private fun searchSource(searchUrl: String) = BookSourceRule.fromJson(
        """{"bookSourceUrl":"$base","bookSourceName":"编码站",
            "searchUrl":${kotlinx.serialization.json.JsonPrimitive(searchUrl)},
            "ruleSearch":{"bookList":"div.result","name":"h3 a@text","bookUrl":"h3 a@href"}}"""
    )

    /**
     * GET 地址里的关键词从前原样代入：`C#` 把后面的 `&page=` 截成了片段，`C++` 到服务端成了空格，
     * `A&B` 被拆成两个字段，`100%` 是个坏转义。
     */
    @Test
    fun `GET 搜索的关键词按 URL 组件转义`() = runBlocking {
        val eng = engine()
        val src = searchSource("/s?q={{key}}&page={{page}}")
        val cases = listOf(
            "C#" to "C%23", "C++" to "C%2B%2B", "A&B" to "A%26B", "100%" to "100%25", "a b" to "a%20b",
            "修真" to "%E4%BF%AE%E7%9C%9F",
        )
        for ((kw, encoded) in cases) {
            eng.search(src, kw)
            assertEquals("/s?q=$encoded&page=1", server.takeRequest().path, "关键词: $kw")
        }
        // {{keyword}} 是同一个变量，同样转义
        eng.search(searchSource("/s?q={{keyword}}"), "C#")
        assertEquals("/s?q=C%23", server.takeRequest().path)
    }

    @Test
    fun `路径里的关键词也安全 —— 空格是 %20、斜杠不拆路径`() = runBlocking {
        engine().search(searchSource("/so/{{key}}.html"), "a/b c+d")
        assertEquals("/so/a%2Fb%20c%2Bd.html", server.takeRequest().path)
    }

    @Test
    fun `GBK 站的关键词按站点编码转义，JS 拼出来的地址也一样`() = runBlocking {
        val eng = engine()
        eng.search(searchSource("""/s?q={{keyword}},{"charset":"gbk"}"""), "中文")
        assertEquals("/s?q=%D6%D0%CE%C4", server.takeRequest().path)
        // 脚本把原文关键词直接拼进地址：交给 OkHttp 会按 UTF-8 编码，GBK 站搜出来是乱码
        eng.search(searchSource("""@js:'/s?q='+key+',{"charset":"gbk"}'"""), "中文")
        assertEquals("/s?q=%D6%D0%CE%C4", server.takeRequest().path)
    }

    /** 选项块里有没加引号的 {{page}}、body 写成 JSON 对象 —— Legado 先展开模板再解析，对象 body 序列化成 JSON */
    @Test
    fun `选项先展开再解析，对象 body 按 JSON 发送`() = runBlocking {
        val src = searchSource("""/s,{"method":"POST","body":{"keyword":"{{key}}","page":{{page}}}}""")
        engine().search(src, "A\"B", page = 3)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/s", req.path)
        assertEquals("""{"keyword":"A\"B","page":3}""", req.body.readUtf8())
        assertTrue(req.getHeader("Content-Type")!!.startsWith("application/json"))
    }

    @Test
    fun `解析不出来的选项块作废，但绝不留在地址里`() = runBlocking {
        engine().search(searchSource("""/s?q={{key}},{"method":"POST","body":}"""), "x")
        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/s?q=x", req.path)
    }

    @Test
    fun `splitUrlOptions 认对象 body 与未展开的选项块`() {
        val (bare, opt) = splitUrlOptions("""https://a.com/api,{"method":"POST","body":{"id":1,"q":"x"}}""")!!
        assertEquals("https://a.com/api", bare)
        assertEquals("""{"id":1,"q":"x"}""", opt.body)
        // 结构上是选项块、不是合法 JSON：地址照样切出来，选项作废
        val (bare2, opt2) = splitUrlOptions("""https://a.com/api,{"body":{"page":{{page}}}}""")!!
        assertEquals("https://a.com/api", bare2)
        assertNull(opt2.method)
        // `,{{` 是模板不是选项块
        assertNull(splitUrlOptions("https://a.com/list?ids=1,{{page}}"))
    }

    // ---- 发现页 ----

    @Test
    fun `exploreUrl 的 && 与换行都是分隔符`() {
        val items = parseExplore("玄幻::/a/{{page}}&&武侠::/b/{{page}}\n都市::/c/{{page}}", enabled = true)
        assertEquals(listOf("玄幻", "武侠", "都市"), items.map { it.name })
        assertEquals(listOf("/a/{{page}}", "/b/{{page}}", "/c/{{page}}"), items.map { it.url })
    }

    // ---- 跨阶段变量 ----

    /**
     * chapterList 上 java.put 的目录级变量，chapterUrl 的 @get 要取得到、也要随章节带出去；
     * 详情页传进来的书级变量同样可见，但没改过的不在每章里重复。
     */
    @Test
    fun `目录级与书级变量对章节项可见`() = runBlocking {
        val json = """
            {"bookSourceUrl":"$base","bookSourceName":"目录变量",
             "ruleToc":{
               "chapterList":"<js>java.put('bid','B9');JSON.stringify([{n:'第一章',u:'/vchap/1'},{n:'第二章',u:'/vchap/2'}])</js>",
               "chapterName":"$.n",
               "chapterUrl":"{{$.u}}?bid=@get:{bid}&book=@get:{book}"}}
        """.trimIndent()
        val toc = engine().getToc(
            BookSourceRule.fromJson(json), "$base/vartoc", variables = mapOf("book" to "X"),
        )
        assertEquals(listOf("$base/vchap/1?bid=B9&book=X", "$base/vchap/2?bid=B9&book=X"), toc.map { it.url })
        assertTrue(toc[0].variable.contains("B9"), "目录级变量随章节落库，实际: ${toc[0].variable}")
        assertFalse(toc[0].variable.contains("\"book\""), "原样传进来的书级变量不必每章重复，实际: ${toc[0].variable}")
    }

    @Test
    fun `详情页 put 的变量随详情带出`() = runBlocking {
        // 键不加引号的 @put 是 Legado 文档里的写法
        val json = sourceJson(base)
            .replace("\"ruleBookInfo\": {", "\"ruleBookInfo\": { \"init\": \"@put:{bid:\\\"h1@text\\\"}\",")
        val detail = engine().getDetail(BookSourceRule.fromJson(json), "$base/book/1")
        assertEquals("魔道修真", detail.variables["bid"])
    }

    @Test
    fun `正文先查本章变量、再查书级变量`() = runBlocking {
        val json = """
            {"bookSourceUrl":"$base","bookSourceName":"正文变量",
             "ruleContent":{"content":"<js>java.get('book')+'/'+java.get('cid')</js>"}}
        """.trimIndent()
        val content = engine().getContent(
            BookSourceRule.fromJson(json), "$base/chap/1",
            chapterVariable = """{"cid":"c1"}""",
            variables = mapOf("book" to "X", "cid" to "旧值"),
        )
        assertEquals("X/c1", (content.elements.first() as ContentElement.Paragraph).text)
    }

    // ---- 源级 replaceRegex ----

    /** 一条写坏的源级正则从前让每一章都打不开；Legado 退回字面替换，其余规则照常生效 */
    @Test
    fun `源级 replaceRegex 有坏正则时退回字面替换，不判整章失败`() = runBlocking {
        val src = source().let { s ->
            s.copy(ruleContent = s.ruleContent!!.copy(replaceRegex = "##(广告\n##*少年*\n##本站广告.*##\n##钟响##\$x"))
        }
        val texts = engine().getContent(src, "$base/chap/1")
            .elements.filterIsInstance<ContentElement.Paragraph>().map { it.text }
        assertTrue(texts.none { it.contains("本站广告") }, "好的规则照常生效，实际: $texts")
        // 替换串里的 $x 是非法组引用：退回字面替换，替换串也按字面
        assertTrue(texts.contains("山那边传来一声\$x。"), "实际: $texts")
    }

    // ---- JS 渲染回退 ----

    private class FakeRenderer(private val pages: Map<String, String>) : PageRenderer {
        val calls = java.util.concurrent.CopyOnWriteArrayList<Pair<String, Map<String, String>>>()
        override suspend fun render(url: String, headers: Map<String, String>, userAgent: String?): FetchedPage {
            calls += url to headers
            return FetchedPage(url, 200, pages[url] ?: "<html><body></body></html>", "UTF-8")
        }
    }

    private fun renderEngine(renderer: PageRenderer) = BookSourceEngine(
        SourceHttpClient(OkHttpClient(), retryBaseDelayMs = 1, allowPrivateNetwork = true),
        { _, _ -> emptyList() },
        scriptRuntime = com.radium.inkwell.core.source.js.RhinoScriptRuntime(),
        renderer = renderer,
    )

    /**
     * 记住「这一级要渲染」之后：渲染结果对不上规则要回静态再抓一遍（从前直接判空）；
     * 别的阶段不受连累；POST 请求、选项后缀都不进渲染器。
     */
    @Test
    fun `渲染记忆不是只许渲染 —— 按阶段记、对不上回静态、POST 不进渲染器`() = runBlocking {
        val renderer = FakeRenderer(
            mapOf("$base/js/chap" to """<html><body><div id="content"><p>渲染出来的正文。</p></div></body></html>"""),
        )
        val eng = renderEngine(renderer)
        val src = source()

        // 静态是空的 → 渲染成功 → 记住「正文要渲染」
        val rendered = eng.getContent(src, "$base/js/chap")
        assertEquals("渲染出来的正文。", (rendered.elements.single() as ContentElement.Paragraph).text)

        // 另一章渲染结果是空页，静态那份是好的：回静态，而不是判这一章读不出来
        val fallback = eng.getContent(src, "$base/chap/1")
        assertTrue(fallback.elements.isNotEmpty())

        // 目录这一级没记过：先静态，不该去惊动渲染器
        renderer.calls.clear()
        eng.getToc(src, "$base/book/1/toc")
        assertTrue(renderer.calls.none { it.first.contains("/toc") }, "目录不该走渲染器: ${renderer.calls}")

        // 带选项的 GET：交给渲染器的是剥掉选项的地址，选项里的 header 一并带上
        eng.getContent(src, "$base/js/chap") // 再记一次「正文要渲染」
        renderer.calls.clear()
        eng.getContent(src, """$base/js/chap,{"headers":{"X-A":"1"}}""")
        assertEquals("$base/js/chap", renderer.calls.first().first)
        assertEquals("1", renderer.calls.first().second["X-A"])

        // POST：渲染器只会 GET，method/body 交给它就丢了 —— 一律静态抓
        renderer.calls.clear()
        while (server.takeRequest(10, java.util.concurrent.TimeUnit.MILLISECONDS) != null) Unit // 清掉此前的请求记录
        eng.getContent(src, """$base/chap/1,{"method":"POST","body":"a=1"}""")
        assertTrue(renderer.calls.none { it.first.endsWith("/chap/1") || it.first.contains(",{") }, "${renderer.calls}")
        val post = generateSequence { server.takeRequest(10, java.util.concurrent.TimeUnit.MILLISECONDS) }
            .firstOrNull { it.path == "/chap/1" }
        assertEquals("POST", post?.method)
        assertEquals("a=1", post?.body?.readUtf8())
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

        const val API_INFO =
            """{"code":0,"data":{"book_name":"接口小说","author":"某人","toc":"/api/book/777/toc"}}"""

        const val API_TOC =
            """{"chapters":[{"t":"第一章 起","u":"/api/c/1"},{"t":"第二章 承","u":"/api/c/2"}]}"""

        const val LOOP_A = """<html><body><div id="content"><p>甲页内容。</p></div>
            <a id="next" href="/loop/b">下一页</a></body></html>"""

        const val LOOP_B = """<html><body><div id="content"><p>乙页内容。</p></div>
            <a id="next" href="/loop/a">下一页</a></body></html>"""

        const val MP_TOC_1 = """<html><body><ul class="chapters">
            <li><a href="/mp/c1">一</a></li>
            </ul><div class="pages">
            <a href="/mp/toc">1</a><a href="/mp/toc2">2</a><a href="/mp/toc3">3</a>
            </div></body></html>"""

        const val MP_TOC_2 = """<html><body><ul class="chapters">
            <li><a href="/mp/c2">二</a></li>
            </ul><div class="pages">
            <a href="/mp/toc">1</a><a href="/mp/toc2">2</a><a href="/mp/toc3">3</a>
            </div></body></html>"""

        const val MP_TOC_3 = """<html><body><ul class="chapters">
            <li><a href="/mp/c3">三</a></li>
            </ul><div class="pages">
            <a href="/mp/toc">1</a><a href="/mp/toc2">2</a><a href="/mp/toc3">3</a>
            </div></body></html>"""

        /** 详情页即目录页：书名和章节列表在同一页上 */
        const val SELF_TOC_PAGE = """<html><body><h1>同页书</h1>
            <ul class="chapters"><li><a href="/self/c1">第一章</a></li><li><a href="/self/c2">第二章</a></li></ul>
            </body></html>"""

        const val EXPLORE_PAGE = """<html><body>
            <div class="result"><h3><a href="/book/9">发现书一</a></h3></div>
            <div class="result"><h3><a href="/book/10">发现书二</a></h3></div>
            </body></html>"""

        const val GBK_CHAP = """<html><head><meta charset="gbk"></head><body>
            <div id="content"><p>　　这是GBK编码的中文正文。</p></div></body></html>"""
    }
}
