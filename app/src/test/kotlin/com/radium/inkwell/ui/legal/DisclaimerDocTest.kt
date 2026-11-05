package com.radium.inkwell.ui.legal

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 协议页按行首认结构（DisclaimerDoc.kt）。条文只在 `assets/legal/disclaimer.txt` 一份，页面照认出来的结构排版：
 * 这里钉住两件事 —— 现在这份条文认得出来（一张提示卡、七节、每节的条数），以及**一个字都不丢**：
 * 认出来的结构原样拼回去，和原文逐行一样。格式改坏了只许排得难看，不许漏字。
 */
class DisclaimerDocTest {

    private val raw = File("src/main/assets/legal/disclaimer.txt").readText(Charsets.UTF_8)

    @Test
    fun `current disclaimer parses into notice and seven sections`() {
        val doc = parseDisclaimer(raw)
        val notice = doc.blocks.first() as DisclaimerBlock.Notice
        assertEquals("特别提示", notice.title)
        assertTrue(notice.text.startsWith("在下载、安装或使用 Inkwell"))
        assertEquals(listOf("一", "二", "三", "四", "五", "六", "七"), doc.sections.map { it.numeral })
        assertEquals(listOf(4, 4, 3, 4, 4, 3, 1), doc.sections.map { it.items.size })
        assertEquals(listOf("1", "2", "3", "4"), doc.sections.first().items.map { it.number })
        // 「七、协议变更」底下是一段不带编号的话
        assertEquals(null, doc.sections.last().items.single().number)
        assertTrue(doc.blocks.none { it is DisclaimerBlock.Paragraph })
    }

    @Test
    fun `nothing is lost when the structure is put back together`() {
        assertEquals(nonBlankLines(raw), rebuild(parseDisclaimer(raw)))
    }

    @Test
    fun `unrecognized text falls back to plain paragraphs`() {
        val text = "第一行随便写\n\n1. 没有节的编号行\n还有一行"
        val doc = parseDisclaimer(text)
        assertTrue(doc.blocks.all { it is DisclaimerBlock.Paragraph })
        assertEquals(nonBlankLines(text), rebuild(doc))
    }

    @Test
    fun `chip titles drop the second half and trailing filler`() {
        val titles = parseDisclaimer(raw).sections.map { it.shortTitle }
        assertEquals(listOf("软件性质", "用户行为", "第三方内容", "隐私", "知识产权", "免责", "协议变更"), titles)
        // 去完什么都不剩就用原标题
        assertEquals("说明", shortSectionTitle("说明"))
    }

    @Test
    fun `active section is the last one whose top has scrolled past the line`() {
        val tops = mapOf(0 to 100, 1 to 400, 2 to 900)
        assertEquals(0, activeSection(tops, 3, 0))
        assertEquals(0, activeSection(tops, 3, 399))
        assertEquals(1, activeSection(tops, 3, 400))
        assertEquals(2, activeSection(tops, 3, 5000))
        // 还没排好版的节不算
        assertEquals(0, activeSection(mapOf(0 to 100), 3, 5000))
    }

    private fun nonBlankLines(text: String): List<String> = text.lines().map { it.trim() }.filter { it.isNotEmpty() }

    private fun rebuild(doc: DisclaimerDoc): List<String> = doc.blocks.flatMap { block ->
        when (block) {
            is DisclaimerBlock.Notice -> listOf("【${block.title}】") + block.text.lines().filter { it.isNotEmpty() }
            is DisclaimerBlock.Section -> listOf("${block.numeral}、${block.title}") +
                block.items.map { if (it.number != null) "${it.number}. ${it.text}" else it.text }
            is DisclaimerBlock.Paragraph -> listOf(block.text)
        }
    }
}
