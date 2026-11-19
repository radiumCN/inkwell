package com.radium.inkwell.util

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeedbackLogSanitizerTest {
    @Test
    fun `反馈日志隐藏地址凭据和邮箱并保留错误堆栈`() {
        val raw = """
            GET https://example.org/chapter?token=private-query HTTP 403
            Authorization: Bearer private-auth
            Cookie=session=private-cookie
            {"access_token":"private-token","password":"private-password"}
            api_key=private-key
            contact user@example.org
            java.io.IOException: request failed
                at com.radium.inkwell.Reader.load(Reader.kt:42)
        """.trimIndent()
        val clean = FeedbackLogSanitizer.sanitize(raw)
        listOf("example.org", "private-query", "private-auth", "private-cookie", "private-token", "private-password", "private-key", "user@").forEach {
            assertFalse(it in clean, "leaked $it")
        }
        assertTrue("HTTP 403" in clean)
        assertTrue("java.io.IOException" in clean)
        assertTrue("Reader.kt:42" in clean)
    }
}
