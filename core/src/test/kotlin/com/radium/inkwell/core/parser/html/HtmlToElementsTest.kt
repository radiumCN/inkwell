package com.radium.inkwell.core.parser.html

import com.radium.inkwell.core.model.ContentElement
import org.jsoup.Jsoup
import kotlin.test.Test
import kotlin.test.assertEquals

class HtmlToElementsTest {

    private fun paragraphs(html: String): List<String> =
        HtmlToElements().convert(Jsoup.parse(html).body())
            .filterIsInstance<ContentElement.Paragraph>()
            .map { it.text }

    /**
     * 不少站点正文既不用 `<p>` 也不用 `<br>`：靠原始换行分段，每段开头两个全角空格缩进。
     * 从前这类源整章会挤成一大坨 —— 换行被 Jsoup 的 text() 归一化成空格、结构当场丢失，
     * 而段首的 `　　` 不在 WHITESPACE 字符类里，于是原样留在句子之间，
     * 变成正文里一个个三字符宽的空隙（本该是段落边界的地方）。
     */
    @Test
    fun `原始换行加全角缩进的源应按段切分`() {
        val paras = paragraphs(
            "<div id=\"content\">\n" +
                "　　一拳碰撞，火星四溅！\n" +
                "　　火神闷哼一声，倒飞了出去。\n" +
                "　　他的嘴角，溢出鲜血。\n" +
                "</div>",
        )
        assertEquals(
            listOf("一拳碰撞，火星四溅！", "火神闷哼一声，倒飞了出去。", "他的嘴角，溢出鲜血。"),
            paras,
        )
    }

    /**
     * 同一类源走 `@html` 规则时，outerHtml() 的 pretty-print 先把文本里的换行折成了空格：
     * `\n　　段2` 到转换这一步只剩 ` 　　段2`。不认这个形态，整章还是一段。
     */
    @Test
    fun `被 pretty-print 压扁的换行加缩进同样分段`() {
        val paras = paragraphs("<div id=\"BookText\">　　一拳碰撞，火星四溅！ 　　火神闷哼一声。 　　他的嘴角，溢出鲜血。</div>")
        assertEquals(listOf("一拳碰撞，火星四溅！", "火神闷哼一声。", "他的嘴角，溢出鲜血。"), paras)
    }

    /** 没有换行时只认两个以上的缩进字符：空格后偶尔跟一个全角空格不是段落边界 */
    @Test
    fun `空格后跟单个全角空格不分段`() {
        assertEquals(listOf("他说： 　好。"), paragraphs("<div>他说： 　好。</div>"))
    }

    /** 单个全角空格缩进的站点同样要切开 */
    @Test
    fun `单个全角空格缩进也算段首`() {
        val paras = paragraphs("<div>\n　第一段\n　第二段\n</div>")
        assertEquals(listOf("第一段", "第二段"), paras)
    }

    /**
     * 反向保险：HTML 源码里为了可读性折的行**不是**段落边界，浏览器也只渲染成一个空格。
     * 只认「换行 + 全角缩进」这个组合，就是为了不误伤这种情况。
     */
    @Test
    fun `纯源码折行不该被当成分段`() {
        val paras = paragraphs("<p>\n  第一行接着\n  第二行是同一段\n</p>")
        assertEquals(listOf("第一行接着 第二行是同一段"), paras)
    }

    /** 规规矩矩用 `<p>` 的源不受影响，段首缩进照旧剥掉（缩进交给排版层） */
    @Test
    fun `p 标签分段的源保持原样`() {
        val paras = paragraphs("<div>\n  <p>　　第一段</p>\n  <p>　　第二段</p>\n</div>")
        assertEquals(listOf("第一段", "第二段"), paras)
    }

    /** 不收图（默认）：正文里的图不当元素、也不占位；前后文本仍按原分段切开 */
    @Test
    fun `img 和 svg image 直接抹掉`() {
        val els = HtmlToElements().convert(
            Jsoup.parse(
                "<p>前段</p><img src=\"/a.jpg\" alt=\"插图\"><p>后段</p>" +
                    "<svg><image href=\"/b.png\"/></svg>",
            ).body(),
        )
        assertEquals(emptyList(), els.filterIsInstance<ContentElement.Image>())
        assertEquals(listOf("前段", "后段"), els.filterIsInstance<ContentElement.Paragraph>().map { it.text })
    }

