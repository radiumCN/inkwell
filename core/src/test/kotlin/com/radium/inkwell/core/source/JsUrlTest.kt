package com.radium.inkwell.core.source

import com.radium.inkwell.core.source.js.RhinoScriptRuntime
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/**
 * 「地址本身是 JS」这条路径。26 个真实书源里有 10 个这么写，从前全军覆没：
 * 我们把 `@js:…` 整串当字面路径发出去，请求打到 `站点/@js:url=%22https://…`。
 */
class JsUrlTest {

    private val evaluator = RuleEvaluator(scriptRuntime = RhinoScriptRuntime())

    private fun ctx(keyword: String = "武动乾坤", baseUrl: String = "https://www.siluke.com") =
        EvalContext(
            element = null,
            json = null,
            baseUrl = baseUrl,
            vars = mapOf("keyword" to keyword, "page" to "1"),
            js = JsContext(sourceKey = baseUrl),
        )

    @Test
    fun `普通地址不是 JS，原样放行`() {
        assertNull(evaluator.evalUrlJs("/search?q={{keyword}}", ctx()))
    }

    @Test
    fun `@js 地址返回脚本结果，而不是字面路径`() {
        val url = """@js:url="https://m.siluke.com/search.php";result=url;"""
        assertEquals("https://m.siluke.com/search.php", evaluator.evalUrlJs(url, ctx()))
    }

    @Test
    fun `脚本里能拿到 key 与 baseUrl`() {
        val url = """@js:url=baseUrl+"/so/"+key+".html";result=url;"""
        assertEquals(
            "https://www.qidian.com/so/武动乾坤.html",
            evaluator.evalUrlJs(url, ctx(baseUrl = "https://www.qidian.com")),
        )
    }

    @Test
    fun `js 标签形式`() {
        val url = """<js>result="https://x.com/s?k="+key;result;</js>"""
        assertEquals("https://x.com/s?k=武动乾坤", evaluator.evalUrlJs(url, ctx()))
    }

    // ---- ,{options} 的切分 ----

    @Test
    fun `没有选项后缀时返回 null`() {
        assertNull(splitUrlOptions("https://x.com/search?q=abc"))
    }

    @Test
    fun `单引号 JSON 选项能解析出 POST 与 body`() {
        val (bare, opt) = assertNotNull(
            splitUrlOptions("""/search.php,{'body':'k={{keyword}}','method':'POST','charset':'gbk'}""")
        )
        assertEquals("/search.php", bare)
        assertEquals("POST", opt.method)
        assertEquals("k={{keyword}}", opt.body)
        assertEquals("gbk", opt.charset)
    }

    @Test
    fun `headers 也能带出来`() {
        val (_, opt) = assertNotNull(
            splitUrlOptions("""/s,{'headers':{'Referer':'https://m.siluke.com/'}}""")
        )
        assertEquals("https://m.siluke.com/", opt.headers["Referer"])
    }

    /**
     * 这条是真实事故：`,{` 落在 JS 字符串里面。从前见到第一个 `,{` 就切，
     * 把地址拦腰截成 `@js:url="https://m.wcxsw.o`，请求 403。
     */
    @Test
    fun `JS 脚本里的逗号花括号不是选项后缀`() {
        val raw = """@js:url="https://m.wcxsw.org/search.php,{'method':'POST'}";result=url;"""
        // 整串不是合法选项（`{'method':'POST'}";result=url;` 解析不出 JSON），所以不切
        assertNull(splitUrlOptions(raw))
    }

    @Test
    fun `脚本吐出的地址自带选项，运行期才切得动`() {
        val raw = """@js:url="https://m.wcxsw.org/search.php,{'body':'keyword={{key}}','method':'POST'}";result=url;"""
        val resolved = assertNotNull(evaluator.evalUrlJs(raw, ctx()))
        val (bare, opt) = assertNotNull(splitUrlOptions(resolved))
        assertEquals("https://m.wcxsw.org/search.php", bare)
        assertEquals("POST", opt.method)
        assertEquals("keyword={{key}}", opt.body)
    }

    // ---- 原生模型原样保留 JS 搜索地址（不再有转换期切坏的可能）----

    @Test
    fun `原生模型原样保留整条 JS 搜索地址`() {
        val legado = """
            {
              "bookSourceName": "思路客",
              "bookSourceUrl": "https://www.siluke.com",
              "searchUrl": "@js:url=\"https://m.siluke.com/search.php,{'body':'keyword={{key}}','method':'POST'}\";result=url;",
              "ruleSearch": {"bookList": "class.item", "name": "tag.h3@text", "bookUrl": "tag.a@href"},
              "ruleToc": {"chapterList": "class.chapter@tag.a", "chapterName": "text", "chapterUrl": "href"},
              "ruleContent": {"content": "id.content@html"}
            }
        """.trimIndent()
        val url = BookSourceRule.fromJson(legado).searchUrl!!
        // 整串原文入库，一个字都不许被切掉
        assertTrue(url.startsWith("@js:"), "JS 地址被截断了: $url")
        assertTrue(url.endsWith("result=url;"), "JS 地址被截断了: $url")
    }
}
