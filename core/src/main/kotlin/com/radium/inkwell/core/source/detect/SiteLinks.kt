package com.radium.inkwell.core.source.detect

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** 页面里一条可用的站内链接 */
internal class SiteLink(val el: Element, val url: String, val text: String)

/** 链接的取舍与「下一页」识别；目录、正文、详情三处共用 */
internal object SiteLinks {

    /** 页面上所有可用的站内链接，按文档顺序 */
    fun collect(doc: Document): List<SiteLink> {
        val host = hostOf(doc.location()) ?: return emptyList()
        return doc.body()?.select("a[href]").orEmpty().mapNotNull { linkOf(it, host) }
    }

    /**
     * 能当章节 / 下一页用的链接：有文字、能抓、在本站。
     * 「本站」按主域算：PC 站的目录链到 m. 子域的章节很常见，同一个站不该被拆开。
     */
    fun linkOf(a: Element, pageHost: String): SiteLink? {
        if (a.normalName() != "a") return null
        val href = a.attr("href").trim()
        if (href.isEmpty() || href.startsWith("#") || NON_PAGE_SCHEME.containsMatchIn(href)) return null
        val abs = a.absUrl("href").substringBefore('#')
        val host = hostOf(abs) ?: return null
        if (!sameSite(host, pageHost)) return null
        val text = a.text().trim()
        if (text.isEmpty()) return null
        return SiteLink(a, abs, text)
    }

    /**
     * 「下一页」链接。只认「页」不认「章」：正文的下一页是同一章的后半截，下一章是另一章；
     * 目录的下一页同理。末页的「下一页」常指回本页或是 `javascript:`，在这里已经筛掉了。
     */
    fun findNextPage(doc: Document): DetectedField? {
        val host = hostOf(doc.location()) ?: return null
        val self = doc.location().substringBefore('#')
        for (a in doc.body()?.select("a[href]").orEmpty()) {
            val link = linkOf(a, host) ?: continue
            if (!NEXT_PAGE.matches(link.text) || link.url == self) continue
            val keyword = link.text.trimEnd(*ARROWS).trim()
            // 关键词里带选择器语法字符就没法塞进 :contains()，别生成一条坏规则
            if (keyword.any { it in SELECTOR_UNSAFE }) continue
            val selector = "a:contains($keyword)"
            val first = CssPaths.select(doc, selector)?.firstNotNullOfOrNull { linkOf(it, host) }
            if (first?.url == link.url) return DetectedField("@css:$selector@href", link.url)
        }
        return null
    }

    fun hostOf(url: String): String? = url.toHttpUrlOrNull()?.host

    /** 同一主域（最后两段；`.com.cn` 这类取三段） */
    fun sameSite(a: String, b: String): Boolean = a == b || siteOf(a) == siteOf(b)

    private fun siteOf(host: String): String {
        val parts = host.split('.')
        if (parts.size <= 2) return host
        val take = if (parts.last().length == 2 && parts[parts.size - 2] in SECOND_LEVEL) 3 else 2
        return parts.takeLast(take).joinToString(".")
    }

    /**
     * 链接是不是「这本书的」：落在当前页带书号的目录下，或带着当前页地址里的书号。
     * 推荐书单、排行榜指向别的书，两条都不满足 —— 目录打分靠它把它们压下去。
     *
     * 目录必须带书号才算：`/book/123.html` 的目录是 `/book/`，推荐的 `/book/456.html` 也在它下面。
     * 书号按整段数字比，`/book/51234.html` 不能因为含着 `123` 就算进来。
     */
    fun isRelated(url: String, pageUrl: String): Boolean {
        val pagePath = pageUrl.toHttpUrlOrNull()?.encodedPath ?: return false
        val path = url.toHttpUrlOrNull()?.encodedPath ?: return false
        if (path == pagePath) return false
        val dir = pagePath.substringBeforeLast('/') + "/"
        if (BOOK_ID.containsMatchIn(dir) && path.startsWith(dir)) return true
        val ids = BOOK_ID.findAll(pagePath).map { it.value }.toSet()
        return BOOK_ID.findAll(path).any { it.value in ids }
    }

    /** 地址的「形状」：数字串一律换成 #，`/book/12/345.html` → `/book/#/#.html` */
    fun shapeOf(url: String): String {
        val u = url.toHttpUrlOrNull() ?: return url
        val q = u.encodedQuery?.let { "?$it" }.orEmpty()
        return DIGITS.replace(u.encodedPath + q, "#")
    }

    private val NON_PAGE_SCHEME = Regex("^(javascript|mailto|tel|sms):", RegexOption.IGNORE_CASE)
    private val NEXT_PAGE = Regex(
        "^(下\\s*一?\\s*页|后\\s*一?\\s*页|next(\\s*page)?)\\s*[>›»→]*$",
        RegexOption.IGNORE_CASE,
    )
    private val ARROWS = charArrayOf('>', '›', '»', '→', ' ')
    private val SECOND_LEVEL = setOf("com", "net", "org", "gov", "edu", "co")
    private val BOOK_ID = Regex("\\d{3,}")
    private val DIGITS = Regex("\\d+")

    /** 不能出现在 `:contains()` 关键词里的字符：会提前收括号，或被 Legado 规则当成提取器分隔 */
    const val SELECTOR_UNSAFE = "()[]@\"'\\"
}

/** 识别出来的一个字段：生成的 Legado 规则 + 它在样本页上取到的值（给界面核对用） */
data class DetectedField(val rule: String, val value: String)
