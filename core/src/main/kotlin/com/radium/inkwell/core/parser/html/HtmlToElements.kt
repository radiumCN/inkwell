package com.radium.inkwell.core.parser.html

import com.radium.inkwell.core.model.ContentElement
import com.radium.inkwell.core.model.textOrNull
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
 *
 * [keepBlocks]（默认跟 [keepImages] 一起，也就是只给订阅文章开）：代码块、列表、引用各成一种元素
 * （[ContentElement.Code] / [ContentElement.ListItem] / [ContentElement.Quote]），文章页按各自的样子排。
 * 从前它们全压成普通段落：代码块的换行、缩进一个不剩，挤成一行（「她和她的猫」那篇被反馈过）；
 * 列表没有圆点，引用和正文一个样。书不开 —— 阅读器只排段落，书里的这些照旧当段落。
 */
class HtmlToElements(
    private val keepImages: Boolean = false,
    private val keepBlocks: Boolean = keepImages,
) {

    fun convert(body: Element): List<ContentElement> {
        val run = Run()
        body.childNodes().forEach(run::walk)
        run.flushText()
        return run.out
    }

    /** 一次转换的状态。写成类而不是 convert 里的局部函数：遍历、列表、引用要互相递归调用 */
    private inner class Run {
        val out = mutableListOf<ContentElement>()
        private val textBuf = StringBuilder()

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
                    "pre" -> if (keepBlocks) {
                        flushText()
                        codeOf(node)?.let { out += it }
                    } else {
                        node.childNodes().forEach(::walk)
                    }
                    "ul", "ol" -> if (keepBlocks) {
                        flushText()
                        listItems(node, depth = 0)
                    } else {
                        node.childNodes().forEach(::walk)
                    }
                    "blockquote" -> {
                        flushText()
                        if (keepBlocks) quote(node) else node.childNodes().forEach(::walk)
                        flushText()
                    }
                    // figure / figcaption：图注自成一段，不和图后面的正文粘在一起
                    "p", "div", "li", "td", "section", "article", "dd", "dt", "figure", "figcaption" -> {
                        flushText()
                        node.childNodes().forEach(::walk)
                        flushText()
                    }
                    "table" -> {
                        if (keepBlocks && node.selectFirst("pre") != null) {
                            // 带行号的代码块（Hugo 的 lntable、不少博客主题）：一格行号、一格代码，各是一个 pre
                            flushText()
                            codeTable(node)
                        } else {
                            // table 退化为逐格文本
                            node.select("td, th").forEach { cell ->
                                val t = cell.text().trim()
                                if (t.isNotEmpty()) out += ContentElement.Paragraph(t)
                            }
                        }
                    }
                    // svg 整块丢掉（里面的 image 也不收）
                    "script", "style", "head", "svg" -> Unit
                    else -> node.childNodes().forEach(::walk)
                }
            }
        }

        /** 列表：每一项一个 [ContentElement.ListItem]，项里嵌着的列表、代码、图跟在它后面 */
        private fun listItems(list: Element, depth: Int) {
            val ordered = list.tagName().equals("ol", ignoreCase = true)
            var number = list.attr("start").trim().toIntOrNull() ?: 1
            for (child in list.children()) {
                if (!child.tagName().equals("li", ignoreCase = true)) {
                    // 列表里直接夹着别的（写错的 HTML）：照普通内容走
                    walk(child)
                    flushText()
                    continue
                }
                val marker = if (ordered) "${number++}." else BULLETS[minOf(depth, BULLETS.lastIndex)]
                val text = inlineText(child)
                if (text.isNotEmpty()) out += ContentElement.ListItem(text, marker, depth)
                listItemBlocks(child, depth)
            }
        }

        /** 列表项里不是字的那些：嵌套的列表、代码、图（字已经在 [inlineText] 里取过） */
        private fun listItemBlocks(node: Element, depth: Int) {
            for (child in node.children()) {
                when (child.tagName().lowercase()) {
                    "ul", "ol" -> listItems(child, depth + 1)
                    "pre" -> codeOf(child)?.let { out += it }
                    "img", "image" -> if (keepImages && emojiText(child) == null) imageOf(child)?.let { out += it }
                    "table", "script", "style", "svg" -> Unit
                    else -> listItemBlocks(child, depth)
                }
            }
        }

        /**
         * 引用：里面的段落、小标题合成一个 [ContentElement.Quote]（段与段之间换行），图跟在它后面。
         * 引用里再套代码块、列表的极少，按普通段落收进引用的字里。
         */
        private fun quote(blockquote: Element) {
            val inner = HtmlToElements(keepImages = keepImages, keepBlocks = false).convert(blockquote)
            val text = inner
                .filter { it is ContentElement.Paragraph || it is ContentElement.Heading }
                .mapNotNull { it.textOrNull }
                .joinToString("\n")
            if (text.isNotBlank()) out += ContentElement.Quote(text)
            out += inner.filterIsInstance<ContentElement.Image>()
        }

        private fun codeTable(table: Element) {
            for (pre in table.select("pre")) {
                // 行号那一格：只有数字和空白
                if (LINE_NUMBERS_ONLY.matches(pre.wholeText())) continue
                codeOf(pre)?.let { out += it }
            }
        }
    }

    /**
     * 代码块的字：原样的换行与缩进。代码高亮把一行拆成一堆 span，换行字符还在里面，照原样拼回来；
     * 有的高亮器一行一个 div、不带换行字符，或者用 br 换行，这两种补上换行。行内行号（Hugo 的 `ln`）不要。
     * 首尾的空行去掉，每行行尾的空白去掉；制表符换成四个空格（Compose 的 Text 不按制表位对齐）。
     */
    private fun codeOf(pre: Element): ContentElement.Code? {
        val sb = StringBuilder()
        fun newLine() {
            if (sb.isNotEmpty() && sb.last() != '\n') sb.append('\n')
        }
        fun collect(node: Node) {
            when (node) {
                is TextNode -> sb.append(node.wholeText)
                is Element -> {
                    val tag = node.tagName().lowercase()
                    when {
                        LINE_NUMBER_CLASS.containsMatchIn(node.className()) -> Unit
                        tag == "br" -> sb.append('\n')
                        tag == "img" || tag == "image" -> emojiText(node)?.let { sb.append(it) }
                        tag in CODE_LINE_TAGS -> {
                            newLine()
                            node.childNodes().forEach(::collect)
                            newLine()
                        }
                        else -> node.childNodes().forEach(::collect)
                    }
                }
            }
        }
        pre.childNodes().forEach(::collect)
        val lines = sb.toString()
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace('\u00A0', ' ')
            .replace("\t", TAB)
            .lines()
            .map { it.trimEnd() }
            .dropWhile { it.isBlank() }
            .dropLastWhile { it.isBlank() }
        if (lines.isEmpty()) return null
        return ContentElement.Code(lines.joinToString("\n"), languageOf(pre))
    }

    /**
     * 代码块标的语言：`language-bash` / `lang-bash`（Hugo、Prism、highlight.js）、`brush: bash`（WordPress 的
     * SyntaxHighlighter）、`data-lang` 这类属性。看 pre 自己和它里面的 code；外面两层只认 `language-`
     * （Jekyll 把它挂在包着的 div 上），`lang-` 太容易撞上别的（`lang-zh` 是网页语言）。text、plaintext 这类等于没标。
     */
    private fun languageOf(pre: Element): String? {
        fun clean(raw: String): String? =
            raw.trim().lowercase().takeIf { it.isNotEmpty() && it.length <= MAX_LANGUAGE && it !in NO_LANGUAGE }

        fun fromSelf(el: Element): String? {
            LANGUAGE_ATTRS.firstNotNullOfOrNull { el.attr(it).takeIf(String::isNotBlank) }?.let { return clean(it) }
            val m = LANGUAGE_CLASS.find(el.className()) ?: return null
            return clean(m.groupValues[1].ifEmpty { m.groupValues[2] })
        }

        fromSelf(pre)?.let { return it }
        pre.selectFirst("code")?.let { code -> fromSelf(code)?.let { return it } }
        var ancestor = pre.parent()
        repeat(2) {
            val a = ancestor ?: return null
            ANCESTOR_LANGUAGE_CLASS.find(a.className())?.let { m -> clean(m.groupValues[1])?.let { return it } }
            ancestor = a.parent()
        }
        return null
    }

    /**
     * 列表项的字：项里的段落、行内样式都摊平成一行，嵌套的列表、代码、表格不算（它们另成元素，见 listItemBlocks），
     * 表情图换回文字。在副本上改：别动调用方传进来的文档。
     */
    private fun inlineText(item: Element): String {
        val copy = item.clone()
        copy.select("ul, ol, pre, table, script, style, svg").remove()
        copy.select("img, image").forEach { img ->
            val emoji = emojiText(img)
            if (emoji != null) img.replaceWith(TextNode(emoji)) else img.remove()
        }
        return normalize(copy.text())
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
        if (!isEmojiImage(img)) return null
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

        /** 表情的 alt 最长这么多个 UTF-16 单元：带 ZWJ 的组合（一家四口）十一个，Discourse 的 `:link:` 这类短码也在内 */
        const val MAX_EMOJI_ALT = 16

        /** 无序列表的圆点，按嵌套层数：第一层实心、第二层空心、再往里方块 */
        val BULLETS = arrayOf("•", "◦", "▪")

        /** 代码里一行一个元素的高亮器用的标签：进出时补换行 */
        val CODE_LINE_TAGS = setOf("div", "p", "li")

        /** 行内行号（Hugo chroma 的 `ln`、`lnt`，别的主题的 line-number / lineno / gutter） */
        val LINE_NUMBER_CLASS = Regex("(?i)(^|\\s)(ln|lnt|lineno|line-?numbers?|gutter)(\\s|$)")

        /** 带行号的代码表里，行号那一格的字只有数字和空白 */
        val LINE_NUMBERS_ONLY = Regex("[\\d\\s]*")

        const val TAB = "    "

        val LANGUAGE_ATTRS = arrayOf("data-lang", "data-language", "data-enlighter-language")
        val LANGUAGE_CLASS = Regex("(?:^|\\s)(?:language|lang)-([\\w+#.-]+)|brush:\\s*([\\w+#-]+)")
        val ANCESTOR_LANGUAGE_CLASS = Regex("(?:^|\\s)language-([\\w+#.-]+)")
        val NO_LANGUAGE = setOf("text", "plaintext", "plain", "txt", "none", "nohighlight", "no-highlight")
        const val MAX_LANGUAGE = 20

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

/**
 * 这张图是不是表情（WordPress 的 wp-smiley、Twemoji、GitHub、Discourse 的 emoji 图）：网页上靠 CSS 缩成一个字高，
 * 我们不读 CSS，当插图就会被放大。正文解析（[HtmlToElements]）把它换回文字，订阅列表挑缩略图时跳过它
 * （爱范儿早报的摘要里，头一张图常常就是一个 🚨）。
 */
fun isEmojiImage(img: Element): Boolean =
    EMOJI_CLASS.containsMatchIn(img.className()) || EMOJI_SRC.containsMatchIn(img.attr("src"))

/** 表情图的 class：WordPress 的 wp-smiley，Twemoji / Discourse 的 emoji，emojione 之类 */
private val EMOJI_CLASS = Regex("(?i)smiley|emoji|emoticon")

/** 表情图库的地址：WordPress（s.w.org/images/core/emoji/）、Twemoji、GitHub、Discourse 都带 emoji 一段路径 */
private val EMOJI_SRC = Regex("(?i)/emoji/|twemoji|emojione|joypixels")
