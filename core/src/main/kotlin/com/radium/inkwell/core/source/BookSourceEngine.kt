package com.radium.inkwell.core.source

import com.radium.inkwell.core.model.ContentElement
import com.radium.inkwell.core.parser.html.HtmlToElements
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup

/** 书源业务错误（规则未配置/规则不匹配等） */
class SourceException(message: String, cause: Throwable? = null) : Exception(message, cause)

// ---- 结果模型 ----

/** 可序列化：预览页需要整条带过去，详情页解析不出的字段（书名/作者/封面）由它回落 */
@kotlinx.serialization.Serializable
data class SearchResult(
    val title: String,
    val bookUrl: String,
    val author: String? = null,
    val coverUrl: String? = null,
    val intro: String? = null,
    val latestChapter: String? = null,
    val sourceId: String,
    /** 书源名称。只有 id（其实是书源网址）的话，换源列表里满屏都是域名，没人认得出哪个是哪个 */
    val sourceName: String = "",
)

data class SearchPage(
    val items: List<SearchResult>,
    val hasMore: Boolean,
)

data class RemoteBookDetail(
    val title: String,
    val author: String?,
    val coverUrl: String?,
    val intro: String?,
    val tocUrl: String,
)

data class RemoteChapter(
    val index: Int,
    val title: String,
    val url: String,
    /** 目录规则用 @put/java.put 存下的变量（JSON）；正文规则会用 @get 取 */
    val variable: String = "",
)

/** 正文；图片元素的 resourceId 为绝对 URL */
data class RemoteChapterContent(
    val elements: List<ContentElement>,
)

// ---- 调试插桩 ----

data class FetchTrace(
    val requestUrl: String,
    val method: String,
    val statusCode: Int,
    val finalUrl: String,
    val detectedCharset: String,
    val bodyPreview: String,
)

data class RuleTrace(
    val stage: String,
    val fieldName: String,
    val rule: String,
    val output: String?,
    val error: String? = null,
)

interface RuleTraceCollector {
    fun onFetch(trace: FetchTrace)
    fun onRule(trace: RuleTrace)
}

// ---- 引擎 ----

