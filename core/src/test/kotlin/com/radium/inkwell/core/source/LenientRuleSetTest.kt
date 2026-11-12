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
}
