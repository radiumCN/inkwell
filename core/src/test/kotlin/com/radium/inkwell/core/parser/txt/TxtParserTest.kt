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

    // ---- 拆章按偏移扫描、正文流式解码：结果必须与从前逐行 substring().trim() 完全一致 ----

    @Test
    fun `CRLF 与全角空白包着的标题照样识别，取 trim 后的文本`() {
        val text = buildString {
            append("　　\r\n") // 只有空白的开头：不成「前言」章
            for (c in 1..4) {
                append("　第${c}章 标题$c  \r\n")
                append("　　" + "正文内容。".repeat(60) + "\r\n")
            }
        }
        val handle = TxtParser().open(openTemp(text))
        assertEquals((1..4).map { "第${it}章 标题$it" }, handle.chapters.map { it.title })
        // 标题行仍被识别为本章首行剔掉，只剩那一段正文
        assertEquals(1, handle.loadChapter(0).elements.size)
    }

    @Test
    fun `UTF-16 带 BOM 的文件流式解码`() {
        val handle = TxtParser().open(
            openTemp(novel(), charset = Charsets.UTF_16LE, bom = byteArrayOf(0xFF.toByte(), 0xFE.toByte()))
        )
        assertEquals("第1章 风起云涌之1", handle.chapters[1].title)
    }

    @Test
    fun `超过探测样本的大文件逐块解码，跨块的多字节字符不被切坏`() {
        // GBK 下约 600KB，远超 64KB 探测样本与 64K 字符的读缓冲
        val text = novel(chapters = 300, paraPerChapter = 10)
        val handle = TxtParser().open(openTemp(text, charset = charset("GBK")))
        assertEquals(301, handle.chapters.size)
        assertEquals("第300章 风起云涌之300", handle.chapters[300].title)
        val last = handle.loadChapter(300).elements
            .map { (it as com.radium.inkwell.core.model.ContentElement.Paragraph).text }
        assertEquals(10, last.size)
        assertTrue(last.all { it.startsWith("这是第300章的第") && it.endsWith("情节发展。") }, "实际: $last")
    }

    private fun paragraphs(handle: com.radium.inkwell.core.model.BookHandle, index: Int): List<String> =
        handle.loadChapter(index).elements.map { (it as com.radium.inkwell.core.model.ContentElement.Paragraph).text }

    private fun tempDir(): File = kotlin.io.path.createTempDirectory("txt-cache").toFile().also { it.deleteOnExit() }

    /**
     * 缓存版（第二次打开读的是缓存文件）与整本解码版逐章一模一样 —— 偏移换算错一个字，
     * 就是章节开头少字、结尾串进下一章。标题里带 tab、反斜杠的也得原样往返。
     */
    @Test
    fun `缓存命中时目录与正文和整本解码完全一致`() {
        val text = novel(chapters = 40, paraPerChapter = 4) + "第41章\t反斜杠\\标题\n　　最后一段。\n"
        val file = openTemp(text, charset = charset("GBK"))
        val plain = TxtParser().open(file)
        val dir = tempDir()
        TxtParser(cacheDir = dir).open(file).close() // 第一次：建缓存
        assertTrue(dir.listFiles()!!.any { it.name.endsWith(".u16") } && dir.listFiles()!!.any { it.name.endsWith(".idx") })

        TxtParser(cacheDir = dir).open(file).use { cached ->
            assertEquals(plain.chapters, cached.chapters)
            assertEquals(plain.toc, cached.toc)
            assertEquals("第41章\t反斜杠\\标题", cached.chapters.last().title)
            for (i in plain.chapters.indices) assertEquals(paragraphs(plain, i), paragraphs(cached, i), "第 $i 章")
        }
    }

    /** 原文件换了（重新导入覆盖、外部改过），旧的拆章表与正文就作废，不能按旧结果读 */
    @Test
    fun `原文件变了缓存作废`() {
        val file = openTemp(novel(chapters = 5))
        val dir = tempDir()
        TxtParser(cacheDir = dir).open(file).close()

        file.writeText(novel(chapters = 8))
        file.setLastModified(file.lastModified() + 5_000)
        TxtParser(cacheDir = dir).open(file).use { reopened ->
            assertEquals(9, reopened.chapters.size)
        }
    }

    @Test
    fun `删书时清掉这本的缓存`() {
        val file = openTemp(novel(chapters = 3))
        val dir = tempDir()
        val parser = TxtParser(cacheDir = dir)
        parser.open(file).close()
        assertTrue(dir.listFiles()!!.isNotEmpty())
        parser.evictCache(file)
        assertTrue(dir.listFiles()!!.isEmpty(), "实际: ${dir.listFiles()!!.map { it.name }}")
    }
}
