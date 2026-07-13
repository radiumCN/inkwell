package com.radium.inkwell.core.parser.html

import com.radium.inkwell.core.model.ContentElement
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * HTML → 段落流转换器。EPUB 章节、MOBI 正文、书源抓取的正文三方共用。
 * CSS 全部丢弃，只保留段落 / 标题 / 图片 / 分隔线结构。
 *
 * @param resolveImage 把 img 的 src 原值转换为统一模型的 resourceId
 *（EPUB 中解析为 zip 内路径，MOBI 中是 recindex，书源中是绝对 URL）
 */
class HtmlToElements(
    private val resolveImage: (Element) -> String? = { it.attr("src").ifBlank { null } },
) {

    fun convert(body: Element): List<ContentElement> {
        val out = mutableListOf<ContentElement>()
        val textBuf = StringBuilder()

        fun flushText() {
            val text = normalize(textBuf)
            textBuf.setLength(0)
            if (text.isNotBlank()) out += ContentElement.Paragraph(text)
        }

        fun walk(node: Node) {
            when (node) {
                is TextNode -> textBuf.append(node.text())
                is Element -> when (node.tagName().lowercase()) {
                    "h1", "h2", "h3", "h4", "h5", "h6" -> {
                        flushText()
                        val level = node.tagName()[1].digitToInt()
                        val text = node.text().trim()
                        if (text.isNotEmpty()) out += ContentElement.Heading(level, text)
                    }
                    "img", "image" -> {
                        flushText()
                        resolveImage(node)?.let {
                            out += ContentElement.Image(it, node.attr("alt").ifBlank { null })
                        }
                    }
                    "hr" -> {
                        flushText()
                        out += ContentElement.Divider
                    }
                    "br" -> flushText()
                    "p", "div", "li", "blockquote", "td", "section", "article", "dd", "dt" -> {
                        flushText()
                        node.childNodes().forEach(::walk)
                        flushText()
                    }
                    "script", "style", "head", "svg", "table" -> {
                        // svg 内的 image 单独处理；table 退化为逐格文本
                        if (node.tagName() == "svg") {
                            node.select("image").forEach { img ->
                                resolveImage(img)?.let { out += ContentElement.Image(it, null) }
                            }
                        } else if (node.tagName() == "table") {
                            node.select("td, th").forEach { cell ->
                                val t = cell.text().trim()
                                if (t.isNotEmpty()) out += ContentElement.Paragraph(t)
                            }
                        }
                    }
                    else -> node.childNodes().forEach(::walk)
                }
            }
        }

        body.childNodes().forEach(::walk)
        flushText()
        return out
    }

    /** 折叠空白；全角空格开头的缩进交给排版层，不保留 */
    private fun normalize(sb: StringBuilder): String =
        sb.toString()
            .replace('\u00A0', ' ')
            .replace(WHITESPACE, " ")
            .trim()
            .trimStart('　')
            .trim()

    private companion object {
        val WHITESPACE = Regex("[ \\t\\r\\n]+")
    }
}
