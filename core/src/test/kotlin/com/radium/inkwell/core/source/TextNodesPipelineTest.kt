package com.radium.inkwell.core.source

import com.radium.inkwell.core.model.ContentElement
import com.radium.inkwell.core.parser.html.HtmlToElements
import org.jsoup.Jsoup
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `@textNodes` 正文规则的**完整链路**，与 BookSourceEngine.getContent 一致：
 *
 *     LegadoSelector.strings(rule) → Jsoup.parseBodyFragment → HtmlToElements.convert
 *
 * 单独测 HtmlToElements 是不够的 —— 「若雨中文」（id.BookText@textNodes）那次就是：
 * HtmlToElements 的单测全绿，实际却毫无变化，因为段落结构早在 @textNodes 那一步就被
 * `text()`（换行→空格）和 `trim()`（剥掉全角缩进）毁了，压根轮不到下游的切段逻辑。
 * 这个测试把两段拼起来跑，正是为了让这类「修了上游够不着」的问题当场暴露。
 */
class TextNodesPipelineTest {

    private fun paragraphs(html: String): List<String> {
        val root = Jsoup.parse(html).body()
        // getContent 会把规则结果当 HTML 再解析一次，这里如实复现
        val ruleResult = LegadoSelector.strings(root, "id.BookText@textNodes").joinToString("")
        val body = Jsoup.parseBodyFragment(ruleResult).body()
        return HtmlToElements().convert(body)
            .filterIsInstance<ContentElement.Paragraph>()
            .map { it.text }
    }

    private val expected =
        listOf("一拳碰撞，火星四溅！", "火神闷哼一声，倒飞了出去。", "他的嘴角，溢出鲜血。")

    /** `<br>` 分段 + 段首全角缩进：老站最常见的写法 */
    @Test
    fun `br 分段加全角缩进`() {
        assertEquals(
            expected,
            paragraphs(
                "<div id=\"BookText\">\n" +
                    "　　一拳碰撞，火星四溅！<br />\n" +
                    "　　火神闷哼一声，倒飞了出去。<br />\n" +
                    "　　他的嘴角，溢出鲜血。<br />\n" +
                    "</div>",
            ),
        )
    }

    /**
     * 整章就是**一个**文本节点，靠原始换行分段、每段全角缩进 —— 「若雨中文」正是这种。
     *
     * 从前 @textNodes 里的 `text()` 把换行压成空格、`trim()` 把段首缩进剥掉，
     * 于是整章塌成一段，只在句子之间留下「空格 + 两个全角空格」的空隙 ——
     * 恰好落在本该分段的位置。
     */
    @Test
    fun `原始换行分段加全角缩进`() {
        assertEquals(
            expected,
            paragraphs(
                "<div id=\"BookText\">\n" +
                    "　　一拳碰撞，火星四溅！\n" +
                    "　　火神闷哼一声，倒飞了出去。\n" +
                    "　　他的嘴角，溢出鲜血。\n" +
                    "</div>",
            ),
        )
    }

    /**
     * 已知边界：既没有换行、也没有 `<br>`，整章挤在一行里纯用全角空格分隔。
     *
     * 这种切不开 —— 没有任何信号能把「段落分隔」与「作者有意打的空格」区分开，
     * 强切必然误伤正常内容。它在浏览器里本来也是一坨（除非靠 CSS 撑着，而我们丢弃 CSS）。
     * 钉在这里是为了说明这是**已知取舍**，不是漏掉的 case。
     */
    @Test
    fun `无换行纯全角空格分隔的切不开 —— 已知边界`() {
        assertEquals(
            listOf("一拳碰撞，火星四溅！　　火神闷哼一声，倒飞了出去。"),
            paragraphs("<div id=\"BookText\">　　一拳碰撞，火星四溅！　　火神闷哼一声，倒飞了出去。</div>"),
        )
    }
}
