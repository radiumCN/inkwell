package com.radium.inkwell.core.source.rss

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import com.radium.inkwell.core.model.ContentElement
import com.radium.inkwell.core.parser.html.HtmlToElements
import com.radium.inkwell.core.source.EvalContext
import com.radium.inkwell.core.source.JsContext
import com.radium.inkwell.core.source.LegadoRuleAnalyzer
import com.radium.inkwell.core.source.PageRenderer
import com.radium.inkwell.core.source.RuleEvaluator
import com.radium.inkwell.core.source.SourceException
import com.radium.inkwell.core.source.SourceHttpClient
import com.radium.inkwell.core.source.UrlOptions
import com.radium.inkwell.core.source.js.EngineJsHttp
import com.radium.inkwell.core.source.js.ScriptRuntime
import com.radium.inkwell.core.source.js.SourceFacts
import com.radium.inkwell.core.source.parseHeaderJson
import com.radium.inkwell.core.source.parsePage
import com.radium.inkwell.core.source.resolveUrl
import com.radium.inkwell.core.source.splitUrlOptions
import com.radium.inkwell.core.source.withoutLegadoOnlyHeaders
import org.jsoup.Jsoup

/**
 * 订阅引擎。规则求值、HTTP、HTML→元素这些都复用书源那一套 —— 订阅和书源的差别只在
 * 层级（分类 → 文章 → 正文，没有搜索和目录），规则语义是同一套。
 */
