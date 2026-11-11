package com.radium.inkwell.core.parser.html

import com.radium.inkwell.core.model.ContentElement
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * HTML → 段落流转换器。EPUB 章节、MOBI 正文、书源抓取的正文三方共用。
 * CSS 全部丢弃，只保留段落 / 标题 / 分隔线。
 *
 * `<img>` / `<image>` / svg 内嵌图直接抹掉：产品只排文本，收进来会让分页按
 * 整栏宽 4:3 留空框，滚动时半页空白。封面走元数据，不走正文元素。
 *
 * [keepImages] 只给订阅文章开：文章页是滚动着看的（不分页），图是文章的一部分。
 * 从前订阅也共用不收图的这一版，文章页写好的显示图片那段代码一张图都没等到过。
 *
 * 表情图（WordPress 的 wp-smiley 之类）不管收不收图，一律换回它的 alt 那个字、留在段落里（见 [emojiText]）。
 */
class HtmlToElements(private val keepImages: Boolean = false) {

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
                        val text = headingText(node)
                        if (text.isNotEmpty()) out += ContentElement.Heading(level, text)
                    }
                    "img", "image" -> {
                        // 表情图就是一个字：接回这一段里，不切段、不当插图（见 emojiText）
                        val emoji = emojiText(node)
                        if (emoji != null) {
                            textBuf.append(emoji)
                        } else {
                            flushText()
                            if (keepImages) imageOf(node)?.let { out += it }
                        }
                    }
                    "hr" -> {
                        flushText()
                        out += ContentElement.Divider
                    }
                    "br" -> flushText()
                    // figure / figcaption：图注自成一段，不和图后面的正文粘在一起
                    "p", "div", "li", "blockquote", "td", "section", "article", "dd", "dt", "figure", "figcaption" -> {
                        flushText()
                        node.childNodes().forEach(::walk)
                        flushText()
                    }
                    "script", "style", "head", "svg", "table" -> {
                        // table 退化为逐格文本；svg 整块丢掉（里面的 image 也不收）
                        if (node.tagName() == "table") {
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

    /**
     * 一张图的地址。懒加载的站 `src` 常是占位（一像素的 data: 图、loading.gif），真地址在
     * `data-src` / `data-original` 这类属性上；`src` 像真图就用它（少数派的 `src` 是按手机宽度缩过的版本，
     * `data-original` 是原图，动辄几 MB）。相对地址按页面地址补全，补不全的不要。
     * 一两像素的统计像素（feed 里常夹一个）也不要。
     */
    private fun imageOf(img: Element): ContentElement.Image? {
        val tiny = sequenceOf("width", "height").any { img.attr(it).trim().toIntOrNull()?.let { n -> n <= TRACKING_PIXEL } == true }
        if (tiny) return null
        fun usable(name: String) = img.attr(name).trim().let { it.isNotEmpty() && !it.startsWith("data:", ignoreCase = true) }
        val lazy = LAZY_SRC_ATTRS.firstOrNull(::usable)
        // 有懒加载属性、src 又像占位图时才换：占位图的判断只看名字，没有别的地址可换时宁可信 src
        val attr = when {
            usable("src") && (lazy == null || !PLACEHOLDER.containsMatchIn(img.attr("src"))) -> "src"
            else -> lazy
        } ?: return null
        // absUrl：按页面地址补全相对地址；没有页面地址时相对地址补不出来，返回空串
        val url = img.absUrl(attr).takeIf { it.startsWith("http", ignoreCase = true) } ?: return null
        return ContentElement.Image(url, img.attr("alt").trim().ifEmpty { null })
    }

    /**
     * 表情图换回的那个字；不是表情图返回 null，是表情图但没写 alt 返回空串（丢掉，不切段）。
     *
     * WordPress 在 feed 和页面里把正文的 emoji 换成一张 72×72 的小图（`class="wp-smiley"`，
     * 地址在 s.w.org 的表情图库），网页上靠 `height: 1em` 缩成字高；Twemoji、GitHub、Discourse 同理。
     * 我们不读 CSS，当插图收进来就是按正文宽度铺满 —— 爱范儿的「图｜官方博客🔗 链接」那个 🔗
     * 被放大成整屏宽的一团糊，早报每条新闻前的 🚨💻🧠 一篇二十几个（用户截图反馈）。
     * 不收图的小说正文也有一半毛病：图当段落边界，一句话从表情那儿断成两段。
     *
     * alt 就是那个 emoji 本身，换回文字和原网页看到的一样；alt 太长的（不像一个字）不要，免得把一句描述塞进正文。
     */
    private fun emojiText(img: Element): String? {
        val isEmoji = EMOJI_CLASS.containsMatchIn(img.className()) ||
            EMOJI_SRC.containsMatchIn(img.attr("src"))
        if (!isEmoji) return null
        return img.attr("alt").trim().takeIf { it.length <= MAX_EMOJI_ALT } ?: ""
    }

    /** 小标题的字。`text()` 不念图，标题里的表情图得先换成文字，不然就丢了 */
    private fun headingText(heading: Element): String {
        val emojis = heading.select("img").filter { emojiText(it) != null }
        if (emojis.isEmpty()) return heading.text().trim()
        // 在副本上换：别改调用方传进来的文档
        val copy = heading.clone()
        copy.select("img").forEach { img -> emojiText(img)?.let { img.replaceWith(TextNode(it)) } }
        return copy.text().trim()
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

        /** 懒加载放真地址的属性，常见的几种 */
        val LAZY_SRC_ATTRS = arrayOf("data-src", "data-original", "data-lazy-src", "data-actualsrc", "data-url")

        /** `src` 是这种的，当它是懒加载的占位图 */
        val PLACEHOLDER = Regex("(?i)blank\\.(gif|png)|placeholder|loading\\.(gif|svg|png)|lazy|spacer\\.|pixel\\.(gif|png)")

        /** 宽或高不到这么多像素的是统计像素，不是插图 */
        const val TRACKING_PIXEL = 2

        /** 表情图的 class：WordPress 的 wp-smiley，Twemoji / Discourse 的 emoji，emojione 之类 */
        val EMOJI_CLASS = Regex("(?i)smiley|emoji|emoticon")

        /** 表情图库的地址：WordPress（s.w.org/images/core/emoji/）、Twemoji、GitHub、Discourse 都带 emoji 一段路径 */
        val EMOJI_SRC = Regex("(?i)/emoji/|twemoji|emojione|joypixels")

        /** 表情的 alt 最长这么多个 UTF-16 单元：带 ZWJ 的组合（一家四口）十一个，Discourse 的 `:link:` 这类短码也在内 */
        const val MAX_EMOJI_ALT = 16

        /**
         * 段首信号：换行 + 全角空格 / `&nbsp;` 缩进。
         *
         * **只认这个组合**，不能见换行就切 —— HTML 源码里为了可读性折的行（换行后跟的是
         * 普通空格）不是段落边界，浏览器也只把它渲染成一个空格，切了就成了过度分段。
         * 而全角空格与 `&nbsp;` 是模板/作者**有意**打出来的缩进，普通空格不是，
         * 这正是两者能被区分开的依据。
         *
         * 第二支是同一个信号**被 Jsoup 压扁之后**的样子：`@html` 规则取的是 outerHtml()，
         * pretty-print 把文本里的换行折成了一个空格，`\n　　段2` 到这里只剩 ` 　　段2`。
         * 不认它的话，最常见的正文规则 `id.content@html` 会把整章挤成一段、段间夹着缩进空隙。
         * 这一支要求**至少两个**缩进字符：正文里空格后偶尔跟一个全角空格不算分段。
         * （不去关掉 pretty-print：Legado 的 @html 也是 pretty 的，书源里接在后面的 `##替换##`
         * 是照着那个格式写的。）
         */
        val INDENT_BREAK = Regex("\\n[ \\t]*[　\\u00A0]+|[ \\t]+[　\\u00A0]{2,}")
    }
}
