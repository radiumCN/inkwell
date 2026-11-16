package com.radium.inkwell.core.source

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 规则集读得宽（同阅读导入）：没用到的发现规则写成 `[]`，从前整个来源解析失败、导入时被跳过 ——
 * UU看书、肉文小说这几个就是这么没的，搜索、目录、正文明明都好好的。
 */
class LenientRuleSetTest {

    @Test
    fun `规则集写成数组、数字时当没写，别的照常`() {
        val rule = BookSourceRule.fromJson(
            """{"bookSourceUrl":"https://a.com","bookSourceName":"UU看书","searchUrl":"/s?q={{key}}",
               "ruleSearch":{"bookList":".list li","name":"a@text"},
               "ruleExplore":[],"ruleBookInfo":3,"ruleContent":"不是 JSON",
               "ruleToc":{"chapterList":"#list a"}}""",
        )
        assertEquals(".list li", rule.ruleSearch?.bookList)
        assertNull(rule.ruleExplore)
        assertNull(rule.ruleBookInfo)
        assertNull(rule.ruleContent)
        assertEquals("#list a", rule.ruleToc?.chapterList)
    }

    @Test
    fun `规则集存成 JSON 字符串的解开再读，写出去是对象`() {
        val rule = BookSourceRule.fromJson(
            """{"bookSourceUrl":"https://a.com","ruleToc":"{\"chapterList\":\"li\",\"chapterName\":\"a@text\"}"}""",
        )
        assertEquals("li", rule.ruleToc?.chapterList)
        assertEquals("a@text", rule.ruleToc?.chapterName)
        val out = rule.toJson()
        assertTrue(out.contains(""""ruleToc":{"chapterList":"li""""), out)
        // 来回一趟不变
        assertEquals(rule, BookSourceRule.fromJson(out))
    }

    @Test
    fun `规则集为 null、没写时照旧为空`() {
        val rule = BookSourceRule.fromJson("""{"bookSourceUrl":"https://a.com","ruleSearch":null}""")
        assertNull(rule.ruleSearch)
        assertNull(rule.ruleToc)
        assertTrue(!rule.toJson().contains("ruleSearch"))
    }

    /** 合集里 header 直接写成 JSON 对象（思兔小说）：照收，按 JSON 原文存；登录脚本、登录界面一并留着给脚本读 */
    @Test
    fun `header 写成对象也能导入，登录脚本和界面留着`() {
        val rule = BookSourceRule.fromJson(
            """{"bookSourceUrl":"https://a.com","header":{"User-Agent":"UA","Referer":"https://a.com/"},
               "loginUrl":"function login(){}","loginUi":[{"name":"账号","type":"text"}]}""",
        )
        assertEquals(mapOf("User-Agent" to "UA", "Referer" to "https://a.com/"), rule.headers)
        assertEquals("function login(){}", rule.loginUrl)
        assertEquals("""[{"name":"账号","type":"text"}]""", rule.loginUi)
        // 写出去是字符串（阅读的格式），再读回来不变
        assertEquals(rule, BookSourceRule.fromJson(rule.toJson()))
    }
}
