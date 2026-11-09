package com.radium.inkwell.ui.settings

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReleaseNotesTest {

    @Test
    fun `按类型前缀分组，去掉前缀与「更新内容」那一行`() {
        val notes = """
            更新内容

            feat：没有现成来源的网站也能读了
            feat：顶栏换成毛玻璃
            fix：状态栏不再是白字看不清
            优化：阅读时的强调色跟着纸张走
        """.trimIndent()
        assertEquals(
            listOf(
                NoteSection("新功能", listOf("没有现成来源的网站也能读了", "顶栏换成毛玻璃")),
                NoteSection("修复", listOf("状态栏不再是白字看不清")),
                NoteSection("优化", listOf("阅读时的强调色跟着纸张走")),
            ),
            parseReleaseNotes(notes),
        )
    }

    @Test
    fun `组按第一次出现的顺序排，同类分散的条目归到一起`() {
        val notes = "fix：甲\r\nfeat：乙\r\nfix：丙"
        assertEquals(
            listOf(NoteSection("修复", listOf("甲", "丙")), NoteSection("新功能", listOf("乙"))),
            parseReleaseNotes(notes),
        )
    }

    @Test
    fun `老格式也认：Markdown 列表、半角冒号、英文前缀`() {
        val notes = "- Feat: 新的\n* perf：快了\n• docs:文档\n· FIX ： 修了"
        assertEquals(
            listOf(
                NoteSection("新功能", listOf("新的")),
                NoteSection("优化", listOf("快了")),
                NoteSection("文档", listOf("文档")),
                NoteSection("修复", listOf("修了")),
            ),
            parseReleaseNotes(notes),
        )
    }

    @Test
    fun `认不出前缀的行不丢，放进无标题的组`() {
        val notes = "更新内容：\n修了一些小问题\nfeat：新的\n提升了稳定性"
        assertEquals(
            listOf(
                NoteSection(null, listOf("修了一些小问题", "提升了稳定性")),
                NoteSection("新功能", listOf("新的")),
            ),
            parseReleaseNotes(notes),
        )
    }

    @Test
    fun `空说明、只有前缀没有内容的行都是空的`() {
        assertEquals(emptyList(), parseReleaseNotes(""))
        assertEquals(emptyList(), parseReleaseNotes("更新内容\n\nfeat：\n  "))
    }

    @Test
    fun `前缀只认行首，正文里提到 fix 不算`() {
        assertEquals(
            listOf(NoteSection(null, listOf("这次没有 fix：只是调整"))),
            parseReleaseNotes("这次没有 fix：只是调整"),
        )
    }

    /** 关于页「v… 更新内容」头部的发布日期：今年的不写年份，认不出的不显示 */
    @Test
    fun `发布日期标签`() {
        val today = LocalDate.of(2026, 11, 5)
        assertEquals("11 月 5 日发布", releaseDateLabel("2026-11-05", today))
        assertEquals("2025 年 12 月 30 日发布", releaseDateLabel("2025-12-30\n", today))
        assertNull(releaseDateLabel(null, today))
        assertNull(releaseDateLabel("", today))
        assertNull(releaseDateLabel("Wed Nov 5", today))
    }
}
