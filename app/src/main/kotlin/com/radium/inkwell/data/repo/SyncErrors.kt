package com.radium.inkwell.data.repo

import com.radium.inkwell.core.webdav.WebDavClient
import com.radium.inkwell.util.LocalNetworkPermissionMissing
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException

/**
 * 同步 / 测试连接的失败原因，换成用户看得懂的一句话。调用方自己加「同步失败：」「连接失败：」这类前缀。
 *
 * 从前直接把异常原文摆出来：`Software caused connection abort`、`Unable to resolve host "dav.example.com"`、
 * `PUT 失败: 507` —— 前两句是英文的系统报错，后一句只有懂 HTTP 的人知道是「空间满了」。
 * 用户看不懂就不知道该做什么：是自己网不好、密码错了，还是服务器的事。
 *
 * 顺着 cause 链往里找：OkHttp 常把真正的原因包一层（证书错误裹在 SSLException 里）。
 * 认不出的才回落成原文，原文仍留在括号里，反馈问题时有东西可查。
 */
fun describeSyncError(e: Throwable): String {
    var cur: Throwable? = e
    var depth = 0
    while (cur != null && depth < MAX_CAUSE_DEPTH) {
        describeOne(cur)?.let { return it }
        cur = cur.cause.takeIf { it !== cur }
        depth++
    }
    val raw = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
    return "出了点意外（$raw）"
}

private fun describeOne(e: Throwable): String? = when (e) {
    // 本应用自己抛的，本来就是中文
    is LocalNetworkPermissionMissing -> e.message
    is WebDavClient.WebDavException -> describeHttp(e.code)
    is UnknownHostException -> "找不到服务器：网络没连上，或者地址写错了"
    is NoRouteToHostException, is ConnectException -> "连不上服务器：网络不通，或者服务器没有开"
    // SocketTimeoutException 是 InterruptedIOException 的子类；后者在 OkHttp 里也表示整次调用超时
    is SocketTimeoutException, is InterruptedIOException -> "连接超时：网络太慢，或者服务器没有响应"
    is CertificateException -> UNTRUSTED_CERT
    // 证书不受信任时外层是 SSLHandshakeException、证书错误在 cause 里：外层先被认出来的话，
    // 用户只会看到笼统的「安全连接失败」，不知道是证书的事
    is SSLException -> if (e.hasCause<CertificateException>()) UNTRUSTED_CERT
    else "安全连接没能建立：检查地址是否写对，以及该用 https 还是 http"
    // 连接被本机系统掐断（ECONNABORTED）、被对面重置、管道断了：多半是 App 退到后台被断网，或网络刚好在切换
    is SocketException -> "同步被中断：App 退到后台时系统切断了网络，或者网络刚好在切换。下次同步时会再试"
    else -> e.message?.takeIf { it.containsCjk() }
}

private fun describeHttp(code: Int): String = when (code) {
    401 -> "账号或密码不对，或者登录已经失效"
    403 -> "服务器拒绝访问：这个账号没有权限读写这个目录"
    404 -> "服务器上找不到这个地址：检查一下 WebDAV 地址"
    409 -> "服务器上的目录不存在：检查一下 WebDAV 地址"
    413 -> "备份太大，超过了服务器允许的单个文件大小"
    423 -> "服务器上的备份文件被锁住了，稍后再试"
    // 坚果云这类服务对请求频率有限制，超了回 429 或 503
    429, 503 -> "服务器正忙（请求太频繁），稍后再试"
    507 -> "服务器空间已满，清理一下再同步"
    in 500..599 -> "服务器出错了（HTTP $code），稍后再试"
    else -> "服务器返回了错误（HTTP $code）"
}

private fun String.containsCjk(): Boolean = any { it in '一'..'鿿' }

private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean {
    var c = cause
    var depth = 0
    while (c != null && depth < MAX_CAUSE_DEPTH) {
        if (c is T) return true
        c = c.cause
        depth++
    }
    return false
}

private const val UNTRUSTED_CERT = "服务器的证书不受信任：检查地址是否写对，自建服务器要配好 HTTPS 证书"
private const val MAX_CAUSE_DEPTH = 5
