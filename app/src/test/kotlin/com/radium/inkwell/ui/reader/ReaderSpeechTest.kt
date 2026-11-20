package com.radium.inkwell.ui.reader

import com.radium.inkwell.core.model.ChapterContent
import com.radium.inkwell.core.model.ContentElement
import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderSpeechTest {
    @Test
    fun `speech anchors use the same offsets and element indices as pagination`() {
        val chapter = speechChapter(
            2,
            "第三章",
            ChapterContent(
                listOf(
                    ContentElement.Image("cover"),
                    ContentElement.Paragraph("  "),
                    ContentElement.Heading(2, "小标题"),
                    ContentElement.Divider,
                    ContentElement.Paragraph("正文第一段"),
                ),
            ),
        )

        assertEquals(listOf(5, 9), chapter.paragraphs.map { it.offset })
        assertEquals(listOf(3, 5), chapter.paragraphs.map { it.elementIndex })
        assertEquals(0, chapter.paragraphIndexAt(0))
        assertEquals(0, chapter.paragraphIndexAt(7))
        assertEquals(1, chapter.paragraphIndexAt(8))
        assertEquals(1, chapter.paragraphIndexAt(Int.MAX_VALUE))
    }

    @Test
    fun `seeking into a paragraph keeps the requested chapter offset`() {
        val paragraph = SpeechParagraph(120, 1, "前页😀后页")
        val chapter = SpeechChapter(2, "第三章", listOf(paragraph))
        val start = paragraph.startWithin(124)
        val paused = SpeechState(status = SpeechStatus.PAUSED, chapter = chapter, paragraphStart = start)

        assertEquals("后页", paragraph.text.substring(start))
        assertEquals(124, paused.chapterOffset)
        assertEquals(122, paused.copy(paragraphStart = paragraph.startWithin(123)).chapterOffset)
        assertEquals(120, paused.copy(paragraphStart = 0).chapterOffset)
        assertEquals(0, paragraph.startWithin(Int.MAX_VALUE))
    }

    @Test
    fun `image-only chapters have no playable anchor`() {
        val chapter = speechChapter(0, "图", ChapterContent(listOf(ContentElement.Image("cover"))))

        assertEquals(-1, chapter.paragraphIndexAt(0))
    }

    @Test
    fun `long utterances never split a surrogate pair`() {
        val text = "甲😀乙"

        assertEquals(1, speechChunkEnd(text, 0, 2))
        assertEquals(3, speechChunkEnd(text, 1, 2))
        assertEquals(4, speechChunkEnd(text, 3, 2))
    }
}
