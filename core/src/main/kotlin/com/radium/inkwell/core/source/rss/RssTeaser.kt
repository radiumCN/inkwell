package com.radium.inkwell.core.source.rss

import com.radium.inkwell.core.model.ContentElement
import com.radium.inkwell.core.model.textOrNull

/**
 * feed 给的是全文，还是只给了摘要。
 *
 * 少数派、不少新闻站的 feed 每篇只有一两句话加一个「查看全文」链接，点进文章页就只剩那一句。
 * 认出来之后去原网页整理全文（见 [ArticleExtractor]）。两种信号：
 * - 字很少（不到 [SHORT_CHARS]）：一两句话不会是一篇文章。
 * - 以「查看全文 / 阅读全文 / Read more / …」收尾：明说了后面还有（WordPress 默认的摘要以 `[…]` 结尾）。
 *   只认不太长的（[MAX_TEASER_CHARS] 以内）—— 正文本身以省略号结尾的长文不是摘要。
 */
internal object RssTeaser {

    fun isTeaser(elements: List<ContentElement>): Boolean {
        val text = plainText(elements)
        if (text.length < SHORT_CHARS) return true
        return text.length < MAX_TEASER_CHARS &&
            (READ_MORE_END.containsMatchIn(text) || ELLIPSIS_END.containsMatchIn(text))
    }

    /**
     * 去掉末尾的「查看全文」这类字：在 feed 里它是个链接，到文章页成了点不动的死字。
     * 省略号留着 —— 它说明这段话本来就没完。
     */
    fun stripReadMore(elements: List<ContentElement>): List<ContentElement> {
        val i = elements.indexOfLast { it is ContentElement.Paragraph }
        if (i < 0) return elements
        val text = (elements[i] as ContentElement.Paragraph).text
        val stripped = text.replace(READ_MORE_END, "").trimEnd()
        if (stripped == text) return elements
        return elements.toMutableList().apply {
            if (stripped.isEmpty()) removeAt(i) else set(i, ContentElement.Paragraph(stripped))
        }
    }

    /**
     * 正文的字数（段落、小标题、代码、列表、引用；图不算）。代码和列表也得算上：技术文章大半是代码块，
     * 只数段落的话整理出来的全文会被当成「太短、多半认错了块」扔掉。
     */
    fun textLength(elements: List<ContentElement>): Int = elements.sumOf { it.textOrNull?.length ?: 0 }

    private fun plainText(elements: List<ContentElement>): String =
        elements.mapNotNull { it.textOrNull }.joinToString("\n").trim()

    /** 一两句话的长度：比这短的不当全文 */
    private const val SHORT_CHARS = 200

    /** 摘要再长也就这么多；更长的即使以省略号结尾也当全文 */
    private const val MAX_TEASER_CHARS = 1500

    private val READ_MORE_END = Regex(
        "[\\[(（【]?\\s*(查看全文|阅读全文|阅读原文|继续阅读|全文阅读|点击阅读|显示全文|read more|continue reading|keep reading|" +
            "read the full (story|article|post)|read full (story|article|post))\\s*[\\])）】]?[\\s»›>→….:：]*$",
        RegexOption.IGNORE_CASE,
    )

    /** `…` / `...` / WordPress 的 `[…]` */
    private val ELLIPSIS_END = Regex("(…|\\.{3})\\s*]?\\s*$")
}
