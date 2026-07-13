package com.radium.inkwell.core.parser.mobi

import com.radium.inkwell.core.model.BookParseException
import com.radium.inkwell.core.model.ContentElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import java.io.File

/** 用手工构造的 MOBI7 验证 PDB/头/内联 TOC filepos 切章等确定性行为 */
class MobiSyntheticTest {

    private fun temp(): File =
        kotlin.io.path.createTempFile(suffix = ".mobi").toFile().also { it.deleteOnExit() }

    @Test
    fun `sniff accepts synthetic mobi and rejects garbage`() {
        val f = temp()
        MobiFixture.mobi7(f)
        assertTrue(MobiParser().sniff(f))

        val g = temp()
        g.writeBytes(ByteArray(200) { 1 })
        assertTrue(!MobiParser().sniff(g))
    }

    @Test
    fun `inline toc filepos splits chapters at byte level`() {
        val f = temp()
        MobiFixture.mobi7(f, chapterCount = 6)
        MobiParser().open(f).use { book ->
            assertEquals("合成测试书", book.metadata.title)
            assertEquals("测试作者", book.metadata.author)
            // 6 个 toc 锚点各成一章；纯 head/guide 的前导切片被丢弃
            assertEquals(6, book.chapters.size)
            assertEquals(6, book.toc.size)
            assertEquals("Chapter 1", book.toc[0].title)
            assertEquals(0, book.toc[0].chapterIndex)

            val first = book.loadChapter(book.toc[0].chapterIndex)
            val headings = first.elements.filterIsInstance<ContentElement.Heading>()
            val paras = first.elements.filterIsInstance<ContentElement.Paragraph>()
            assertEquals("Chapter 1", headings.first().text)
            assertTrue(paras.any { it.text.contains("这是第 1 章的正文内容") })

            // 最后一个 toc 章节包含书尾 TOC 页，但正文段落也必须在
            val last = book.loadChapter(book.toc[5].chapterIndex)
            assertTrue(
                last.elements.filterIsInstance<ContentElement.Paragraph>()
                    .any { it.text.contains("这是第 6 章的正文内容") },
            )
        }
    }

    @Test
    fun `drm mobi is rejected`() {
        val f = temp()
        MobiFixture.mobi7(f, encryption = 2)
        assertFailsWith<BookParseException.DrmProtected> { MobiParser().open(f) }
    }

    @Test
    fun `huff compression reports unsupported`() {
        val f = temp()
        MobiFixture.mobi7(f, compression = 17480)
        assertFailsWith<BookParseException.UnsupportedFeature> { MobiParser().open(f) }
    }
}
