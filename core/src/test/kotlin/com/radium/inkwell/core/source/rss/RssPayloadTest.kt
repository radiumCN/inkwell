package com.radium.inkwell.core.source.rss

import com.radium.inkwell.core.util.LegadoLinks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 粘进来的地址里装的是什么。从前不看内容、一律当 feed：源仓库的订阅源合集被建成一个永远打不开的订阅源。
 */
class RssPayloadTest {

    @Test
    fun `阅读的订阅源合集`() {
        // 源仓库 …/yuedu/rsss/json/id/205.json 返回的样子（节选）
        val body = """
            [
              {"articleStyle":0,"singleUrl":true,"sourceGroup":"Legado","sourceName":"阅读 · 全版本汇总",
               "sourceUrl":"https://legado.erchuang.online/","type":0},
              {"sourceName":"源仓库","sourceUrl":"https://www.yckceo.com/##","ruleArticles":".layui-card-body@.ylist"}
            ]
        """.trimIndent()
        assertEquals(RssPayload.LEGADO_SOURCES, RssPayload.of(body))
        // 单条、带 BOM、前面有空行也认
        assertEquals(RssPayload.LEGADO_SOURCES, RssPayload.of("\uFEFF\n  {\"sourceUrl\":\"https://a.com\",\"sourceName\":\"甲\"}"))
    }

    @Test
    fun `RSS 和 Atom 是 feed`() {
        assertEquals(RssPayload.FEED, RssPayload.of("<?xml version=\"1.0\"?>\n<rss version=\"2.0\"><channel></channel></rss>"))
        assertEquals(RssPayload.FEED, RssPayload.of("<feed xmlns=\"http://www.w3.org/2005/Atom\"></feed>"))
        assertEquals(RssPayload.FEED, RssPayload.of("<rdf:RDF></rdf:RDF>"))
    }

    @Test
    fun `来源合集放错了地方`() {
        assertEquals(
            RssPayload.BOOK_SOURCES,
            RssPayload.of("""[{"bookSourceUrl":"https://a.com","bookSourceName":"甲","sourceUrl":"x"}]"""),
        )
    }

    @Test
    fun `普通网页、别的 JSON 都不是`() {
        assertEquals(RssPayload.UNKNOWN, RssPayload.of("<!doctype html><html><body>hi</body></html>"))
        assertEquals(RssPayload.UNKNOWN, RssPayload.of("""{"code":0,"data":[]}"""))
        assertEquals(RssPayload.UNKNOWN, RssPayload.of(""))
    }
}

class LegadoLinksTest {

    @Test
    fun `订阅源的一键导入链接拆出地址`() {
        assertTrue(LegadoLinks.isAppLink("legado://import/rssSource?src=https://a.com/r.json"))
        assertEquals(
            "https://www.yckceo.com/yuedu/rsss/json/id/205.json",
            LegadoLinks.unwrap("legado://import/rssSource?src=https%3A%2F%2Fwww.yckceo.com%2Fyuedu%2Frsss%2Fjson%2Fid%2F205.json"),
        )
        assertEquals("https://a.com/r.json", LegadoLinks.unwrap("yuedu://rsssource/importonline?src=https://a.com/r.json"))
    }

    @Test
    fun `普通网址和拆不出的原样返回`() {
        assertFalse(LegadoLinks.isAppLink("https://a.com"))
        assertEquals("https://a.com", LegadoLinks.unwrap("  https://a.com "))
        assertEquals("legado://import/rssSource", LegadoLinks.unwrap("legado://import/rssSource"))
    }
}
