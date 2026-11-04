package com.radium.inkwell.ui.sourcewizard

import com.radium.inkwell.core.source.detect.FormField
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SearchAttemptsTest {

    /**
     * 站点脚本拦下表单提交、自己拼地址跳过去：表单照样被记下，可它的 action 只是当前页。
     * 从前只按表单生成，搜不到就卡死 —— 「重新搜一次」记到的还是同一个表单。地址里明明带着关键词。
     */
    @Test
    fun `falls back to the url when the form action is just the current page`() {
        val form = CapturedForm("https://www.example.com/", "get", "UTF-8", listOf(FormField("searchkey", "剑", "text")))
        val attempts = searchAttempts(form, "https://www.example.com/search/%E5%89%91/")
        assertEquals("https://www.example.com/?searchkey={{key}}" to "剑", attempts.first())
        assertTrue("https://www.example.com/search/{{key}}/" to "剑" in attempts)
    }

    /** 没有表单（脚本拼地址跳过去的站）：只能在地址里找建议用户搜的那个词 */
    @Test
    fun `without a form only the url is tried`() {
        assertEquals(
            listOf("https://www.example.com/s?q={{key}}" to "剑"),
            searchAttempts(null, "https://www.example.com/s?q=%E5%89%91"),
        )
        assertTrue(searchAttempts(null, "https://www.example.com/").isEmpty())
    }
}
