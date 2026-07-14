package com.radium.inkwell.core.source.rss

import com.radium.inkwell.core.model.ContentElement
import com.radium.inkwell.core.parser.html.HtmlToElements
import com.radium.inkwell.core.source.EvalContext
import com.radium.inkwell.core.source.JsContext
import com.radium.inkwell.core.source.LegadoRuleAnalyzer
import com.radium.inkwell.core.source.RuleEvaluator
import com.radium.inkwell.core.source.SourceException
import com.radium.inkwell.core.source.SourceHttpClient
import com.radium.inkwell.core.source.js.ScriptRuntime
import com.radium.inkwell.core.source.resolveUrl
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

    suspend fun articles(rule: RssSourceRule, sortUrl: String, page: Int = 1): RssArticlePage {
        val url = resolveUrl(rule.id, expandPage(sortUrl, page))
        val fetched = http.fetch(
            url,
            headers = rule.headers,
            charsetOverride = rule.charset,
        )

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
            vars = mapOf("baseUrl" to rule.id, "page" to page.toString()),
            js = JsContext(sourceKey = rule.id),
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
    suspend fun content(rule: RssSourceRule, article: RssArticle): List<ContentElement>? {
        if (!rule.content.isNullOrBlank() && article.link.isNotBlank()) {
            val url = resolveUrl(rule.id, article.link)
            val fetched = http.fetch(url, headers = rule.headers, charsetOverride = rule.charset)
            val ctx = EvalContext(
                element = Jsoup.parse(fetched.bodyText, fetched.finalUrl),
                json = fetched.bodyText,
                baseUrl = fetched.finalUrl,
                js = JsContext(sourceKey = rule.id),
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

    private fun expandPage(url: String, page: Int): String =
        url.replace("{{page}}", page.toString())

    /** 相对链接补成绝对的 —— 否则点开文章就是一串打不开的路径 */
    private fun RssArticle.absolutize(base: String): RssArticle = copy(
        link = link.takeIf { it.isBlank() } ?: resolveUrl(base, link),
        image = image?.takeIf { it.isNotBlank() }?.let { resolveUrl(base, it) },
    )
}
