package com.radium.inkwell.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 防盗链重试时带的 Referer：只带网站来源，和浏览器跨站取图一样 */
class ImageRefererTest {

    @Test
    fun `取文章网址的来源`() {
        assertEquals("https://synyan.cn/", siteOrigin("https://synyan.cn/117793/"))
        assertEquals("https://her-cat.com/", siteOrigin("https://her-cat.com/posts/2025/05/31/a/?utm_source=rss#top"))
        assertEquals("http://blog.example.com:8080/", siteOrigin("http://blog.example.com:8080/p/1"))
        assertEquals("https://e.com/", siteOrigin("  HTTPS://e.com/a  "))
    }

    @Test
    fun `不是网址的不给`() {
        assertNull(siteOrigin(null))
        assertNull(siteOrigin(""))
        assertNull(siteOrigin("data:text/html,hi"))
        assertNull(siteOrigin("@js:java.ajax(url)"))
        assertNull(siteOrigin("/relative/path"))
    }
}
