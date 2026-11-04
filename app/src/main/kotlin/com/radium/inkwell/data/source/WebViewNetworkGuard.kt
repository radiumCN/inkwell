package com.radium.inkwell.data.source

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.radium.inkwell.core.source.isPrivateNetworkHost
import com.radium.inkwell.core.source.isPrivateNetworkLiteral
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * WebView 的内网拦截。页面里的脚本、XHR、子框架同样不许碰内网 —— 书源的 SSRF 防护只装在 OkHttp 上，
 * WebView 的请求由 Chromium 自己发，没有这一层就一路畅通：渲染一个公网页，它的脚本就能去读
 * `http://192.168.1.1/`。Android 17 起应用拿到局域网权限（WebDAV 连 NAS 要用）后，WebView 也跟着能连局域网。
 *
 * 阅读器的渲染回退（[WebViewPageRenderer]）和新建书源里给用户看、给用户点的网页共用这一份：
 * 安全相关的拦截只该有一处，改一处漏一处，加载任意网站、开着脚本的那个 WebView 就留在旧行为上。
 *
 * 一个实例管一次渲染 / 一个网页：内网判定按主机缓存，一页几十个子请求多半是同几个域，别每个都解析一次。
 */
internal class WebViewNetworkGuard {

    private val verdicts = ConcurrentHashMap<String, Boolean>()

    /** 子请求：内网的回 403。这个回调跑在 WebView 的 IO 线程上，可以解析 DNS */
    fun intercept(request: WebResourceRequest): WebResourceResponse? =
        if (blocks(request.url)) blockedResponse() else null

    /**
     * 跳转（含主文档的 302）。这里在主线程上，做 DNS 会直接抛异常，只能认字面量：
     * 内网 IP、localhost。跳去的若是域名，新请求随后还会经过 [intercept]。
     */
    fun blocksNavigation(request: WebResourceRequest): Boolean =
        request.url.host?.let { isPrivateNetworkLiteral(it) } ?: false

    private fun blocks(uri: Uri): Boolean {
        // data:/blob:/about: 没有主机，不出网；file:/content: 已在 settings 里关掉
        val host = uri.host ?: return false
        return verdicts.getOrPut(host) { isPrivateNetworkHost(host) }
    }

    /** 每次新建：响应体是流，复用会被上一次读空 */
    private fun blockedResponse() = WebResourceResponse(
        "text/plain", "utf-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)),
    )
}
