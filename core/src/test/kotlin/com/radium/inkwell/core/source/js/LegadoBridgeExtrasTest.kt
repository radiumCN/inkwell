package com.radium.inkwell.core.source.js

import com.radium.inkwell.core.source.SourceHttpClient
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 阅读的脚本接口里，我们从前缺的那一批：java.ajaxAll / setContent 之外的
 * get·post·head 不跟随重定向、响应的 header / cookies / raw、toNumChapter、t2s / s2t、source 的登录信息。
 * 缺一个，调到的那条规则就是 ReferenceError，整条落空。
 */
class LegadoBridgeExtrasTest {

    private val runtime = RhinoScriptRuntime()

    /** 记下每次请求跟不跟随重定向，回一个带 Set-Cookie、Location 的 302 */
    private class RecordingHttp : JsHttp {
        val calls = mutableListOf<Pair<String, Boolean>>()
        override fun fetch(
            url: String,
            method: String,
            body: String?,
            headers: Map<String, String>,
            followRedirects: Boolean,
        ): StrResponse {
            calls += "$method $url" to followRedirects
            return StrResponse(
                bodyText = "body:$url",
                status = 302,
                finalUrl = "https://a.com/final",
                headerMap = mapOf(
                    "location" to listOf("/r/9"),
                    "Set-Cookie" to listOf("token=abc; Path=/", "secret=150; HttpOnly"),
                ),
            )
        }
        override fun cookieOf(url: String) = ""
        override fun setCookie(url: String, cookie: String) = Unit
        override fun removeCookie(url: String) = Unit
    }

    private fun eval(script: String, bindings: Map<String, Any?>) = runtime.eval(script, bindings)

    @Test
    fun `get、post、head 不跟随重定向，ajax、connect 跟随`() {
        val http = RecordingHttp()
        val java = JavaBridge(http, JsCache(), mutableMapOf())
        eval(
            "java.get('https://a.com/g', {}); java.post('https://a.com/p', 'q=1', {}); java.head('https://a.com/h', {});" +
                "java.get('https://a.com/g2', {}, 3000); java.ajax('https://a.com/x'); java.connect('https://a.com/c')",
            mapOf("java" to java),
        )
        assertEquals(
            listOf(
                "GET https://a.com/g" to false,
                "POST https://a.com/p" to false,
                "HEAD https://a.com/h" to false,
                "GET https://a.com/g2" to false,
                "GET https://a.com/x" to true,
                "GET https://a.com/c" to true,
            ),
            http.calls,
        )
    }

    @Test
    fun `响应的 header、cookies、raw 照阅读的写法`() {
        val java = JavaBridge(RecordingHttp(), JsCache(), mutableMapOf())
        fun js(script: String) = eval(script, mapOf("java" to java))
        // 书仓网：POST 搜索后拿 Location；头名不分大小写
        assertEquals("/r/9", js("java.post('https://a.com/s', 'k=1', {}).header('Location')"))
        // 一读：head 一下拿下发的 cookie，`.token` 和 `.get('token')` 都行
        assertEquals("abc|150|abc", js("var c = java.head('https://a.com', {}).cookies(); c.token + '|' + c.secret + '|' + c.get('token')"))
        assertEquals("""{"token":"abc","secret":"150"}""", js("JSON.stringify(java.head('https://a.com', {}).cookies())"))
        assertEquals("abc", js("java.get('https://a.com', {}).cookie('token')"))
        // 耽美文库：connect 之后读跳转后的地址
        assertEquals("https://a.com/final", js("String(java.connect('https://a.com/s').raw().request().url())"))
        assertEquals("false", js("String(java.connect('u').isSuccessful())"))
        assertEquals("/r/9", js("java.get('u', {}).headers().location"))
        // 没有原型也能拼字符串
        assertTrue(js("'' + java.get('u', {}).cookies()")!!.contains("\"token\":\"abc\""))
    }

