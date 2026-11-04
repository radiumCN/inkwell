package com.radium.inkwell.core.source.detect

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode

/**
 * 一个候选正文块。
 *
 * @param rule Legado `ruleContent.content`（`@css:选择器@html`：引擎把 HTML 转成段落，`<br>` / `<p>` 都认）
 * @param textLength 块内正文字数，界面上和预览一起给用户判断是不是选对了
 */
data class ContentCandidate(
    val rule: String,
    val textLength: Int,
    val preview: String,
    val score: Double,
)

/**
 * 正文页识别：找出正文所在的那个元素。
 *
 * 思路与 Readability 一脉：正文块的**直接**内容是大段文字（文本节点，或一段一个的 `<p>` / `<div>`），
 * 中文标点密、链接少。每个元素只数自己直接带的文字，段落元素把字算给父级 —— 所以得分最高的是
 * 「段落们的父亲」，而不是某一段，也不是把导航、推荐一起包进来的外层容器。
 *
 * 两条边界：
 * - 带 `<br>` 的大块（老站整章一个 `div#content`，段落靠 `<br>` 分）自己就是正文块，
 *   **不**把字再算给外层，否则外层包装（往往还夹着广告）会和它打平。
 * - 标题（h1~h6）不算正文：章节名在目录里有，正文里再来一遍是重复。
 */
object ContentDetector {

    fun detect(doc: Document, limit: Int = 3): List<ContentCandidate> {
        val body = doc.body() ?: return emptyList()
        val scored = ArrayList<Pair<Element, Scored>>()
        for (el in body.allElements) {
            if (el.normalName() in SKIP_TAGS) continue
            val s = scoreOf(el) ?: continue
            scored += el to s
        }
        // 同分取深的：外层包装和正文块打平时，要的是正文块本身
        scored.sortWith(compareByDescending<Pair<Element, Scored>> { it.second.score }.thenByDescending { it.first.parents().size })
        val picked = mutableListOf<Pair<Element, Scored>>()
        for (pair in scored) {
            val el = pair.first
            // 互相包含的只留分高的：外层与内层是同一块正文的两种选法，不是两个候选
            if (picked.any { (p, _) -> p === el || el.parents().any { it === p } || p.parents().any { it === el } }) continue
            picked += pair
            if (picked.size >= limit) break
        }
        return picked.map { (el, s) -> candidateOf(el, s) }
    }

    /**
     * 用户在网页上点了正文里的一段（[snippet] 是点中元素的文字开头）：找到页面上含这段字的最深元素，
     * 再从它往上挑得分最高的那一层。点中的常是一个 `<p>`，正文块是它的父亲；点中老站整章一个
     * `div#content` 时，它自己就是。网页与静态 HTML 的空白不一样（innerText 带换行），比对前都去掉空白。
     */
    fun detectAround(doc: Document, snippet: String): ContentCandidate? {
        val needle = snippet.filterNot(Char::isWhitespace).take(SNIPPET_CHARS)
        if (needle.length < MIN_SNIPPET_CHARS) return null
        val body = doc.body() ?: return null
        val hit = body.allElements
            .filter { it.normalName() !in SKIP_TAGS && it.text().filterNot(Char::isWhitespace).contains(needle) }
            .maxByOrNull { it.parents().size }
            ?: return null
        return (listOf(hit) + hit.parents().takeWhile { it !== body.parent() })
            .mapNotNull { el -> scoreOf(el)?.let { el to it } }
            .maxByOrNull { it.second.score }
            ?.let { (el, s) -> candidateOf(el, s) }
    }

    private fun candidateOf(el: Element, s: Scored) = ContentCandidate(
        rule = "@css:${CssPaths.unique(el)}@html",
        textLength = s.textLength,
        preview = el.text().take(PREVIEW_CHARS),
        score = s.score,
    )

    private class Scored(val textLength: Int, val score: Double)

    private fun scoreOf(el: Element): Scored? {
        var textLen = 0
        var punct = 0
        for (node in el.childNodes()) {
            val text = when (node) {
                is TextNode -> node.text().trim()
                is Element -> if (isParagraph(node)) node.text() else continue
                else -> continue
            }
            textLen += text.length
            punct += text.count { it in PUNCTUATION }
        }
        if (textLen < MIN_TEXT) return null
        val total = el.text().length.coerceAtLeast(1)
        val linkText = el.select("a").sumOf { it.text().length }
        val linkDensity = (linkText.toDouble() / total).coerceAtMost(1.0)
        if (linkDensity > MAX_LINK_DENSITY) return null
        return Scored(textLen, (textLen + punct * PUNCT_WEIGHT) * (1 - linkDensity))
    }

    /**
     * 能把字算给父级的「一段」：`<p>`，或没有块级后代、没有 `<br>`、又不太长的元素
     * （`<div>一段</div>` 一段一个的写法）。链接占比高的不算（「上一章 目录 下一章」那一行）。
     */
    private fun isParagraph(el: Element): Boolean {
        val tag = el.normalName()
        if (tag in SKIP_TAGS || tag in HEADINGS) return false
        val text = el.text()
        if (text.isEmpty()) return false
        val linkText = el.select("a").sumOf { it.text().length }
        if (linkText > text.length * PARAGRAPH_LINK_RATIO) return false
        if (tag == "p") return true
        val descendants = el.allElements.drop(1)
        return descendants.none { it.normalName() in BLOCK_TAGS || it.normalName() == "br" } &&
            text.length < MAX_INLINE_PARAGRAPH
    }

    private val SKIP_TAGS = setOf(
        "script", "style", "noscript", "iframe", "textarea", "select", "option", "button",
        "nav", "header", "footer", "aside", "form", "head", "title", "meta", "link",
    )
    private val HEADINGS = setOf("h1", "h2", "h3", "h4", "h5", "h6")
    private val BLOCK_TAGS = setOf(
        "p", "div", "section", "article", "ul", "ol", "li", "dl", "dd", "dt", "table", "tr", "td",
        "blockquote", "pre", "h1", "h2", "h3", "h4", "h5", "h6",
    )
    private const val PUNCTUATION = "，。！？；：、“”‘’…—,.!?;"

    /** 直接文字不到这么多不当候选（导航、页脚、版权行） */
    private const val MIN_TEXT = 50

    /** 链接字占全部文字的比例超过它就不是正文（目录、推荐列表） */
    private const val MAX_LINK_DENSITY = 0.5
    private const val PARAGRAPH_LINK_RATIO = 0.3

    /** 中文标点是正文最稳的信号：导航、标签云几乎不带 */
    private const val PUNCT_WEIGHT = 10

    /** 一段「无块级后代的 div」最长多少字还算一段；再长就是整章塞在一个元素里，自己当正文块 */
    private const val MAX_INLINE_PARAGRAPH = 1000
    private const val PREVIEW_CHARS = 160

    /** 点选比对用点中文字的前这么多个字：够认出是哪一段，又不至于跨进下一段（网页与静态页的分段可能不同） */
    private const val SNIPPET_CHARS = 20
    private const val MIN_SNIPPET_CHARS = 4
}
