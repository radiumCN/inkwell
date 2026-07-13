package com.radium.inkwell.core.source

import com.radium.inkwell.core.model.ContentElement
import com.radium.inkwell.core.parser.html.HtmlToElements
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup

/** 书源业务错误（规则未配置/规则不匹配等） */
class SourceException(message: String, cause: Throwable? = null) : Exception(message, cause)

// ---- 结果模型 ----

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
) {

    private val evaluator = RuleEvaluator(scriptRuntime)

    suspend fun search(source: BookSourceRule, keyword: String, page: Int = 1): SearchPage {
        val rule = source.search ?: throw SourceException("书源「${source.name}」未配置搜索规则")
        val vars = varsOf(source, "keyword" to keyword, "page" to page.toString())
        val fetched = fetchByRequest(source, rule.request, vars, "search")
        return parseListPage(source, "search", fetched, vars, rule.list, rule.fields, rule.nextPage)
    }

    suspend fun explore(source: BookSourceRule, exploreIndex: Int, page: Int = 1): SearchPage {
        val rule = source.explore.getOrNull(exploreIndex)
            ?: throw SourceException("书源「${source.name}」发现页下标越界: $exploreIndex")
        val vars = varsOf(source, "page" to page.toString())
        val fetched = fetchByRequest(source, RequestRule(url = rule.url), vars, "explore")
        return parseListPage(source, "explore", fetched, vars, rule.list, rule.fields, rule.nextPage)
    }

    suspend fun getDetail(source: BookSourceRule, bookUrl: String): RemoteBookDetail {
        val rule = source.detail ?: throw SourceException("书源「${source.name}」未配置详情规则")
        val fetched = fetchUrl(source, resolveUrl(source.baseUrl, bookUrl), "detail")
        val ctx = pageContext(fetched, varsOf(source))
        val f = rule.fields
        val title = evalField("detail", "title", f["title"], ctx)
        if (title.isNullOrBlank()) throw SourceException("详情页未匹配到书名: ${fetched.finalUrl}")
        val tocUrl = evalField("detail", "tocUrl", f["tocUrl"], ctx)
            ?.takeIf { it.isNotBlank() }
            ?.let { resolveUrl(fetched.finalUrl, it) }
            ?: fetched.finalUrl // 缺省：详情页即目录页
        return RemoteBookDetail(
            title = title,
            author = evalField("detail", "author", f["author"], ctx),
            coverUrl = evalField("detail", "coverUrl", f["coverUrl"], ctx)
                ?.let { resolveUrl(fetched.finalUrl, it) },
            intro = evalField("detail", "intro", f["intro"], ctx),
            tocUrl = tocUrl,
        )
    }

    suspend fun getToc(source: BookSourceRule, tocUrl: String): List<RemoteChapter> {
        val rule = source.toc ?: throw SourceException("书源「${source.name}」未配置目录规则")
        val collected = mutableListOf<Pair<String, String>>()
        var url: String? = resolveUrl(source.baseUrl, tocUrl)
        val visited = HashSet<String>()
        var pages = 0
        // nextPage 循环：上限 + visited 防环
        while (url != null && pages < MAX_TOC_PAGES && visited.add(url)) {
            pages++
            val fetched = fetchUrl(source, url, "toc")
            val ctx = pageContext(fetched, varsOf(source))
            for (item in evalList("toc", rule.list, ctx)) {
                val title = evalField("toc", "title", rule.fields["title"], item)
                val chapUrl = evalField("toc", "url", rule.fields["url"], item)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { resolveUrl(fetched.finalUrl, it) }
                if (!title.isNullOrBlank() && !chapUrl.isNullOrBlank()) collected += title to chapUrl
            }
            url = rule.nextPage
                ?.let { evalField("toc", "nextPage", it, ctx) }
                ?.takeIf { it.isNotBlank() }
                ?.let { resolveUrl(fetched.finalUrl, it) }
        }
        if (collected.isEmpty()) throw SourceException("目录规则未匹配到章节: $tocUrl")
        val ordered = if (rule.reverse) collected.asReversed() else collected
        return ordered.mapIndexed { i, (title, u) -> RemoteChapter(i, title, u) }
    }

    suspend fun getContent(source: BookSourceRule, chapterUrl: String): RemoteChapterContent {
        val rule = source.content ?: throw SourceException("书源「${source.name}」未配置正文规则")
        // 净化：源级在前、全局在后；正则预编译
        val purifiers = compilePurify(rule.purify + globalPurify)
        val elements = mutableListOf<ContentElement>()
        var url: String? = resolveUrl(source.baseUrl, chapterUrl)
        val visited = HashSet<String>()
        var pages = 0
        while (url != null && pages < MAX_CONTENT_PAGES && visited.add(url)) {
            pages++
            val fetched = fetchUrl(source, url, "content")
            val ctx = pageContext(fetched, varsOf(source))
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
                ?.let { evalField("content", "nextPage", it, ctx) }
                ?.takeIf { it.isNotBlank() }
                ?.let { resolveUrl(fetched.finalUrl, it) }
        }
        val purified = applyPurify(elements, purifiers)
        if (purified.isEmpty()) throw SourceException("正文规则未匹配到内容: $chapterUrl")
        return RemoteChapterContent(purified)
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
        val url = resolveUrl(source.baseUrl, expandTemplate(request.url, vars))
        return doFetch(
            stage = stage,
            url = url,
            method = request.method,
            body = request.body?.let { expandTemplate(it, vars) },
            headers = mergeHeaders(source.headers, request.headers, vars),
            charset = request.charset ?: source.charset,
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
    private fun pageContext(fetched: FetchedPage, vars: Map<String, String>): EvalContext =
        EvalContext(
            element = Jsoup.parse(fetched.bodyText, fetched.finalUrl),
            json = fetched.bodyText,
            baseUrl = fetched.finalUrl,
            vars = vars,
        )

    private fun parseListPage(
        source: BookSourceRule,
        stage: String,
        fetched: FetchedPage,
        vars: Map<String, String>,
        listRule: String,
        fields: Map<String, String>,
        nextPageRule: String?,
    ): SearchPage {
        val ctx = pageContext(fetched, vars)
        val items = evalList(stage, listRule, ctx).mapNotNull { itemCtx ->
            val title = evalField(stage, "title", fields["title"], itemCtx)
            val bookUrl = evalField(stage, "bookUrl", fields["bookUrl"], itemCtx)
                ?.takeIf { it.isNotBlank() }
                ?.let { resolveUrl(fetched.finalUrl, it) }
            if (title.isNullOrBlank() || bookUrl.isNullOrBlank()) return@mapNotNull null
            SearchResult(
                title = title,
                bookUrl = bookUrl,
                author = evalField(stage, "author", fields["author"], itemCtx),
                coverUrl = evalField(stage, "coverUrl", fields["coverUrl"], itemCtx)
                    ?.let { resolveUrl(fetched.finalUrl, it) },
                intro = evalField(stage, "intro", fields["intro"], itemCtx),
                latestChapter = evalField(stage, "latestChapter", fields["latestChapter"], itemCtx),
                sourceId = source.id,
            )
        }
        val hasMore = nextPageRule != null && !evalField(stage, "nextPage", nextPageRule, ctx).isNullOrBlank()
        return SearchPage(items, hasMore)
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
        const val MAX_TOC_PAGES = 200
        const val MAX_CONTENT_PAGES = 50
    }
}

/** 相对 → 绝对 URL 解析；base 非法或无法解析时原样返回 */
internal fun resolveUrl(base: String, ref: String): String {
    val r = ref.trim()
    if (r.isEmpty()) return r
    if (r.startsWith("http://") || r.startsWith("https://")) return r
    return base.toHttpUrlOrNull()?.resolve(r)?.toString() ?: r
}
