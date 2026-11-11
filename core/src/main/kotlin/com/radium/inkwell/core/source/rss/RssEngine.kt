package com.radium.inkwell.core.source.rss

import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import com.radium.inkwell.core.model.ContentElement
import com.radium.inkwell.core.parser.html.HtmlToElements
import com.radium.inkwell.core.source.EvalContext
import com.radium.inkwell.core.source.JsContext
import com.radium.inkwell.core.source.LegadoRuleAnalyzer
import com.radium.inkwell.core.source.RuleEvaluator
import com.radium.inkwell.core.source.SourceException
import com.radium.inkwell.core.source.SourceHttpClient
import com.radium.inkwell.core.source.UrlOptions
import com.radium.inkwell.core.source.js.ScriptRuntime
import com.radium.inkwell.core.source.resolveUrl
import com.radium.inkwell.core.source.splitUrlOptions
import org.jsoup.Jsoup

/**
 * 订阅引擎。规则求值、HTTP、HTML→元素这些都复用书源那一套 —— 订阅和书源的差别只在
 * 层级（分类 → 文章 → 正文，没有搜索和目录），规则语义是同一套。
 */
class RssEngine(
    private val http: SourceHttpClient,
    scriptRuntime: ScriptRuntime? = null,
) {
    private val evaluator = RuleEvaluator(scriptRuntime = scriptRuntime)
    private val htmlToElements = HtmlToElements()

    /**
     * 公开入口一律在 IO 线程自我确权（同 BookSourceEngine）：调用方是 viewModelScope，
     * 抓取恢复之后的解码、XML/Jsoup 解析、规则脚本（Rhino 最多 2000 万条指令）从前全跑在主线程上。
     */
    suspend fun articles(rule: RssSourceRule, sortUrl: String, page: Int = 1): RssArticlePage =
        withContext(Dispatchers.IO) { articlesOnIo(rule, sortUrl, page) }

    private suspend fun articlesOnIo(rule: RssSourceRule, sortUrl: String, page: Int): RssArticlePage {
        val vars = mapOf("baseUrl" to rule.id, "page" to page.toString())
        val js = JsContext(sourceKey = rule.id, jsLib = rule.jsLib)
        val fetched = fetch(rule, sortUrl, vars, js)
        val url = fetched.finalUrl

        // 没配列表规则 = 标准 XML feed。这是常态，不是错误
        if (rule.articles.isNullOrBlank()) {
            val items = RssXmlParser.parse(rule.id, fetched.bodyText)
                ?: throw SourceException("既不是 RSS/Atom feed，也没有配文章列表规则: $url")
            return RssArticlePage(items.map { it.absolutize(fetched.finalUrl) }, hasMore = false)
        }

        val doc = Jsoup.parse(fetched.bodyText, fetched.finalUrl)
        val root = EvalContext(
            element = doc,
            json = fetched.bodyText,
            baseUrl = fetched.finalUrl,
            vars = vars,
            js = js,
        )
        val nodes = evaluator.evalToNodes(LegadoRuleAnalyzer.analyze(rule.articles), root)
        val items = nodes.mapNotNull { item ->
            val title = field(rule.title, item)
            val link = field(rule.link, item)
            if (title.isNullOrBlank() && link.isNullOrBlank()) return@mapNotNull null
            RssArticle(
                sourceId = rule.id,
                title = title.orEmpty().ifBlank { link.orEmpty() },
                link = link.orEmpty(),
                description = field(rule.description, item),
                image = field(rule.image, item),
                pubDate = field(rule.pubDate, item),
            ).absolutize(fetched.finalUrl)
        }
        val hasMore = !rule.nextPage.isNullOrBlank() &&
            !field(rule.nextPage, root).isNullOrBlank()
        return RssArticlePage(items, hasMore)
    }

    /**
     * 文章正文。
     *
     * 三级回落：配了正文规则就抓原文页解析；没配就用列表里带的 description（多数 feed
     * 的 content:encoded 本来就是全文）；两者都没有则返回 null —— 调用方该拿浏览器打开
     * 原链接，而不是给用户看一个空白页。
     */
    suspend fun content(rule: RssSourceRule, article: RssArticle): List<ContentElement>? =
        withContext(Dispatchers.IO) { contentOnIo(rule, article) }

    private suspend fun contentOnIo(rule: RssSourceRule, article: RssArticle): List<ContentElement>? {
        if (!rule.content.isNullOrBlank() && article.link.isNotBlank()) {
            val js = JsContext(sourceKey = rule.id, jsLib = rule.jsLib)
            val fetched = fetch(rule, article.link, mapOf("baseUrl" to rule.id), js)
            val ctx = EvalContext(
                element = Jsoup.parse(fetched.bodyText, fetched.finalUrl),
                json = fetched.bodyText,
                baseUrl = fetched.finalUrl,
                js = js,
            )
            val html = evaluator.evalToString(LegadoRuleAnalyzer.analyze(rule.content), ctx)
            if (!html.isNullOrBlank()) {
                val body = Jsoup.parseBodyFragment(html, fetched.finalUrl).body()
                return htmlToElements.convert(body).takeIf { it.isNotEmpty() }
            }
        }
        val desc = article.description?.takeIf { it.isNotBlank() } ?: return null
        val body = Jsoup.parseBodyFragment(desc, article.link).body()
        return htmlToElements.convert(body).takeIf { it.isNotEmpty() }
    }

    private fun field(rule: String?, ctx: EvalContext): String? {
        if (rule.isNullOrBlank()) return null
        return runCatching { evaluator.evalToString(LegadoRuleAnalyzer.analyze(rule), ctx) }.getOrNull()
    }

    /**
     * 分类地址、文章链接 → 一次抓取，照阅读的 AnalyzeUrl：先跑地址里的 `@js:` / `<js>`，再展开 `{{…}}`
     * （`{{page}}`、算术、脚本，jsLib 里的函数也调得到），再拆出 `,{…}` 选项（请求方式、请求体、请求头、编码）。
     *
     * 从前只替换 `{{page}}`：`must_xx={{getMust()}}` 这类调用 jsLib 的地址原样发出去，`,{"method":"POST"}` 也一直挂在地址上。
     * `data:` 地址不发请求，由 [SourceHttpClient] 直接答（十六进制的内容，见 dataUriPage）。
     */
    private suspend fun fetch(
        rule: RssSourceRule,
        raw: String,
        vars: Map<String, String>,
        js: JsContext,
    ): com.radium.inkwell.core.source.FetchedPage {
        val ctx = EvalContext(element = null, json = null, baseUrl = rule.id, vars = vars, js = js)
        val scripted = evaluator.evalUrlJs(raw, ctx) ?: raw
        val expanded = evaluator.expandTemplate(scripted, ctx)
        val (bare, options) = splitUrlOptions(expanded) ?: (expanded to UrlOptions())
        val url = if (bare.startsWith("data:", ignoreCase = true)) bare else resolveUrl(rule.id, bare)
        return http.fetch(
            url,
            method = options.method?.uppercase() ?: "GET",
            body = options.body,
            headers = rule.headers + options.headers,
            charsetOverride = options.charset ?: rule.charset,
        )
    }

    /**
     * 相对链接补成绝对的 —— 否则点开文章就是一串打不开的路径。
     * 图片地址后面常跟着阅读的请求选项（`…jpg,{"headers":{"referer":…}}`，晋江书单就这么写）：
     * 选项丢掉、地址留着，带着 `,{…}` 去加载只会是一张裂图
     */
    private fun RssArticle.absolutize(base: String): RssArticle = copy(
        // 脚本地址、data: 地址原样留着：它们要到抓正文时才算（见 [fetch]），补成「站点/@js:…」就废了
        link = link.takeIf { it.isBlank() || it.isScriptOrDataUrl() } ?: resolveUrl(base, link),
        image = image?.takeIf { it.isNotBlank() }?.let { resolveUrl(base, splitUrlOptions(it)?.first ?: it) },
    )
}

private fun String.isScriptOrDataUrl(): Boolean {
    val t = trimStart()
    return t.startsWith("data:", ignoreCase = true) ||
        t.contains("@js:", ignoreCase = true) ||
        t.contains("<js>", ignoreCase = true)
}
