package com.radium.inkwell.core.parser.txt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.io.File

class TxtParserTest {

    private fun novel(chapters: Int = 5, paraPerChapter: Int = 5): String = buildString {
        appendLine("这是一本测试小说的开头简介，交代背景。")
        for (c in 1..chapters) {
            appendLine("第${c}章 风起云涌之${c}")
            repeat(paraPerChapter) { p ->
                appendLine("　　这是第${c}章的第${p + 1}段正文，" + "情节发展".repeat(15) + "。")
            }
        }
    }

    private fun openTemp(content: String, charset: java.nio.charset.Charset = Charsets.UTF_8, bom: ByteArray = byteArrayOf()): File {
        val f = kotlin.io.path.createTempFile(suffix = ".txt").toFile()
        f.writeBytes(bom + content.toByteArray(charset))
        f.deleteOnExit()
        return f
    }

    @Test
    fun `utf8 novel splits into preface plus chapters`() {
        val handle = TxtParser().open(openTemp(novel(chapters = 5)))
        // 前言 + 5 章
        assertEquals(6, handle.chapters.size)
        assertEquals("前言", handle.chapters[0].title)
        assertEquals("第1章 风起云涌之1", handle.chapters[1].title)
        val content = handle.loadChapter(1)
        assertTrue(content.elements.isNotEmpty())
    }

    @Test
    fun `gbk novel decodes correctly`() {
        val handle = TxtParser().open(openTemp(novel(), charset = charset("GBK")))
        assertEquals("第1章 风起云涌之1", handle.chapters[1].title)
        val text = (handle.loadChapter(1).elements.first() as com.radium.inkwell.core.model.ContentElement.Paragraph).text
        assertTrue(text.contains("第1章的第1段正文"))
    }

    @Test
    fun `utf8 bom is stripped`() {
        val handle = TxtParser().open(
            openTemp(novel(), bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
        )
        assertTrue(handle.chapters[1].title.startsWith("第1章"))
    }

    @Test
    fun `no chapter markers falls back to fixed slices`() {
        val plain = "无标题正文。".repeat(5000) + "\n" // 3万字无章节
        val handle = TxtParser().open(openTemp(plain))
        assertTrue(handle.chapters.size >= 3)
        assertEquals("第 1 部分", handle.chapters[0].title)
    }

    @Test
    fun `volume and chapter produce two level toc`() {
        val text = buildString {
            for (v in 1..2) {
                appendLine("第${v}卷 卷名$v")
                for (c in 1..3) {
                    appendLine("第${c}章 章名$c")
                    appendLine("　　" + "正文内容。".repeat(60))
                }
            }
        }
        val handle = TxtParser().open(openTemp(text))
        val levels = handle.toc.map { it.level }.toSet()
        assertTrue(0 in levels && 1 in levels)
    }

    @Test
    fun `chapter body excludes title line and keeps paragraphs`() {
        val handle = TxtParser().open(openTemp(novel(chapters = 3, paraPerChapter = 3)))
        val content = handle.loadChapter(1)
        assertEquals(3, content.elements.size)
    }
}
