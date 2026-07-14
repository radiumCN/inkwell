package com.radium.inkwell.core.source

import com.radium.inkwell.core.model.ContentElement
import com.radium.inkwell.core.parser.html.HtmlToElements
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
    private val globalPurify: List<PurifyRule> = emptyList(),
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

    suspend fun search(source: BookSourceRule, keyword: String, page: Int = 1): SearchPage {
        val rule = source.search ?: throw SourceException("书源「${source.name}」未配置搜索规则")
        val vars = varsOf(source, "keyword" to keyword, "page" to page.toString())
        val fetched = fetchByRequest(source, rule.request, vars, "search")
        return parseListPage(
            source, "search", fetched, vars, rule.list, rule.fields, rule.nextPage,
            pageable = pageable(rule.request.url, rule.request.body),
        )
    }

    /**
     * 地址模板里有 page 变量才谈得上翻页 —— 没有它，请求「第 2 页」拿回来的还是第 1 页。
     * Legado 的搜索/发现没有独立的 nextPage 规则，全靠 searchUrl/exploreUrl 里的 {{page}} 重发请求。
     */
    private fun pageable(vararg templates: String?): Boolean =
        templates.any { it != null && PAGE_VAR.containsMatchIn(it) }

    suspend fun explore(source: BookSourceRule, exploreIndex: Int, page: Int = 1): SearchPage {
        val rule = source.explore.getOrNull(exploreIndex)
            ?: throw SourceException("书源「${source.name}」发现页下标越界: $exploreIndex")
        val vars = varsOf(source, "page" to page.toString())
        val fetched = fetchByRequest(source, RequestRule(url = rule.url), vars, "explore")
        return parseListPage(
            source, "explore", fetched, vars, rule.list, rule.fields, rule.nextPage,
            pageable = pageable(rule.url),
        )
    }

    suspend fun getDetail(source: BookSourceRule, bookUrl: String): RemoteBookDetail {
        val rule = source.detail ?: throw SourceException("书源「${source.name}」未配置详情规则")
        val url = resolveUrl(source.baseUrl, bookUrl)
        return withRenderFallback(source) { render -> parseDetail(source, rule, url, render) }
            ?: throw SourceException("详情页未匹配到书名: $url")
    }

    private suspend fun parseDetail(
        source: BookSourceRule,
        rule: DetailRule,
        url: String,
        render: Boolean,
    ): RemoteBookDetail? {
        val fetched = fetchPage(source, url, "detail", render)
        val ctx = pageContext(fetched, varsOf(source), jsContextOf(source, bookUrl = url))
        val f = rule.fields
        val title = evalField("detail", "title", f["title"], ctx)
        // 书源不配书名规则是常态：JSON API 源的「详情页」往往只是目录接口，压根没有书名，
        // 书名搜索结果里已经给过了（Legado 同样是把搜索结果的书名带下来）。
        // 只有「配了书名规则却没匹配到」才说明这一页真没解析出来 —— 那时返回 null，
        // 交给 JS 渲染兜底重试。从前一律要求详情页解析出书名，把这一整类书源判了死刑。
        if (!f["title"].isNullOrBlank() && title.isNullOrBlank()) return null
        val tocUrl = evalUrlField("detail", "tocUrl", f["tocUrl"], ctx)
            ?.takeIf { it.isNotBlank() }
            ?.let { resolveUrl(fetched.finalUrl, it) }
            ?: fetched.finalUrl // 缺省：详情页即目录页
        return RemoteBookDetail(
            title = title.orEmpty(), // 可能为空 → 调用方回落到搜索结果的书名
            author = evalField("detail", "author", f["author"], ctx),
            coverUrl = evalUrlField("detail", "coverUrl", f["coverUrl"], ctx)
                ?.let { resolveUrl(fetched.finalUrl, it) },
            intro = evalField("detail", "intro", f["intro"], ctx),
            tocUrl = tocUrl,
        )
    }

    suspend fun getToc(source: BookSourceRule, tocUrl: String): List<RemoteChapter> {
        val rule = source.toc ?: throw SourceException("书源「${source.name}」未配置目录规则")
        return withRenderFallback(source) { render -> collectToc(source, rule, tocUrl, render) }
            ?: throw SourceException("目录规则未匹配到章节: $tocUrl")
    }

    private suspend fun collectToc(
        source: BookSourceRule,
        rule: TocRule,
        tocUrl: String,
        render: Boolean,
    ): List<RemoteChapter>? {
        val collected = mutableListOf<Triple<String, String, String>>()
        var url: String? = resolveUrl(source.baseUrl, tocUrl)
        val visited = HashSet<String>()
        var pages = 0
        // nextPage 循环：上限 + visited 防环
        while (url != null && pages < MAX_TOC_PAGES && visited.add(url)) {
            pages++
            val fetched = fetchPage(source, url, "toc", render)
            val ctx = pageContext(fetched, varsOf(source), jsContextOf(source, tocUrl = url))
            for (item in evalList("toc", rule.list, ctx)) {
                // 每个章节项一份变量表：@put 存的参数属于这一章，不能串到别的章节上
                val itemCtx = item.copy(js = item.js.copy(scriptVars = java.util.concurrent.ConcurrentHashMap()))
                val title = evalField("toc", "title", rule.fields["title"], itemCtx)
                val chapUrl = evalUrlField("toc", "url", rule.fields["url"], itemCtx)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { resolveUrl(fetched.finalUrl, it) }
                if (!title.isNullOrBlank() && !chapUrl.isNullOrBlank()) {
                    collected += Triple(title, chapUrl, encodeVars(itemCtx.js.scriptVars))
                }
            }
            url = rule.nextPage
                ?.let { evalUrlField("toc", "nextPage", it, ctx) }
                ?.takeIf { it.isNotBlank() }
                ?.let { resolveUrl(fetched.finalUrl, it) }
        }
        if (collected.isEmpty()) return null
        val ordered = if (rule.reverse) collected.asReversed() else collected
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
    ): RemoteChapterContent {
        val rule = source.content ?: throw SourceException("书源「${source.name}」未配置正文规则")
        return withRenderFallback(source) { render ->
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
        rule: ContentRule,
        chapterUrl: String,
        otherChapterUrls: Set<String>,
        chapterVariable: String,
        render: Boolean,
    ): RemoteChapterContent? {
        // 净化：源级在前、全局在后；正则预编译
        val purifiers = compilePurify(rule.purify + globalPurify)
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
            url = rule.nextPage
                ?.let { evalUrlField("content", "nextPage", it, ctx) }
                ?.takeIf { it.isNotBlank() }
                ?.let { resolveUrl(fetched.finalUrl, it) }
                ?.takeIf { it !in stopAt } // 翻到别的章节了 → 本章结束
        }
        return applyPurify(elements, purifiers).takeIf { it.isNotEmpty() }?.let { RemoteChapterContent(it) }
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
            val headers = mergeHeaders(source.headers, emptyMap(), emptyMap())
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
        request: RequestRule,
        vars: Map<String, String>,
        stage: String,
    ): FetchedPage {
        // 地址里的 {{}} 同样按 Legado 语义展开（JS 表达式 / 嵌套规则），不只是变量替换
        val urlCtx = urlContext(source, vars)

        // 地址整串可能是 JS（<js>…</js> / @js:…），脚本的返回值才是真地址；
        // 而且脚本吐出来的地址常自带 ,{method/body/charset/headers} —— 只能在这里、
        // 拿到结果之后才谈得上解析，转换期是看不见的。
        var rawUrl = evaluator.evalUrlJs(request.url, urlCtx) ?: request.url
        var method = request.method
        var body = request.body
        var headers = request.headers
        var charset = request.charset
        splitUrlOptions(rawUrl)?.let { (bare, opt) ->
            rawUrl = bare
            opt.method?.let { method = it }
            opt.body?.let { body = it }
            opt.charset?.let { charset = it }
            if (opt.headers.isNotEmpty()) headers = headers + opt.headers
        }
        // 非 UTF-8 站点的关键词要按站点编码转义。静态地址在转换期就写成了 {{keyword|encode:gbk}}，
        // JS 地址的编码只有此刻才知道，补上同样的管道即可复用。
        charset?.lowercase()?.takeIf { it != "utf-8" && it != "utf8" }?.let { cs ->
            rawUrl = rawUrl.replace("{{keyword}}", "{{keyword|encode:$cs}}")
            body = body?.replace("{{keyword}}", "{{keyword|encode:$cs}}")
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

    private suspend fun fetchUrl(source: BookSourceRule, url: String, stage: String): FetchedPage =
        doFetch(stage, url, "GET", null, mergeHeaders(source.headers, emptyMap(), emptyMap()),
            source.charset, source.rateLimit)

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
            val nodes = evaluator.evalToNodes(RuleParser.parse(rule), ctx)
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
            val value = evaluator.evalToStrings(RuleParser.parse(rule), ctx)
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
            val value = evaluator.evalToString(RuleParser.parse(rule), ctx)
            trace?.onRule(RuleTrace(stage, name, rule, value?.take(200)))
            value
        } catch (e: Exception) {
            trace?.onRule(RuleTrace(stage, name, rule, null, e.message))
            throw e
        }
    }

    // ---- 净化 ----

    private class CompiledPurify(rule: PurifyRule) {
        private val regex: Regex? = if (rule.isRegex) Regex(rule.pattern) else null
        private val literal: String = rule.pattern
        private val replacement: String = rule.replacement
        fun apply(text: String): String =
            regex?.replace(text, replacement) ?: text.replace(literal, replacement)
    }

    private fun compilePurify(rules: List<PurifyRule>): List<CompiledPurify> =
        rules.map {
            try {
                CompiledPurify(it)
            } catch (e: Exception) {
                throw SourceException("净化规则正则错误: ${it.pattern}", e)
            }
        }

    /** 逐段落应用净化；替换后全空的段落丢弃 */
    private fun applyPurify(
        elements: List<ContentElement>,
        purifiers: List<CompiledPurify>,
    ): List<ContentElement> {
        if (purifiers.isEmpty()) return elements
        fun clean(text: String): String? =
            purifiers.fold(text) { acc, p -> p.apply(acc) }.trim().takeIf { it.isNotEmpty() }
        return elements.mapNotNull { el ->
            when (el) {
                is ContentElement.Paragraph -> clean(el.text)?.let { ContentElement.Paragraph(it) }
                is ContentElement.Heading -> clean(el.text)?.let { ContentElement.Heading(el.level, it) }
                else -> el
            }
        }
    }

    private companion object {
        val VARS_JSON = kotlinx.serialization.json.Json
        val PAGE_VAR = Regex("\\{\\{[^}]*page[^}]*\\}\\}")
        const val MAX_TOC_PAGES = 200
        const val MAX_CONTENT_PAGES = 50
    }
}

