package com.radium.inkwell.core.source.rss

import com.radium.inkwell.core.parser.html.isEmojiImage
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser

/**
 * 标准 RSS 2.0 / Atom feed 的解析。
 *
 * 这条路径没有任何规则可言 —— 用户填个地址就该能订阅。Legado 也是这么做的：
 * 没配 ruleArticles 的源直接按 XML feed 解析。
 *
 * 用 jsoup 的 XML parser 而不是 HTML parser：HTML parser 会把 `<link>` 当成自闭合的
 * HTML 标签，`<link>https://…</link>` 的正文就此丢失，所有文章都点不开。
 */
object RssXmlParser {

    /** 看着像 XML feed 就当 feed 解析。返回 null 表示"这不是 feed，走规则那条路" */
    fun parse(sourceId: String, xml: String): List<RssArticle>? {
        if (!looksLikeFeed(xml)) return null
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        val items = doc.select("item, entry")
        if (items.isEmpty()) return null
        return items.mapNotNull { item -> article(sourceId, item) }
    }

    /** 开头几百字里有 `<rss` / `<feed` / `<rdf:RDF`：是 RSS 2.0 / Atom / RSS 1.0 */
    fun looksLikeFeed(text: String): Boolean {
        val head = text.trimStart().take(600)
        return head.contains("<rss", true) || head.contains("<feed", true) || head.contains("<rdf:RDF", true)
    }

    /** feed 自己的标题。用户粘一个裸地址进来时拿它当订阅源的名字 */
    fun feedTitle(xml: String): String? = runCatching {
        Jsoup.parse(xml, "", Parser.xmlParser())
            .selectFirst("channel > title, feed > title")
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun article(sourceId: String, item: Element): RssArticle? {
        val title = item.selectFirst("title")?.text()?.trim().orEmpty()
        val link = link(item)
        if (title.isEmpty() && link.isEmpty()) return null

        // 全文在前、摘要在后：content:encoded（RSS）和 content（Atom）是全文，description / summary 常常只是摘要。
        // 从前 Atom 的 summary 排在 content 前面 —— 两样都给的 feed（不少博客）点进去只有摘要，
        // 现在还会被当成「只给了摘要」，平白去原网页再抓一遍。空着的标签跳过，接着找下一样
        val description = FULL_TEXT_FIRST
            .mapNotNull { item.selectFirst(it)?.feedHtml() }
            .firstOrNull { it.isNotBlank() }

        return RssArticle(
            sourceId = sourceId,
            title = title.ifEmpty { link },
            link = link,
            description = description?.trim(),
            image = image(item, description),
            pubDate = item.selectFirst("pubDate")?.text()
                ?: item.selectFirst("published")?.text()
                ?: item.selectFirst("updated")?.text()
                ?: item.selectFirst("dc|date")?.text(),
        )
    }

    /**
     * Atom 的 link 是 `<link href="…"/>`，RSS 的是 `<link>…</link>` —— 两种都要认。
     * Atom 里可能有多个 link（alternate / self / enclosure），要的是 alternate。
     */
    private fun link(item: Element): String {
        item.select("link").forEach { l ->
            val rel = l.attr("rel")
            val href = l.attr("href")
            if (href.isNotBlank() && (rel.isBlank() || rel == "alternate")) return href
        }
        return item.selectFirst("link")?.text()?.trim()
            ?: item.selectFirst("guid")?.text()?.trim().orEmpty()
    }

    private val FULL_TEXT_FIRST = listOf("content|encoded", "content", "description", "summary")

    /**
     * 正文是 HTML 的字符串：转义过的 HTML / CDATA 在 XML 里是一段文字，取 text()；
     * Atom 的 `type="xhtml"` 直接嵌着元素，取 text() 会把段落、图全压成一行字，取里面的 HTML
     */
    private fun Element.feedHtml(): String = if (children().isEmpty()) text() else html()

    private fun image(item: Element, description: String?): String? {
        item.selectFirst("enclosure[type^=image]")?.attr("url")
            ?.takeIf { it.isNotBlank() }?.let { return it }
        item.selectFirst("media|thumbnail, media|content")?.attr("url")
            ?.takeIf { it.isNotBlank() }?.let { return it }
        // 摘要里的头一张图，聊胜于无。表情图不算：爱范儿早报的头一张图是条目前面那个 🚨，当缩略图就是一枚放大的表情
        return description
            ?.let { html -> Jsoup.parse(html).select("img").firstOrNull { !isEmojiImage(it) }?.attr("src") }
            ?.takeIf { it.isNotBlank() }
    }
}
