package com.radium.inkwell.core.source.js

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/**
 * Jsoup 的替身（JsoupShim）：来源脚本里直接用 Jsoup 的写法能跑，真的 Jsoup 对象拿不到。
 *
 * 用例照合集里的原样写法：解析 ajax 回来的页面再 select、`toArray().map`、逐个改文字节点（翻译类订阅源的脚本库）、
 * 按 XML 解析 feed、`Jsoup.connect(url).get()`。
 */
class JsoupShimTest {

    private val runtime = RhinoScriptRuntime()

    private fun eval(script: String, vararg bindings: Pair<String, Any?>): String? = runtime.eval(script, mapOf(*bindings))

    private val page = """<html><head><title>T</title></head><body>
        <div class="list"><a href="/b/1">书一</a><a href="/b/2">书二</a><a href="/b/3">书三</a></div>
        <p class="c">第一段<span>注</span></p></body></html>"""

    @Test
    fun `parse 之后 select、text、attr`() {
        assertEquals("书一 书二 书三", eval("org.jsoup.Jsoup.parse(result).select('.list a').text()", "result" to page))
        assertEquals("/b/2", eval("org.jsoup.Jsoup.parse(result).select('.list a').get(1).attr('href')", "result" to page))
        assertEquals("3", eval("String(org.jsoup.Jsoup.parse(result).select('a').size())", "result" to page))
        assertEquals("T", eval("org.jsoup.Jsoup.parse(result).title()", "result" to page))
        assertEquals("第一段", eval("org.jsoup.Jsoup.parse(result).select('p.c').first().ownText()", "result" to page))
        // Packages 写法、JavaImporter + with 写法
        assertEquals("书一", eval("Packages.org.jsoup.Jsoup.parse(result).selectFirst('a').text()", "result" to page))
        assertEquals(
            "书三",
            eval("var im = new JavaImporter(Packages.org.jsoup.Jsoup); with (im) { Jsoup.parse(result).select('a').last().text() }", "result" to page),
        )
    }

    @Test
    fun `一组元素当数组用：下标、length、toArray 再 map、forEach、for in、Array from`() {
        fun js(body: String) = eval("var els = org.jsoup.Jsoup.parse(result).select('.list a');\n$body", "result" to page)
        assertEquals("书一|3", js("els[0].text() + '|' + els.length"))
        // 撸哥网的写法
        assertEquals("书一::/b/1\n书二::/b/2\n书三::/b/3", js("els.toArray().map(x => x.text() + '::' + x.attr('href')).join('\\n')"))
        assertEquals("书一书二书三", js("var s = ''; els.forEach(function (e) { s += e.text() }); s"))
        assertEquals("书二", js("var r; for (var i in els) { if (i == 1) r = els[i].text() } r"))
        assertEquals("书一,书二,书三", js("Array.from(els).map(e => e.text()).join(',')"))
        assertEquals("书三", js("var last; for (var e of els) last = e.text(); last"))
        assertEquals("2", js("String(els.filter(e => e.attr('href') != '/b/1').length)"))
        // String(元素组) 是各自的 HTML
        assertTrue(js("String(els)")!!.startsWith("<a href=\"/b/1\">书一</a>"))
    }

    @Test
    fun `改文字节点再取 HTML（翻译脚本库 TC 的路子）`() {
        val script = """
            var doc = org.jsoup.Jsoup.parseBodyFragment(result), els = doc.select('*'), nodes = [];
            for (var i = 0; i < els.size(); i++) {
              var tn = els.get(i).textNodes();
              for (var k = 0; k < tn.size(); k++) if (String(tn.get(k).getWholeText()).trim()) nodes.push(tn.get(k));
            }
            for (var i = 0; i < nodes.length; i++) nodes[i].text('[' + nodes[i].text() + ']');
            doc.body().html()
        """.trimIndent()
        assertEquals("<p>[Hello]<b>[World]</b></p>", eval(script, "result" to "<p>Hello<b>World</b></p>"))
    }

