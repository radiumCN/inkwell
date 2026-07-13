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
        // 默认语法原样透传给 LegadoSelector（索引作用于匹配集，CSS 表达不了）
        assertEquals("legado:class.result-list@tag.li", search.list)
        assertEquals("legado:class.book-name@tag.a@text", search.fields["title"])
        assertEquals("legado:class.book-name@tag.a@href", search.fields["bookUrl"])
        // ## 后缀仍然抽成 regex 管道
        assertEquals("legado:class.author@text | regex:作者[:：] ", search.fields["author"])

        val detail = assertNotNull(rule.detail)
        assertEquals("legado:id.intro@html", detail.fields["intro"])
        assertEquals("legado:class.read-btn@tag.a.0@href", detail.fields["tocUrl"])

        val toc = assertNotNull(rule.toc)
        assertEquals("legado:id.list@tag.dd@tag.a", toc.list)
        assertEquals("legado:text", toc.fields["title"])
        assertEquals("legado:href", toc.fields["url"])

        val content = assertNotNull(rule.content)
        assertEquals("legado:id.content@html", content.content)
        // 正文 ## 与 replaceRegex 全部进 purify
        assertEquals(3, content.purify.size)
        assertEquals("天才一秒记住.*?org", content.purify[0].pattern)
        assertEquals("笔趣阁.*?最快更新", content.purify[1].pattern)

        assertEquals(2, rule.explore.size)
        assertEquals("玄幻", rule.explore[0].name)
        assertEquals("/list/1_{{page}}.html", rule.explore[0].url)
        assertEquals("legado:class.book-list@tag.li", rule.explore[0].list)
    }

    @Test
    fun `js rules convert to js pipes`() {
        val js = """
        {
          "bookSourceUrl": "https://js.example.com",
          "bookSourceName": "JS站",
          "searchUrl": "/s?q={{key}}",
          "ruleSearch": {
            "bookList": "<js>JSON.parse(result).list</js>",
            "name": "name",
            "bookUrl": "tag.a.0@href@js:result.replace('m.','www.')"
          },
          "ruleToc": { "chapterList": "tag.a", "chapterName": "text", "chapterUrl": "href" },
          "ruleContent": { "content": "id.content@html" }
        }
        """.trimIndent()
        val result = LegadoConverter.convert(js)
        assertEquals(0, result.skipped.size)
        val rule = result.converted.single().rule
        // 纯脚本 → js 原子规则；规则@js: → js 管道（均 base64 编码）
        assertTrue(rule.search!!.list.startsWith("js:b64:"))
        assertTrue(rule.search!!.fields["bookUrl"]!!.contains("| js:b64:"))
    }

    @Test
    fun `js referencing unsupported objects is skipped`() {
        val js = """
        {
          "bookSourceUrl": "https://java.example.com",
          "bookSourceName": "Java桥站",
          "searchUrl": "/s?q={{key}}",
          "ruleSearch": { "bookList": "<js>java.ajax(url)</js>", "name": "name", "bookUrl": "url" },
          "ruleToc": { "chapterList": "tag.a", "chapterName": "text", "chapterUrl": "href" },
          "ruleContent": { "content": "id.content@html" }
        }
        """.trimIndent()
        val result = LegadoConverter.convert(js)
        assertEquals(1, result.skipped.size)
        assertTrue(result.skipped[0].reason.contains("不支持的对象"))
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
        assertEquals("legado:@css:.author@text", rule.search!!.fields["author"])
        assertEquals("legado:@css:ul.chapters > li > a", rule.toc!!.list)
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
        // 含 || 的规则整条 base64 后交给 LegadoSelector（它自己处理回退），避免被 DSL 切分器切坏
        assertEquals(
            "legado:b64:" + java.util.Base64.getEncoder()
                .encodeToString("tag.img@data-src||tag.img@src".toByteArray()),
            rule.search!!.fields["coverUrl"],
        )
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
            "bookUrl": "tag.a.0@href",
            "author": "class.info a@text##作者 大人",
            "intro": "class.intro summary@text"
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
        // 排除索引 !0：以前 CSS 只能近似成 :gt(0)（兄弟序号，语义不同），现在原样透传
        assertEquals("legado:id.author@tag.tbody@tag.tr!0", search.list)
        // 空格用 unicode 转义（反斜杠+空格在 Android ICU 上非法）；class 名带空格 = 多 class
        assertTrue(search.fields["author"]!!.contains("regex:作者\\u0020大人"))
        assertEquals("legado:class.intro summary@text", search.fields["intro"])
        assertEquals("legado:class.full@children[0]@tag.a", rule.toc!!.list)
        assertEquals("legado:class.box.-1@tag.p.!-1@html", rule.content!!.content)
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

    @Test
    fun `模板型 tocUrl 转为字面量模板，JS 与 POST 选项仍报不支持`() {
        fun tocUrlOf(tocUrl: String) = LegadoConverter.convert(
            """
            {
              "bookSourceUrl": "https://api.example.com",
              "bookSourceName": "接口站",
              "searchUrl": "/s?q={{key}}",
              "ruleSearch": { "bookList": "class.list@tag.li", "name": "tag.h3@text", "bookUrl": "tag.a@href" },
              "ruleBookInfo": { "name": "tag.h1@text", "tocUrl": ${'"'}$tocUrl${'"'} },
              "ruleToc": { "chapterList": "class.dir@tag.a", "chapterName": "text", "chapterUrl": "href" },
              "ruleContent": { "content": "id.content@html" }
            }
            """.trimIndent()
        )

        // {{$.x}} / {{baseUrl}} 都能在求值期填 → 整条转成字面量模板
        tocUrlOf("/api/book/{{$.book_id}}/chapters").let {
            assertEquals(0, it.skipped.size)
            assertEquals(
                "text:/api/book/{{$.book_id}}/chapters",
                it.converted.single().rule.detail!!.fields["tocUrl"],
            )
        }

        // JS 脚本里也会出现 {{$.x}} 插值，那是脚本的一部分，不能整条当模板
        tocUrlOf("@js:java.get('u')+'{{$.bid}}'").let {
            assertEquals(1, it.skipped.size)
            assertTrue(it.skipped.single().reason.startsWith("目录地址规则无法转换"))
        }

        // 带 POST/body 的「地址,{选项}」：剥掉选项后发 GET 是错的，宁可跳过也不要产出坏书源
        tocUrlOf("/api/toc,{'method':'POST','body':'id={{$.book_id}}'}").let {
            assertEquals(1, it.skipped.size)
            assertTrue(it.skipped.single().reason.startsWith("目录地址规则无法转换"))
        }
    }
}
