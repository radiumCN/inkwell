package com.radium.inkwell.core.source

import com.radium.inkwell.core.source.js.RhinoScriptRuntime
import com.radium.inkwell.core.source.js.StrResponse
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Cookie
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SourceLoginTest {
    private class Sessions : SourceSessionStore {
        private val data = ConcurrentHashMap<Pair<String, SourceSessionKey>, String>()

        override fun read(sourceId: String, key: SourceSessionKey) = data[sourceId to key]

        override fun write(sourceId: String, key: SourceSessionKey, value: String?) {
            if (value == null) data.remove(sourceId to key) else data[sourceId to key] = value
        }
    }

    private fun http(
        sessions: SourceSessionStore = Sessions(),
        webCookies: (String, HttpUrl) -> String? = { _, _ -> null },
    ) = SourceHttpClient(allowPrivateNetwork = true, sessionStore = sessions, webCookies = webCookies)

    private fun engine(http: SourceHttpClient, sessions: SourceSessionStore = Sessions()) =
        BookSourceEngine(http, scriptRuntime = RhinoScriptRuntime(), sessionStore = sessions)

    @Test
    fun `仅配置登录校验脚本的来源有独立会话且不显示登录入口`() {
        val source = fixture("https://books.example").copy(loginCheckJs = "result")
        val other = source.copy(bookSourceUrl = "${source.bookSourceUrl}#other")
        val updated = source.copy(loginCheckJs = "result;")

        assertFalse(source.hasLogin)
        assertTrue(source.loginSessionId != null)
        assertTrue(source.loginSessionId != other.loginSessionId)
        assertTrue(source.loginSessionId != updated.loginSessionId)
        assertEquals(source.loginSessionId, BookSourceRule.fromJson(source.toJson()).loginSessionId)
        assertNull(source.copy(loginCheckJs = " ").loginSessionId)
    }

    @Test
    fun `登录表单保存会话并在响应要求登录时续期重试`(): Unit = runBlocking {
        MockWebServer().use { server ->
            val base = server.url("/").toString().trimEnd('/')
            val loginCount = AtomicInteger()
            val token = AtomicInteger()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/login" -> {
                        if (request.body.readUtf8() != """{"email":"reader","password":"fixture-password"}""") {
                            MockResponse().setResponseCode(401)
                        } else {
                            token.set(loginCount.incrementAndGet())
                            MockResponse().setHeader("Set-Cookie", "native=fixture; Path=/; Max-Age=3600")
                                .setBody("""{"token":"${token.get()}"}""")
                        }
                    }
                    "/search" -> {
                        if (request.getHeader("Authorization") == "Bearer ${token.get()}" &&
                            request.getHeader("Cookie") == "native=fixture") books()
                        else MockResponse().setResponseCode(401)
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
            val source = fixture(base).copy(
                jsLib = """function fields() { return [{name:'email',default:'reader'}, {name:'password',type:'password'}]; }""",
                loginUi = "@js:fields()",
                loginUrl = """
                    function login(save) {
                      if (save) source.putLoginInfo(JSON.stringify(result));
                      var data = JSON.parse(source.getLoginInfo() || '{}');
                      var response = JSON.parse(java.ajax(baseUrl + '/login,' +
                        JSON.stringify({method: 'POST', body: JSON.stringify(data)})));
                      if (!response.token) throw '未登录';
                      source.setVariable(response.token);
                      source.putLoginHeader(JSON.stringify({Authorization: 'Bearer ' + response.token}));
                    }
                """.trimIndent(),
                loginCheckJs = """
                    if (result.code() === 401) {
                      source.login();
                      java.getStrResponse(result.request().url(), String(source.getLoginHeader()));
                    } else { result; }
                """.trimIndent(),
            )
            val sessions = Sessions()
            val engine = engine(http(sessions), sessions)
            val form = engine.getLoginForm(source)
            assertEquals(mapOf("email" to "reader", "password" to ""), form.values)
            assertEquals("password", form.fields.last().type)
            assertFailsWith<SourceException> { engine.submitLogin(source, form.values, action = "login(true)") }
            assertNull(sessions.read(checkNotNull(source.loginSessionId), SourceSessionKey.INFO))
            engine.submitLogin(source, form.values + ("password" to "fixture-password"), action = "login(true)")
            assertEquals("1", sessions.read(checkNotNull(source.loginSessionId), SourceSessionKey.VARIABLE))
            assertEquals("样例", engine.search(source, "样例").items.single().title)

            val restarted = engine(http(sessions), sessions)
            assertEquals("样例", restarted.search(source, "样例").items.single().title)
            assertEquals(1, loginCount.get())
            token.set(-1)
            assertEquals("样例", restarted.search(source, "样例").items.single().title)
            assertEquals(2, loginCount.get())
            assertFailsWith<SourceException> { restarted.search(source.copy(bookSourceUrl = "$base#other"), "样例") }
            val replacement = source.copy(bookSourceComment = "规则已更新")
            assertEquals(source.id, replacement.id)
            assertEquals("", restarted.getLoginForm(replacement).values["password"])
            assertFailsWith<SourceException> { restarted.search(replacement, "样例") }
            assertEquals("样例", restarted.search(BookSourceRule.fromJson(source.toJson()), "样例").items.single().title)
        }
    }

    @Test
    fun `网页 Cookie 对脚本与请求可见且不跟随跨站重定向`() = runBlocking {
        MockWebServer().use { server ->
            MockWebServer().use { other ->
                val home = server.url("/")
                val base = home.toString().trimEnd('/')
                val source = fixture(base).copy(
                    loginUrl = "$base/sign-in",
                    searchUrl = "<js> '/search?' + cookie.getCookie(baseUrl) </js>",
                )
                server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/results")))
                other.enqueue(books())
                val engine = engine(http(webCookies = { sourceId, request ->
                    "session=fixture".takeIf { sourceId == source.loginSessionId && request.host == home.host && request.port == home.port }
                }))
                assertEquals("样例", engine.search(source, "样例").items.single().title)

                val request = server.takeRequest()
                assertEquals("/search?session=fixture", request.path)
                assertEquals("session=fixture", request.getHeader("Cookie"))
                assertNull(other.takeRequest().getHeader("Cookie"))
            }
        }
    }

    @Test
    fun `第三方资源和跨站重定向不携带持久化登录头`() = runBlocking {
        MockWebServer().use { server ->
            MockWebServer().use { other ->
                val base = server.url("/").toString().trimEnd('/')
                val source = fixture(base).copy(
                    loginUrl = """function login() {
                        source.putLoginHeader(JSON.stringify({Authorization:"Bearer fixture", Cookie:"auth=fixture", "X-Session":"fixture"}));
                    }""",
                )
                val engine = engine(http())
                engine.submitLogin(source, emptyMap())
                server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/redirected")))
                other.enqueue(MockResponse().setBody("image"))
                other.enqueue(MockResponse().setBody("image"))
                engine.getImage(source, "$base/image")
                engine.getImage(source, other.url("/direct").toString())

                val authorized = server.takeRequest()
                assertEquals("Bearer fixture", authorized.getHeader("Authorization"))
                assertEquals("auth=fixture", authorized.getHeader("Cookie"))
                assertEquals("fixture", authorized.getHeader("X-Session"))
                repeat(2) {
                    val foreign = other.takeRequest()
                    assertNull(foreign.getHeader("Authorization"))
                    assertNull(foreign.getHeader("Cookie"))
                    assertNull(foreign.getHeader("X-Session"))
                }
            }
        }
    }

    @Test
    fun `脚本登录重试仅向授权来源发送持久化请求头`() = runBlocking {
        MockWebServer().use { server ->
            MockWebServer().use { other ->
                val base = server.url("/").toString().trimEnd('/')
                val source = fixture(base).copy(
                    loginUrl = """
                        function login() {
                          source.putLoginHeader('{"X-Session":"fixture"}');
                          java.getStrResponse('${other.url("/direct")}', '{"x-session":"fixture","X-Request":"keep"}');
                        }
                    """.trimIndent(),
                    loginCheckJs = """
                        if (result.code() === 401) {
                          source.login();
                          var saved = JSON.parse(String(source.getLoginHeader()));
                          var headers = JSON.stringify({'x-session': saved['X-Session'], 'X-Request': 'keep'});
                          java.getStrResponse(result.request().url(), headers);
                        } else result;
                    """.trimIndent(),
                )
                val sessions = Sessions()
                server.enqueue(MockResponse().setResponseCode(401))
                server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/redirect")))
                other.enqueue(MockResponse().setBody("ok"))
                other.enqueue(books())

                assertEquals("样例", engine(http(sessions), sessions).search(source, "样例").items.single().title)
                assertNull(server.takeRequest().getHeader("X-Session"))
                assertEquals("fixture", server.takeRequest().getHeader("X-Session"))
                for (path in listOf("/direct", "/redirect")) {
                    val request = other.takeRequest()
                    assertEquals(path, request.path)
                    assertNull(request.getHeader("X-Session"))
                    assertEquals("keep", request.getHeader("X-Request"))
                }
            }
        }
    }

    @Test
    fun `无参登录重试保留 POST 请求体和请求选项并重算登录头`() = runBlocking {
        MockWebServer().use { server ->
            val base = server.url("/").toString().trimEnd('/')
            val source = fixture(base).copy(
                searchUrl = """/search,{"method":"POST","body":{"q":"{{key}}"},"headers":{"X-Request":"keep"}}""",
                loginUrl = """function login() { source.putLoginHeader('{"Authorization":"Bearer fresh"}'); }""",
                loginCheckJs = "if (result.code() === 401) { source.login(); java.getStrResponse(); } else result;",
            )
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(books())
            assertEquals("样例", engine(http()).search(source, "样例").items.single().title)
            val initial = server.takeRequest()
            val renewed = server.takeRequest()
            for (request in listOf(initial, renewed)) {
                assertEquals("/search", request.path)
                assertEquals("POST", request.method)
                assertEquals("""{"q":"样例"}""", request.body.readUtf8())
                assertEquals("keep", request.getHeader("X-Request"))
            }
            assertNull(initial.getHeader("Authorization"))
            assertEquals("Bearer fresh", renewed.getHeader("Authorization"))
        }
    }

    @Test
    fun `WebView 结果会执行登录校验并再次渲染`() = runBlocking {
        val requests = mutableListOf<Map<String, String>>()
        val base = "https://example.org"
        val renderer = object : PageRenderer {
            override suspend fun render(url: String, headers: Map<String, String>, userAgent: String?) =
                error("需要带来源会话渲染")

            override suspend fun renderForSource(
                sessionId: String?,
                url: String,
                headers: Map<String, String>,
                userAgent: String?,
                credentialHeaders: Map<String, String>,
            ): FetchedPage {
                requests += credentialHeaders
                val body = if (credentialHeaders["Authorization"] == "Bearer fresh") {
                    """{"items":[{"title":"样例","url":"/book"}]}"""
                } else """{"loginRequired":true}"""
                return FetchedPage(url, 200, body, "UTF-8")
            }
        }
        val engine = BookSourceEngine(http(), scriptRuntime = RhinoScriptRuntime(), renderer = renderer)
        val source = fixture(base).copy(
            searchUrl = """/search,{"webView":true}""",
            loginUrl = """function login() { source.putLoginHeader('{"Authorization":"Bearer fresh"}'); }""",
            loginCheckJs = "if (JSON.parse(result.body()).loginRequired) { source.login(); java.getStrResponse(); } else result;",
        )
        assertEquals("样例", engine.search(source, "样例").items.single().title)
        assertEquals(listOf(emptyMap(), mapOf("Authorization" to "Bearer fresh")), requests)
    }

    @Test
    fun `原生请求和脚本更新或删除已授权的网页 Cookie`() = runBlocking {
        MockWebServer().use { server ->
            val url = server.url("/session")
            val sessionId = "fixture"
            val browser = mutableMapOf("session" to "initial")
            val http = SourceHttpClient(
                allowPrivateNetwork = true,
                webCookies = { _, _ -> browser.entries.joinToString("; ") { "${it.key}=${it.value}" } },
                updateWebCookies = { _, _, cookies: List<Cookie> ->
                    for (cookie in cookies) {
                        if (cookie.expiresAt <= System.currentTimeMillis()) browser.remove(cookie.name)
                        else browser[cookie.name] = cookie.value
                    }
                },
                clearWebCookies = { _, _ -> browser.clear() },
            )
            server.enqueue(MockResponse().setHeader("Set-Cookie", "session=renewed; Path=/"))
            server.enqueue(MockResponse().setHeader("Set-Cookie", "session=; Path=/; Max-Age=0"))
            http.fetch(url.toString(), sourceId = sessionId)
            assertEquals("session=initial", server.takeRequest().getHeader("Cookie"))
            assertEquals("session=renewed", http.cookieOf(url.toString(), sessionId))
            http.fetch(url.toString(), sourceId = sessionId)
            assertEquals("session=renewed", server.takeRequest().getHeader("Cookie"))
            assertEquals("", http.cookieOf(url.toString(), sessionId))
            http.setCookie(url.toString(), "session=script", sessionId)
            assertEquals("script", browser["session"])
            http.removeCookie(url.toString(), sessionId)
            assertEquals("", http.cookieOf(url.toString(), sessionId))
        }
    }

    @Test
    fun `只允许网页登录入口或来源站点作为回跳地址`() {
        val start = "https://auth.example.org/sign-in".toHttpUrl()
        val source = fixture("https://www.example.org").copy(loginUrl = start.toString())
        assertTrue(source.allowsLoginReturn(start, "https://www.example.org/callback".toHttpUrl()))
        assertTrue(source.allowsLoginReturn(start, "https://auth.example.org/done".toHttpUrl()))
        assertFalse(source.allowsLoginReturn(start, "https://other.example.org/callback".toHttpUrl()))
        assertFalse(source.allowsLoginReturn(start, "https://www.example.org:8443/callback".toHttpUrl()))
        assertFalse(source.allowsLoginReturn(start, "http://www.example.org/callback".toHttpUrl()))
    }

    @Test
    fun `带登录校验的来源保留 HTTP 限流等待时间`(): Unit = runBlocking {
        MockWebServer().use { server ->
            val source = fixture(server.url("/").toString().trimEnd('/')).copy(
                loginUrl = "function login() {}",
                loginCheckJs = "throw '不应在限流时执行登录校验';",
            )
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "180"))
            val error = assertFailsWith<SourceHttpClient.HttpStatusException> {
                engine(http()).search(source, "样例")
            }
            assertEquals(429, error.code)
            assertEquals("180", error.retryAfter)
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `同一站点按注册域名认`() {
        val base = "https://www.a.com".toHttpUrl()
        assertTrue(base.sharesSiteWith("https://api.a.com/search".toHttpUrl()))
        assertTrue(base.sharesSiteWith("https://a.com/".toHttpUrl()))
        assertFalse(base.sharesSiteWith("https://a.com.evil.net/".toHttpUrl()))
        // https 登录的凭据不降级发给 http
        assertFalse(base.sharesSiteWith("http://api.a.com/".toHttpUrl()))
        // 公共后缀底下各家是各家
        assertFalse("https://a.com.cn".toHttpUrl().sharesSiteWith("https://b.com.cn/".toHttpUrl()))
        // 没有注册域名的（IP、localhost）只认同一个源
        assertTrue("http://127.0.0.1:1".toHttpUrl().sharesSiteWith("http://127.0.0.1:1/x".toHttpUrl()))
        assertFalse("http://127.0.0.1:1".toHttpUrl().sharesSiteWith("http://127.0.0.1:2/".toHttpUrl()))
    }

    @Test
    fun `登录凭据发给同一站点的子域名，不发给别家站`() = runBlocking {
        // 主页 www、接口 api 是常见的分法：从前只认主页那一个源，api 上的请求拿不到 token，登录了也搜不到。
        // 走渲染器看每个请求拿到的凭据（不连网：SourceHttpClient 用自己的 DNS，测试里没法把几个域名指到本机）
        val sent = mutableMapOf<String, Map<String, String>>()
        val renderer = object : PageRenderer {
            override suspend fun render(url: String, headers: Map<String, String>, userAgent: String?) =
                error("需要带来源会话渲染")

            override suspend fun renderForSource(
                sessionId: String?,
                url: String,
                headers: Map<String, String>,
                userAgent: String?,
                credentialHeaders: Map<String, String>,
            ): FetchedPage {
                sent[url.toHttpUrl().host] = credentialHeaders
                return FetchedPage(url, 200, """{"items":[{"title":"样例","url":"/book"}]}""", "UTF-8")
            }
        }
        val sessions = Sessions()
        val engine = BookSourceEngine(http(sessions), scriptRuntime = RhinoScriptRuntime(), sessionStore = sessions, renderer = renderer)
        val source = fixture("https://www.a.com").copy(
            searchUrl = """https://api.a.com/search,{"webView":true}""",
            loginUrl = """function login() { source.putLoginHeader('{"Authorization":"Bearer fixture"}'); }""",
            ruleToc = TocRuleSet(chapterList = "$.items", chapterName = "title", chapterUrl = "url"),
        )
        engine.submitLogin(source, emptyMap())
        assertEquals("样例", engine.search(source, "样例").items.single().title)
        engine.getToc(source, """https://cdn.other.com/toc,{"webView":true}""")
        assertEquals(mapOf("Authorization" to "Bearer fixture"), sent["api.a.com"])
        assertEquals(emptyMap(), sent["cdn.other.com"])
    }

    @Test
    fun `没有登录入口的来源，脚本请求和页面请求一样带上存下的请求头`() = runBlocking {
        // 没登录入口的旧规则拿 putLoginHeader 存防盗链挑战的临时头，页面请求一直是处处带的；脚本请求从前却给剥了
        MockWebServer().use { server ->
            MockWebServer().use { other ->
                val source = fixture(server.url("/").toString().trimEnd('/')).copy(
                    searchUrl = "<js>source.putLoginHeader('{\"X-Challenge\":\"ok\"}');" +
                        " java.ajax('${other.url("/api")},' + JSON.stringify({headers: {'X-Challenge': 'ok'}})); '/search'</js>",
                )
                server.enqueue(books())
                other.enqueue(MockResponse().setBody("ok"))
                assertEquals("样例", engine(http()).search(source, "样例").items.single().title)
                assertEquals("ok", other.takeRequest().getHeader("X-Challenge"))
                assertEquals("ok", server.takeRequest().getHeader("X-Challenge"))
            }
        }
    }

    @Test
    fun `脚本写的 Cookie 重启后还在，服务端下发的会话 Cookie 同阅读只留在这一次`() = runBlocking {
        // 登录脚本常用 cookie.setCookie 存登录态，阅读把它落库；没写过期时间，从前在我们这里算会话 Cookie，重启就登出了
        MockWebServer().use { server ->
            val url = server.url("/").toString()
            val sessions = Sessions()
            server.enqueue(MockResponse().setHeader("Set-Cookie", "PHPSESSID=server; Path=/"))
            http(sessions).apply {
                setCookie(url, "token=script", "s1")
                fetch(url, sourceId = "s1")
                assertEquals(setOf("token=script", "PHPSESSID=server"), cookieOf(url, "s1").split("; ").toSet())
            }
            assertEquals("token=script", http(sessions).cookieOf(url, "s1"))
        }
    }

    @Test
    fun `登录网页开着的时候，这个来源的抓取不排队等它`() = runBlocking {
        // 登录锁从前把整个网页登录（等人操作，可能几分钟）都包在里面：带登录校验的来源，离线缓存、目录全卡在锁上
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = books()
            }
            val base = server.url("/").toString().trimEnd('/')
            val source = fixture(base).copy(loginUrl = "function login() {}", loginCheckJs = "result")
            val engine = engine(http())
            val opened = kotlinx.coroutines.CompletableDeferred<Unit>()
            val closed = kotlinx.coroutines.CompletableDeferred<StrResponse?>()
            val browser = object : SourceLoginInteraction {
                override fun message(text: String) = Unit
                override fun updateFields(values: Map<String, String>) = Unit
                override suspend fun openBrowser(url: String, title: String): StrResponse? {
                    opened.complete(Unit)
                    return closed.await()
                }
            }
            val login = launch(kotlinx.coroutines.Dispatchers.Default) {
                engine.submitLogin(source, emptyMap(), action = "$base/sign-in", interaction = browser)
            }
            opened.await()
            val found = kotlinx.coroutines.withTimeoutOrNull(5_000) { engine.search(source, "样例") }
            closed.complete(null)
            login.join()
            assertEquals("样例", found?.items?.single()?.title)
        }
    }

    private fun fixture(base: String) = BookSourceRule(
        bookSourceUrl = base, bookSourceName = "登录来源", searchUrl = "/search",
        ruleSearch = SearchRuleSet(bookList = "$.items", name = "title", bookUrl = "url"),
    )

    private fun books() = MockResponse().setHeader("Content-Type", "application/json")
        .setBody("""{"items":[{"title":"样例","url":"/book"}]}""")
}
