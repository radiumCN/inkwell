package com.radium.inkwell.core.source

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
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 目录、正文、请求体上照 Legado 补齐的行为（同 [LegadoParityRulesTest] 那份合集查出来的）：
 * 目录列表的 `+`、没地址的单章、后续页 404、空数组的下一页、无目录规则时保留请求选项、
 * 正文脚本自己取正文、POST 表单按字段编码、来源自带的校验词。
 */
class LegadoParityEngineTest {

    private lateinit var server: MockWebServer
    private lateinit var base: String
    private val hits = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val requests = java.util.concurrent.ConcurrentLinkedQueue<RecordedRequest>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val path = request.path.orEmpty()
                hits.merge(path, 1, Int::plus)
                val body = when (path) {
                    "/toc/plus" -> """<ul><li><a href="/c/1">一</a></li><li><a href="/c/2">二</a></li></ul>"""
                    "/toc/single" -> "<h1>唯一一章</h1><div id=\"content\">正文</div>"
                    "/toc/mixed" -> """<ul><li>第一卷</li><li><a href="/c/1">一</a></li></ul>"""
                    // 链接是脚本填的：静态页上只有书名
                    "/toc/js" -> """<ul><li><a>一</a></li><li><a>二</a></li></ul>"""
                    "/toc/p1" -> """<ul><li><a href="/c/1">一</a></li></ul><a id="next" href="/toc/p2">下一页</a>"""
                    "/toc/p2" -> """<ul><li><a href="/c/2">二</a></li></ul><a id="next" href="/toc/p3">下一页</a>"""
                    "/api/book" -> if (request.method == "POST") """{"list":[{"t":"一","id":"1"}]}""" else """{"error":"GET"}"""
                    "/real/1" -> "<p>脚本取到的正文</p>"
                    "/forbid/1" -> return MockResponse().setResponseCode(403)
                    else -> return MockResponse().setResponseCode(404)
                }
                return MockResponse().setBody(body)
            }
        }
        server.start()
        base = server.url("/").toString().removeSuffix("/")
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private val engine by lazy {
        BookSourceEngine(
            SourceHttpClient(OkHttpClient(), retryBaseDelayMs = 1, allowPrivateNetwork = true),
            scriptRuntime = com.radium.inkwell.core.source.js.RhinoScriptRuntime(),
        )
    }

    private fun source(rules: String) = BookSourceRule.fromJson(
        """{ "bookSourceUrl": "$base", "bookSourceName": "对照测试", $rules }""",
    )

    private fun toc(rules: String, url: String) = runBlocking { engine.getToc(source(rules), "$base$url") }

    // ---- 目录 ----

    @Test
    fun `目录列表规则前面的加号是正序记号，去掉再求值`() {
        val chapters = toc(""""ruleToc": { "chapterList": "+tag.li@tag.a", "chapterName": "text", "chapterUrl": "href" }""", "/toc/plus")
        assertEquals(listOf("一", "二"), chapters.map { it.title })
    }

    @Test
    fun `整份目录都没有章节地址时，那一章记成目录页本身`() {
        // 克苏鲁公社：目录页就是正文页，章节地址规则取不到东西（Legado 用目录页地址顶上）
        val chapters = toc(""""ruleToc": { "chapterList": "tag.h1", "chapterName": "text", "chapterUrl": "@href" }""", "/toc/single")
        assertEquals(listOf("唯一一章" to "$base/toc/single"), chapters.map { it.title to it.url })
    }

    @Test
    fun `正常目录里没有链接的分卷行照旧丢掉`() {
        val chapters = toc(""""ruleToc": { "chapterList": "tag.li", "chapterName": "text", "chapterUrl": "tag.a@href" }""", "/toc/mixed")
        assertEquals(listOf("一"), chapters.map { it.title })
    }

    @Test
    fun `只有书名没有地址的目录先换渲染再试，渲染也没地址才记成目录页本身`() = runBlocking {
        // 从前「没地址就记成目录页本身」一兜底，静态那一回就算取到了，渲染那一回轮不到：整本书只剩一章，校验还报可用
        val rendered = """<ul><li><a href="/c/1">一</a></li><li><a href="/c/2">二</a></li></ul>"""
        val eng = BookSourceEngine(
            SourceHttpClient(OkHttpClient(), retryBaseDelayMs = 1, allowPrivateNetwork = true),
            scriptRuntime = com.radium.inkwell.core.source.js.RhinoScriptRuntime(),
            renderer = object : PageRenderer {
                override suspend fun render(url: String, headers: Map<String, String>, userAgent: String?) =
                    FetchedPage(url, 200, if (url.endsWith("/toc/js")) rendered else "<h1>唯一一章</h1>", "UTF-8")
            },
        )
        val linked = eng.getToc(source(""""ruleToc": { "chapterList": "tag.li", "chapterName": "text", "chapterUrl": "tag.a@href" }"""), "$base/toc/js")
        assertEquals(listOf("一" to "$base/c/1", "二" to "$base/c/2"), linked.map { it.title to it.url })
        val single = eng.getToc(source(""""ruleToc": { "chapterList": "tag.h1", "chapterName": "text", "chapterUrl": "@href" }"""), "$base/toc/single")
        assertEquals(listOf("唯一一章" to "$base/toc/single"), single.map { it.title to it.url })
    }

    @Test
    fun `目录后续页 404 就停在那里，已经拿到的章节照用`() {
        val rules = """"ruleToc": { "chapterList": "tag.li@tag.a", "chapterName": "text", "chapterUrl": "href", "nextTocUrl": "id.next@href" }"""
        assertEquals(listOf("一", "二"), toc(rules, "/toc/p1").map { it.title })
        assertEquals(1, hits["/toc/p3"])
        // 第一页就 404 的照旧报错：那是这本书的目录真的打不开
        assertFailsWith<SourceHttpClient.HttpStatusException> { toc(rules, "/toc/none") }
    }

    @Test
    fun `下一页脚本返回空数组不当成地址`() {
        val rules = """"ruleToc": { "chapterList": "tag.li@tag.a", "chapterName": "text", "chapterUrl": "href", "nextTocUrl": "@js:[]" }"""
        assertEquals(listOf("一", "二"), toc(rules, "/toc/plus").map { it.title })
        assertTrue(hits.keys.none { "[]" in it || "%5B%5D" in it }, "空数组被当成地址抓了：${hits.keys}")
    }

    @Test
    fun `没有目录地址规则时，目录地址保留书地址的请求选项`() = runBlocking {
        // 画涯爱子：详情规则是空的，书的地址是 POST 接口；跳转后的 finalUrl 会把选项丢掉，目录变成不带参数的 GET
        val src = source(
            """"ruleBookInfo": { "name": "$.none" },
               "ruleToc": { "chapterList": "list", "chapterName": "t", "chapterUrl": "id" }""",
        )
        val bookUrl = """$base/api/book,{"method":"POST","body":"id=1"}"""
        val detail = engine.getDetail(src, bookUrl)
        assertEquals(bookUrl, detail.tocUrl)
        assertEquals(listOf("一"), engine.getToc(src, detail.tocUrl).map { it.title })
    }

    // ---- 正文 ----

    @Test
    fun `章节地址抓不到、正文脚本自己取到了正文时照用`() = runBlocking {
        // 茄子免费小说：章节地址只是个 id，拼到接口下面必然 404，正文是脚本拿章节号自己请求的
        val src = source(""""ruleContent": { "content": "<js>java.ajax(baseUrl.replace('/c/', '/real/'))</js>" }""")
        val content = engine.getContent(src, "$base/c/1", allowJsRender = false)
        assertEquals(listOf("脚本取到的正文"), content.elements.map { (it as com.radium.inkwell.core.model.ContentElement.Paragraph).text })
    }

    @Test
    fun `脚本也没取到正文时，照旧报章节地址的那个 HTTP 错误`() {
        val src = source(""""ruleContent": { "content": "<js>''</js>" }""")
        val e = assertFailsWith<SourceHttpClient.HttpStatusException> {
            runBlocking { engine.getContent(src, "$base/c/1", allowJsRender = false) }
        }
        assertEquals(404, e.code)
    }

    @Test
    fun `脚本没自己发请求、只是吐了句兜底文字，照旧报 404，不当正文`() {
        // 手里只有一张空页的脚本，吐出来的是它自己的兜底；存成正文就再也换不掉了
        val src = source(""""ruleContent": { "content": "<js>try{JSON.parse(result).data}catch(e){'加载失败'}</js>" }""")
        val e = assertFailsWith<SourceHttpClient.HttpStatusException> {
            runBlocking { engine.getContent(src, "$base/c/1", allowJsRender = false) }
        }
        assertEquals(404, e.code)
    }

    @Test
    fun `章节地址 403 不放给脚本兜底，照原样报被拒`() {
        // 403 / 429 是被拒、限流：换源、退避都看这个错误，不能让脚本的兜底顶掉
        val src = source(""""ruleContent": { "content": "<js>java.ajax(baseUrl.replace('/forbid/', '/real/'))</js>" }""")
        val e = assertFailsWith<SourceHttpClient.HttpStatusException> {
            runBlocking { engine.getContent(src, "$base/forbid/1", allowJsRender = false) }
        }
        assertEquals(403, e.code)
    }

    // ---- POST 表单 ----

    private fun searchBody(searchUrl: String, keyword: String, header: String? = null): String {
        requests.clear()
        val src = BookSourceRule.fromJson(
            """{ "bookSourceUrl": "$base", "bookSourceName": "表单", "searchUrl": ${kotlinx.serialization.json.JsonPrimitive(searchUrl)},
                 ${header?.let { "\"header\": ${kotlinx.serialization.json.JsonPrimitive(it)}," }.orEmpty()}
                 "ruleSearch": { "bookList": "tag.li", "name": "text", "bookUrl": "tag.a@href" } }""",
        )
        runCatching { runBlocking { engine.search(src, keyword) } }
        return requests.single { it.path == "/s" }.body.readUtf8()
    }

    @Test
    fun `POST 表单体按字段编码，已经编码过的不再编`() {
        // 书耽这类接口把 Base64 拼进表单：`+` `/` `=` 原样发出去，服务端把 + 解成空格，解不开
        assertEquals(
            "q=%E6%88%91%E7%9A%84&sig=a%2Bb%2Fc%3D%3D&t=%E6%90%9C%E7%B4%A2&ok=a%20b",
            searchBody("""/s,{"method":"POST","body":"q={{key}}&sig=a+b/c==&t=搜索&ok=a%20b"}""", "我的"),
        )
    }

    @Test
    fun `URLEncoder 编过的值（空格是加号、带着百分号转义）不再编一遍`() {
        // java.encodeURI("斗 破") 拼进表单体：再编一遍，% 成了 %25，服务端拿到一串百分号
        assertEquals("q=%E6%96%97+%E7%A0%B4", searchBody("""/s,{"method":"POST","body":"q=%E6%96%97+%E7%A0%B4"}""", "x"))
    }

    @Test
    fun `向导做的来源，表单体原样发`() {
        // 向导生成时已经逐字段编好：隐藏字段的空格是 +，再编就成了 %2B
        requests.clear()
        val src = BookSourceRule(
            bookSourceUrl = base,
            bookSourceName = "自建",
            bookSourceComment = "由「新建来源」根据 $base/b/1 自动识别生成",
            searchUrl = """/s,{"method":"POST","body":"go=Search+Now&q={{key}}"}""",
            ruleSearch = SearchRuleSet(bookList = "tag.li", name = "text", bookUrl = "tag.a@href"),
        )
        runCatching { runBlocking { engine.search(src, "我的") } }
        assertEquals("go=Search+Now&q=%E6%88%91%E7%9A%84", requests.single { it.path == "/s" }.body.readUtf8())
    }

    @Test
    fun `关键词里的空格只编一次`() {
        assertEquals("q=a%20b%2Bc", searchBody("""/s,{"method":"POST","body":"q={{key}}"}""", "a b+c"))
    }

    @Test
    fun `关键词和别的字符挤在一个字段里，关键词也只编一次`() {
        // 体里拼了一段 JSON：字段里有 { " :，要编；关键词代进来时已经编过，它的 % 不能再编成 %25
        assertEquals(
            "params=%7B%22key%22%3A%22%E6%88%91%22%7D",
            searchBody("""/s,{"method":"POST","body":"params={\"key\":\"{{key}}\"}"}""", "我"),
        )
    }

    @Test
    fun `写了 Content-Type 或体是 JSON 的原样发`() {
        assertEquals(
            "sig=a+b/c==",
            searchBody(
                """/s,{"method":"POST","body":"sig=a+b/c=="}""", "x",
                header = """{"Content-Type":"application/x-www-form-urlencoded"}""",
            ),
        )
        assertEquals("""{"k":"a+b"}""", searchBody("""/s,{"method":"POST","body":"{\"k\":\"a+b\"}"}""", "x"))
    }

    // ---- 校验词 ----

    @Test
    fun `来源自带的校验词随规则读进来、写出去`() {
        val src = source(""""searchUrl": "/s?q={{key}}", "ruleSearch": { "bookList": "li", "checkKeyWord": "催眠" }""")
        assertEquals("催眠", src.search?.checkKeyWord)
        assertEquals("催眠", BookSourceRule.fromJson(src.toJson()).search?.checkKeyWord)
    }

    @Test
    fun `认了校验词以后，带登录的来源会话标识不变，升级不会被登出`() {
        // 会话标识是规则的摘要：0.6.1 之前 checkKeyWord 导入时就丢了，摘要里没有它。新认的字段得在摘要外面
        val withWord = source(
            """"loginUrl": "https://ex.com/login", "searchUrl": "/s?q={{key}}",
               "ruleSearch": { "bookList": "li", "checkKeyWord": "催眠" }, "ruleExplore": { "bookList": "li", "checkKeyWord": "x" }""",
        )
        val without = source(
            """"loginUrl": "https://ex.com/login", "searchUrl": "/s?q={{key}}",
               "ruleSearch": { "bookList": "li" }, "ruleExplore": { "bookList": "li" }""",
        )
        assertEquals(without.loginSessionId, withWord.loginSessionId)
    }
}
