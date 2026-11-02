package com.radium.inkwell.data.repo

import com.radium.inkwell.core.webdav.WebDavClient
import com.radium.inkwell.util.LocalNetworkPermissionMissing
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SyncErrorsTest {

    @Test
    fun `退到后台被系统掐断连接（截图里那一条）说成「被中断、会再试」`() {
        val msg = describeSyncError(SocketException("Software caused connection abort"))
        assertTrue(msg.startsWith("同步被中断"), msg)
        assertTrue("Software" !in msg)
    }

    @Test
    fun `常见网络错误各有一句中文`() {
        assertTrue(describeSyncError(UnknownHostException("Unable to resolve host \"dav.example.com\"")).startsWith("找不到服务器"))
        assertTrue(describeSyncError(ConnectException("Failed to connect")).startsWith("连不上服务器"))
        assertTrue(describeSyncError(SocketTimeoutException("timeout")).startsWith("连接超时"))
    }

    @Test
    fun `HTTP 状态码翻成人话`() {
        assertTrue(describeSyncError(WebDavClient.WebDavException(401, "PROPFIND 失败: 401")).startsWith("账号或密码不对"))
        assertTrue(describeSyncError(WebDavClient.WebDavException(507, "PUT 失败: 507")).startsWith("服务器空间已满"))
        assertTrue(describeSyncError(WebDavClient.WebDavException(503, "GET 失败: 503")).startsWith("服务器正忙"))
        assertEquals("服务器出错了（HTTP 502），稍后再试", describeSyncError(WebDavClient.WebDavException(502, "GET 失败: 502")))
    }

    @Test
    fun `证书错误裹在 SSL 异常里也认得出`() {
        val e = SSLHandshakeException("Handshake failed").apply { initCause(CertificateException("Trust anchor not found")) }
        assertTrue(describeSyncError(e).startsWith("服务器的证书不受信任"))
    }

    @Test
    fun `原因被包了一层时往里找`() {
        val e = IOException("unexpected", UnknownHostException("dav.example.com"))
        assertTrue(describeSyncError(e).startsWith("找不到服务器"))
    }

    @Test
    fun `本应用自己的中文报错原样保留`() {
        assertEquals("尚未配置 WebDAV", describeSyncError(IllegalStateException("尚未配置 WebDAV")))
        assertEquals(LocalNetworkPermissionMissing().message, describeSyncError(LocalNetworkPermissionMissing()))
    }

    @Test
    fun `认不出的保留原文，反馈时有东西可查`() {
        assertEquals("出了点意外（boom）", describeSyncError(IllegalArgumentException("boom")))
        assertEquals("出了点意外（IllegalStateException）", describeSyncError(IllegalStateException()))
    }
}
