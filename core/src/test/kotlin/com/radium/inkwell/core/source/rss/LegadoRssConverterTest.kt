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

    /** Legado 的分类分隔符除了换行还有 `&&`：写成一行的源从前只剩一个拼坏了地址的分类 */
    @Test
    fun `分类也按 && 拆开`() {
        val legado = """
        [{"sourceName":"某站","sourceUrl":"https://x.com",
          "sortUrl":"最新::https://x.com/new&&热门::https://x.com/hot"}]
        """.trimIndent()
        val rule = LegadoRssConverter.convert(legado).converted.single().rule
        assertEquals(listOf("https://x.com/new", "https://x.com/hot"), rule.sorts.map { it.url })
        assertEquals(listOf("最新", "热门"), rule.sorts.map { it.title })
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
     * 单网页源：阅读里点开就是用内置浏览器打开一个网页。照阅读的做法导进来、点开是应用内网页 ——
     * 从前整条跳过，源仓库的订阅源合集导进来少一大半（202.json 六个只进来一个）。
     */
    @Test
    fun `单网页订阅源照样导进来，点开的地址照阅读取`() {
        val legado = """
        [
          {"sourceName":"使用文档","sourceUrl":"https://www.yuque.com/legado/wiki","singleUrl":true},
          {"sourceName":"带分类","sourceUrl":"https://x.com/##","singleUrl":true,"sortUrl":"首页::https://x.com/home&&榜单::https://x.com/rank"},
          {"sourceName":"带选项","sourceUrl":"https://y.com/novel,{\"headers\":{\"Referer\":\"https://y.com\"}}","singleUrl":true,"enableJs":false}
        ]
        """.trimIndent()
        val result = LegadoRssConverter.convert(legado)
        assertTrue(result.skipped.isEmpty())
        val rules = result.converted.map { it.rule }
        assertTrue(rules.all { it.singleUrl })
        assertEquals("https://www.yuque.com/legado/wiki", rules[0].pageUrl())
        // 配了分类地址：取第一个分类的（同阅读的 getSingleUrl）
        assertEquals("https://x.com/home", rules[1].pageUrl())
        // 地址后面的选项丢掉，地址照样打开；页面脚本开关照搬
        assertEquals("https://y.com/novel", rules[2].pageUrl())
        assertEquals(false, rules[2].enableJs)
        assertEquals(true, rules[0].enableJs)
    }

    @Test
    fun `地址要跑脚本才算得出来的单网页源跳过，并说清原因`() {
        val legado = """
        [
          {"sourceName":"脚本地址","sourceUrl":"http@js:eval(cache.getFromMemory('x')+'')","singleUrl":true},
          {"sourceName":"脚本分类","sourceUrl":"https://a.com","singleUrl":true,"sortUrl":"@js:'https://a.com/'+1"},
          {"sourceName":"脚本选项","sourceUrl":"https://b.com/novel,{\"js\":\"java.longToast(1)\"}","singleUrl":true}
        ]
        """.trimIndent()
        val result = LegadoRssConverter.convert(legado)
        assertTrue(result.converted.isEmpty())
        assertEquals(3, result.skipped.size)
        assertTrue(result.skipped.all { it.reason.contains("脚本") })
    }

    @Test
    fun `不是单网页源就没有点开的网页地址`() {
        val rule = LegadoRssConverter.convert("""{"sourceName":"列表","sourceUrl":"https://c.com","ruleArticles":"a"}""")
            .converted.single().rule
        assertEquals(false, rule.singleUrl)
        assertEquals(null, rule.pageUrl())
    }

    @Test
    fun `认得出这是订阅源而不是书源`() {
        assertTrue(LegadoRssConverter.looksLikeLegadoRss("""{"sourceUrl":"https://a"}"""))
        assertTrue(!LegadoRssConverter.looksLikeLegadoRss("""{"bookSourceUrl":"https://a"}"""))
    }
}