class BookSourceEngine(
    private val http: SourceHttpClient,
    /**
     * 用户自定义的全局净化规则。取的是**函数**不是列表：引擎是单例，规则随时可改，
     * 拿一份构造期的快照就永远读不到用户新加的规则。入参是当前书源，供按作用域筛选。
     */
    private val globalPurify: (BookSourceRule) -> List<PurifyRule> = { emptyList() },
    private val trace: RuleTraceCollector? = null,
    scriptRuntime: com.radium.inkwell.core.source.js.ScriptRuntime? = null,
    /** JS 渲染回退；未注入时行为与从前完全一致 */
    private val renderer: PageRenderer? = null,
) {

    private val evaluator = RuleEvaluator(
        scriptRuntime,
        jsHttp = com.radium.inkwell.core.source.js.EngineJsHttp(http),
    )

    /** 已确认需要 JS 渲染的书源；后续请求直接走渲染器，省掉每次先静态空跑一遍 */
    private val needsRender = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    // 公开入口一律在 IO 线程自我确权（withContext(Dispatchers.IO)）：底层脚本 HTTP 出口
    // （EngineJsHttp）用 runBlocking 同步等网络，若调用方恰好在主线程发起就会 ANR。这样无论
    // 调用方在不在主线程都安全，且不必改 app/reader 的调用点。withContext(IO) 内再 runBlocking
    // 阻塞的是 IO 线程池里的工作线程、而非同一线程，不会自锁。
    suspend fun search(source: BookSourceRule, keyword: String, page: Int = 1): SearchPage =
        withContext(Dispatchers.IO) {
            val searchUrl = source.searchUrl?.takeIf { it.isNotBlank() }
                ?: throw SourceException("书源「${source.name}」未配置搜索规则")
            val rule = source.ruleSearch ?: throw SourceException("书源「${source.name}」未配置搜索规则")
            val vars = varsOf(source, "key" to keyword, "keyword" to keyword, "page" to page.toString())
            val fetched = fetchByRequest(source, searchUrl, vars, "search")
            parseListPage(
                source, "search", fetched, vars, rule.bookList.orEmpty(), listFieldMap(rule), nextPageRule = null,
                pageable = pageable(searchUrl),
            )
        }

    /**
     * 地址模板里有 page 变量才谈得上翻页 —— 没有它，请求「第 2 页」拿回来的还是第 1 页。
     * Legado 的搜索/发现没有独立的 nextPage 规则，全靠 searchUrl/exploreUrl 里的 {{page}} 重发请求。
     */
    private fun pageable(vararg templates: String?): Boolean =
        templates.any { it != null && PAGE_VAR.containsMatchIn(it) }

    suspend fun explore(source: BookSourceRule, exploreIndex: Int, page: Int = 1): SearchPage =
        withContext(Dispatchers.IO) {
            val item = source.explore.getOrNull(exploreIndex)
                ?: throw SourceException("书源「${source.name}」发现页下标越界: $exploreIndex")
            // Legado：发现页列表/字段规则缺省时复用搜索规则
            val rule = source.ruleExplore ?: source.ruleSearch
                ?: throw SourceException("书源「${source.name}」未配置发现规则")
            val vars = varsOf(source, "key" to "", "keyword" to "", "page" to page.toString())
            val fetched = fetchByRequest(source, item.url, vars, "explore")
            parseListPage(
                source, "explore", fetched, vars, rule.bookList.orEmpty(), listFieldMap(rule), nextPageRule = null,
                pageable = pageable(item.url),
            )
        }

    /** 搜索/发现列表项的字段规则 → 引擎内部字段名 */
    private fun listFieldMap(r: SearchRuleSet): Map<String, String> = buildMap {
        r.name?.let { put("title", it) }
        r.bookUrl?.let { put("bookUrl", it) }
        r.author?.let { put("author", it) }
        r.coverUrl?.let { put("coverUrl", it) }
        r.intro?.let { put("intro", it) }
        r.lastChapter?.let { put("latestChapter", it) }
    }

    suspend fun getDetail(source: BookSourceRule, bookUrl: String): RemoteBookDetail =
        withContext(Dispatchers.IO) {
            val url = resolveUrl(source.baseUrl, bookUrl)
            // 详情页解析不出来也不判死：书名/作者在搜索结果里早就拿到了，详情页只是「有就覆盖」。
            // 真正决定这本书能不能读的是目录 —— 让下一步去报错，那才是有信息量的错误。
            // Legado 里 ruleBookInfo 可缺省（详情页即目录页），缺省时直接用详情页地址当目录地址。
            withRenderFallback(source) { render -> parseDetail(source, url, render) }
                ?: RemoteBookDetail(title = "", author = null, coverUrl = null, intro = null, tocUrl = url)
        }

    private suspend fun parseDetail(
        source: BookSourceRule,
        url: String,
        render: Boolean,
    ): RemoteBookDetail? {
        val info = source.ruleBookInfo
        if (info == null) {
            // 无详情规则：详情页即目录页，不抓取，直接把地址透传给目录阶段
            return RemoteBookDetail(title = "", author = null, coverUrl = null, intro = null, tocUrl = url)
        }
        val fetched = fetchPage(source, url, "detail", render)
        val ctx = pageContext(fetched, varsOf(source), jsContextOf(source, bookUrl = url))
        val title = evalField("detail", "title", info.name, ctx)
        val author = evalField("detail", "author", info.author, ctx)
        val cover = evalUrlField("detail", "coverUrl", info.coverUrl, ctx)
        val intro = evalField("detail", "intro", info.intro, ctx)
        val tocRule = evalUrlField("detail", "tocUrl", info.tocUrl, ctx)?.takeIf { it.isNotBlank() }

        // 一个字段都没匹配上 → 这一页大概率是 JS 渲染的，返回 null 交给渲染兜底重试。
        //
        // 判据不能是「书名没匹配到」。书名在搜索结果里早就拿到了，详情页的书名规则
        // 只是「有就覆盖」—— Legado 从不因此判错。我们从前拿它当「页面没解析出来」的信号，
        // 于是站点改版、或书名规则只对 PC 版页面有效这类小事，都会让整个书源被判死。
        // 校验里那一大片「详情页未匹配到书名」就是这么来的，而它们在阅读 APP 里好好的。
        val nothingMatched = title.isNullOrBlank() && author.isNullOrBlank() &&
            cover.isNullOrBlank() && intro.isNullOrBlank() && tocRule == null
        val anyRuleConfigured = listOf(info.name, info.author, info.coverUrl, info.intro, info.tocUrl)
            .any { !it.isNullOrBlank() }
        if (nothingMatched && anyRuleConfigured) return null

        val tocUrl = tocRule?.let { resolveUrl(fetched.finalUrl, it) }
            ?: fetched.finalUrl // 缺省：详情页即目录页
        return RemoteBookDetail(
            title = title.orEmpty(), // 可能为空 → 调用方回落到搜索结果的书名
            author = author,
            coverUrl = cover?.let { resolveUrl(fetched.finalUrl, it) },
            intro = intro,
            tocUrl = tocUrl,
        )
    }

    suspend fun getToc(source: BookSourceRule, tocUrl: String): List<RemoteChapter> =
        withContext(Dispatchers.IO) {
            val rule = source.ruleToc ?: throw SourceException("书源「${source.name}」未配置目录规则")
            withRenderFallback(source) { render -> collectToc(source, rule, tocUrl, render) }
                ?: throw SourceException("目录规则未匹配到章节: $tocUrl")
        }

    private suspend fun collectToc(
        source: BookSourceRule,
        rule: TocRuleSet,
        tocUrl: String,
        render: Boolean,
    ): List<RemoteChapter>? {
        // Legado：chapterList 前导 `-` 表示整体倒序
        val listRaw = rule.chapterList.orEmpty().trim()
        val reverse = listRaw.startsWith("-")
        val listRule = if (reverse) listRaw.substring(1) else listRaw
        val collected = mutableListOf<Triple<String, String, String>>()
        var url: String? = resolveUrl(source.baseUrl, tocUrl)
        val visited = HashSet<String>()
        var pages = 0
        // nextPage 循环：上限 + visited 防环
        while (url != null && pages < MAX_TOC_PAGES && visited.add(url)) {
            pages++
            val fetched = fetchPage(source, url, "toc", render)
            val ctx = pageContext(fetched, varsOf(source), jsContextOf(source, tocUrl = url))
            for (item in evalList("toc", listRule, ctx)) {
                // 每个章节项一份变量表：@put 存的参数属于这一章，不能串到别的章节上
                val itemCtx = item.copy(js = item.js.copy(scriptVars = java.util.concurrent.ConcurrentHashMap()))
                val title = evalField("toc", "title", rule.chapterName, itemCtx)
                val chapUrl = evalUrlField("toc", "url", rule.chapterUrl, itemCtx)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { resolveUrl(fetched.finalUrl, it) }
                if (!title.isNullOrBlank() && !chapUrl.isNullOrBlank()) {
                    collected += Triple(title, chapUrl, encodeVars(itemCtx.js.scriptVars))
                }
            }
            url = rule.nextTocUrl
                ?.let { evalUrlField("toc", "nextPage", it, ctx) }
                ?.takeIf { it.isNotBlank() }
                ?.let { resolveUrl(fetched.finalUrl, it) }
        }
        if (collected.isEmpty()) return null
        val ordered = if (reverse) collected.asReversed() else collected
        return ordered.mapIndexed { i, (title, u, vars) -> RemoteChapter(i, title, u, vars) }
    }

    /**
     * @param otherChapterUrls 本书其余章节的地址。正文分页会跟着 nextPage 一直翻，而不少站点把
     * 分页按钮也标成「下一章」（如仓库看书网 874007_2.html → 874007_3.html → 874008.html），
     * 一路跟下去会把后面十几章吞进当前章。撞上目录里的其他章节即停。
     */
    /**
     * @param chapterVariable 目录阶段存下的变量（[RemoteChapter.variable]）。正文规则里的
     * `@get:{k}` / `java.get(k)` 要取得到它 —— 不传这条链路就是断的。
     */
    suspend fun getContent(
        source: BookSourceRule,
        chapterUrl: String,
        otherChapterUrls: Set<String> = emptySet(),
        chapterVariable: String = "",
    ): RemoteChapterContent = withContext(Dispatchers.IO) {
        val rule = source.ruleContent ?: throw SourceException("书源「${source.name}」未配置正文规则")
        withRenderFallback(source) { render ->
            collectContent(source, rule, chapterUrl, otherChapterUrls, chapterVariable, render)
        } ?: throw SourceException("正文规则未匹配到内容: $chapterUrl")
    }

    /** 变量表 ↔ JSON（随章节一起落库，见 ChapterEntity.variable） */
    private fun encodeVars(vars: Map<String, String>): String =
        if (vars.isEmpty()) "" else VARS_JSON.encodeToString(vars.toMap())

    private fun decodeVars(json: String): MutableMap<String, String> {
        if (json.isBlank()) return java.util.concurrent.ConcurrentHashMap()
        val map = runCatching { VARS_JSON.decodeFromString<Map<String, String>>(json) }
            .getOrElse { emptyMap() }
        return java.util.concurrent.ConcurrentHashMap(map)
    }

    private suspend fun collectContent(
        source: BookSourceRule,
        rule: ContentRuleSet,
        chapterUrl: String,
        otherChapterUrls: Set<String>,
        chapterVariable: String,
        render: Boolean,
    ): RemoteChapterContent? {
        // 净化：源级(replaceRegex) 在前、用户的通用规则在后；正则预编译
        val sourcePurifier = Purifier.strict(parseReplaceRegex(rule.replaceRegex))
        val userPurifier = Purifier.lenient(globalPurify(source))
        val elements = mutableListOf<ContentElement>()
        val firstUrl = resolveUrl(source.baseUrl, chapterUrl)
        val stopAt = otherChapterUrls.filterNotTo(HashSet()) { it == firstUrl }
        var url: String? = firstUrl
        val visited = HashSet<String>()
        var pages = 0
        while (url != null && pages < MAX_CONTENT_PAGES && visited.add(url)) {
            pages++
            val fetched = fetchPage(source, url, "content", render)
            // 目录阶段存下的变量在这里喂回脚本：正文规则的 @get:{k} / java.get(k) 靠它
            val js = jsContextOf(source, chapterUrl = url)
                .copy(scriptVars = decodeVars(chapterVariable))
            val ctx = pageContext(fetched, varsOf(source), js)
            val html = evalField("content", "content", rule.content, ctx)
            if (html != null) {
                // 每页单独转换，图片相对路径按该页最终 URL 解析为绝对 URL
                val body = Jsoup.parseBodyFragment(html, fetched.finalUrl).body()
                val converter = HtmlToElements(resolveImage = { img ->
                    img.attr("abs:src")
                        .ifBlank { resolveUrl(fetched.finalUrl, img.attr("src")) }
                        .ifBlank { null }
                })
                elements += converter.convert(body)
            }
            url = rule.nextContentUrl
                ?.let { evalUrlField("content", "nextPage", it, ctx) }
                ?.takeIf { it.isNotBlank() }
                ?.let { resolveUrl(fetched.finalUrl, it) }
                ?.takeIf { it !in stopAt } // 翻到别的章节了 → 本章结束
        }
        return userPurifier.apply(sourcePurifier.apply(elements)).takeIf { it.isNotEmpty() }?.let { RemoteChapterContent(it) }
    }

    // ---- JS 渲染回退 ----

    /**
     * 先按已知模式抓一次；解析为空且注入了渲染器时，用 WebView 执行页面 JS 后重试一次。
     * 一旦确认某书源需要渲染就记住，后续请求直接走渲染器，不再每次先静态空跑。
     *
     * 只用于 detail/toc/content —— 这三级都是用户点开某一本书触发的，慢一点可以接受。
     * 搜索/发现会向上百个书源并发扇出，其中大量是已死站点，逐个渲染会把整次搜索拖垮。
     */
    private suspend fun <T : Any> withRenderFallback(
        source: BookSourceRule,
        attempt: suspend (render: Boolean) -> T?,
    ): T? {
        val known = source.id in needsRender
        attempt(known)?.let { return it }
        if (known || renderer == null) return null
        return attempt(true)?.also { needsRender += source.id }
    }

    /** render=true 时走 JS 渲染；渲染器缺席或渲染失败时静默退回静态抓取 */
    private suspend fun fetchPage(
        source: BookSourceRule,
        url: String,
        stage: String,
        render: Boolean,
    ): FetchedPage {
        if (render && renderer != null) {
            // 源级 header 模板（{{baseUrl}} 等）要用真实变量展开，否则防盗链站拿到空 Referer 会 403
            val headers = mergeHeaders(source.headers, emptyMap(), varsOf(source))
            // UA 必须与静态抓取一致：站点常按 UA 给不同 DOM，规则是照静态那份写的
            val ua = headers.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.value
                ?: SourceHttpClient.DEFAULT_UA
            renderer.render(url, headers, ua)?.let { page ->
                trace?.onFetch(
                    FetchTrace(url, "RENDER", page.statusCode, page.finalUrl,
                        page.detectedCharset, page.bodyText.take(500))
                )
                return page
            }
        }
        return fetchUrl(source, url, stage)
    }

    // ---- 内部 ----

    private fun varsOf(source: BookSourceRule, vararg extra: Pair<String, String>): Map<String, String> =
        mapOf("baseUrl" to source.baseUrl, *extra)

    private suspend fun fetchByRequest(
        source: BookSourceRule,
        urlTemplate: String,
        vars: Map<String, String>,
        stage: String,
    ): FetchedPage {
        // 地址里的 {{}} 同样按 Legado 语义展开（JS 表达式 / 嵌套规则），不只是变量替换
        val urlCtx = urlContext(source, vars)

        // 地址整串可能是 JS（<js>…</js> / @js:…），脚本的返回值才是真地址；
        // 而且地址常自带 ,{method/body/charset/headers} —— 拿到最终地址后才谈得上解析。
        var rawUrl = evaluator.evalUrlJs(urlTemplate, urlCtx) ?: urlTemplate
        var method = "GET"
        var body: String? = null
        var headers: Map<String, String> = emptyMap()
        var charset: String? = null
        splitUrlOptions(rawUrl)?.let { (bare, opt) ->
            rawUrl = bare
            opt.method?.let { method = it }
            opt.body?.let { body = it }
            opt.charset?.let { charset = it }
            if (opt.headers.isNotEmpty()) headers = opt.headers
        }
        // 非 UTF-8 站点的关键词要按站点编码转义。Legado 地址里关键词写作 {{key}}，
        // 编码只有拿到 charset 选项后才知道，补上 encode 管道。
        charset?.lowercase()?.takeIf { it != "utf-8" && it != "utf8" }?.let { cs ->
            rawUrl = rawUrl.replace("{{key}}", "{{key|encode:$cs}}")
            body = body?.replace("{{key}}", "{{key|encode:$cs}}")
        }

        val url = resolveUrl(source.baseUrl, evaluator.expandTemplate(rawUrl, urlCtx))
        return doFetch(
            stage = stage,
            url = url,
            method = method,
            body = body?.let { evaluator.expandTemplate(it, urlCtx) },
            headers = mergeHeaders(source.headers, headers, vars),
            charset = charset ?: source.charset,
            rateLimit = source.rateLimit,
        )
    }

    /**
     * detail/toc/content 阶段的抓取。从前恒 GET、且把 header 模板用 emptyMap() 展开：
     * - 规则/JS 拼出的地址常自带 `,{method/body/charset/headers}` 选项（尤其翻页、POST 目录接口），
     *   要用 [splitUrlOptions] 解析并透传，否则 method/body/charset 全丢、请求方式错到底。
     * - 源级 header 里的 `{{baseUrl}}` 在这三级要展开成真实值（fix：传 varsOf(source)），否则 403。
     */
    private suspend fun fetchUrl(source: BookSourceRule, url: String, stage: String): FetchedPage {
        var target = url
        var method = "GET"
        var body: String? = null
        var charset: String? = source.charset
        var headers: Map<String, String> = emptyMap()
        splitUrlOptions(url)?.let { (bare, opt) ->
            target = bare
            opt.method?.let { method = it }
            opt.body?.let { body = it }
            opt.charset?.let { charset = it }
            if (opt.headers.isNotEmpty()) headers = opt.headers
        }
        val vars = varsOf(source)
        return doFetch(
            stage = stage,
            url = target,
            method = method,
            body = body?.let { expandTemplate(it, vars) },
            headers = mergeHeaders(source.headers, headers, vars),
            charset = charset,
            rateLimit = source.rateLimit,
        )
    }

    private suspend fun doFetch(
        stage: String,
        url: String,
        method: String,
        body: String?,
        headers: Map<String, String>,
        charset: String?,
        rateLimit: RateLimitRule?,
    ): FetchedPage {
        val page = http.fetch(url, method, body, headers, charset, rateLimit)
        trace?.onFetch(
            FetchTrace(
                requestUrl = url,
                method = method.uppercase(),
                statusCode = page.statusCode,
                finalUrl = page.finalUrl,
                detectedCharset = page.detectedCharset,
                bodyPreview = page.bodyText.take(500),
            )
        )
        return page
    }

    private fun mergeHeaders(
        sourceHeaders: Map<String, String>,
        requestHeaders: Map<String, String>,
        vars: Map<String, String>,
    ): Map<String, String> =
        (sourceHeaders + requestHeaders).mapValues { (_, v) -> expandTemplate(v, vars) }

    /** 页面上下文：HTML 与原始文本（JSON 规则懒解析）同时可用 */
    private fun pageContext(
        fetched: FetchedPage,
        vars: Map<String, String>,
        js: JsContext = JsContext(),
    ): EvalContext =
        EvalContext(
            element = Jsoup.parse(fetched.bodyText, fetched.finalUrl),
            json = fetched.bodyText,
            baseUrl = fetched.finalUrl,
            vars = vars,
            js = js,
        )

    /** 脚本上下文：书源标识 + 当前书/章节。scriptVars 每条抓取链路一份，供 java.put/get 传值 */
    /** 展开地址模板用的上下文：没有页面，只有变量与脚本桥 */
    private fun urlContext(source: BookSourceRule, vars: Map<String, String>) = EvalContext(
        element = null,
        json = null,
        baseUrl = source.baseUrl,
        vars = vars,
        js = jsContextOf(source),
    )

    private fun jsContextOf(
        source: BookSourceRule,
        bookUrl: String = "",
        tocUrl: String = "",
        chapterUrl: String = "",
    ) = JsContext(
        sourceKey = source.id,
        book = com.radium.inkwell.core.source.js.BookBridge(
            bookUrl = bookUrl,
            tocUrl = tocUrl,
        ),
        chapter = com.radium.inkwell.core.source.js.ChapterBridge(url = chapterUrl),
    )

    private fun parseListPage(
        source: BookSourceRule,
        stage: String,
        fetched: FetchedPage,
        vars: Map<String, String>,
        listRule: String,
        fields: Map<String, String>,
        nextPageRule: String?,
        pageable: Boolean = false,
    ): SearchPage {
        val ctx = pageContext(fetched, vars, jsContextOf(source))
        val items = evalList(stage, listRule, ctx).mapNotNull { itemCtx ->
            val title = evalField(stage, "title", fields["title"], itemCtx)
            val bookUrl = evalUrlField(stage, "bookUrl", fields["bookUrl"], itemCtx)
                ?.takeIf { it.isNotBlank() }
                ?.let { resolveUrl(fetched.finalUrl, it) }
            if (title.isNullOrBlank() || bookUrl.isNullOrBlank()) return@mapNotNull null
            SearchResult(
                title = title,
                bookUrl = bookUrl,
                author = evalField(stage, "author", fields["author"], itemCtx),
                coverUrl = evalUrlField(stage, "coverUrl", fields["coverUrl"], itemCtx)
                    ?.let { resolveUrl(fetched.finalUrl, it) },
                intro = evalField(stage, "intro", fields["intro"], itemCtx),
                latestChapter = evalField(stage, "latestChapter", fields["latestChapter"], itemCtx),
                sourceId = source.id,
                sourceName = source.name,
            )
        }
        // nextPage 规则命中即可继续；书源没有该规则时，只要地址模板带 page 且这一页有结果，
        // 就还能往下翻（Legado 的搜索/发现正是这样分页的）
        val hasNextRule = nextPageRule != null &&
            !evalUrlField(stage, "nextPage", nextPageRule, ctx).isNullOrBlank()
        return SearchPage(items, hasNextRule || (pageable && items.isNotEmpty()))
    }

    private fun evalList(stage: String, rule: String, ctx: EvalContext): List<EvalContext> =
        try {
            val nodes = evaluator.evalToNodes(LegadoRuleAnalyzer.analyze(rule), ctx)
            trace?.onRule(RuleTrace(stage, "list", rule, "${nodes.size} 个节点"))
            nodes
        } catch (e: Exception) {
            trace?.onRule(RuleTrace(stage, "list", rule, null, e.message))
            throw e
        }

    /**
     * URL 字段求值：只取首个非空匹配。
     * 文本字段多匹配时换行拼接是合理的（如多段简介），但地址字段绝不能拼 ——
     * 页面上下两处导航常各有一个「下一章」，拼起来会得到一个必然 404 的废地址。
     */
    private fun evalUrlField(stage: String, name: String, rule: String?, ctx: EvalContext): String? {
        if (rule.isNullOrBlank()) return null
        return try {
            val value = evaluator.evalToStrings(LegadoRuleAnalyzer.analyze(rule), ctx)
                .firstOrNull { it.isNotBlank() }
            trace?.onRule(RuleTrace(stage, name, rule, value?.take(200)))
            value
        } catch (e: Exception) {
            trace?.onRule(RuleTrace(stage, name, rule, null, e.message))
            throw e
        }
    }

    /** 单字段求值；规则缺失返回 null，求值异常上报 trace 后原样抛出 */
    private fun evalField(stage: String, name: String, rule: String?, ctx: EvalContext): String? {
        if (rule.isNullOrBlank()) return null
        return try {
            val value = evaluator.evalToString(LegadoRuleAnalyzer.analyze(rule), ctx)
            trace?.onRule(RuleTrace(stage, name, rule, value?.take(200)))
            value
        } catch (e: Exception) {
            trace?.onRule(RuleTrace(stage, name, rule, null, e.message))
            throw e
        }
    }

    private companion object {
        val VARS_JSON = kotlinx.serialization.json.Json
        val PAGE_VAR = Regex("\\{\\{[^}]*page[^}]*\\}\\}")
        const val MAX_TOC_PAGES = 200
        const val MAX_CONTENT_PAGES = 50
    }
}