    @Test
    fun `按 XML 解析 feed：标签、文字照原样，outerHtml 带着声明`() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?><rss><channel><item><title>Hi</title><link>https://a/1</link><dc:creator>X</dc:creator></item></channel></rss>"""
        val script = """
            var doc = org.jsoup.Jsoup.parse(result, '', org.jsoup.parser.Parser.xmlParser()), all = doc.getAllElements(), names = [];
            for (var i = 0; i < all.size(); i++) { var el = all.get(i); if (el.tagName() == 'title') el.text('你好'); names.push(el.tagName()); }
            names.join(',') + '|' + doc.select('link').text() + '|' + doc.outerHtml()
        """.trimIndent()
        val out = eval(script, "result" to xml)!!
        assertEquals("#root,rss,channel,item,title,link,dc:creator|https://a/1", out.substringBefore("|<"))
        val html = out.substringAfter("|https://a/1|")
        assertTrue(html.startsWith("<?xml"), html)
        assertTrue("<title>你好</title>" in html, html)
    }

    @Test
    fun `改节点：属性迭代删除、replaceWith 文字节点、createElement、after`() {
        val script = """
            var doc = org.jsoup.Jsoup.parse(result);
            var img = doc.select('img').first();
            var attrs = img.attributes().iterator();
            while (attrs.hasNext()) { var a = attrs.next(); if (String(a.getKey()) !== 'src') attrs.remove(); }
            doc.select('b').first().replaceWith(new org.jsoup.nodes.TextNode('粗'));
            var span = doc.createElement('span'); span.addClass('x'); span.text('后');
            img.after(span);
            doc.body().html()
        """.trimIndent()
        assertEquals(
            "<p><img src=\"a.png\"><span class=\"x\">后</span>粗</p>",
            eval(script, "result" to "<p><img src=\"a.png\" data-x=\"1\" loading=\"lazy\"><b>B</b></p>")?.replace("\n", ""),
        )
    }

    @Test
    fun `Jsoup_connect 经来源的网络出口发：get 解析、PUT 带体、4xx 抛错`() {
        val calls = mutableListOf<String>()
        val http = object : JsHttp {
            override fun fetch(url: String, method: String, body: String?, headers: Map<String, String>, followRedirects: Boolean): StrResponse {
                calls += "$method $url ${body.orEmpty()} ${headers["Authorization"].orEmpty()}".trim()
                return if (url.endsWith("/404")) StrResponse(bodyText = "nope", status = 404, finalUrl = url)
                else StrResponse(bodyText = "<div class=\"t\">页 $method</div>", status = 200, finalUrl = url)
            }
            override fun cookieOf(url: String) = ""
            override fun setCookie(url: String, cookie: String) = Unit
            override fun removeCookie(url: String) = Unit
        }
        val java = JavaBridge(http, JsCache(), mutableMapOf())
        fun js(script: String) = eval(script, "java" to java)
        assertEquals("页 GET", js("org.jsoup.Jsoup.connect('https://a.com/x').get().select('.t').text()"))
        // 轻说机翻：PUT + 请求体 + 头，读 execute().body()
        assertEquals(
            "<div class=\"t\">页 PUT</div>",
            js("Packages.org.jsoup.Jsoup.connect('https://a.com/h').method(Packages.org.jsoup.Connection.Method.PUT).header('Authorization', 'tk').requestBody('12').execute().body()"),
        )
        assertEquals("caught", js("try { org.jsoup.Jsoup.connect('https://a.com/404').get(); 'no' } catch (e) { 'caught' }"))
        assertEquals("nope", js("org.jsoup.Jsoup.connect('https://a.com/404').ignoreHttpErrors(true).execute().body()"))
        assertEquals("GET https://a.com/q?k=%E4%B9%A6", js("org.jsoup.Jsoup.connect('https://a.com/q').data('k', '书').get(); ''").let { calls.last() })
        assertEquals("PUT https://a.com/h 12 tk", calls[1])
    }

    @Test
    fun `真的 Jsoup 对象拿不到：getClass、底下的节点都被沙箱挡住`() {
        assertFails { eval("org.jsoup.Jsoup.parse('<p>x</p>').getClass().getName()") }
        // Kotlin 的 internal 成员在字节码里是公开的（名字带 $模块名）：调得到，但交回来的 Jsoup 节点包不出来
        assertFails { eval("var e = org.jsoup.Jsoup.parse('<p>x</p>').select('p').get(0); e['getNode\$core']().text()") }
        assertFails { eval("org.jsoup.Jsoup.parse('<p>x</p>').select('p').get(0)['getElement\$core']().ownerDocument()") }
        // 没出现 org.jsoup 的脚本不装替身，org 照旧不存在
        assertFails { eval("org.toString()") }
    }
}