    // ---- 书：EPUB / MOBI 的地址交给解析器自己换 ----

    private fun bookImages(html: String) =
        HtmlToElements(keepImages = true, keepBlocks = false, resolveImage = { img ->
            img.attr("src").ifBlank { img.attr("xlink:href") }.ifBlank { img.attr("href") }
                .takeIf { it.isNotBlank() }?.let { "zip:$it" }
        }).convert(Jsoup.parse(html).body())

    /**
     * EPUB 的整页插画是 svg 包着一个 image（svg 只为按页面等比缩放）。订阅里的 svg 多是图标，照旧整块丢；
     * 书里得收，不然轻小说的彩页一张都出不来。图前后的段落各自成段、顺序不乱
     */
    @Test
    fun `书里的 img 与 svg 包着的 image 都收，地址交给解析器`() {
        val els = bookImages(
            "<p>前段</p><img src=\"../Images/1.jpg\" alt=\"插图一\"><p>中段</p>" +
                "<div><svg viewBox=\"0 0 600 800\"><image width=\"600\" height=\"800\" xlink:href=\"../Images/2.jpg\"/></svg></div>" +
                "<p>后段</p>",
        )
        assertEquals(
            listOf(
                ContentElement.Paragraph("前段"),
                ContentElement.Image("zip:../Images/1.jpg", "插图一"),
                ContentElement.Paragraph("中段"),
                ContentElement.Image("zip:../Images/2.jpg"),
                ContentElement.Paragraph("后段"),
            ),
            els,
        )
    }

    /** 解析器认不出的地址（resolveImage 给 null）不收；表情图照旧换回文字、不切段 */
    @Test
    fun `书里认不出的图不收，表情图仍换回字`() {
        val els = HtmlToElements(keepImages = true, keepBlocks = false, resolveImage = { null }).convert(
            Jsoup.parse(
                "<p>前文<img src=\"x.jpg\"/>后文</p>" +
                    "<p>赞<img class=\"emoji\" alt=\"👍\" src=\"https://twemoji.maxcdn.com/72x72/1f44d.png\"/>好</p>",
            ).body(),
        )
        assertEquals(
            listOf(
                ContentElement.Paragraph("前文"),
                ContentElement.Paragraph("后文"),
                ContentElement.Paragraph("赞👍好"),
            ),
            els,
        )
    }

    /** 段内插图当边界切开，避免「前文+后文」粘成一句 */
    @Test
    fun `段内 img 切开前后文本`() {
        val paras = paragraphs("<p>前文<img src=\"x.jpg\"/>后文</p>")
        assertEquals(listOf("前文", "后文"), paras)
    }

    /** `<br>` 分段的源不受影响 */
    @Test
    fun `br 分段的源保持原样`() {
        val paras = paragraphs("<div>　　第一段<br /><br />　　第二段<br /></div>")
        assertEquals(listOf("第一段", "第二段"), paras)
    }

    // ---- keepImages：只给订阅文章开 ----

    private fun withImages(html: String, base: String = "https://site.com/post/1") =
        HtmlToElements(keepImages = true).convert(Jsoup.parse(html, base).body())

    @Test
    fun `订阅文章留图，相对地址按页面补全，图注自成一段`() {
        val els = withImages(
            "<p>前文</p><figure><img src=\"/img/a.png\" alt=\"示意\"/><figcaption>图注</figcaption></figure>后文",
        )
        assertEquals(
            listOf(
                ContentElement.Paragraph("前文"),
                ContentElement.Image("https://site.com/img/a.png", "示意"),
                ContentElement.Paragraph("图注"),
                ContentElement.Paragraph("后文"),
            ),
            els,
        )
    }