/**
 * Legado replaceRegex：多行 `##正则##替换`（或 `正则##替换`），逐行解析为净化规则。
 * 含 JS 的整块忽略（我们的净化不跑脚本）。非法正则由 [Purifier] 兜底跳过。
 */
internal fun parseReplaceRegex(raw: String?): List<PurifyRule> {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty() || text.contains("<js>", ignoreCase = true) || text.contains("@js:", ignoreCase = true)) {
        return emptyList()
    }
    return text.lines().mapNotNull { line ->
        val t = line.trim().removePrefix("##")
        if (t.isEmpty()) return@mapNotNull null
        val sep = t.indexOf("##")
        val pattern = if (sep < 0) t else t.substring(0, sep)
        val replacement = if (sep < 0) "" else t.substring(sep + 2).removeSuffix("###")
        if (pattern.isBlank()) null else PurifyRule(pattern = pattern, replacement = replacement, isRegex = true)
    }
}

/**
 * 相对 → 绝对 URL 解析；base 非法或无法解析时原样返回。
 *
 * 地址尾部的 `,{…}` 选项后缀（method/body/charset/headers）**原样保留**：这里只解析地址主体，
 * 解析完把后缀接回去，留到 detail/toc/content 抓取时才由 [splitUrlOptions] 解析。从前在这里直接
 * 剥掉，于是这三级拿不到 POST/body/charset，用 JS 拼目录地址、POST 目录接口的书源全默默降级成 GET。
 */
