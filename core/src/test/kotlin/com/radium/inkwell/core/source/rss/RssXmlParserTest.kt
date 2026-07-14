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
}