class RssEngine(
    private val http: SourceHttpClient,
    scriptRuntime: ScriptRuntime? = null,
    /**
     * 地址声明了 `webView` 的（阅读的写法：东方财富的列表是页面脚本画的、学术站拿它过人机验证）用它渲染；
     * 没注入、渲染失败照旧静态抓
     */
    private val renderer: PageRenderer? = null,
) {
    /**
     * 规则脚本的网络出口（`java.ajax` / `post` / `ajaxAll`）。从前订阅这边没接：脚本里的请求一律回空，
     * 拿 ajax 拼正文的图集、把文章发给翻译接口的学术订阅源，列表和正文全空 —— 两份公开合集里 58 个订阅源这么写
     */
    private val jsHttp = EngineJsHttp(http)
    private val evaluator = RuleEvaluator(scriptRuntime = scriptRuntime, jsHttp = jsHttp)
    // 订阅文章留着图：文章页是滚动着看的，不分页（小说、本地书那几处照旧不收图）
    private val htmlToElements = HtmlToElements(keepImages = true)

    /**
     * 公开入口一律在 IO 线程自我确权（同 BookSourceEngine）：调用方是 viewModelScope，
     * 抓取恢复之后的解码、XML/Jsoup 解析、规则脚本（Rhino 最多 2000 万条指令）从前全跑在主线程上。
     *
     * 并把当前 Job 绑给 [jsHttp]（同 BookSourceEngine.engineIo）：脚本里的 java.ajax 是阻塞等的，
     * 用户退出文章页时外层取消要传得进去，掐掉还在飞的请求。
     */
    private suspend fun <T> rssIo(block: suspend () -> T): T =
        withContext(Dispatchers.IO) {
            withContext(jsHttp.bindingTo(coroutineContext[Job])) {
                try {
                    block()
                } catch (e: Exception) {
                    // 外层取消时脚本里的 ajax 只能交回空串、规则「正常」跑完抽不到东西 —— 别让它冒成「解析不出文章」
                    if (e !is CancellationException) currentCoroutineContext().ensureActive()
                    throw e
                }
            }
        }

    suspend fun articles(rule: RssSourceRule, sortUrl: String, page: Int = 1): RssArticlePage =
        rssIo { articlesOnIo(rule, sortUrl, page) }

    private suspend fun articlesOnIo(rule: RssSourceRule, sortUrl: String, page: Int): RssArticlePage {
        val vars = mapOf("baseUrl" to rule.id, "page" to page.toString())
        val js = jsContextOf(rule)
        val fetched = fetch(rule, sortUrl, vars, js)
        val url = fetched.finalUrl

        // 没配列表规则 = 标准 XML feed。这是常态，不是错误
        if (rule.articles.isNullOrBlank()) {
            val items = RssXmlParser.parse(rule.id, fetched.bodyText)
                ?: throw SourceException("既不是 RSS/Atom feed，也没有配文章列表规则: $url")
            return RssArticlePage(items.map { it.absolutize(fetched.finalUrl) }, hasMore = false)
        }

        // 在 feed 上写规则的（`//item`、`link@text`）得按 XML 解析，HTML 解析器会把 `<link>` 的地址挤出元素
        val doc = parsePage(fetched.bodyText, fetched.finalUrl)
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
     * 配了正文规则就抓原文页解析；没配（或没解析出东西）就用列表里带的 description（多数 feed
     * 的 content:encoded 本来就是全文）。description 只是一两句摘要、或者什么都没有的，标上
     * [RssContent.teaser]：调用方先把摘要亮出来，再拿 [fullText] 去原网页整理全文。
     */
    suspend fun content(rule: RssSourceRule, article: RssArticle): RssContent =
        rssIo { contentOnIo(rule, article) }

    /**
     * 去原网页把全文整理出来（feed 只给了摘要时，见 [RssContent.teaser]）。请求头、编码照订阅源里写的。
     *
     * 整理不出来返回 null：页面要跑脚本才出内容、认出来的块还没摘要长、字数不到 [MIN_FULL_TEXT]
     * （一两句话不值得替掉摘要，多半是认错了块）。网络错误照常抛出，调用方说原因。
     */
    suspend fun fullText(
        rule: RssSourceRule,
        article: RssArticle,
        summary: List<ContentElement> = emptyList(),
    ): List<ContentElement>? = rssIo {
        val js = jsContextOf(rule)
        val fetched = fetch(rule, article.link, mapOf("baseUrl" to rule.id), js)
        val elements = ArticleExtractor.extract(Jsoup.parse(fetched.bodyText, fetched.finalUrl), article.title)
        val length = RssTeaser.textLength(elements)
        elements.takeIf { length >= MIN_FULL_TEXT && length > RssTeaser.textLength(summary) }
    }

    /** 脚本里的 `source`：`source.sourceUrl` / `sourceName` 是订阅源自己的（阅读的 RssSource 字段） */
    private fun jsContextOf(rule: RssSourceRule) =
        JsContext(sourceKey = rule.id, sourceFacts = SourceFacts(name = rule.name, jsLib = rule.jsLib), jsLib = rule.jsLib)

    private suspend fun contentOnIo(rule: RssSourceRule, article: RssArticle): RssContent {
        if (!rule.content.isNullOrBlank() && article.link.isNotBlank()) {
            val js = jsContextOf(rule)
            val fetched = fetch(rule, article.link, mapOf("baseUrl" to rule.id), js)
            val ctx = EvalContext(
                element = parsePage(fetched.bodyText, fetched.finalUrl),
                json = fetched.bodyText,
                baseUrl = fetched.finalUrl,
                js = js,
            )
            val html = evaluator.evalToString(LegadoRuleAnalyzer.analyze(rule.content), ctx)
            if (!html.isNullOrBlank()) {
                val body = Jsoup.parseBodyFragment(html, fetched.finalUrl).body()
                val elements = htmlToElements.convert(body)
                if (elements.isNotEmpty()) return RssContent(elements, teaser = false)
            }
        }
        val elements = article.description
            ?.takeIf { it.isNotBlank() }
            ?.let { htmlToElements.convert(Jsoup.parseBodyFragment(it, article.link).body()) }
            .orEmpty()
        // 原网页得是个网页才去整理：脚本地址、data: 地址没有「原网页」可言
        val teaser = article.link.isHttpUrl() && RssTeaser.isTeaser(elements)
        return RssContent(if (teaser) RssTeaser.stripReadMore(elements) else elements, teaser)
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
        val method = options.method?.uppercase() ?: "GET"
        val headers = sourceHeaders(rule, js) + options.headers
        // 声明了 webView 的交给渲染器（同书源：只会 GET，带体的照旧静态抓）；渲染失败退回静态抓
        if (options.webView && options.body == null && method == "GET" && renderer != null && !url.startsWith("data:")) {
            val ua = headers.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.value
                ?: SourceHttpClient.DEFAULT_UA
            renderer.render(url, headers.withoutLegadoOnlyHeaders(), ua)?.let { return it }
        }
        return http.fetch(
            url,
            method = method,
            body = options.body,
            headers = headers,
            charsetOverride = options.charset ?: rule.charset,
        )
    }

    /**
     * 订阅源级的请求头：JSON 写法照存着的；脚本写法（[RssSourceRule.headerScript]）同书源每次请求现跑 ——
     * 脚本里有 java / source / cookie / cache、`baseUrl`（订阅源地址）和 jsLib，跑不出 JSON 就当没有。
     * 变量表拷一份给它，header 脚本里 java.put 的东西不混进这条链路。脚本 `source.putLoginHeader` 存下的头盖在最上面
     */
    private fun sourceHeaders(rule: RssSourceRule, js: JsContext): Map<String, String> {
        val base = rule.headerScript?.let { script ->
            val ctx = EvalContext(
                element = null,
                json = null,
                baseUrl = rule.id,
                js = js.copy(scriptVars = java.util.concurrent.ConcurrentHashMap(js.scriptVars)),
            )
            parseHeaderJson(evaluator.evalSourceScript(script, ctx))
        } ?: rule.headers
        return base + evaluator.loginHeaders(rule.id)
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

/**
 * 一篇文章的正文。
 *
 * @param elements 可能是空的：订阅源只给了标题和链接
 * @param teaser feed 只给了摘要（或什么都没给），全文在原网页上：值得拿 [RssEngine.fullText] 去整理一次
 */
data class RssContent(
    val elements: List<ContentElement>,
    val teaser: Boolean,
)

/** 原网页整理出来的全文至少这么多字，才替掉摘要 */
private const val MIN_FULL_TEXT = 200

private fun String.isHttpUrl(): Boolean =
    startsWith("http://", ignoreCase = true) || startsWith("https://", ignoreCase = true)

private fun String.isScriptOrDataUrl(): Boolean {
    val t = trimStart()
    return t.startsWith("data:", ignoreCase = true) ||
        t.contains("@js:", ignoreCase = true) ||
        t.contains("<js>", ignoreCase = true)
}
