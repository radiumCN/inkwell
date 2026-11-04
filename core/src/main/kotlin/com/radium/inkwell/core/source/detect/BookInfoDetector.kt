package com.radium.inkwell.core.source.detect

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** 详情页上识别出的书籍信息；每项都是「规则 + 样本值」，没识别出来为 null */
data class DetectedBookInfo(
    val name: DetectedField? = null,
    val author: DetectedField? = null,
    val coverUrl: DetectedField? = null,
    val intro: DetectedField? = null,
) {
    val isEmpty: Boolean get() = name == null && author == null && coverUrl == null && intro == null
}

/**
 * 详情页识别：书名、作者、封面、简介，以及「目录在另一页」时指向目录的链接。
 *
 * 国内小说站大多沿用同一套模板，`<head>` 里带 `og:novel:*` 系列 meta（给搜索引擎的小说结构化数据），
 * 这是最稳的来源：与页面排版无关，改版也很少动。没有时才退回页面元素（唯一的 `<h1>`、「作者：」那一行）。
 */
object BookInfoDetector {

    fun detect(doc: Document): DetectedBookInfo = DetectedBookInfo(
        name = meta(doc, "og:novel:book_name") ?: soleHeading(doc) ?: meta(doc, "og:title"),
        author = meta(doc, "og:novel:author") ?: authorLine(doc),
        coverUrl = meta(doc, "og:image", absolute = true),
        intro = meta(doc, "og:description"),
    )

    /**
     * 「查看完整目录」这类链接：手机站的详情页通常只列最新十来章，完整目录在另一页。
     * 返回的 [DetectedField.value] 是目录页的绝对地址。
     */
    fun findTocLink(doc: Document): DetectedField? = findLink(doc, TOC_LINK)

    /** 章节页上回书页的链接（「返回书页」「书籍详情」）；没有「目录」链接时退而求其次 */
    fun findBookPageLink(doc: Document): DetectedField? = findLink(doc, BOOK_PAGE_LINK)

    private fun findLink(doc: Document, pattern: Regex): DetectedField? {
        val host = SiteLinks.hostOf(doc.location()) ?: return null
        val self = doc.location().substringBefore('#')
        for (a in doc.body()?.select("a[href]").orEmpty()) {
            val link = SiteLinks.linkOf(a, host) ?: continue
            if (!pattern.matches(link.text) || link.url == self) continue
            if (link.text.any { it in SiteLinks.SELECTOR_UNSAFE }) continue
            val selector = "a:contains(${link.text})"
            val first = CssPaths.select(doc, selector)?.firstNotNullOfOrNull { SiteLinks.linkOf(it, host) }
            if (first?.url == link.url) return DetectedField("@css:$selector@href", link.url)
        }
        return null
    }

    private fun meta(doc: Document, property: String, absolute: Boolean = false): DetectedField? {
        val el = doc.selectFirst("meta[property=$property]") ?: return null
        val value = (if (absolute) el.absUrl("content") else el.attr("content")).trim()
        if (value.isEmpty()) return null
        return DetectedField("@css:meta[property=$property]@content", value)
    }

    /** 全页只有一个 `<h1>`、且长度像书名：目录页 / 详情页的 h1 基本就是书名 */
    private fun soleHeading(doc: Document): DetectedField? {
        val h1 = doc.select("h1").singleOrNull() ?: return null
        val text = h1.text().trim()
        if (text.isEmpty() || text.length > MAX_TITLE) return null
        return DetectedField("@css:h1@text", text)
    }

    /**
     * 「作者：xxx」那一行。作者名有时包在链接里（`<p>作者：<a>xxx</a></p>`），所以按元素**自己的**文字
     * 认这一行、取它的**全部**文字，再用 `##` 把前缀和后面同行的其他字段（「分类：…」）剥掉。
     *
     * 选择器用 `:matchesOwn` 按文字找而不按位置：同站的书，作者行在页面上的位置不一定一样。
     * 正则里刻意不写反斜杠 —— 规则串要过 Legado 解析和 jsoup 两道转义，`.{0,6}` 兼顾「作&nbsp;&nbsp;者」。
     */
    private fun authorLine(doc: Document): DetectedField? {
        val el = doc.body()?.allElements?.firstOrNull { isAuthorLine(it) } ?: return null
        val value = AUTHOR_STRIP.replace(el.text(), "").trim()
        if (value.isEmpty() || value.length > MAX_AUTHOR) return null
        val selector = "${el.normalName()}:matchesOwn($AUTHOR_OWN)"
        if (CssPaths.select(doc, selector)?.firstOrNull() !== el) return null
        return DetectedField("@css:$selector@text##${AUTHOR_STRIP.pattern}", value)
    }

    private fun isAuthorLine(el: Element): Boolean {
        val own = el.ownText()
        return own.length <= MAX_AUTHOR_LINE && AUTHOR_OWN_REGEX.containsMatchIn(own)
    }

    internal const val AUTHOR_OWN = "^.{0,3}作.{0,6}者.{0,3}[：:]"
    private val AUTHOR_OWN_REGEX = Regex(AUTHOR_OWN)

    /** 剥掉「作者：」前缀，以及作者名后面同一行的其他字段 */
    internal val AUTHOR_STRIP = Regex("^[^：:]*[：:][\\s\\u00a0]*|[\\s\\u00a0].*$")

    private val TOC_LINK = Regex(
        "^(查看|进入|点击|浏览|返回)?\\s*(完整|全部|所有)?\\s*(目录|章节|章节目录|章节列表|目录列表)\\s*[>›»→]*$",
    )

    private val BOOK_PAGE_LINK = Regex("^(返回)?\\s*(书页|书籍详情|作品详情|书籍首页|小说首页|返回书架)\\s*[>›»→]*$")

    /**
     * 章节页的「上一章 / 下一章」导航。目录页、详情页没有这一对，章节页几乎都有 ——
     * 区分用户给的是哪种页面，靠它最稳。
     */
    private val CHAPTER_NAV = Regex("^[<‹«←]*\\s*(上|下)\\s*一?\\s*(章|节|回)\\s*[>›»→]*$")

    /**
     * 是不是章节页：有上一章 / 下一章导航，又有一大块正文。
     *
     * 用户粘过来的常是正在读的那一章。章节页上唯一的 h1 是章节名，拿去当书名就错了；
     * 地址当书的地址存下来，详情、目录都从这一章去认，一路认偏。
     */
    fun isChapterPage(doc: Document): Boolean {
        val hasNav = doc.body()?.select("a")?.any { CHAPTER_NAV.matches(it.text().trim()) } ?: false
        if (!hasNav) return false
        val best = ContentDetector.detect(doc, limit = 1).firstOrNull() ?: return false
        return best.textLength >= CHAPTER_PAGE_MIN_CHARS
    }

    /** 章节页正文至少这么多字：分页章节的第一页也有上千字，详情页的简介很少过两百 */
    private const val CHAPTER_PAGE_MIN_CHARS = 200

    private const val MAX_TITLE = 40
    private const val MAX_AUTHOR = 30
    private const val MAX_AUTHOR_LINE = 40
}
