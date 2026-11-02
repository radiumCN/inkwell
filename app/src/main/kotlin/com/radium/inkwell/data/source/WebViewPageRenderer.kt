package com.radium.inkwell.data.source

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.net.toUri
import com.radium.inkwell.core.source.FetchedPage
import com.radium.inkwell.core.source.PageRenderer
import com.radium.inkwell.core.source.isPrivateNetworkHost
import com.radium.inkwell.core.source.isPrivateNetworkLiteral
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONTokener

/**
 * 用 WebView 执行页面 JS 后再取 HTML。
 *
 * WebView 只能在主线程上操作，且一个实例同时只能加载一个页面，故全局串行化（Mutex）。
 * 这也是引擎不在搜索阶段回退到渲染的原因：并发扇出会在这里排成长队。
 */
class WebViewPageRenderer(
    private val context: Context,
    /** 单页渲染总超时；含加载与 JS 执行 */
    private val timeoutMs: Long = 25_000,
) : PageRenderer {

    private val mutex = Mutex()

    override suspend fun render(
        url: String,
        headers: Map<String, String>,
        userAgent: String?,
    ): FetchedPage? {
        // 书源给的地址先过一道内网判定（要解析 DNS，不能在主线程）。拦下来就返回 null：
        // 引擎退回静态抓取，那条路上的 OkHttp 会以「拒绝访问内网地址」报出来
        val host = url.toUri().host ?: return null
        if (withContext(Dispatchers.IO) { isPrivateNetworkHost(host) }) return null
        return renderLocked(url, headers, userAgent)
    }

    private suspend fun renderLocked(
        url: String,
        headers: Map<String, String>,
        userAgent: String?,
    ): FetchedPage? = mutex.withLock {
        withContext(Dispatchers.Main) {
            var view: WebView? = null
            try {
                withTimeout(timeoutMs) {
                    val wv = createWebView(userAgent)
                    view = wv
                    loadPage(wv, url, headers)
                    val html = awaitSettledHtml(wv)
                    FetchedPage(
                        finalUrl = wv.url ?: url,
                        statusCode = 200, // WebView 不暴露状态码；加载失败already走 null 分支
                        bodyText = html,
                        detectedCharset = "utf-8",
                    )
                }
            } catch (e: TimeoutCancellationException) {
                null // 超时 → 调用方沿用静态抓取的结果
            } catch (e: RenderFailed) {
                null
            } finally {
                view?.let {
                    it.stopLoading()
                    it.destroy() // 每次用完即销毁：长期持有的离屏 WebView 容易泄漏 Activity/内存
                }
            }
        }
    }

    private class RenderFailed(message: String) : Exception(message)

    /** 一次渲染内的内网判定，按主机缓存：一页几十个子请求多半是同几个域，别每个都解析一次 */
    private class HostGuard {
        private val verdicts = ConcurrentHashMap<String, Boolean>()

        fun blocks(uri: Uri): Boolean {
            // data:/blob:/about: 没有主机，不出网；file:/content: 已在 settings 里关掉
            val host = uri.host ?: return false
            return verdicts.getOrPut(host) { isPrivateNetworkHost(host) }
        }
    }

    /** 每次新建：响应体是流，复用会被上一次读空 */
    private fun blockedResponse() = WebResourceResponse(
        "text/plain", "utf-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)),
    )

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(userAgent: String?): WebView = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        // 正文只需要 DOM，图片按书源规则里的 URL 另行下载，这里不必真的拉图
        settings.loadsImagesAutomatically = false
        settings.blockNetworkImage = true
        // 第三方页面不该摸到本机文件和其它应用的 content://（allowContentAccess 默认是开的）
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        userAgent?.takeIf { it.isNotBlank() }?.let { settings.userAgentString = it }
    }

    /** 加载页面，等 onPageFinished；主文档加载失败直接放弃 */
    private suspend fun loadPage(wv: WebView, url: String, headers: Map<String, String>) =
        suspendCancellableCoroutine { cont ->
            val guard = HostGuard()
            wv.webViewClient = object : WebViewClient() {
                /**
                 * 页面里的脚本、XHR、子框架同样不许碰内网。书源的 SSRF 防护只装在 OkHttp 上，
                 * WebView 的请求由 Chromium 自己发，从前一路畅通：渲染一个公网页，它的脚本就能去读
                 * `http://192.168.1.1/`，再由规则里的 `java.ajax` 把结果发回书源作者的服务器。
                 * Android 17 起应用拿到局域网权限（WebDAV 连 NAS 要用）后，WebView 也跟着能连局域网。
                 *
                 * 这个回调跑在 WebView 的 IO 线程上，可以解析 DNS。
                 */
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? = if (guard.blocks(request.url)) blockedResponse() else null

                /**
                 * 跳转（含主文档的 302）。这里在主线程上，做 DNS 会直接抛异常，只能认字面量：
                 * 内网 IP、localhost。跳去的若是域名，新请求随后还会经过 [shouldInterceptRequest]。
                 */
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    request.url.host?.let { isPrivateNetworkLiteral(it) } ?: false

                override fun onPageFinished(view: WebView, finishedUrl: String) {
                    if (cont.isActive) cont.resume(Unit)
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError,
                ) {
                    // 只有主文档失败才算失败：子资源（图片/统计脚本）报错很常见，忽略
                    if (request.isForMainFrame && cont.isActive) {
                        cont.cancel(RenderFailed("加载失败: ${error.description}"))
                    }
                }
            }
            // Referer 等源级头随首次请求带上；WebView 不会把它们带到后续 XHR，够用
            wv.loadUrl(url, headers.filterKeys { !it.equals("User-Agent", ignoreCase = true) })
        }

    /**
     * onPageFinished 只代表主文档就绪，正文往往还在 XHR 途中。
     * 轮询 DOM，直到页面连续两次不再变化（视为渲染稳定）或超时。
     *
     * 轮询只取一个**签名**（长度 + 整串哈希，在页面里算好），稳定了再取一次全文。从前每 350ms 把整页
     * outerHTML（几百 KB）经 JSON 字面量传回来，在主线程上 JSONTokener 解一遍、再整串比较，最多 24 次 ——
     * 阅读页翻到要渲染的章节时，翻页动画正好跟它抢主线程。只比长度又不够：定长的占位（补齐的「加载中…」、
     * 倒计时）换成正文前长度可能不变，会被当成已经渲染完。哈希在渲染进程里算，几十万字符一遍是几毫秒。
     */
    private suspend fun awaitSettledHtml(wv: WebView): String {
        var last = ""
        var stable = 0
        repeat(MAX_POLLS) {
            delay(POLL_INTERVAL_MS)
            val signature = readHtmlSignature(wv)
            if (signature.isNotEmpty() && !signature.startsWith("0:") && signature == last) {
                if (++stable >= 2) return readHtml(wv).ifEmpty { throw RenderFailed("渲染后页面为空") }
            } else {
                stable = 0
                last = signature
            }
        }
        return readHtml(wv).ifEmpty { throw RenderFailed("渲染后页面为空") }
    }

    /** 「长度:哈希」；evaluateJavascript 回传的是 JSON 字面量（带引号的短串），解开即可 */
    private suspend fun readHtmlSignature(wv: WebView): String = suspendCancellableCoroutine { cont ->
        wv.evaluateJavascript(SIGNATURE_JS) { value ->
            val signature = runCatching { JSONTokener(value).nextValue() as? String }.getOrNull().orEmpty()
            if (cont.isActive) cont.resume(signature)
        }
    }

    private suspend fun readHtml(wv: WebView): String {
        val literal = suspendCancellableCoroutine<String?> { cont ->
            wv.evaluateJavascript("document.documentElement.outerHTML") { value ->
                if (cont.isActive) cont.resume(value)
            }
        }
        // evaluateJavascript 回传的是 JSON 字面量，需解成原始字符串。几百 KB 的串，解码放到后台
        return withContext(Dispatchers.Default) {
            runCatching { JSONTokener(literal).nextValue() as? String }.getOrNull().orEmpty()
        }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 350L
        /** 页面签名：长度 + 整串的 31 进制滚动哈希（|0 截成 32 位整数） */
        const val SIGNATURE_JS = "(function(){var s=document.documentElement.outerHTML,h=0;" +
            "for(var i=0;i<s.length;i++){h=(h*31+s.charCodeAt(i))|0}return s.length+':'+h})()"
        const val MAX_POLLS = 24 // 与 timeoutMs 共同兜底；连续两次稳定就提前返回
    }
}
