package com.radium.inkwell.core.source.rss

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RssXmlParserTest {

    @Test
    fun `RSS 2_0`() {
        val xml = """
        <?xml version="1.0"?>
        <rss version="2.0">
          <channel>
            <title>某某周刊</title>
            <item>
              <title>第一篇</title>
              <link>https://example.com/a</link>
              <description>&lt;p&gt;正文一&lt;/p&gt;</description>
              <pubDate>Mon, 14 Jul 2025 10:00:00 +0800</pubDate>
            </item>
            <item>
              <title>第二篇</title>
              <link>https://example.com/b</link>
            </item>
          </channel>
        </rss>
        """.trimIndent()

        val items = assertNotNull(RssXmlParser.parse("https://example.com/feed", xml))
        assertEquals(2, items.size)
        assertEquals("第一篇", items[0].title)
        assertEquals("https://example.com/a", items[0].link)
        assertTrue(items[0].description!!.contains("正文一"))
        assertEquals("Mon, 14 Jul 2025 10:00:00 +0800", items[0].pubDate)
    }

    /**
     * Atom 的 link 是 `<link href="…"/>`，RSS 的是 `<link>…</link>`。
     * 用 jsoup 的 HTML parser 会把 <link> 当自闭合标签，RSS 那种的正文就此丢失，
     * 所有文章都点不开 —— 所以必须用 XML parser。这个用例守住它。
     */
    @Test
    fun `Atom 的 link 在 href 属性上`() {
        val xml = """
        <?xml version="1.0"?>
        <feed xmlns="http://www.w3.org/2005/Atom">
          <entry>
            <title>Atom 文章</title>
            <link rel="alternate" href="https://example.com/atom-1"/>
            <link rel="self" href="https://example.com/self"/>
            <summary>摘要</summary>
            <updated>2025-07-14T10:00:00Z</updated>
          </entry>
        </feed>
        """.trimIndent()

        val items = assertNotNull(RssXmlParser.parse("s", xml))
        val a = items.single()
        assertEquals("Atom 文章", a.title)
        // 必须挑 alternate，不能挑 self —— self 是 feed 自己的地址，点开是一坨 XML
        assertEquals("https://example.com/atom-1", a.link)
        assertEquals("摘要", a.description)
    }

    @Test
    fun `content encoded 优先于 description —— 那才是全文`() {
        val xml = """
        <?xml version="1.0"?>
        <rss version="2.0" xmlns:content="http://purl.org/rss/1.0/modules/content/">
          <channel><item>
            <title>T</title>
            <link>https://e.com/1</link>
            <description>只是摘要…</description>
            <content:encoded>&lt;p&gt;这是全文，很长很长&lt;/p&gt;</content:encoded>
          </item></channel>
        </rss>
        """.trimIndent()

        val a = assertNotNull(RssXmlParser.parse("s", xml)).single()
        assertTrue(a.description!!.contains("这是全文"), "拿到的是摘要而不是全文: ${a.description}")
    }

    /**
     * Atom 两样都给的（不少博客）：summary 是摘要、content 是全文。从前 summary 排在前面，点进去只有摘要，
     * 改了「只给摘要就去原网页整理」之后还会平白多抓一次原网页
     */
    @Test
    fun `Atom 的 content 优先于 summary，空标签跳过`() {
        val xml = """
        <?xml version="1.0"?>
        <feed xmlns="http://www.w3.org/2005/Atom">
          <entry>
            <title>A</title>
            <link href="https://e.com/a"/>
            <summary>只是摘要</summary>
            <content type="html">&lt;p&gt;这是全文&lt;/p&gt;</content>
          </entry>
          <entry>
            <title>B</title>
            <link href="https://e.com/b"/>
            <content type="html"></content>
            <summary>只有摘要可用</summary>
          </entry>
        </feed>
        """.trimIndent()

        val (a, b) = assertNotNull(RssXmlParser.parse("s", xml))
        assertEquals("<p>这是全文</p>", a.description)
        assertEquals("只有摘要可用", b.description)
    }

    /** `type="xhtml"` 直接嵌元素：取里面的 HTML，段落和图都还在（取 text() 会压成一行字） */
    @Test
    fun `Atom 的 xhtml 正文保留标签`() {
        val xml = """
        <?xml version="1.0"?>
        <feed xmlns="http://www.w3.org/2005/Atom">
          <entry>
            <title>X</title>
            <link href="https://e.com/x"/>
            <content type="xhtml"><div xmlns="http://www.w3.org/1999/xhtml"><p>第一段</p><img src="https://e.com/1.png"/><p>第二段</p></div></content>
          </entry>
        </feed>
        """.trimIndent()

        val d = assertNotNull(RssXmlParser.parse("s", xml)).single().description!!
        assertTrue("<p>第一段</p>" in d && "<img" in d && "<p>第二段</p>" in d, d)
    }

    @Test
    fun `不是 feed 就返回 null，交给规则那条路`() {
        assertNull(RssXmlParser.parse("s", "<html><body><div>普通网页</div></body></html>"))
    }

    @Test
    fun `摘要里的头一张图当封面`() {
        val xml = """
        <?xml version="1.0"?>
        <rss version="2.0"><channel><item>
          <title>T</title><link>https://e.com/1</link>
          <description>&lt;img src="https://e.com/p.jpg"/&gt;正文</description>
        </item></channel></rss>
        """.trimIndent()
        val a = assertNotNull(RssXmlParser.parse("s", xml)).single()
        assertEquals("https://e.com/p.jpg", a.image)
    }

    /** 爱范儿早报：摘要里头一张图是条目前面的 🚨 表情，后面才是配图 */
    @Test
    fun `封面跳过表情图`() {
        val xml = """
        <?xml version="1.0"?>
        <rss version="2.0"><channel><item>
          <title>早报</title><link>https://e.com/2</link>
          <description>&lt;p&gt;&lt;img src="https://s.w.org/images/core/emoji/17.0.2/72x72/1f6a8.png" alt="🚨" class="wp-smiley"/&gt;头条&lt;/p&gt;&lt;img src="https://e.com/huawei.jpg"/&gt;</description>
        </item></channel></rss>
        """.trimIndent()
        val a = assertNotNull(RssXmlParser.parse("s", xml)).single()
        assertEquals("https://e.com/huawei.jpg", a.image)
    }
}
