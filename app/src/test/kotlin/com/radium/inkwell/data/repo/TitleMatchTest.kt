package com.radium.inkwell.data.repo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 换源的判定标准。
 *
 * 这套规则松一点紧一点，直接决定「其他 165 个书源都没找到这本小说」还是「换到了另一本书」——
 * 之前就因为作者用相等匹配而不是包含匹配，把绝大多数源判死过。手动换源和自动换源共用它，
 * 所以它错一次，两条路一起错。
 */
class TitleMatchTest {

    @Test
    fun `书名号与空白不该影响判定`() {
        assertTrue(TitleMatch.matches("《武动乾坤》", "武动乾坤"))
        assertTrue(TitleMatch.matches("武动乾坤", "《武动乾坤》"))
        assertTrue(TitleMatch.matches(" 武动乾坤 ", "武动乾坤"))
    }

    @Test
    fun `带后缀的同名书认得出来`() {
        assertTrue(TitleMatch.matches("武动乾坤（精校版）", "武动乾坤"))
        assertTrue(TitleMatch.matches("武动乾坤", "武动乾坤（精校版）"))
    }

    @Test
    fun `不同的书不该混为一谈`() {
        assertFalse(TitleMatch.matches("斗破苍穹", "武动乾坤"))
        assertFalse(TitleMatch.matches("", "武动乾坤"))
    }

    /** 单字书名被任意长书名包含，会把整个书源库都判成命中 */
    @Test
    fun `单字不足以构成包含匹配`() {
        assertFalse(TitleMatch.matches("斗", "斗破苍穹"))
    }

    @Test
    fun `作者用包含匹配，而不是相等`() {
        // 书源常带前缀，一律要求相等会把绝大多数源判死 —— 这正是从前的 bug
        assertTrue(TitleMatch.authorMatches("作者：天蚕土豆", "天蚕土豆"))
        assertTrue(TitleMatch.authorMatches("天蚕土豆", "天蚕土豆"))
    }

    @Test
    fun `任一边为空就不拿作者卡人`() {
        assertTrue(TitleMatch.authorMatches(null, "天蚕土豆"))
        assertTrue(TitleMatch.authorMatches("", "天蚕土豆"))
        assertTrue(TitleMatch.authorMatches("天蚕土豆", ""))
    }

    @Test
    fun `作者对不上就是对不上`() {
        assertFalse(TitleMatch.authorMatches("唐家三少", "天蚕土豆"))
    }

    @Test
    fun `章名剥掉标点和括号后相等`() {
        // 只剥括号本身，不剥里面的字：「（修）」变成「修」，避免「上」「下」被剥成同一章
        assertEquals("第一章开端", TitleMatch.normalizeChapter("第一章：开端"))
        assertEquals("第一章", TitleMatch.normalizeChapter("第一章。"))
        assertEquals("第一章修", TitleMatch.normalizeChapter("第一章（修）"))
        assertEquals("第一章", TitleMatch.normalizeChapter("《第一章》"))
        assertEquals("第1章", TitleMatch.normalizeChapter("第 1 章"))
    }

    @Test
    fun `章名对齐认得出站点之间的标点差`() {
        data class Ch(val index: Int, val title: String)
        val toc = listOf(Ch(0, "序章"), Ch(1, "第一章：开端"), Ch(2, "第二章"))
        val hit = TitleMatch.alignChapter("第一章开端", 1, toc, { it.title }, { it.index })
        assertEquals(1, hit?.index)
        val dotted = TitleMatch.alignChapter("第一章。", 0, listOf(Ch(0, "第一章")), { it.title }, { it.index })
        assertEquals(0, dotted?.index)
    }

    @Test
    fun `同名章取离旧序号最近的`() {
        data class Ch(val index: Int, val title: String)
        val toc = listOf(Ch(0, "第一章"), Ch(10, "第一章"), Ch(20, "第一章"))
        val hit = TitleMatch.alignChapter("第一章。", 12, toc, { it.title }, { it.index })
        assertEquals(10, hit?.index)
    }

    @Test
    fun `对不上的章名返回 null，由调用方按序号夹取`() {
        data class Ch(val index: Int, val title: String)
        val toc = listOf(Ch(0, "序章"), Ch(1, "第二章"))
        assertNull(TitleMatch.alignChapter("第一章", 1, toc, { it.title }, { it.index }))
    }

    @Test
    fun `书名归一化仍然保留括号，精校版靠包含匹配`() {
        // 若误用章名剥法，『武动乾坤（精校版）』会变成『武动乾坤精校版』，
        // 仍然 contains 得上；但『精校』这类短后缀场景更依赖括号留着走包含。
        // 这里钉死：书名 normalize 不能把括号剥掉。
        assertEquals("武动乾坤（精校版）", TitleMatch.normalize("《武动乾坤（精校版）》"))
    }
}
