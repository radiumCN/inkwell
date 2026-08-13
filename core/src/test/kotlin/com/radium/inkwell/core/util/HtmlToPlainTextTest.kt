package com.radium.inkwell.core.util

import kotlin.test.Test
import kotlin.test.assertEquals

class HtmlToPlainTextTest {

    @Test
    fun `plain intro is left alone`() {
        val plain = "诺难是个社畜，一觉醒来成了宇智波。"
        assertEquals(plain, plain.htmlToPlainText())
    }

    @Test
    fun `comparison with less-than is not treated as a tag`() {
        val plain = "HP < 50 时会触发回血"
        assertEquals(plain, plain.htmlToPlainText())
    }

    @Test
    fun `paragraph tags become line breaks and entities decode`() {
        val html = "&nbsp;<div class=\"T-L-T-C-Box1\"><p>第一段</p><p>第二段</p></div>"
        assertEquals("第一段\n第二段", html.htmlToPlainText())
    }

    @Test
    fun `br is a single line break`() {
        assertEquals("上\n下", "上<br/>下".htmlToPlainText())
    }

    @Test
    fun `already-plain string with ampersand stays`() {
        assertEquals("A&B 工作室", "A&B 工作室".htmlToPlainText())
    }
}
