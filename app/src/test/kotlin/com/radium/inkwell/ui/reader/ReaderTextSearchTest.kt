package com.radium.inkwell.ui.reader

import com.radium.inkwell.core.model.ChapterContent
import com.radium.inkwell.core.model.ContentElement
import com.radium.inkwell.reader.api.ReadPosition
import com.radium.inkwell.reader.paginate.PageSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReaderTextSearchTest {
    @Test fun `return to results follows the verified character after repagination`() {
        val match = readerTextMatches(ChapterContent(listOf(ContentElement.Paragraph("渡口"))), 4, "", "渡口", 1).single()
        val destination = ReaderTextSearchDestination(match, charOffset = 60, matchNumber = 1, totalMatches = 1)
        val oldPage = PageSpec(4, 2, emptyList(), 50, 75)
        assertTrue(destination.isOnPage(oldPage))
        assertFalse(destination.isOnPage(oldPage.copy(endCharOffset = 60)))
        assertTrue(destination.isOnPage(oldPage.copy(pageIndexInChapter = 3, startCharOffset = 60)))
        assertFalse(destination.isOnPage(oldPage.copy(chapterIndex = 5)))
        assertFalse(destination.isOnPage(null))
    }

    @Test fun `match offsets follow the reader stream across dividers and images`() {
        val content = ChapterContent(
            listOf(
                ContentElement.Paragraph("开头"),
                ContentElement.Image("illustration"),
                ContentElement.Divider,
                ContentElement.Heading(2, "小题"),
                ContentElement.Paragraph("前面 Ink，后面 ink"),
            ),
        )
        val match = readerTextMatches(content, 4, "标题", "INK", limit = 1).single()

        assertEquals(ReadPosition(4, 10), match.position)
        assertEquals(5, match.selection.elementIndex)
        assertEquals(3, match.selection.start)
        assertEquals("Ink", match.selection.text)
    }

    @Test fun `excerpt highlights the selected occurrence when the same word appears earlier`() {
        val content = ChapterContent(listOf(ContentElement.Paragraph("前".repeat(30) + "渡口与渡口")))
        val matches = readerTextMatches(content, 0, "", "渡口", limit = 2)
        val second = matches.last()

        assertEquals(33, second.selection.start)
        assertEquals(25, second.excerptMatchStart)
        assertEquals("渡口", second.excerpt.substring(second.excerptMatchStart))
        assertEquals("渡口与", second.excerpt.substring(second.excerptMatchStart - 3, second.excerptMatchStart))
    }
}
