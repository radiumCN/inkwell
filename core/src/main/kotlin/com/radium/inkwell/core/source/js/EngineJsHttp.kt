package com.radium.inkwell.core.source.js

import com.radium.inkwell.core.source.SourceHttpClient
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * 书源脚本的 HTTP 出口。
 *
 * 脚本是同步的（`var html = java.ajax(url)` 立刻要结果），所以这里只能阻塞等待。
 * 调用发生在引擎的抓取协程里，底层 OkHttp 又跑在 IO 线程池上，阻塞的是当前工作线程而非事件循环。
 *
 * **但 runBlocking 是叫不停的**：它起的是自己的事件循环，外层协程被取消（用户翻页、退出阅读页、
 * 换源掐掉上一轮）传不进来，这根线程只能等到请求自己了结。所以「自己了结」必须有期限 ——
 * 底层 [SourceHttpClient] 已经配了 callTimeout 兜住网络侧，这里再加一道 [timeout] 兜住整体
 * （限速等待、重试退避这些发生在 OkHttp 之外的时间它管不到）。
 */
class EngineJsHttp(
    private val http: SourceHttpClient,
    /** 略大于底层 callTimeout(45s) + 两次重试退避，让网络侧的超时先于这道闸生效、错误信息更准 */
    private val timeout: Duration = 60.seconds,
) : JsHttp {

    override fun fetch(
        url: String,
        method: String,
        body: String?,
        headers: Map<String, String>,
    ): String? = runCatching {
        runBlocking {
            withTimeout(timeout) {
                http.fetch(url, method = method, body = body, headers = headers).bodyText
            }
        }
    }.getOrNull()

    override fun cookieOf(url: String): String = http.cookieOf(url)

    override fun setCookie(url: String, cookie: String) = http.setCookie(url, cookie)

    override fun removeCookie(url: String) = http.removeCookie(url)
}
