package com.radium.inkwell.ui.sourcewizard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WizardUrlTest {

    @Test
    fun `plain url passes through`() {
        assertEquals("https://www.example.com/book/1/", normalizeBookUrl("  https://www.example.com/book/1/ "))
    }

    @Test
    fun `missing scheme gets https`() {
        assertEquals("https://www.example.com/book/1/", normalizeBookUrl("www.example.com/book/1/"))
    }

    @Test
    fun `url is dug out of shared text`() {
        // 浏览器「分享」出来的：书名在前、网址后面紧跟中文标点
        assertEquals(
            "https://m.example.com/book/88/",
            normalizeBookUrl("《手机之书》最新章节 https://m.example.com/book/88/，快来看"),
        )
    }

    @Test
    fun `things that are not urls are rejected`() {
        assertNull(normalizeBookUrl(""))
        assertNull(normalizeBookUrl("手机之书"))
        assertNull(normalizeBookUrl("localhost/book"))
        assertNull(normalizeBookUrl("两个 词"))
    }
}
