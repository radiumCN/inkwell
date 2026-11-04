package com.radium.inkwell.core.source.detect

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PageDetectorsTest {

    private fun doc(html: String, url: String = "https://www.example.com/book/1234/"): Document = Jsoup.parse(html, url)

    // ---- 章节标题 ----

    @Test
    fun `chinese and arabic chapter numbers`() {
        assertEquals(103, ChapterTitles.parseNumber("一百零三"))
        assertEquals(12, ChapterTitles.parseNumber("十二"))
        assertEquals(20, ChapterTitles.parseNumber("二十"))
        assertEquals(10005, ChapterTitles.parseNumber("一万零五"))
        assertEquals(1234, ChapterTitles.parseNumber("１２３４"))
        assertEquals(42, ChapterTitles.numberOf("正文 第四十二章 风起"))
        assertEquals(7, ChapterTitles.numberOf("007 夜行"))
        assertNull(ChapterTitles.numberOf("楔子"))
    }

    @Test
    fun `chapter like titles`() {
        assertTrue(ChapterTitles.isChapterLike("第一章 山门"))
        assertTrue(ChapterTitles.isChapterLike("VIP 第12章 归来"))
        assertTrue(ChapterTitles.isChapterLike("12、出发"))
        assertTrue(ChapterTitles.isChapterLike("楔子"))
        // 页码条、导航都不像章节
        assertFalse(ChapterTitles.isChapterLike("3"))
        assertFalse(ChapterTitles.isChapterLike("玄幻小说"))
        assertFalse(ChapterTitles.isChapterLike("排行榜"))
    }

    // ---- 目录 ----

    @Test
    fun `biquge toc excludes the latest block and keeps reading order`() {
        val d = doc(DetectFixtures.biqugeToc(chapters = 30, latest = 6))
        val best = TocDetector.detect(d).first()
        assertEquals(30, best.chapters.size)
        assertEquals("第1章 山门之外（1）", best.chapters.first().title)
        assertEquals("https://www.example.com/book/1234/100001.html", best.chapters.first().url)
        assertEquals("第30章 山门之外（30）", best.chapters.last().title)
        assertFalse(best.reversed)
        // 按分隔标题排除，而不是按位置：换一本章节少的书照样对
        assertEquals("@css:#list > dl > dt ~ dt ~ dd a", best.listRule)
    }

    @Test
    fun `latest block exclusion survives a short book`() {
        // 用长书上生成的规则去套一本只有 4 章的书（最新块也只有 4 条）：正文一章不能少
        val rule = TocDetector.detect(doc(DetectFixtures.biqugeToc(chapters = 30, latest = 12))).first().listRule
        val short = doc(DetectFixtures.biqugeToc(chapters = 4, latest = 4))
        val got = short.select(rule.removePrefix("@css:")).map { it.text() }
        assertEquals((1..4).map { "第${it}章 山门之外（$it）" }, got)
    }

    @Test
    fun `volume toc merges all volumes and ignores the nav`() {
        val d = doc(DetectFixtures.volumeToc(volumes = 3, perVolume = 10), "https://www.example.com/book/77.html")
        val best = TocDetector.detect(d).first()
        assertEquals(30, best.chapters.size)
        assertEquals("https://www.example.com/read/77/1.html", best.chapters.first().url)
        assertEquals("https://www.example.com/read/77/30.html", best.chapters.last().url)
        assertTrue(best.chapters.none { it.title == "首页" || it.title.startsWith("另一本书") })
    }

    @Test
    fun `reversed toc is detected and flipped`() {
        val d = doc(DetectFixtures.reversedToc(20), "https://www.example.com/b/9/")
        val best = TocDetector.detect(d).first()
        assertTrue(best.reversed)
        assertTrue(best.listRule.startsWith("-@css:"))
        assertEquals("第1章 山门之外（1）", best.chapters.first().title)
        assertEquals(20, best.chapters.size)
    }

    @Test
    fun `large toc stays fast`() {
        // 长篇连载几千章、整页一个 dl：分组要从每条链接往上走几层，别写成平方级
        val d = doc(DetectFixtures.biqugeToc(chapters = 3000, latest = 12))
        val started = System.nanoTime()
        val best = TocDetector.detect(d).first()
        val ms = (System.nanoTime() - started) / 1_000_000
        assertEquals(3000, best.chapters.size)
        assertTrue(ms < 3_000, "3000 章目录识别用了 ${ms}ms")
    }

    @Test
    fun `sample chapter skips notices at the top`() {
        fun c(t: String) = DetectedChapter(t, "https://www.example.com/$t")
        val toc = TocCandidate("@css:a", listOf(c("作品相关"), c("第0章 上架感言"), c("第1章 出山"), c("第2章 下山")), false, 1.0)
        assertEquals("第1章 出山", toc.sampleChapter.title)
        // 一个都不像：用第一章，别返回空
        val odd = TocCandidate("@css:a", listOf(c("春"), c("夏"), c("秋")), false, 1.0)
        assertEquals("春", odd.sampleChapter.title)
    }

    @Test
    fun `page without a chapter list yields nothing`() {
        val d = doc("<html><body><div class='nav'><a href='/'>首页</a><a href='/a'>关于</a></div><p>没有目录</p></body></html>")
        assertTrue(TocDetector.detect(d).isEmpty())
    }

    // ---- 正文 ----

    @Test
    fun `br separated content picks the content div itself`() {
        val d = doc(DetectFixtures.brContent(), "https://www.example.com/book/1234/100001.html")
        val best = ContentDetector.detect(d).first()
        assertEquals("@css:#content@html", best.rule)
        assertTrue(best.preview.contains("这是第1段正文"))
    }

    @Test
    fun `paragraph content picks the paragraphs parent not the wrapper`() {
        val best = ContentDetector.detect(doc(DetectFixtures.pContent())).first()
        assertEquals("@css:div.read-content@html", best.rule)
    }

    @Test
    fun `div per paragraph content picks the container`() {
        val best = ContentDetector.detect(doc(DetectFixtures.divContent())).first()
        assertEquals("@css:#chaptercontent@html", best.rule)
    }

    @Test
    fun `next page link but not next chapter`() {
        val withNext = doc(DetectFixtures.brContent(nextPage = "/book/1234/100001_2.html"), "https://www.example.com/book/1234/100001.html")
        val next = assertNotNull(SiteLinks.findNextPage(withNext))
        assertEquals("@css:a:contains(下一页)@href", next.rule)
        assertEquals("https://www.example.com/book/1234/100001_2.html", next.value)
        // 只有「下一章」：不是分页
        assertNull(SiteLinks.findNextPage(doc(DetectFixtures.brContent(nextPage = null))))
    }

    // ---- 书籍信息 ----

    @Test
    fun `og meta wins over page elements`() {
        val info = BookInfoDetector.detect(doc(DetectFixtures.biqugeToc()))
        assertEquals("示例之书", info.name?.value)
        assertEquals("@css:meta[property=og:novel:book_name]@content", info.name?.rule)
        assertEquals("某作者", info.author?.value)
        assertEquals("https://www.example.com/cover/1234.jpg", info.coverUrl?.value)
        assertEquals("一个少年走出山门的故事。", info.intro?.value)
    }

    @Test
    fun `falls back to sole h1 and the author line`() {
        val info = BookInfoDetector.detect(doc(DetectFixtures.volumeToc()))
        assertEquals("分卷之书", info.name?.value)
        assertEquals("@css:h1@text", info.name?.rule)
        // 作者名包在链接里、同一行后面还跟着「分类」
        assertEquals("写书人", info.author?.value)
        assertNull(info.coverUrl)
    }

    @Test
    fun `toc link on a mobile detail page`() {
        val base = "https://m.example.com"
        val link = assertNotNull(BookInfoDetector.findTocLink(doc(DetectFixtures.mobileDetail(base), "$base/book/88/")))
        assertEquals("$base/book/88/all_1.html", link.value)
        assertEquals("@css:a:contains(查看完整目录)@href", link.rule)
    }
}
