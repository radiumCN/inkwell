package com.radium.inkwell.core.source

import org.jsoup.Jsoup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LegadoSelectorTest {

    private val doc = Jsoup.parse(
        """
        <html><body>
          <div class="wrap"><div class="row"><a href="/a1">A1</a><a href="/a2">A2</a></div></div>
          <div class="wrap"><div class="row"><a href="/b1">B1</a></div></div>
          <ul id="list"><li>甲</li><li>乙</li><li>丙</li><li>丁</li></ul>
          <div id="content">
            <script>bad()</script>
            <style>p{color:red}</style>
            <p>正文一</p><p>正文二</p>
          </div>
          <div class="nav"><a href="/next">下一页</a></div>
          <div class="nav"><a href="/next">下一页</a></div>
        </body></html>
        """.trimIndent(),
        "https://ex.com/",
    )

    private fun s(rule: String) = LegadoSelector.strings(doc, rule)
    private fun e(rule: String) = LegadoSelector.elements(doc, rule)

    @Test
    fun `层级逐段选择`() {
        assertEquals(listOf("/a1", "/a2", "/b1"), s("class.row@tag.a@href"))
        assertEquals(3, e("class.row@tag.a").size)
    }

    /**
     * 全篇最关键的一条：索引作用于「匹配集」，不是 CSS 的兄弟序号。
     * 两个 .row 各自是所在 .wrap 里唯一的 div，所以 CSS 的 `:last-of-type` 会把两个都选中，
     * 而 Legado 的 `.-1` 只该选中第二个。这正是译成 CSS 补不完窟窿的地方。
     */
    @Test
    fun `索引作用于匹配集而非兄弟序号`() {
        assertEquals(2, doc.select(".row:last-of-type").size)
        assertEquals(listOf("/b1"), s("class.row.-1@tag.a@href"))
        assertEquals(listOf("/a1", "/a2"), s("class.row.0@tag.a@href"))
    }

    @Test
    fun `阅读原写法的索引：单个、多个、排除`() {
        assertEquals(listOf("甲", "乙", "丙", "丁"), s("tag.li@text"))
        assertEquals(listOf("甲"), s("tag.li.0@text"))
        assertEquals(listOf("丁"), s("tag.li.-1@text"))
        // `.` 后的 `:` 分隔的是多个索引，不是区间
        assertEquals(listOf("甲", "丙"), s("tag.li.0:2@text"))
        assertEquals(listOf("乙", "丙", "丁"), s("tag.li!0@text"))
        assertEquals(listOf("乙", "丙"), s("tag.li!0:-1@text"))
    }

    @Test
    fun `方括号写法的索引：区间、步长、逆序、排除`() {
        // `[]` 里的 `:` 才是区间
        assertEquals(listOf("乙", "丙"), s("tag.li[1:2]@text"))
        assertEquals(listOf("甲", "丙"), s("tag.li[0:3:2]@text"))
        assertEquals(listOf("丁", "丙", "乙", "甲"), s("tag.li[-1:0]@text"))
        assertEquals(listOf("丙", "丁"), s("tag.li[!0,1]@text"))
        assertEquals(listOf("甲", "丁"), s("tag.li[0,-1]@text"))
        // 越界的索引丢弃而不是报错
        assertEquals(listOf("甲"), s("tag.li[0,99]@text"))
    }

    @Test
    fun `方括号里不是索引时按 CSS 属性选择器处理`() {
        assertEquals(listOf("下一页"), s("div[class=nav]@tag.a@text").distinct())
    }

    @Test
    fun `提取器`() {
        assertEquals(listOf("正文一", "正文二"), s("id.content@tag.p@text"))
        // html：整个匹配集合成一段，且去掉脚本与样式
        val html = s("id.content@html").single()
        assertTrue("正文一" in html && "正文二" in html)
        assertFalse("bad()" in html)
        assertFalse("color:red" in html)
        // 属性：上下两处导航重复，去重
        assertEquals(listOf("/next"), s("class.nav@tag.a@href"))
    }

    @Test
    fun `html 提取不污染文档：后续规则仍能取到脚本样式所在的节点`() {
        s("id.content@html")
        assertEquals(1, doc.select("#content script").size)
    }

    @Test
    fun `选择器种类`() {
        assertEquals(listOf("正文一"), s("text.正文一@text"))
        assertEquals(4, e("id.list@children").size)
        assertEquals(listOf("甲", "乙", "丙", "丁"), s("@css:#list li@text"))
        assertEquals(listOf("甲"), s("#list li@text").take(1))
    }

    @Test
    fun `组合：回退、拼接、交错`() {
        assertEquals(listOf("甲"), s("tag.li.0@text||tag.nope@text"))
        assertEquals(listOf("甲"), s("tag.nope@text||tag.li.0@text"))
        assertEquals(listOf("甲", "丁"), s("tag.li.0@text&&tag.li.-1@text"))
        assertEquals(listOf("甲", "丙", "乙", "丁"), s("tag.li.0:1@text%%tag.li.2:3@text"))
    }

    @Test
    fun `class 名里的空格与点都表示同时具有这些 class`() {
        val d = Jsoup.parse("<div class='row book_info'><b>命中</b></div><div class='row'><b>否</b></div>")
        assertEquals(listOf("命中"), LegadoSelector.strings(d, "class.row book_info@tag.b@text"))
    }
}