internal fun resolveUrl(base: String, ref: String): String {
    val trimmed = ref.trim()
    if (trimmed.isEmpty()) return trimmed
    val optAt = optionsIndex(trimmed)
    val bare = (if (optAt >= 0) trimmed.substring(0, optAt) else trimmed).trim()
    val suffix = if (optAt >= 0) trimmed.substring(optAt) else ""
    val resolved = when {
        bare.isEmpty() -> bare
        bare.startsWith("http://") || bare.startsWith("https://") -> bare
        else -> base.toHttpUrlOrNull()?.resolve(bare)?.toString() ?: bare
    }
    return resolved + suffix
}

/** Legado 的地址选项后缀：`url,{"method":"POST","body":"…"}`（常用单引号） */
internal data class UrlOptions(
    val method: String? = null,
    val body: String? = null,
    val charset: String? = null,
    val headers: Map<String, String> = emptyMap(),
)

private val optionsJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * 找到地址里真正开启 `,{…}` 选项块的下标，找不到返回 -1。
 *
 * 不能见到第一个 `,{` 就认 —— 那个 `,{` 可能在 JS 字符串**里面**：
 * `@js:url="https://m.wcxsw.org/search.php,{'body':'…'}"`。所以逐个候选位置试着把 `{…}` 解析成
 * JSON（含单引号→双引号回退），能解析出来的才是真选项。从前 stripUrlOptions 用裸 `indexOf(",{")`
 * 不验证，把地址从中间截断成 `@js:url="https://m.wcxsw.o`，请求直接 403。
 */
