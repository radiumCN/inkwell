package com.radium.inkwell.ui.sourcemanage

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import kotlin.test.Test
import kotlin.test.assertEquals

class CheckMessagesTest {

    @Test
    fun `可用只写耗时，按秒写一位小数`() {
        assertEquals("可用 · 4.8 秒", CheckMessages.ok(4819, shortContent = false))
        assertEquals("可用 · 1.2 秒 · 正文偏短", CheckMessages.ok(1200, shortContent = true))
        // 没测到耗时的就别写「0.0 秒」
        assertEquals("可用", CheckMessages.ok(0, shortContent = false))
    }

    /** 库里存着的旧文案：章数不再写，毫秒按库里的响应耗时改成秒 */
    @Test
    fun `旧的可用文案按新样子显示`() {
        assertEquals("可用 · 4.8 秒", CheckMessages.okLine(4819, "可用 · 69 章 · 4819ms"))
        assertEquals("可用 · 0.3 秒 · 正文偏短", CheckMessages.okLine(320, "可用 · 12 章 · 320ms · 正文偏短（45 字）"))
    }

    @Test
    fun `整源超时写停在哪一步、预算几秒`() {
        val e = runCatching { runBlocking { withTimeout(1_000) { awaitCancellation() } } }.exceptionOrNull()
        check(e is TimeoutCancellationException)
        assertEquals("正文超时（1 秒）", CheckMessages.failure(CheckStep.CONTENT, e))
        assertEquals("超时（60 秒）", CheckMessages.display("Timed out waiting for 60000 ms"))
    }

    @Test
    fun `HTTP 状态码按类说，带上哪一步，不再截在半截网址上`() {
        assertEquals(
            "详情页被拒（HTTP 403）",
            CheckMessages.failure(CheckStep.DETAIL, IOException("HTTP 403: https://www.69shuba.com/modules/article/x")),
        )
        assertEquals("搜索时站点出错（HTTP 502）", CheckMessages.failure(CheckStep.SEARCH, IOException("HTTP 502: https://a.com/s")))
        assertEquals("正文地址失效（HTTP 404）", CheckMessages.failure(CheckStep.CONTENT, IOException("HTTP 404: https://a.com/1")))
        assertEquals("目录被限流（HTTP 429）", CheckMessages.failure(CheckStep.TOC, IOException("HTTP 429: https://a.com/toc")))
        // 旧文案截在 60 字：不知道哪一步就不写
        assertEquals("被拒（HTTP 403）", CheckMessages.display("HTTP 403: https://www.69shuba.com/modules/art"))
    }

    @Test
    fun `网络层的失败归成连不上、连接中断、证书、超时`() {
        assertEquals(
            "连不上站点",
            CheckMessages.failure(CheckStep.SEARCH, UnknownHostException("Unable to resolve host \"www.81zw.us\": No address associated with hostname")),
        )
        assertEquals("连不上站点", CheckMessages.failure(CheckStep.DETAIL, ConnectException("Failed to connect to www.x.com/1.2.3.4:443")))
        // OkHttp 的连接超时：是连不上，不是「超时」
        assertEquals(
            "连不上站点",
            CheckMessages.failure(CheckStep.SEARCH, SocketTimeoutException("failed to connect to www.x.com/1.2.3.4 (port 443) after 10000ms")),
        )
        assertEquals("目录超时", CheckMessages.failure(CheckStep.TOC, SocketTimeoutException("timeout")))
        assertEquals(
            "站点证书有问题",
            CheckMessages.failure(CheckStep.SEARCH, SSLHandshakeException("java.security.cert.CertPathValidatorException: Trust anchor for certification path not found.")),
        )
        // SSL 层报的连接被掐，不是证书
        assertEquals(
            "正文时连接中断",
            CheckMessages.failure(CheckStep.CONTENT, SSLException("Read error: ssl=0x7a: I/O error during system call, Connection reset by peer")),
        )
        // 旧文案只有消息、没有类名，截在 60 字也认得出
        assertEquals("连不上站点", CheckMessages.display("Unable to resolve host \"www.81zw.us\": No addr"))
        assertEquals("站点证书有问题", CheckMessages.display("java.security.cert.CertPathValidatorException: Trust anchor "))
    }

    /** 主机名、网址里碰巧有 ssl / timeout，别被认成证书问题、超时 */
    @Test
    fun `网址里的字不参与归类`() {
        assertEquals(
            "目录出错：目录规则未匹配到章节",
            CheckMessages.failure(CheckStep.TOC, IllegalStateException("目录规则未匹配到章节: https://www.sslxs.com/timeout/1")),
        )
        assertEquals("连不上站点", CheckMessages.failure(CheckStep.SEARCH, UnknownHostException("Unable to resolve host \"www.sslbook.com\"")))
    }

    @Test
    fun `脚本与规则报错算书源规则出错`() {
        assertEquals(
            "来源规则出错（目录）",
            CheckMessages.failure(CheckStep.TOC, IllegalStateException("ReferenceError: \"java\" is not defined. (#3)")),
        )
        assertEquals("来源规则出错", CheckMessages.display("脚本执行超出指令数限制"))
    }

    @Test
    fun `认不出的带上步骤、去掉网址和书源名`() {
        assertEquals(
            "正文出错：正文规则未匹配到内容",
            CheckMessages.failure(CheckStep.CONTENT, IllegalStateException("正文规则未匹配到内容: https://a.com/1.html")),
        )
        assertEquals("目录出错：未配置目录规则", CheckMessages.failure(CheckStep.TOC, IllegalStateException("来源「笔趣阁」未配置目录规则")))
        assertEquals("搜索失败", CheckMessages.failure(CheckStep.SEARCH, IllegalStateException()))
        assertEquals("正文规则未匹配到内容", CheckMessages.display("正文规则未匹配到内容: https://a.com/1.html"))
    }

    /** 本来就是人话的原样留着；新存的文案再显示一遍不能被改写（尤其别把「（HTTP 403）」前面的步骤吃掉） */
    @Test
    fun `显示新文案是原样`() {
        listOf("搜索无结果", "目录为空", "正文为空", "来源规则损坏", "失败").forEach {
            assertEquals(it, CheckMessages.display(it))
        }
        listOf(
            CheckMessages.failure(CheckStep.DETAIL, IOException("HTTP 403: https://a.com")),
            CheckMessages.failure(CheckStep.SEARCH, IOException("HTTP 503: https://a.com")),
            CheckMessages.failure(CheckStep.TOC, IllegalStateException("ReferenceError: x")),
            CheckMessages.failure(CheckStep.CONTENT, SocketTimeoutException("timeout")),
            CheckMessages.failure(CheckStep.CONTENT, IllegalStateException("正文规则未匹配到内容: https://a.com/1")),
        ).forEach { assertEquals(it, CheckMessages.display(it)) }
    }

    /**
     * 界面上不写「书源」二字，统一叫「来源」。0.5.5 及以前存进库的文案还带着它 —— 显示时换掉，
     * 引擎报错里旧写法的「书源「某某」」照样把名字去掉。
     */
    @Test
    fun `库里的旧文案显示时不出现「书源」`() {
        assertEquals("来源规则损坏", CheckMessages.display("书源规则损坏"))
        assertEquals("来源规则出错（目录）", CheckMessages.display("书源规则出错（目录）"))
        assertEquals("未配置目录规则", CheckMessages.display("书源「笔趣阁」未配置目录规则"))
        assertEquals("目录出错：未配置目录规则", CheckMessages.display("目录出错：书源「笔趣阁」未配置目录规则"))
    }
}
