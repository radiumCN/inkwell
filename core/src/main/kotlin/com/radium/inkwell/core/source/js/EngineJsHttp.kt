package com.radium.inkwell.core.source.js

import com.radium.inkwell.core.source.SourceHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.CoroutineContext
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

    /**
     * 把 [job] 绑成协程上下文元素，随协程一起迁移线程。
     *
     * 从前是裸 `ThreadLocal.set/remove`：绑定发生在进入 IO 线程池的那一根线程上，可协程在
     * 第一次挂起（抓取网页）之后恢复到的往往是**另一根**线程 —— 脚本求值几乎总在抓取之后，
     * 于是读到的是恢复线程上的值：多半是 null（取消传不进来，注释里的承诺落空）；更坏的是
     * 前一个协程残留的、**已完成**的 Job —— `join()` 立刻返回 → 掐掉 runBlocking → `java.ajax()`
     * 瞬间回空串，书源就「时好时坏」，还复现不出来。
     * [asContextElement] 在每次恢复时重设、挂起时还原，线程怎么跳都拿到自己的 Job。
     */
    fun bindingTo(job: Job?): CoroutineContext = parentJob.asContextElement(job)

    override fun fetch(
        url: String,
        method: String,
        body: String?,
        headers: Map<String, String>,
    ): StrResponse? = runCatching {
        val parent = parentJob.get()
        runBlocking {
            val blockingScope = this
            // 看门协程**挂在外层 Job 之下**：父一进入 cancelling，子立刻被取消 —— 这才是我们要的
            // 「外层取消」信号。从前写的是 `p.join()`，等的却是父「完成」；而父此刻正被这个
            // runBlocking 堵着、完成不了，于是永远等不到，取消一次也没传进来过。
            val watcher = parent?.let { p ->
                CoroutineScope(p + Dispatchers.Unconfined).launch {
                    try {
                        awaitCancellation()
                    } finally {
                        // 只在父真被取消时掐 runBlocking；请求正常结束时是我们自己在下面 cancel 的看门，
                        // 那时父还活着，不该把已经算好的结果也掐掉
                        if (p.isCancelled) blockingScope.cancel()
                    }
                }
            }
            try {
                withTimeout(timeout) {
                    // 4xx/5xx 也要交给脚本看 statusCode，不能在这儿抛成 ajax 空串
                    val page = http.fetch(
                        url, method = method, body = body, headers = headers,
                        throwOnHttpError = false,
                    )
                    StrResponse(
                        bodyText = page.bodyText,
                        status = page.statusCode,
                        finalUrl = page.finalUrl,
                        headerMap = page.headers,
                        cookieStr = http.cookieOf(page.finalUrl),
                    )
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
