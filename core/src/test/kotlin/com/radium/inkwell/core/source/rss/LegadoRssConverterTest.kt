package com.radium.inkwell.core.source.rss

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LegadoRssConverterTest {

    @Test
    fun `分类按「名称__地址」逐行拆开`() {
        val legado = """
        [{
          "sourceName": "少数派",
          "sourceUrl": "https://sspai.com",
          "sourceGroup": "科技",
          "sortUrl": "首页::https://sspai.com/feed\n热门::https://sspai.com/feed/hot",
          "ruleArticles": "class.article",
          "ruleTitle": "tag.h2@text",
          "ruleLink": "tag.a@href"
        }]
        """.trimIndent()

        val rule = LegadoRssConverter.convert(legado).converted.single().rule
        assertEquals("少数派", rule.name)
        assertEquals("科技", rule.group)
        assertEquals(2, rule.sorts.size)
        assertEquals("首页", rule.sorts[0].title)
        assertEquals("https://sspai.com/feed", rule.sorts[0].url)
        assertEquals("热门", rule.sorts[1].title)
    }

    /** 没有列表规则是**常态**：标准 XML feed 填个地址就该能用 */
    @Test
    fun `纯 feed 源（没有任何规则）照样转得进来`() {
        val legado = """
        [{"sourceName":"某博客","sourceUrl":"https://blog.com/rss.xml"}]
        """.trimIndent()
        val rule = LegadoRssConverter.convert(legado).converted.single().rule
        assertEquals(null, rule.articles)
        // 没配分类时，源地址本身就是那个分类
        assertEquals(1, rule.sortsOrDefault().size)
        assertEquals("https://blog.com/rss.xml", rule.sortsOrDefault()[0].url)
    }

    /**
     * singleUrl 的源只是"用内置浏览器打开一个网页"，没有任何可解析的东西。
     * 硬转进来只会在订阅列表里躺一个永远加载失败的条目。
     */
    @Test
    fun `单网页订阅源被跳过，并说清原因`() {
        val legado = """
        [{"sourceName":"某单页","sourceUrl":"https://x.com","singleUrl":true}]
        """.trimIndent()
        val result = LegadoRssConverter.convert(legado)
        assertTrue(result.converted.isEmpty())
        assertTrue(result.skipped.single().reason.contains("singleUrl"))
    }

    @Test
    fun `认得出这是订阅源而不是书源`() {
        assertTrue(LegadoRssConverter.looksLikeLegadoRss("""{"sourceUrl":"https://a"}"""))
        assertTrue(!LegadoRssConverter.looksLikeLegadoRss("""{"bookSourceUrl":"https://a"}"""))
    }
}
