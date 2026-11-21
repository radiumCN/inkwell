package com.radium.inkwell.core.source.js

import com.radium.inkwell.core.source.SourceHttpClient
import com.radium.inkwell.core.source.parseHeaderJson
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
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
    fun bindingTo(job: Job?, sourceId: String? = null): CoroutineContext =
        parentJob.asContextElement(job) + currentSource.asContextElement(SourceScope(sourceId))

    internal fun bindingTo(
        job: Job?,
        sourceId: String?,
        loginHeaderNames: () -> Set<String>,
        allowsLoginOrigin: (String) -> Boolean,
        userAgent: () -> String? = { null },
    ): CoroutineContext = parentJob.asContextElement(job) +
        currentSource.asContextElement(SourceScope(sourceId, loginHeaderNames, allowsLoginOrigin, userAgent))

    private data class SourceScope(
        val id: String?,
        val loginHeaderNames: () -> Set<String> = { emptySet() },
        val allowsLoginOrigin: (String) -> Boolean = { true },
        /**
         * 脚本请求没写 User-Agent 时发的：和这个来源的页面请求同一个 UA（见 BookSourceEngine.engineIo）。
         * 同阅读：`java.ajax` 带着来源的请求头发，`java.get` / `post` 走 Jsoup、默认也是桌面 UA。
         * 从前一律落到 SourceHttpClient 的手机 UA，同一个来源，页面是一套、脚本请求是另一套。交回 null = 不管（订阅源）。
         * 是个函数：要跑请求头脚本才算得出来，没有脚本请求的调用就不算
         */
        val userAgent: () -> String? = { null },
    ) {
        val observedLoginHeaderNames: MutableSet<String> = ConcurrentHashMap.newKeySet()
        /** 这一次引擎调用里脚本发出去的请求数，见 [requestCount] */
        val requests = AtomicInteger()
    }

    /**
     * 这一次引擎调用里，脚本到目前为止发出去的请求数（java.ajax、Jsoup 替身、ajaxAll 都算）。
     * 正文抓页失败后放脚本兜底时，引擎拿它判断脚本是不是真自己取了正文（见 BookSourceEngine.collectContent）
     */
    internal fun requestCount(): Int = currentSource.get()?.requests?.get() ?: 0

    // 交互登录脚本可能在会话正式保存前就用新请求头做校验。
    internal fun rememberLoginHeaders(header: String) {
        currentSource.get()?.observedLoginHeaderNames?.addAll(parseHeaderJson(header).keys)
    }

    companion object {
        /**
         * 每次引擎调用各绑各的（[bindingTo] 随协程迁移），所以几个引擎实例共用一个 ThreadLocal 也不会串。
         * 放在伴生对象上是为了让脚本运行时也读得到：见 [currentJob]。
         */
        private val parentJob = ThreadLocal<Job?>()
        private val currentSource = ThreadLocal<SourceScope?>()

        /** 此刻这根线程上跑的那次引擎调用的 Job；脚本运行时靠它响应取消（RhinoScriptRuntime） */
        internal fun currentJob(): Job? = parentJob.get()

        /** `java.ajaxAll` 同时在飞的请求数 */
        private const val AJAX_ALL_PARALLEL = 6
        private val AUTH_HEADER_NAMES = setOf("Authorization", "Proxy-Authorization", "Cookie")
    }

    override fun fetch(
        url: String,
        method: String,
        body: String?,
        headers: Map<String, String>,
        followRedirects: Boolean,
    ): StrResponse? = runCatching {
        val scope = currentSource.get()
        blockingUnderParent { request(url, method, body, headers, followRedirects, scope) }
    }.getOrNull()

    /**
     * `java.ajaxAll`：一起发、同一道看门（外层一取消，整批一起掐掉）。同时在飞的封顶 [AJAX_ALL_PARALLEL] 个：
     * 图集正文一次几十页，全放出去就是对着一个站并发几十个请求。单个失败只空那一格，不连累别的
     */
    override fun fetchAll(requests: List<JsHttpRequest>): List<StrResponse?> =
        runCatching {
            val scope = currentSource.get()
            blockingUnderParent {
                val gate = Semaphore(AJAX_ALL_PARALLEL)
                requests.map { r ->
                    async {
                        gate.withPermit {
                            try {
                                request(r.url, r.method, r.body, r.headers, followRedirects = true, scope = scope)
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
        scope: SourceScope?,
    ): StrResponse {
        val loginNames = if (headers.isEmpty() || scope == null) emptySet()
        else scope.loginHeaderNames() + scope.observedLoginHeaderNames
        val persisted = headers.keys.filterTo(mutableSetOf()) { name ->
            loginNames.any { it.equals(name, ignoreCase = true) }
        }
        val scopedHeaders = if (persisted.isNotEmpty() && scope?.allowsLoginOrigin?.invoke(url) == false) {
            headers - persisted
        } else {
            headers
        }
        val credentialHeaders = scopedHeaders.keys.filterTo(mutableSetOf()) { name ->
            name in persisted || AUTH_HEADER_NAMES.any { it.equals(name, ignoreCase = true) }
        }
        scope?.requests?.incrementAndGet()
        val sent = if (scope == null || scopedHeaders.keys.any { it.equals("User-Agent", ignoreCase = true) }) {
            scopedHeaders
        } else {
            scope.userAgent()?.let { scopedHeaders + ("User-Agent" to it) } ?: scopedHeaders
        }
        // 4xx/5xx 也要交给脚本看 statusCode，不能在这儿抛成 ajax 空串
        val page = http.fetch(
            url, method = method, body = body, headers = sent,
            throwOnHttpError = false,
            followRedirects = followRedirects,
            sourceId = scope?.id,
            credentialHeaders = credentialHeaders,
        )
        return StrResponse(
            bodyText = page.bodyText,
            status = page.statusCode,
            finalUrl = page.finalUrl,
            headerMap = page.headers,
            cookieStr = http.cookieOf(page.finalUrl, scope?.id),
        )
    }

    /** 外层已取消返回 null；否则阻塞跑完 [block]（整段最多 [timeout]），外层一取消就掐掉 */
    internal fun <T> blockingUnderParent(block: suspend CoroutineScope.() -> T): T? {
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

    override fun cookieOf(url: String): String = http.cookieOf(url, currentSource.get()?.id)

    override fun setCookie(url: String, cookie: String) = http.setCookie(url, cookie, currentSource.get()?.id)

    override fun removeCookie(url: String) = http.removeCookie(url, currentSource.get()?.id)
}