    @Test
    fun `ajaxAll 一组地址按顺序交回，地址选项（请求方式、请求体、请求头）整块带上`() {
        val seen = mutableListOf<JsHttpRequest>()
        val http = object : JsHttp {
            override fun fetch(url: String, method: String, body: String?, headers: Map<String, String>, followRedirects: Boolean) =
                if (url.endsWith("/bad")) null else StrResponse(bodyText = "page:" + url.substringAfterLast('/'))
            override fun fetchAll(requests: List<JsHttpRequest>): List<StrResponse?> {
                seen += requests
                return super.fetchAll(requests)
            }
            override fun cookieOf(url: String) = ""
            override fun setCookie(url: String, cookie: String) = Unit
            override fun removeCookie(url: String) = Unit
        }
        val java = JavaBridge(http, JsCache(), mutableMapOf())
        assertEquals(
            "3|page:1,,page:3",
            eval(
                "var r = java.ajaxAll(['https://a.com/1', 'https://a.com/bad', 'https://a.com/3,{\"method\":\"post\",\"body\":\"q=1\",\"headers\":{\"referer\":\"x\"}}'], false);" +
                    "r.length + '|' + [r[0].body(), r[1].body(), r[2].body()].join(',')",
                mapOf("java" to java),
            ),
        )
        // 选项整块带上：翻译类订阅源每一格都是 POST
        assertEquals(JsHttpRequest("https://a.com/3", "POST", "q=1", mapOf("referer" to "x")), seen.single { it.url == "https://a.com/3" })
        assertEquals(JsHttpRequest("https://a.com/1"), seen.first())
        // 不带第二个参数的写法（图集正文）
        assertEquals("page:1", eval("java.ajaxAll(['https://a.com/1'])[0].body()", mapOf("java" to java)))
    }

