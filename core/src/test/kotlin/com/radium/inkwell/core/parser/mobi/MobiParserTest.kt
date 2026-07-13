package com.radium.inkwell.core.parser.mobi

import com.radium.inkwell.core.model.BookHandle
import com.radium.inkwell.core.model.ContentElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import java.io.File

/**
 * 用 Project Gutenberg 真实图书做集成测试（公版书，随仓库入库）：
 * - pg1342.mobi：Pride and Prejudice，MOBI7（mobi_version 6，NCX INDX 目录）
 * - pg84-kf8.mobi：Frankenstein，独立 KF8（mobi_version 8，含插图）
 * - pg11-kf8.mobi：Alice's Adventures in Wonderland，独立 KF8（含插图）
 */
class MobiParserTest {

    private fun fixture(name: String): File {
        val url = javaClass.getResource("/mobi/$name")
            ?: error("缺少测试 fixture: $name")
        return File(url.toURI())
    }

    private fun checkBook(name: String, expectTitle: String) {
        val f = fixture(name)
        assertTrue(MobiParser().sniff(f), "$name sniff 失败")
        MobiParser().open(f).use { book ->
            assertEquals(expectTitle, book.metadata.title)
            assertTrue(book.chapters.size > 5, "$name 章节数只有 ${book.chapters.size}")
            assertTrue(book.toc.isNotEmpty(), "$name 目录为空")
            for (idx in listOf(0, book.chapters.size - 1)) {
                assertTrue(
                    book.loadChapter(idx).elements.isNotEmpty(),
                    "$name 第 $idx 章内容为空",
                )
            }
            // 至少有一章有实际文本段落
            assertTrue(
                (0 until book.chapters.size).any { idx ->
                    book.loadChapter(idx).elements
                        .filterIsInstance<ContentElement.Paragraph>()
                        .any { it.text.length > 50 }
                },
                "$name 全书无正文段落",
            )
        }
    }

    @Test
    fun `mobi7 pride and prejudice parses`() {
        checkBook("pg1342.mobi", "Pride and Prejudice")
    }

    @Test
    fun `kf8 frankenstein parses`() {
        checkBook("pg84-kf8.mobi", "Frankenstein; or, the modern prometheus")
    }

    @Test
    fun `kf8 alice parses`() {
        checkBook("pg11-kf8.mobi", "Alice's Adventures in Wonderland")
    }

    @Test
    fun `kf8 first and last chapters have paragraphs`() {
        MobiParser().open(fixture("pg84-kf8.mobi")).use { book ->
            for (idx in listOf(0, book.chapters.size - 1)) {
                val texts = book.loadChapter(idx).elements.filter {
                    it is ContentElement.Paragraph || it is ContentElement.Heading || it is ContentElement.Image
                }
                assertTrue(texts.isNotEmpty(), "第 $idx 章无内容")
            }
        }
    }

    @Test
    fun `kf8 images resolve to pdb records`() {
        MobiParser().open(fixture("pg11-kf8.mobi")).use { book ->
            assertNotNull(book.metadata.cover, "封面缺失")
            assertTrue(book.metadata.cover!!.data.size > 100)

            val image = firstImage(book)
            assertNotNull(image, "全书未解析出插图")
            val blob = book.loadResource(image.resourceId)
            assertNotNull(blob, "插图 ${image.resourceId} 加载失败")
            assertTrue(blob.mimeType!!.startsWith("image/"))
        }
    }

    @Test
    fun `mobi7 toc entries point at chapters`() {
        MobiParser().open(fixture("pg1342.mobi")).use { book ->
            assertTrue(book.toc.size > 5)
            book.toc.forEach { e ->
                assertTrue(e.chapterIndex in book.chapters.indices, "目录项 ${e.title} 越界")
                assertTrue(e.title.isNotBlank())
            }
        }
    }

    private fun firstImage(book: BookHandle): ContentElement.Image? {
        for (idx in 0 until book.chapters.size) {
            book.loadChapter(idx).elements.filterIsInstance<ContentElement.Image>().firstOrNull()
                ?.let { return it }
        }
        return null
    }
}
