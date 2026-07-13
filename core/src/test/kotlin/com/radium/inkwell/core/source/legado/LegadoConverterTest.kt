package com.radium.inkwell.core.source.legado

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LegadoConverterTest {

    // 典型 Legado 书源：默认 Jsoup 层级 + POST GBK 搜索 + ##净化 + replaceRegex + 发现页
    private val typical = """
    {
      "bookSourceUrl": "https://www.example.com",
      "bookSourceName": "示例站",
      "bookSourceType": 0,
      "enabled": true,
      "header": "{\"User-Agent\":\"Mozilla/5.0 Test\"}",
      "concurrentRate": "500",
      "searchUrl": "/search.php,{\"method\":\"POST\",\"body\":\"searchkey={{key}}&page={{page}}\",\"charset\":\"gbk\"}",
      "exploreUrl": "玄幻::/list/1_{{page}}.html\n都市::/list/2_{{page}}.html",
      "ruleSearch": {
        "bookList": "class.result-list@tag.li",
        "name": "class.book-name@tag.a@text",
        "author": "class.author@text##作者[:：]",
        "bookUrl": "class.book-name@tag.a@href",
        "coverUrl": "tag.img@src",
        "lastChapter": "class.last@text"
      },
      "ruleBookInfo": {
        "name": "class.info@tag.h1@text",
        "intro": "id.intro@html",
        "tocUrl": "class.read-btn@tag.a.0@href"
      },
      "ruleToc": {
        "chapterList": "id.list@tag.dd@tag.a",
        "chapterName": "text",
        "chapterUrl": "href",
        "nextTocUrl": "class.next@href"
      },
      "ruleContent": {
        "content": "id.content@html##天才一秒记住.*?org",
        "replaceRegex": "##笔趣阁.*?最快更新##\n##(?m)^\\s*请收藏本站.*$##"
      },
      "ruleExplore": {
        "bookList": "class.book-list@tag.li",
        "name": "class.name@text",
        "bookUrl": "tag.a@href",
        "author": "class.author@text"
      }
    }
    """.trimIndent()

    @Test
    fun `detects legado format`() {
        assertTrue(LegadoConverter.looksLikeLegado(typical))
        assertTrue(!LegadoConverter.looksLikeLegado("""{"id":"x","baseUrl":"https://a.com","name":"n"}"""))
    }

    @Test
    fun `typical source converts fully`() {
        val result = LegadoConverter.convert(typical)
        assertEquals(0, result.skipped.size)
        val rule = result.converted.single().rule

        assertEquals("www.example.com", rule.id)
        assertEquals("示例站", rule.name)
        assertEquals("https://www.example.com", rule.baseUrl)
        assertEquals("gbk", rule.charset)
        assertEquals("Mozilla/5.0 Test", rule.headers["User-Agent"])
        assertEquals(500L, rule.rateLimit?.intervalMs)

        val search = assertNotNull(rule.search)
        assertEquals("POST", search.request.method)
        assertTrue(search.request.body!!.contains("searchkey={{keyword|encode:gbk}}"))
        assertEquals("css:.result-list li", search.list)
        assertEquals("css:.book-name a@text", search.fields["title"])
        assertEquals("css:.book-name a@href", search.fields["bookUrl"])
        // ## 后缀 → regex 管道
        assertEquals("css:.author@text | regex:作者[:：] ", search.fields["author"])

        val detail = assertNotNull(rule.detail)
        assertEquals("css:#intro@html", detail.fields["intro"])
        // 末段索引 0 → first 管道
        assertEquals("css:.read-btn a@href | first", detail.fields["tocUrl"])

        val toc = assertNotNull(rule.toc)
        assertEquals("css:#list dd a", toc.list)
        assertEquals("css:@text", toc.fields["title"])
        assertEquals("css:@href", toc.fields["url"])

        val content = assertNotNull(rule.content)
        assertEquals("css:#content@html", content.content)
        // 正文 ## 与 replaceRegex 全部进 purify
        assertEquals(3, content.purify.size)
        assertEquals("天才一秒记住.*?org", content.purify[0].pattern)
        assertEquals("笔趣阁.*?最快更新", content.purify[1].pattern)

        assertEquals(2, rule.explore.size)
        assertEquals("玄幻", rule.explore[0].name)
        assertEquals("/list/1_{{page}}.html", rule.explore[0].url)
        assertEquals("css:.book-list li", rule.explore[0].list)
    }

    @Test
    fun `js source is skipped with reason`() {
        val js = """
        {
          "bookSourceUrl": "https://js.example.com",
          "bookSourceName": "JS站",
          "searchUrl": "/s?q={{key}}",
          "ruleSearch": { "bookList": "<js>result.list</js>", "name": "name", "bookUrl": "url" },
          "ruleToc": { "chapterList": "tag.a", "chapterName": "text", "chapterUrl": "href" },
          "ruleContent": { "content": "id.content@html" }
        }
        """.trimIndent()
        val result = LegadoConverter.convert(js)
        assertEquals(0, result.converted.size)
        assertEquals(1, result.skipped.size)
        assertTrue(result.skipped[0].reason.contains("搜索列表规则无法转换"))
    }

    @Test
    fun `array input with mixed results`() {
        val array = "[" + typical + "," + """
        {
          "bookSourceUrl": "https://audio.example.com",
          "bookSourceName": "音频站",
          "bookSourceType": 1,
          "searchUrl": "/s",
          "ruleSearch": {}, "ruleToc": {}, "ruleContent": {}
        }
        """.trimIndent() + "]"
        val result = LegadoConverter.convert(array)
        assertEquals(1, result.converted.size)
        assertEquals(1, result.skipped.size)
        assertTrue(result.skipped[0].reason.contains("非文字书源"))
    }

    @Test
    fun `css prefix and jsonpath rules convert`() {
        val src = """
        {
          "bookSourceUrl": "https://api.example.com",
          "bookSourceName": "API站",
          "searchUrl": "/api/search?q={{key}}",
          "ruleSearch": {
            "bookList": "${'$'}.data.list",
            "name": "${'$'}.bookName",
            "bookUrl": "${'$'}.bookUrl",
            "author": "@css:.author@text"
          },
          "ruleToc": { "chapterList": "@css:ul.chapters > li > a", "chapterName": "text", "chapterUrl": "href" },
          "ruleContent": { "content": "@css:#content@html" }
        }
        """.trimIndent()
        val result = LegadoConverter.convert(src)
        val rule = result.converted.single().rule
        assertEquals("json:$.data.list", rule.search!!.list)
        assertEquals("json:$.bookName", rule.search!!.fields["title"])
        assertEquals("css:.author@text", rule.search!!.fields["author"])
        assertEquals("css:ul.chapters > li > a", rule.toc!!.list)
    }

    @Test
    fun `reversed toc and fallback rules`() {
        val src = """
        {
          "bookSourceUrl": "https://r.example.com",
          "bookSourceName": "倒序站",
          "searchUrl": "/s?q={{key}}",
          "ruleSearch": {
            "bookList": "class.list@tag.li",
            "name": "tag.h3@text",
            "bookUrl": "tag.a@href",
            "coverUrl": "tag.img@data-src||tag.img@src"
          },
          "ruleToc": { "chapterList": "-id.chapters@tag.a", "chapterName": "text", "chapterUrl": "href" },
          "ruleContent": { "content": "id.txt@html" }
        }
        """.trimIndent()
        val result = LegadoConverter.convert(src)
        val rule = result.converted.single().rule
        assertTrue(rule.toc!!.reverse)
        assertEquals("css:img@attr(data-src) || css:img@src", rule.search!!.fields["coverUrl"])
    }

    @Test
    fun `index exclusion bracket index and single-quote options convert`() {
        // 来自真实书源合集的语法形态：tr!0 / children[0] / p.!-1 / .-1 中段索引 / 单引号选项 / page 算术
        val src = """
        {
          "bookSourceUrl": "https://x.example.com",
          "bookSourceName": "语法站",
          "searchUrl": "/search.php,{'method':'POST','body':'key={{key}}&start={{(page-1)*20}}'}",
          "ruleSearch": {
            "bookList": "id.author@tag.tbody@tag.tr!0",
            "name": "tag.td.0@text",
            "bookUrl": "tag.a.0@href"
          },
          "ruleToc": {
            "chapterList": "class.full@children[0]@tag.a",
            "chapterName": "text",
            "chapterUrl": "href"
          },
          "ruleContent": { "content": "class.box.-1@tag.p.!-1@html" }
        }
        """.trimIndent()
        val result = LegadoConverter.convert(src)
        assertEquals(0, result.skipped.size)
        val rule = result.converted.single().rule

        val search = rule.search!!
        assertEquals("POST", search.request.method)
        assertEquals("key={{keyword}}&start={{(page-1)*20}}", search.request.body)
        assertEquals("css:#author tbody tr:gt(0)", search.list)
        assertEquals("css:.full > *:first-child a", rule.toc!!.list)
        assertEquals("css:.box:last-of-type p:not(:last-of-type)@html", rule.content!!.content)
    }

    @Test
    fun `unconvertible optional field is dropped with warning but source survives`() {
        val src = """
        {
          "bookSourceUrl": "https://w.example.com",
          "bookSourceName": "警告站",
          "searchUrl": "/s?q={{key}}",
          "ruleSearch": {
            "bookList": "class.list@tag.li",
            "name": "tag.h3@text",
            "bookUrl": "tag.a@href",
            "intro": "@XPath://div[@class='intro']/text()"
          },
          "ruleToc": { "chapterList": "id.c@tag.a", "chapterName": "text", "chapterUrl": "href" },
          "ruleContent": { "content": "id.txt@html" }
        }
        """.trimIndent()
        val result = LegadoConverter.convert(src)
        val conv = result.converted.single()
        assertEquals(null, conv.rule.search!!.fields["intro"])
        assertTrue(conv.warnings.any { it.contains("XPath") || it.contains("intro") })
    }
}
