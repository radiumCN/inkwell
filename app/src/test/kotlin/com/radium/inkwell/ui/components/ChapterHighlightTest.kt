package com.radium.inkwell.ui.components

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

/** 搜索章节时章名里命中的字标强调色（ChapterListItem 的 highlight）：每一处都标、不分大小写、空词不标 */
class ChapterHighlightTest {

    @Test
    fun `every match is styled, case-insensitively`() {
        val text = highlightMatches("Ch.1 秋分 · ch.2 秋分后", "CH", Color.Red)
        assertEquals("Ch.1 秋分 · ch.2 秋分后", text.text)
        assertEquals(listOf(0 to 2, 10 to 12), text.spanStyles.map { it.start to it.end })
    }

    @Test
    fun `blank query leaves the title plain`() {
        assertEquals(0, highlightMatches("第12章 秋分", "  ", Color.Red).spanStyles.size)
        assertEquals(0, highlightMatches("第12章 秋分", null, Color.Red).spanStyles.size)
    }
}
