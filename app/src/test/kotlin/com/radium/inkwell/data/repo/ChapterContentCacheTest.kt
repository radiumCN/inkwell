package com.radium.inkwell.data.repo

import com.radium.inkwell.core.model.ChapterContent
import com.radium.inkwell.core.model.ContentElement
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChapterContentCacheTest {

    private val root: File = Files.createTempDirectory("cache-test").toFile()
    private val cache = ChapterContentCache(root)

    private val content = ChapterContent(
        listOf(
            ContentElement.Heading(2, "第一章 开端"),
            ContentElement.Paragraph("正文第一段。"),
            ContentElement.Divider,
            ContentElement.Image("https://ex.com/a.jpg"),
            ContentElement.Paragraph("正文第二段。"),
        )
    )

    /**
     * 缓存里躺的是**解析结果**，不是原始 HTML —— 解析器修好了，磁盘上那份旧结果并不会
     * 跟着变好。所以版本对不上必须当没缓存、重抓一遍。
     *
     * 这是 beta.8 踩到的坑：修好了「靠原始换行分段的源整章挤成一坨」，用户升级后一看
     * 一模一样 —— 因为那一章的坏结果早就缓存在磁盘上，cache.read 直接命中，
     * 新解析器根本没机会跑。
     */
    @Test
    fun `旧版本的缓存视为没缓存，逼它重抓`() {
        val url = "https://ex.com/1.html"
        // 先按当前格式写一份，借此拿到真实文件名（key 是 MD5(url)，测试不该自己复刻）
        cache.write("b1", url, content)
        val f = File(root, "b1").listFiles()!!.single { it.name.endsWith(".txt") }
        // 覆写成没有版本行的旧格式
        f.writeText("正文第一段。\n正文第二段。")

        assertNull(cache.read("b1", url))
    }

    /** 重抓后覆盖同名文件，旧内容自然被替换，不留读不到又删不掉的垃圾 */
    @Test
    fun `重抓后覆盖同名文件，不留旧版本垃圾`() {
        val url = "https://ex.com/1.html"
        cache.write("b1", url, content)
        val before = File(root, "b1").listFiles()!!.count { it.name.endsWith(".txt") }
        cache.write("b1", url, content)
        val after = File(root, "b1").listFiles()!!.count { it.name.endsWith(".txt") }
        assertEquals(before, after)
        assertEquals(1, after)
    }

    /**
     * 标题与分隔符必须原样存回。丢掉它们会让「首次分页」（走网络，含 Heading/Divider）与
     * 「二次分页」（走缓存）的页边界和 charOffset 对不上，恢复阅读位置就会漂到别的页。
     */
    @Test
    fun `往返保留标题、分隔符与图片`() {
        cache.write("b1", "https://ex.com/1.html", content)
        assertEquals(content.elements, cache.read("b1", "https://ex.com/1.html")?.elements)
    }

    /**
     * 缓存以章节 URL 为 key。站点在前面插入公告章后目录整体后移：原来的第 0 章变成第 1 章。
     * 若按序号存取，读第 0 章会读出旧的第 0 章正文 —— 缓存必须跟着章节走，不跟着槽位走。
     */
    @Test
    fun `按章节 URL 存取，目录后移也不会串章`() {
        val chapter1 = "https://ex.com/1.html"
        val chapter2 = "https://ex.com/2.html"
        cache.write("b1", chapter1, ChapterContent(listOf(ContentElement.Paragraph("我是第一章"))))
        cache.write("b1", chapter2, ChapterContent(listOf(ContentElement.Paragraph("我是第二章"))))

        // 目录后移后，第一章的序号从 0 变成 1，但按 URL 取到的仍是它自己的正文
        assertEquals(
            "我是第一章",
            (cache.read("b1", chapter1)!!.elements.single() as ContentElement.Paragraph).text,
        )
        assertTrue(cache.has("b1", chapter2))
        // 新插入的公告章还没缓存过
        assertFalse(cache.has("b1", "https://ex.com/notice.html"))
        assertNull(cache.read("b1", "https://ex.com/notice.html"))
    }

    @Test
    fun `按序号命名的旧缓存会被清掉`() {
        File(root, "b1").mkdirs()
        File(root, "b1/0.txt").writeText("旧的第一章")
        File(root, "b1/12.txt").writeText("旧的第十三章")
        cache.write("b1", "https://ex.com/1.html", content)

        cache.purgeLegacy("b1")

        assertFalse(File(root, "b1/0.txt").exists())
        assertFalse(File(root, "b1/12.txt").exists())
        // 按 URL 命名的新缓存不受影响
        assertTrue(cache.has("b1", "https://ex.com/1.html"))
    }

    @Test
    fun `换源整本清空`() {
        cache.write("b1", "https://ex.com/1.html", content)
        cache.clear("b1")
        assertFalse(cache.has("b1", "https://ex.com/1.html"))
    }
}
