package com.radium.inkwell.core.source.detect

import com.radium.inkwell.core.source.LegadoSelector
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.nio.charset.Charset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class SearchDetectorsTest {

    private val gbk = Charset.forName("GBK")

    // ---- 搜索地址模板 ----

    @Test
    fun `get form keeps hidden fields and drops the action query`() {
        val t = SearchTemplates.fromForm(
            action = "https://www.example.com/search.php?from=nav",
            method = "get",
            charset = "UTF-8",
            fields = listOf(FormField("q", "剑", "search"), FormField("type", "all", "hidden")),
        )
        assertEquals("https://www.example.com/search.php?q={{key}}&type=all", t)
    }

    @Test
    fun `gbk post form becomes a post template with charset`() {
        val t = SearchTemplates.fromForm(
            action = "https://www.example.com/modules/article/search.php",
            method = "POST",
            charset = "GB2312",
            fields = listOf(FormField("searchtype", "articlename", "hidden"), FormField("searchkey", "剑", "text")),
        )
        assertEquals(
            """https://www.example.com/modules/article/search.php,{"method":"POST","body":"searchtype=articlename&searchkey={{key}}","charset":"gbk"}""",
            t,
        )
    }

    @Test
    fun `form without a filled text box is not a search`() {
        assertNull(
            SearchTemplates.fromForm(
                "https://www.example.com/login", "post", null,
                listOf(FormField("token", "abc", "hidden"), FormField("user", "", "text")),
            ),
        )
    }

    @Test
    fun `keyword found in the url in either encoding`() {
        assertEquals(
            "https://www.example.com/s?q={{key}}&p=1",
            SearchTemplates.fromUrl("https://www.example.com/s?q=%E5%89%91&p=1", "剑"),
        )
        val gbkKeyword = URLEncoder.encode("剑", gbk)
        assertEquals(
            """https://www.example.com/s?q={{key}},{"charset":"gbk"}""",
            SearchTemplates.fromUrl("https://www.example.com/s?q=$gbkKeyword", "剑"),
        )
        assertEquals(
            "https://www.example.com/search/{{key}}/1.html",
            SearchTemplates.fromUrl("https://www.example.com/search/%e5%89%91/1.html", "剑"),
        )
        assertNull(SearchTemplates.fromUrl("https://www.example.com/s?q=%E5%88%80", "剑"))
    }

    @Test
    fun `form keyword and site ownership`() {
        val fields = listOf(FormField("t", "1", "hidden"), FormField("q", "", "text"), FormField("kw", "剑", "search"))
        assertEquals("剑", SearchTemplates.formKeyword(fields))
        assertNull(SearchTemplates.formKeyword(listOf(FormField("t", "1", "hidden"))))
        // 手机站搜索提交到 PC 站域名：同一个站
        assertEquals(true, SearchTemplates.belongsTo("https://www.example.com/s.php", "https://m.example.com/book/1/"))
        // 页面里广告 iframe 的表单：不认
        assertEquals(false, SearchTemplates.belongsTo("https://ads.other.net/track", "https://m.example.com/book/1/"))
    }

    // ---- 结果列表 ----

    private fun found(html: String, shape: String? = "/book/#/") =
        assertIs<SearchDetection.Found>(
            SearchListDetector.detect(Jsoup.parse(html, "https://www.example.com/search.php?q=x"), "剑", shape),
        )

    @Test
    fun `list layout picks the result items not the sidebar`() {
        val doc = Jsoup.parse(DetectFixtures.searchList("剑", 5), "https://www.example.com/search.php?q=x")
        val d = found(DetectFixtures.searchList("剑", 5))
        assertEquals(5, d.resultCount)
        val r = d.rules
        assertEquals("@css:div.result-list > div.result-item", r.bookList)
        val rows = LegadoSelector.elements(doc, r.bookList!!)
        assertEquals("剑之书1", LegadoSelector.strings(rows[0], r.name!!).single())
        assertEquals("/book/2001/", LegadoSelector.strings(rows[0], r.bookUrl!!).single())
        // 作者带前缀，规则里用 ## 剥掉；封面取懒加载的真地址
        assertEquals("作者：写手1", LegadoSelector.strings(rows[0], r.author!!.substringBefore("##")).single())
        assertEquals("@css:img@data-original", r.coverUrl)
        assertEquals("第999章 终局1", LegadoSelector.strings(rows[0], r.lastChapter!!).single())
    }

    @Test
    fun `table layout maps columns by header`() {
        val doc = Jsoup.parse(DetectFixtures.searchTable("剑", 4), "https://www.example.com/search.php")
        val r = found(DetectFixtures.searchTable("剑", 4)).rules
        val rows = LegadoSelector.elements(doc, r.bookList!!)
        // 表头那一行可以被列表规则选进来：书名规则在它上面取不到东西，引擎会跳过
        val data = rows.filter { LegadoSelector.strings(it, r.name!!).isNotEmpty() }
        assertEquals(4, data.size)
        assertEquals("剑表1", LegadoSelector.strings(data[0], r.name!!).single())
        assertEquals("tag.td.2@text", r.author)
        assertEquals("表作者1", LegadoSelector.strings(data[0], r.author!!).single())
        assertEquals("tag.td.1@text", r.lastChapter)
    }

    @Test
    fun `falls back to keyword links when the book url shape differs`() {
        // 结果链到另一种详情页（/info/#.html），和向导认过的书页形状对不上
        val html = DetectFixtures.searchList("剑", 4).replace(Regex("/book/(\\d+)/\""), "/info/$1.html\"")
        assertEquals(4, found(html, shape = "/book/#/").resultCount)
    }

    @Test
    fun `a single result is too few to learn the list`() {
        val d = SearchListDetector.detect(Jsoup.parse(DetectFixtures.searchSingle("剑"), "https://www.example.com/s"), "剑", "/book/#/")
        assertEquals(SearchDetection.TooFew(1), d)
    }

    /** 按生成的规则在页面上取出所有结果的书名 —— 引擎就是这么取的：列表选出条目，书名规则在每条上求值 */
    private fun titlesOf(html: String, rules: com.radium.inkwell.core.source.SearchRuleSet): List<String> {
        val doc = Jsoup.parse(html, "https://www.example.com/search.php")
        return LegadoSelector.elements(doc, rules.bookList!!)
            .mapNotNull { LegadoSelector.strings(it, rules.name!!).firstOrNull() }
    }

    /** 每个链接自己就是一条时，同一个容器里的作者链接不能被当成书 */
    @Test
    fun `inline results do not pick up the author links`() {
        val html = DetectFixtures.searchInline("剑", 3)
        val r = found(html).rules
        assertEquals(listOf("剑行1", "剑行2", "剑行3"), titlesOf(html, r))
    }

    /** 列表规则多选进来的条目（广告项）上，书名规则必须取不到东西，否则它就成了一条搜索结果 */
    @Test
    fun `an ad item in the list is not a result`() {
        val html = DetectFixtures.searchWithAd("剑", 3)
        val r = found(html).rules
        assertEquals(listOf("剑项1", "剑项2", "剑项3"), titlesOf(html, r))
    }
}