/** 相对 → 绝对 URL 解析；base 非法或无法解析时原样返回 */
internal fun resolveUrl(base: String, ref: String): String {
    val r = stripUrlOptions(ref.trim())
    if (r.isEmpty()) return r
    if (r.startsWith("http://") || r.startsWith("https://")) return r
    return base.toHttpUrlOrNull()?.resolve(r)?.toString() ?: r
}

/**
 * Legado 的「地址,{选项}」写法（选项含 method/body/headers 等），规则产出的地址可能带这条尾巴，
 * 常见于用 JS 拼目录页地址的书源。抓取前剥掉，否则整条尾巴会被当成 URL 的一部分。
 * 选项里的 headers 暂不生效——需要时书源可在源级 header 里配。
 */
private fun stripUrlOptions(url: String): String {
    val i = url.indexOf(",{")
    return if (i > 0) url.substring(0, i).trim() else url
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
 * 切出地址尾部的 `,{…}` 选项。
 *
 * 不能见到第一个 `,{` 就切 —— 那个 `,{` 可能在 JS 字符串**里面**：
 * `@js:url="https://m.wcxsw.org/search.php,{'body':'…'}"`。所以逐个候选位置试着解析成
 * JSON，能解析出来的才是真选项。从前一刀切在第一个 `,{`，把地址从中间截断成
 * `@js:url="https://m.wcxsw.o`，请求直接 403。
 */
internal fun splitUrlOptions(url: String): Pair<String, UrlOptions>? {
    var i = url.indexOf(",{")
    while (i > 0) {
        val text = url.substring(i + 1)
        val obj = runCatching { optionsJson.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: runCatching { optionsJson.parseToJsonElement(text.replace('\'', '"')).jsonObject }.getOrNull()
        if (obj != null) {
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
        i = url.indexOf(",{", i + 1)
    }
    return null
}
