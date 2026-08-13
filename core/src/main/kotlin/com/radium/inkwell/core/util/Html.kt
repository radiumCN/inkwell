package com.radium.inkwell.core.util

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * 把书源简介里夹带的 HTML 收成可读正文。
 *
 * 不少源的 intro 规则写成 `div.intro@html`（或站点直接吐带标签的片段），详情页会原样画出
 * `<p>` / `&nbsp;`。没有标签的简介必须原样返回 —— 拿 Jsoup 去「整理」会把 `HP < 50`
 * 这种当半截标签吃掉。
 */
fun String.htmlToPlainText(): String {
    if (isEmpty() || !LOOKS_LIKE_HTML.containsMatchIn(this)) return this
    val text = flatten(Jsoup.parseBodyFragment(this).body())
        .replace(AROUND_NL, "\n")
        .replace(MULTI_NL, "\n\n")
        .trim()
    return text.ifEmpty { this }
}

private val LOOKS_LIKE_HTML = Regex(
    """</?[A-Za-z][A-Za-z0-9]*(\s[^>]*)?/?>|&(?:nbsp|lt|gt|amp|quot|#\d+|#x[0-9A-Fa-f]+);""",
)

private val AROUND_NL = Regex("[ \\t]*\\n[ \\t]*")
private val MULTI_NL = Regex("\n{3,}")
private val HORIZONTAL_SPACE = Regex("[ \\t\\u00A0]+")

private val BLOCK = setOf(
    "p", "div", "li", "h1", "h2", "h3", "h4", "h5", "h6",
    "tr", "blockquote", "section", "article", "br",
)

private fun flatten(root: Element): String = buildString {
    fun walk(node: Node) {
        when (node) {
            is TextNode -> append(HORIZONTAL_SPACE.replace(node.wholeText, " "))
            is Element -> {
                val tag = node.tagName().lowercase()
                if (tag == "script" || tag == "style") return
                if (tag == "br") {
                    append('\n')
                    return
                }
                val block = tag in BLOCK
                if (block && isNotEmpty() && last() != '\n') append('\n')
                node.childNodes().forEach(::walk)
                if (block && isNotEmpty() && last() != '\n') append('\n')
            }
        }
    }
    root.childNodes().forEach(::walk)
}