    @Test
    fun `ajaxAll 真的一起发出去，结果按传入顺序`() {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val n = request.path!!.removePrefix("/")
                // 第一个最慢：顺序得按传入的排，不是按回来的先后
                if (n == "1") Thread.sleep(300)
                return MockResponse().setBody("p$n")
            }
        }
        server.start()
        try {
            val http = EngineJsHttp(SourceHttpClient(OkHttpClient(), retryBaseDelayMs = 1, allowPrivateNetwork = true))
            val java = JavaBridge(http, JsCache(), mutableMapOf())
            val urls = (1..4).map { server.url("/$it").toString() }.toTypedArray()
            val t0 = System.nanoTime()
            val out = java.ajaxAll(urls).map { it.body() }
            val ms = (System.nanoTime() - t0) / 1_000_000
            assertEquals(listOf("p1", "p2", "p3", "p4"), out)
            assertTrue(ms < 1_000, "四个请求应当一起发，用了 $ms ms")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `toNumChapter 只交回换成数字的那段「第N章」`() {
        val java = JavaBridge(null, JsCache(), mutableMapOf())
        assertEquals("第123章", java.toNumChapter("第一百二十三章 开端"))
        assertEquals("第12章", java.toNumChapter("VIP 第１２章 下山"))
        assertEquals("楔子", java.toNumChapter("楔子"))
        // 来源里的用法：拿它当 replace 的替换串
        assertEquals(
            "第205章 归来",
            eval("'第二百零五章 归来'.replace(/第([零一二三四五六七八九十百千]+)章/g, java.toNumChapter('第二百零五章 归来'))", mapOf("java" to java)),
        )
    }

    @Test
    fun `t2s、s2t 交给注入的转换器，没注入原样返回`() {
        val fake = object : ChineseConverter {
            override fun toSimplified(text: String) = text.replace("書", "书")
            override fun toTraditional(text: String) = text.replace("书", "書")
        }
        val java = JavaBridge(null, JsCache(), mutableMapOf(), chinese = fake)
        assertEquals("书名|書名", eval("java.t2s('書名') + '|' + java.s2t('书名')", mapOf("java" to java)))
        val plain = JavaBridge(null, JsCache(), mutableMapOf())
        assertEquals("書名", eval("java.t2s('書名')", mapOf("java" to plain)))
    }

    @Test
    fun `没登录过：登录信息是空的，脚本自己存进去的读得回来`() {
        val source = SourceBridge("https://a.com", facts = SourceFacts(name = "订阅"))
        fun js(script: String) = eval(script, mapOf("source" to source))
        // 学术订阅源的 header 脚本、霹雳书屋的 jsLib：先读一下登录信息
        assertEquals("null|null", js("String(source.getLoginInfoMap()) + '|' + String(source.getLoginInfo())"))
        js("source.putLoginInfo(JSON.stringify({proxyUrl: 'http://p:1'}))")
        assertEquals("http://p:1|http://p:1", js("var i = source.getLoginInfoMap(); i.get('proxyUrl') + '|' + i.proxyUrl"))
        js("source.putLoginHeader(JSON.stringify({Cookie: 't=1'}))")
        assertEquals("t=1", js("source.getLoginHeaderMap().Cookie"))
        js("source.removeLoginHeader()")
        assertEquals("null", js("String(source.getLoginHeader())"))
        // 订阅源的字段名（阅读的 RssSource）
        assertEquals("https://a.com|订阅", js("source.sourceUrl + '|' + source.sourceName"))
    }

    /**
     * 阅读交给脚本的 `source` 是来源对象本身：登录脚本（公用函数写在里面、规则开头 eval 它）、规则集都读得到；
     * `loginUi` 是**方法** —— 聚合源拿 `typeof source.loginUi == 'function'` 认「是不是官方阅读」
     */
    @Test
    fun `source 带着来源自己的字段，loginUi 是方法`() {
        val facts = SourceFacts(
            name = "爱丽丝",
            loginUrl = "function GetUL() { return 'https://www.alicesw.com' }",
            loginUi = "[]",
            ruleSets = mapOf("ruleSearch" to mapOf("author" to "p.author@text")),
        )
        val source = SourceBridge("https://www.alicesw.com#备用", facts = facts)
        fun js(script: String) = eval(script, mapOf("source" to source))
        assertEquals("https://www.alicesw.com/search", js("eval(String(source.loginUrl)); GetUL() + '/search'"))
        assertEquals("function", js("typeof source.loginUi"))
        assertEquals("p.author@text", js("String(source.ruleSearch.author)"))
        assertEquals("null", js("String(source.ruleToc)"))
    }

    @Test
    fun `book 的字段照阅读的 Book，脚本能改；自定义变量、阅读设置的方法都在`() {
        val book = BookBridge(name = "书")
        fun js(script: String) = eval(script, mapOf("book" to book))
        assertEquals("8", js("String(book.type)"))
        js("book.type = 32; book.putCustomVariable('x'); book.setUseReplaceRule(false)")
        assertEquals(32, book.type)
        assertEquals("x|true", js("book.getCustomVariable() + '|' + book.getUseReplaceRule()"))
    }

    @Test
    fun `androidId 给注入的设备号，timeFormatUTC 的偏移按毫秒`() {
        val java = JavaBridge(null, JsCache(), mutableMapOf(), deviceId = { "3f2a9c7d1e5b8a40" })
        fun js(script: String) = eval(script, mapOf("java" to java))
        assertEquals("3f2a9c7d1e5b8a40", js("java.androidId()"))
        // 1970-01-01 00:00 UTC；偏移 8 小时 = 28800000 毫秒，照阅读写成 8 的就是 UTC
        assertEquals("1970-01-01 08:00", js("java.timeFormatUTC(0, 'yyyy-MM-dd HH:mm', 8 * 60 * 60 * 1000)"))
        assertEquals("197001010000", js("java.timeFormatUTC(0, 'yyyyMMddHHmm', 8)"))
    }

    @Test
    fun `cache 的内存存取存的是对象本身`() {
        val cache = JsCache()
        fun js(script: String) = eval(script, mapOf("cache" to cache))
        js("cache.putMemory('sites', ['a', 'b'])")
        assertEquals("a,b|2", js("var s = cache.getFromMemory('sites'); s.join(',') + '|' + s.length"))
        js("cache.deleteMemory('sites')")
        assertEquals("null", js("String(cache.getFromMemory('sites'))"))
    }
}
