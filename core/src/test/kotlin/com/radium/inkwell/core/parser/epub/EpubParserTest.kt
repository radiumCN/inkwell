package com.radium.inkwell.core.parser.epub

import com.radium.inkwell.core.model.BookParseException
import com.radium.inkwell.core.model.ContentElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import java.io.File

class EpubParserTest {

    private fun temp(): File =
        kotlin.io.path.createTempFile(suffix = ".epub").toFile().also { it.deleteOnExit() }

    @Test
    fun `parses metadata spine and ncx toc`() {
        val f = temp()
        EpubFixture.minimalEpub(f, chapterCount = 3)
        EpubParser().open(f).use { book ->
            assertEquals("测试之书", book.metadata.title)
            assertEquals("测试作者", book.metadata.author)
            assertEquals(3, book.chapters.size)
            assertEquals(3, book.toc.size)
            assertEquals("第2章 起承转合", book.toc[1].title)
            assertEquals(1, book.toc[1].chapterIndex)
        }
    }

    @Test
    fun `chapter content converts to paragraph flow`() {
        val f = temp()
        EpubFixture.minimalEpub(f)
        EpubParser().open(f).use { book ->
            val elements = book.loadChapter(0).elements
            val heading = elements.filterIsInstance<ContentElement.Heading>()
            val paras = elements.filterIsInstance<ContentElement.Paragraph>()
            assertEquals(1, heading.size)
            assertEquals(2, paras.size)
            assertTrue(paras[0].text.startsWith("这是第1章的第一段"))
            // 全角缩进被剥离，交给排版层
            assertTrue(!paras[0].text.startsWith("　"))
        }
    }

    @Test
    fun `epub3 nav takes priority over ncx`() {
        val f = temp()
        EpubFixture.minimalEpub(f, withNav = true)
        EpubParser().open(f).use { book ->
            assertEquals("Nav第1章", book.toc[0].title)
        }
    }

    @Test
    fun `href case mismatch is tolerated`() {
        val f = temp()
        EpubFixture.minimalEpub(f, hrefCaseMismatch = true)
        EpubParser().open(f).use { book ->
            val paras = book.loadChapter(0).elements.filterIsInstance<ContentElement.Paragraph>()
            assertTrue(paras.isNotEmpty())
        }
    }

    @Test
    fun `drm epub is rejected`() {
        val f = temp()
        EpubFixture.minimalEpub(f, withEncryption = true)
        assertFailsWith<BookParseException.DrmProtected> { EpubParser().open(f) }
    }

    @Test
    fun `sniff rejects non zip`() {
        val f = temp()
        f.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        assertTrue(!EpubParser().sniff(f))
    }
}
