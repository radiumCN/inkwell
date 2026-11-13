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
     * 标题、分隔符、图必须原样存回。丢掉它们会让「首次分页」（走网络）与「二次分页」（走缓存）
     * 的页边界和 charOffset 对不上（分隔符和图各占 1 个偏移），恢复阅读位置就会漂到别的页。
     */
    @Test
    fun `往返保留标题、分隔符与图`() {
        cache.write("b1", "https://ex.com/1.html", content)
        val entry = cache.readEntry("b1", "https://ex.com/1.html")!!
        assertEquals(content.elements, entry.content.elements)
        assertTrue(entry.withImages)
    }

    /**
     * v4 是只收字的那一版（0.2.9 ~ 0.5.8 书不收图）。升级后照样认，不然离线缓存了整本的书一升级就读不了；
     * 但标明它没有图：开着「正文显示图片」、又联网时，阅读页拿它当过期的重抓一份带图的
     */
    @Test
    fun `只有字的 v4 缓存照样能读，标明没收过图`() {
        val url = "https://ex.com/1.html"
        cache.write("b1", url, content)
        val f = File(root, "b1").listFiles()!!.single { it.name.endsWith(".txt") }
        f.writeText("\u0001V4\n正文第一段。\n---\n正文第二段。")

        val entry = cache.readEntry("b1", url)!!
        assertFalse(entry.withImages)
        assertEquals(
            listOf(ContentElement.Paragraph("正文第一段。"), ContentElement.Divider, ContentElement.Paragraph("正文第二段。")),
            entry.content.elements,
        )
        // 预取问「还要不要抓」：不要图时 v4 算有；要图时得重抓
        assertTrue(cache.hasCurrent("b1", url))
        assertFalse(cache.hasCurrent("b1", url, needImages = true))
    }

    @Test
    fun `插图按地址存取，跟着书一起清`() {
        val img = "https://ex.com/img/1.jpg"
        assertNull(cache.readImage("b1", img))
        cache.writeImage("b1", img, byteArrayOf(1, 2, 3))
        assertEquals(listOf<Byte>(1, 2, 3), cache.readImage("b1", img)!!.toList())
        cache.clear("b1")
        assertNull(cache.readImage("b1", img))
    }

    @Test
    fun `超龄的插图随自动清理删掉，最近看过的留着`() {
        val now = 1_700_000_000_000L
        val day = 86_400_000L
        cache.writeImage("b1", "https://ex.com/old.jpg", byteArrayOf(1))
        cache.writeImage("b1", "https://ex.com/new.jpg", byteArrayOf(2))
        val dir = File(root, "b1/img")
        val (old, fresh) = dir.listFiles()!!.partition { it.readBytes().contentEquals(byteArrayOf(1)) }
        old.single().setLastModified(now - 20 * day)
        fresh.single().setLastModified(now - 1 * day)

        cache.pruneBehindProgress("b1", readChapterIndex = 0, chapterUrls = emptyList(), maxAgeMs = 14 * day, nowMs = now)

        assertFalse(old.single().exists())
        assertTrue(fresh.single().exists())
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

    @Test
    fun `进度之前且超龄的缓存会被清掉，当前章与之后保留`() {
        val urls = listOf(
            "https://ex.com/0.html",
            "https://ex.com/1.html",
            "https://ex.com/2.html",
            "https://ex.com/3.html",
        )
        urls.forEach { cache.write("b1", it, content) }
        val now = 1_700_000_000_000L
        val day = 86_400_000L
        // 0、1 超龄；2（当前）、3（预取）即使超龄也不删
        File(root, "b1").listFiles()!!.forEach { it.setLastModified(now - 20 * day) }

        val pruned = cache.pruneBehindProgress(
            bookId = "b1",
            readChapterIndex = 2,
            chapterUrls = urls,
            maxAgeMs = 14 * day,
            nowMs = now,
        )

        assertEquals(listOf(0, 1), pruned)
        assertFalse(cache.has("b1", urls[0]))
        assertFalse(cache.has("b1", urls[1]))
        assertTrue(cache.has("b1", urls[2]))
        assertTrue(cache.has("b1", urls[3]))
    }

    @Test
    fun `进度之前但未超龄的缓存保留`() {
        val urls = listOf("https://ex.com/0.html", "https://ex.com/1.html")
        urls.forEach { cache.write("b1", it, content) }
        val now = 1_700_000_000_000L
        val day = 86_400_000L
        File(root, "b1").listFiles()!!.forEach { it.setLastModified(now - 3 * day) }

        val pruned = cache.pruneBehindProgress(
            bookId = "b1",
            readChapterIndex = 1,
            chapterUrls = urls,
            maxAgeMs = 14 * day,
            nowMs = now,
        )

        assertTrue(pruned.isEmpty())
        assertTrue(cache.has("b1", urls[0]))
    }

    @Test
    fun `读缓存会刷新 lastModified，避免刚回看就被当成过期`() {
        val url = "https://ex.com/1.html"
        cache.write("b1", url, content)
        val f = File(root, "b1").listFiles()!!.single { it.name.endsWith(".txt") }
        val old = 1_000_000_000_000L
        assertTrue(f.setLastModified(old))
        assertEquals(old, f.lastModified())

        assertTrue(cache.read("b1", url) != null)
        assertTrue(f.lastModified() > old)
    }

    @Test
    fun `超龄孤儿缓存会被清掉`() {
        cache.write("b1", "https://ex.com/live.html", content)
        val orphan = File(root, "b1/deadbeefdeadbeefdeadbeefdeadbeef.txt")
        orphan.writeText("orphan")
        val now = 1_700_000_000_000L
        val day = 86_400_000L
        orphan.setLastModified(now - 20 * day)
        File(root, "b1").listFiles()!!
            .filter { it != orphan }
            .forEach { it.setLastModified(now) }

        cache.pruneBehindProgress(
            bookId = "b1",
            readChapterIndex = 0,
            chapterUrls = listOf("https://ex.com/live.html"),
            maxAgeMs = 14 * day,
            nowMs = now,
        )

        assertFalse(orphan.exists())
        assertTrue(cache.has("b1", "https://ex.com/live.html"))
    }

    /**
     * 书 id 可能来自同步下来的备份文件，不是本机生成的。`../books` 原样拼进路径的话，
     * clear() 的 deleteRecursively 会删到缓存根之外 —— 正是缓存根的兄弟目录 books/（所有导入的书）。
     */
    @Test
    fun `越界的书 id 删不到缓存根之外`() {
        val parent = Files.createTempDirectory("files-test").toFile()
        val cacheRoot = File(parent, "cache").apply { mkdirs() }
        val books = File(parent, "books").apply { mkdirs() }
        val imported = File(books, "real.epub").apply { writeText("book") }
        val c = ChapterContentCache(cacheRoot)

        c.write("../books", "https://ex.com/1.html", content)
        assertTrue(c.has("../books", "https://ex.com/1.html"))
        c.clear("../books")

        assertTrue(imported.exists())
        assertFalse(c.has("../books", "https://ex.com/1.html"))
    }

    @Test
    fun `本机生成的书 id 仍按原名落目录，老缓存不用搬`() {
        val uuid = "0b6f1c7e-3c1a-4f7e-9a51-2d2f6c7f9e10"
        val md5 = "9e107d9d372bb6826bd81d3542a419d6"
        cache.write(uuid, "https://ex.com/1.html", content)
        cache.write(md5, "https://ex.com/1.html", content)
        assertTrue(File(root, uuid).isDirectory)
        assertTrue(File(root, md5).isDirectory)
    }

    /** 预取问「要不要抓」用 hasCurrent：判断必须和 read 一致，旧版本格式的文件在也得照抓 */
    @Test
    fun `hasCurrent 与 read 一致，旧格式算没缓存`() {
        val url = "https://ex.com/1.html"
        assertFalse(cache.hasCurrent("b1", url))
        cache.write("b1", url, content)
        assertTrue(cache.hasCurrent("b1", url))
        File(root, "b1").listFiles()!!.single { it.name.endsWith(".txt") }.writeText("正文第一段。")
        assertFalse(cache.hasCurrent("b1", url))
    }

    /** 只是问一句有没有，别给没缓存过的书凭空建出空目录（追更时逐章问 isCached） */
    @Test
    fun `查询不建目录`() {
        assertFalse(cache.has("never", "https://ex.com/x.html"))
        assertFalse(cache.hasCurrent("never", "https://ex.com/x.html"))
        assertNull(cache.read("never", "https://ex.com/x.html"))
        assertFalse(File(root, "never").exists())
    }

    /** 库里已经没有的书留下的目录要清；还在库里的、或刚刚还碰过的不能动 */
    @Test
    fun `孤儿目录过了宽限期才删，在库的书不动`() {
        cache.write("live", "https://ex.com/1.html", content)
        cache.write("gone", "https://ex.com/1.html", content)
        val later = System.currentTimeMillis() + 10_000

        // 刚写过：宽限期内不删
        assertEquals(0, cache.pruneOrphanBooks(listOf("live"), graceMs = 60_000, nowMs = later))
        assertTrue(File(root, "gone").isDirectory)

        assertEquals(1, cache.pruneOrphanBooks(listOf("live"), graceMs = 0, nowMs = later))
        assertFalse(File(root, "gone").exists())
        assertTrue(cache.has("live", "https://ex.com/1.html"))
    }

    @Test
    fun `整本缓存抓章期间这本书被清过（换源、删书），抓回来的旧正文不写`() {
        val epoch = cache.epoch("b1")
        cache.clear("b1")
        assertFalse(cache.writeIfEpoch("b1", "https://ex.com/1.html", content, epoch))
        assertFalse(cache.has("b1", "https://ex.com/1.html"))
    }

    @Test
    fun `清的是别的书不影响；清空全部缓存则一律作废`() {
        val epoch = cache.epoch("b1")
        cache.clear("b2")
        assertTrue(cache.writeIfEpoch("b1", "https://ex.com/1.html", content, epoch))
        assertTrue(cache.has("b1", "https://ex.com/1.html"))

        val again = cache.epoch("b1")
        cache.clearAll()
        assertFalse(cache.writeIfEpoch("b1", "https://ex.com/2.html", content, again))
    }

    /**
     * 同一本书隔一阵才清一次：进书、冷启动的全书架清理都先问 claimPrune。
     * 清理要把全书每章过一遍，几千章的书每次进书都来一遍，正撞在进书动画上。
     */
    @Test
    fun `同一本书半天里只清一次，别的书不受影响`() {
        val hour = 3_600_000L
        val t0 = 1_000_000_000L
        assertTrue(cache.claimPrune("b1", nowMs = t0))
        assertFalse(cache.claimPrune("b1", nowMs = t0 + hour))
        assertTrue(cache.claimPrune("b2", nowMs = t0 + hour))
        // 过了间隔又可以清，并从这一次重新计时
        assertTrue(cache.claimPrune("b1", nowMs = t0 + 13 * hour))
        assertFalse(cache.claimPrune("b1", nowMs = t0 + 14 * hour))
    }

    @Test
    fun `时钟往回拨：照样让清，别卡住`() {
        val t0 = 1_000_000_000L
        assertTrue(cache.claimPrune("b1", nowMs = t0))
        assertTrue(cache.claimPrune("b1", nowMs = t0 - 60_000L))
    }
}
