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
            val raw = textBuf.toString()
            textBuf.setLength(0)
            // 有的站点正文既不用 <p> 也不用 <br>：靠原始换行分段，每段开头全角空格（或 &nbsp;）缩进。
            // 不切的话整章会挤成一大坨，而段首缩进不在 WHITESPACE 字符类里、会原样留在句子之间，
            // 变成正文里一个个空隙 —— 恰好落在本该分段的位置。
            raw.split(INDENT_BREAK).forEach { piece ->
                val text = normalize(piece)
                if (text.isNotBlank()) out += ContentElement.Paragraph(text)
            }
        }

        fun walk(node: Node) {
            when (node) {
                // 用 wholeText 而不是 text()：后者当场把换行归一化成空格，
                // 上面那类源的段落结构在这一步就没了，再也救不回来
                is TextNode -> textBuf.append(node.wholeText)
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
    private fun normalize(text: String): String =
        text
            .replace('\u00A0', ' ')
            .replace(WHITESPACE, " ")
            .trim()
            .trimStart('　')
            .trim()

    private companion object {
        val WHITESPACE = Regex("[ \\t\\r\\n]+")

        /**
         * 段首信号：换行 + 全角空格 / `&nbsp;` 缩进。
         *
         * **只认这个组合**，不能见换行就切 —— HTML 源码里为了可读性折的行（换行后跟的是
         * 普通空格）不是段落边界，浏览器也只把它渲染成一个空格，切了就成了过度分段。
         * 而全角空格与 `&nbsp;` 是模板/作者**有意**打出来的缩进，普通空格不是，
         * 这正是两者能被区分开的依据。
         */
        val INDENT_BREAK = Regex("\\n[ \\t]*[　\\u00A0]+")
    }
}