    /** 懒加载：src 是占位（data: 图、loading.gif），真地址在 data-src / data-original 上 */
    @Test
    fun `懒加载的图取真地址，src 是真图时用 src`() {
        val els = withImages(
            "<img src=\"data:image/gif;base64,R0lGOD\" data-src=\"https://cdn.com/1.jpg\"/>" +
                "<img src=\"/static/loading.gif\" data-original=\"https://cdn.com/2.jpg\"/>" +
                // 少数派：src 是按手机宽度缩过的，data-original 是几 MB 的原图 —— 用 src
                "<img src=\"https://cdn.com/3.png?w=1120\" data-original=\"https://cdn.com/3.png\"/>" +
                // 名字像占位图、但没有别的地址可换：信 src
                "<img src=\"https://cdn.com/lazy-cat.jpg\"/>",
        )
        assertEquals(
            listOf("https://cdn.com/1.jpg", "https://cdn.com/2.jpg", "https://cdn.com/3.png?w=1120", "https://cdn.com/lazy-cat.jpg"),
            els.filterIsInstance<ContentElement.Image>().map { it.resourceId },
        )
    }

    @Test
    fun `统计像素和只有占位的图不要`() {
        val els = withImages(
            "<p>正文</p><img src=\"https://feeds.example.com/~r/x\" width=\"1\" height=\"1\"/><img src=\"data:image/png;base64,AAAA\"/>",
        )
        assertEquals(listOf(ContentElement.Paragraph("正文")), els)
    }

    // ---- 表情图：换回那个字，不当插图、不切段 ----

    /** WordPress 的表情图，原样取自爱范儿的 feed */
    private fun wpSmiley(code: String, emoji: String) =
        "<img src=\"https://s.w.org/images/core/emoji/17.0.2/72x72/$code.png\" alt=\"$emoji\" " +
            "class=\"wp-smiley\" style=\"height: 1em; max-height: 1em;\" />"

    /** 从前 🔗 被当成插图按正文宽铺满（72px 的图放大成一整屏的一团糊），后面的链接另起一段 */
    @Test
    fun `WordPress 表情图换回文字，留在原来那一段里`() {
        val els = withImages(
            "<div class=\"editor-image-source\">图｜官方博客${wpSmiley("1f517", "🔗")} https://blog.google/a/</div>",
        )
        assertEquals(listOf(ContentElement.Paragraph("图｜官方博客🔗 https://blog.google/a/")), els)
    }

    /** 爱范儿早报：每条新闻前一个表情，放在一个定宽的 span 里 */
    @Test
    fun `段首的表情图成为这一段的第一个字`() {
        val els = withImages(
            "<p><span style=\"display: inline-block; width: 30px;\">${wpSmiley("1f6a8", "🚨")}</span>" +
                "Anthropic 启动上市路演</p><p>${wpSmiley("1f4bb", "💻")}华为小折叠电脑亮相</p>",
        )
        assertEquals(
            listOf(ContentElement.Paragraph("🚨Anthropic 启动上市路演"), ContentElement.Paragraph("💻华为小折叠电脑亮相")),
            els,
        )
    }

    /** 不收图的正文（小说、本地书）同理：从前表情图当段落边界，一句话断成两段 */
    @Test
    fun `不收图时表情图也不切段`() {
        assertEquals(listOf("他笑了😀然后走了"), paragraphs("<p>他笑了${wpSmiley("1f600", "😀")}然后走了</p>"))
    }

    @Test
    fun `其他站点的表情图同样认得，没写 alt 的直接去掉`() {
        val els = withImages(
            "<p>赞<img class=\"emoji\" draggable=\"false\" alt=\"👍\" src=\"https://twemoji.maxcdn.com/v/latest/72x72/1f44d.png\"/>" +
                "好<img src=\"https://github.githubassets.com/images/icons/emoji/unicode/1f389.png\"/>的</p>",
        )
        assertEquals(listOf(ContentElement.Paragraph("赞👍好的")), els)
    }

    @Test
    fun `小标题里的表情图保留成文字`() {
        val els = withImages("<h3>${wpSmiley("1f4a1", "💡")} 今日看点</h3>")
        assertEquals(listOf(ContentElement.Heading(3, "💡 今日看点")), els)
    }

    /** 普通插图照旧：alt 是一句描述、地址不在表情图库里 */
    @Test
    fun `普通插图不受表情规则影响`() {
        val els = withImages("<p>前文<img src=\"https://cdn.com/smile.jpg\" alt=\"一张笑脸照片\"/>后文</p>")
        assertEquals(
            listOf(
                ContentElement.Paragraph("前文"),
                ContentElement.Image("https://cdn.com/smile.jpg", "一张笑脸照片"),
                ContentElement.Paragraph("后文"),
            ),
            els,
        )
    }
}
