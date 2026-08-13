package com.radium.inkwell.core.source.js

import com.radium.inkwell.core.source.SourceHttpClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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
 * 换源掐掉上一轮）传不进来，这根线程只能等到请求自己了结 —— 除非把外层 [Job] 绑进来，
 * 外层一取消就 cancel 掉 runBlocking，底层 [SourceHttpClient] 再把 OkHttp Call 掐掉。
 */
class EngineJsHttp(
    private val http: SourceHttpClient,
    /** 略大于底层 callTimeout(45s) + 两次重试退避，让网络侧的超时先于这道闸生效、错误信息更准 */
    private val timeout: Duration = 60.seconds,
) : JsHttp {

    private val parentJob = ThreadLocal<Job?>()

    fun bindParent(job: Job?) {
        if (job == null) parentJob.remove() else parentJob.set(job)
    }

    override fun fetch(
        url: String,
        method: String,
        body: String?,
        headers: Map<String, String>,
    ): String? = runCatching {
        val parent = parentJob.get()
        runBlocking {
            val blockingScope = this
            val watcher = parent?.let { p ->
                launch {
                    p.join()
                    blockingScope.cancel()
                }
            }
            try {
                withTimeout(timeout) {
                    http.fetch(url, method = method, body = body, headers = headers).bodyText
                }
            } finally {
                watcher?.cancel()
            }
        }
    }.getOrNull()

    override fun cookieOf(url: String): String = http.cookieOf(url)

    override fun setCookie(url: String, cookie: String) = http.setCookie(url, cookie)

    override fun removeCookie(url: String) = http.removeCookie(url)
}
