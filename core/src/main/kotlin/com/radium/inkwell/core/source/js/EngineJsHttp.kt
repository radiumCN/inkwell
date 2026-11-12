package com.radium.inkwell.core.source.js

import com.radium.inkwell.core.source.SourceHttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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

    companion object {
        /**
         * 每次引擎调用各绑各的（[bindingTo] 随协程迁移），所以几个引擎实例共用一个 ThreadLocal 也不会串。
         * 放在伴生对象上是为了让脚本运行时也读得到：见 [currentJob]。
         */
        private val parentJob = ThreadLocal<Job?>()

        /** 此刻这根线程上跑的那次引擎调用的 Job；脚本运行时靠它响应取消（RhinoScriptRuntime） */
        internal fun currentJob(): Job? = parentJob.get()

        /** `java.ajaxAll` 同时在飞的请求数 */
        private const val AJAX_ALL_PARALLEL = 6
    }

    override fun fetch(
        url: String,
        method: String,
        body: String?,
        headers: Map<String, String>,
        followRedirects: Boolean,
    ): StrResponse? = runCatching {
        blockingUnderParent { request(url, method, body, headers, followRedirects) }
    }.getOrNull()

    /**
     * `java.ajaxAll`：一起发、同一道看门（外层一取消，整批一起掐掉）。同时在飞的封顶 [AJAX_ALL_PARALLEL] 个：
     * 图集正文一次几十页，全放出去就是对着一个站并发几十个请求。单个失败只空那一格，不连累别的
     */
    override fun fetchAll(requests: List<JsHttpRequest>): List<StrResponse?> =
        runCatching {
            blockingUnderParent {
                val gate = Semaphore(AJAX_ALL_PARALLEL)
                requests.map { r ->
                    async {
                        gate.withPermit {
                            try {
                                request(r.url, r.method, r.body, r.headers, followRedirects = true)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                null
                            }
                        }
                    }
                }.awaitAll()
            }
        }.getOrNull() ?: requests.map { null }

    private suspend fun request(
        url: String,
        method: String,
        body: String?,
        headers: Map<String, String>,
        followRedirects: Boolean,
    ): StrResponse {
        // 4xx/5xx 也要交给脚本看 statusCode，不能在这儿抛成 ajax 空串
        val page = http.fetch(
            url, method = method, body = body, headers = headers,
            throwOnHttpError = false,
            followRedirects = followRedirects,
        )
        return StrResponse(
            bodyText = page.bodyText,
            status = page.statusCode,
            finalUrl = page.finalUrl,
            headerMap = page.headers,
            cookieStr = http.cookieOf(page.finalUrl),
        )
    }

    /** 外层已取消返回 null；否则阻塞跑完 [block]（整段最多 [timeout]），外层一取消就掐掉 */
    private fun <T> blockingUnderParent(block: suspend CoroutineScope.() -> T): T? {
        val parent = parentJob.get()
        // 外层已经取消了就别再发：同一段脚本里后面的 java.ajax、目录逐条执行的脚本都会走到这里，
        // 每一个都会实打实跑完一整个请求（最长 [timeout]），用户早就离开了页面
        if (parent?.isCancelled == true) return null
        return runBlocking {
            val blockingScope = this
            // 看门协程**挂在外层 Job 之下**：父一进入 cancelling，子立刻被取消 —— 这才是我们要的
            // 「外层取消」信号。从前写的是 `p.join()`，等的却是父「完成」；而父此刻正被这个
            // runBlocking 堵着、完成不了，于是永远等不到，取消一次也没传进来过。
            //
            // UNDISPATCHED：父若恰在这之前进入 cancelling，普通 launch 出来的子协程**根本不会开跑**，
            // finally 也就不执行 —— 这一次请求照样跑满。立即执行到第一个挂起点，awaitCancellation
            // 当场抛出、finally 当场掐掉 runBlocking。
            val watcher = parent?.let { p ->
                CoroutineScope(p + Dispatchers.Unconfined).launch(start = CoroutineStart.UNDISPATCHED) {
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
                withTimeout(timeout) { block() }
            } finally {
                watcher?.cancel()
            }
        }
    }

    override fun cookieOf(url: String): String = http.cookieOf(url)

    override fun setCookie(url: String, cookie: String) = http.setCookie(url, cookie)

    override fun removeCookie(url: String) = http.removeCookie(url)
}
