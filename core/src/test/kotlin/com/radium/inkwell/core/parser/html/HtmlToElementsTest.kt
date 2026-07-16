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

    /** `<br>` 分段的源不受影响 */
    @Test
    fun `br 分段的源保持原样`() {
        val paras = paragraphs("<div>　　第一段<br /><br />　　第二段<br /></div>")
        assertEquals(listOf("第一段", "第二段"), paras)
    }
}