private fun optionsIndex(url: String): Int {
    var i = url.indexOf(",{")
    while (i > 0) {
        val text = url.substring(i + 1)
        val ok = runCatching { optionsJson.parseToJsonElement(text).jsonObject }.getOrNull() != null ||
            runCatching { optionsJson.parseToJsonElement(text.replace('\'', '"')).jsonObject }.getOrNull() != null
        if (ok) return i
        i = url.indexOf(",{", i + 1)
    }
    return -1
}

/** 切出地址尾部的 `,{…}` 选项；无有效选项返回 null。 */
internal fun splitUrlOptions(url: String): Pair<String, UrlOptions>? {
    val i = optionsIndex(url)
    if (i < 0) return null
    val text = url.substring(i + 1)
    val obj = (runCatching { optionsJson.parseToJsonElement(text).jsonObject }.getOrNull()
        ?: runCatching { optionsJson.parseToJsonElement(text.replace('\'', '"')).jsonObject }.getOrNull())
        ?: return null
    fun str(k: String) = (obj[k] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
    val hdr = (obj["headers"] as? kotlinx.serialization.json.JsonObject)
        ?.mapValues { (_, v) -> (v as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull.orEmpty() }
        ?.filterValues { it.isNotBlank() }
        .orEmpty()
    return url.substring(0, i).trim() to UrlOptions(
        method = str("method")?.uppercase(),
        body = str("body"),
        charset = str("charset"),
        headers = hdr,
    )
}
