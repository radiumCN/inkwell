package com.radium.inkwell.core.source.rss

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
        val head = xml.trimStart().take(600)
        if (!head.contains("<rss", true) &&
            !head.contains("<feed", true) &&
            !head.contains("<rdf:RDF", true)
        ) {
            return null
        }
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        val items = doc.select("item, entry")
        if (items.isEmpty()) return null
        return items.mapNotNull { item -> article(sourceId, item) }
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

        // Atom 用 summary/content，RSS 用 description；content:encoded 是最全的那份
        val description = item.selectFirst("content|encoded")?.text()
            ?: item.selectFirst("description")?.text()
            ?: item.selectFirst("summary")?.text()
            ?: item.selectFirst("content")?.text()

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

    private fun image(item: Element, description: String?): String? {
        item.selectFirst("enclosure[type^=image]")?.attr("url")
            ?.takeIf { it.isNotBlank() }?.let { return it }
        item.selectFirst("media|thumbnail, media|content")?.attr("url")
            ?.takeIf { it.isNotBlank() }?.let { return it }
        // 摘要里的头一张图，聊胜于无
        return description
            ?.let { Jsoup.parse(it).selectFirst("img")?.attr("src") }
            ?.takeIf { it.isNotBlank() }
    }
}
