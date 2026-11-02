package com.radium.inkwell.ui.sourcemanage

import com.radium.inkwell.core.model.ContentElement
import com.radium.inkwell.core.source.BookSourceEngine
import com.radium.inkwell.data.repo.BookSourceRepository
import com.radium.inkwell.data.repo.NetBookRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger

/**
 * 书源校验，挂在**应用级**作用域上（DI 单例），不随书源管理页的 ViewModel 走。
 *
 * 从前校验跑在 viewModelScope 里：几千个源、每个几秒、6 路并发，一轮要一小时往上，
 * 中途退出书源管理页（NavEntry 出栈、ViewModel 清掉）校验就被取消，已经跑完的一半白跑。
 * 现在离开页面照跑，回来接着看进度、照常能停；结果本来就逐条落库，页面不在也不丢。
 *
 * 只活在进程里：App 被系统回收（长时间息屏、内存紧张）校验仍会中断 —— 要扛住那种情况得上
 * WorkManager + 前台通知，代价是新依赖、前台服务类型与通知权限，目前不值。
 * 选项也放在这里：一次会话里改过的关键词、超时，下次进页还在。
 */
class SourceChecker(
    private val sourceRepo: BookSourceRepository,
    private val netBookRepo: NetBookRepository,
    private val engine: BookSourceEngine,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _progress = MutableStateFlow<CheckProgress?>(null)
    /** 校验进度；null = 没在校验 */
    val progress: StateFlow<CheckProgress?> = _progress.asStateFlow()

    private val _options = MutableStateFlow(CheckOptions())
    val options: StateFlow<CheckOptions> = _options.asStateFlow()

    fun setOptions(o: CheckOptions) {
        _options.value = o
    }

    private val _finished = MutableSharedFlow<String>(extraBufferCapacity = 1)
    /** 一轮跑完的那句总结。页面不在时没人收就算了 —— 结果已经落库，进页一看列表就知道 */
    val finished: SharedFlow<String> = _finished

    /** 当前这一轮。只有它能写 [_progress]：停了再立刻重开时，旧那轮还在收尾，别让它把新一轮的进度清掉 */
    @Volatile
    private var job: Job? = null

    fun cancel() {
        job?.cancel()
        _progress.value = null
    }

    /**
     * 校验书源：跑真实的 搜索 → 详情 → 目录 → 正文 冒烟。
     *
     * 只校验搜索是不够的 —— 「搜得到但读不了」的书源一样是废的，那正是最常见的坏法。
     * 结果**落库**：几百个源校验一轮好几分钟，只存内存的话退出页面就没了，
     * 用户根本没机会拿它做后续处理（筛选失效、按响应速度排序、批量禁用）。
     *
     * @return false = 上一轮还在跑，这次没开
     */
    @Synchronized
    fun start(targets: List<String>): Boolean {
        if (job?.isActive == true) return false
        if (targets.isEmpty()) return true
        val opts = _options.value
        _progress.value = CheckProgress(0, targets.size)
        // LAZY：先把 job 记好再开跑，协程里拿它跟自己比才有意义
        val run = scope.launch(start = CoroutineStart.LAZY) {
            val self = coroutineContext[Job]
            fun publish(p: CheckProgress?) {
                if (job === self) _progress.value = p
            }
            // 后台作用域是多线程的：计数用原子量（从前跑在主线程上，++ 才不出错）
            val done = AtomicInteger(0)
            val bad = AtomicInteger(0)
            val limiter = Semaphore(CHECK_CONCURRENCY)
            try {
                coroutineScope {
                    targets.map { id ->
                        async {
                            limiter.withPermit {
                                val check = checkOne(id, opts)
                                sourceRepo.saveCheck(id, check.ok, check.message, check.respondMs)
                                if (!check.ok) bad.incrementAndGet()
                                publish(CheckProgress(done.incrementAndGet(), targets.size))
                            }
                        }
                    }.awaitAll()
                }
                // 按这一轮的实际结果计数：从前数的是列表流里的 checkStatus，最后几条 saveCheck
                // 还没回流到 sources 时就少算了失效数
                _finished.tryEmit("校验完成：可用 ${targets.size - bad.get()}，失效 ${bad.get()}")
            } finally {
                publish(null)
            }
        }
        job = run
        run.start()
        return true
    }

    /**
     * 校验一个书源。两处刻意放宽，从前它们会把好书源判死：
     * 1. 只探第一章。可第一章常常是「作品相关」「防盗公告」这类没有正文的东西 ——
     *    正文抓不到不是书源坏了。所以再探一章中间的，两章都空才判失败。
     * 2. 要求正文 ≥100 字，否则失败。短章节（序章、过渡章）本来就不到 100 字。
     *    现在只要**非空**就算通过，偏短则照常可用、附带提示。
     */
    private suspend fun checkOne(id: String, opts: CheckOptions): SourceCheck {
        val rule = sourceRepo.getRule(id) ?: return SourceCheck(false, "书源规则损坏")
        if (rule.search == null) return SourceCheck(true, "仅发现页（不校验搜索）")
        val startedAt = System.currentTimeMillis()
        return runCatching {
            withTimeout(opts.timeoutMs) {
                val hit = engine.search(rule, opts.keyword).items.firstOrNull()
                    ?: error("搜索无结果")
                if (!opts.checkDetail && !opts.checkToc && !opts.checkContent) {
                    return@withTimeout ok(toc = 0, chars = -1, startedAt)
                }

                val (detail, toc) = netBookRepo.fetchDetailAndToc(rule, hit.bookUrl)
                if (!opts.checkToc && !opts.checkContent) {
                    return@withTimeout ok(toc.size, chars = -1, startedAt)
                }
                check(toc.isNotEmpty()) { "目录为空" }
                if (!opts.checkContent) return@withTimeout ok(toc.size, chars = -1, startedAt)

                val urls = toc.mapTo(HashSet()) { it.url }
                val probes = listOfNotNull(
                    toc.first(),
                    toc.getOrNull(toc.size / 2).takeIf { toc.size > 1 },
                )
                var best = 0
                var lastError: String? = null
                for (chapter in probes) {
                    val chars = runCatching {
                        engine.getContent(
                            rule, chapter.url, urls, chapter.variable,
                            chapterTitle = chapter.title, chapterIndex = chapter.index,
                            variables = detail.variables,
                        )
                            .elements
                            .filterIsInstance<ContentElement.Paragraph>()
                            .sumOf { it.text.length }
                    }.getOrElse {
                        // 取消（含外层 withTimeout 到点）必须放出去给外层那段判定：吞在这里的话，
                        // 超时的源会被写成「正文为空」而不是超时，用户取消校验也会被记成失效。
                        if (it is CancellationException) throw it
                        lastError = it.message
                        0
                    }
                    if (chars > best) best = chars
                    if (best >= SHORT_CONTENT_CHARS) break
                }
                check(best > 0) { lastError ?: "正文为空" }
                ok(toc.size, best, startedAt)
            }
        }.getOrElse {
            // 用户点「取消校验」时，在飞的这些源抛的是 CancellationException。把它当成判定结果
            // 记下来的话，一次取消就能把几十个好书源写成"失效"，而校验一轮要好几分钟、
            // 结果又是落库的 —— 用户得重跑一遍才能洗掉。取消不是判定结果，原样抛出去。
            // 但 withTimeout 的 TimeoutCancellationException 是判定结果（这个源太慢），要留下。
            if (it is CancellationException && it !is TimeoutCancellationException) throw it
            SourceCheck(false, it.message?.take(60) ?: "失败", System.currentTimeMillis() - startedAt)
        }
    }

    private fun ok(toc: Int, chars: Int, startedAt: Long): SourceCheck {
        val ms = System.currentTimeMillis() - startedAt
        val parts = buildList {
            add("可用")
            if (toc > 0) add("$toc 章")
            add("${ms}ms")
            if (chars in 1 until SHORT_CONTENT_CHARS) add("正文偏短（$chars 字）")
        }
        return SourceCheck(true, parts.joinToString(" · "), ms)
    }

    private companion object {
        /** 并发上限。几百个源同时打网站只会集体被限流，反而校验出一堆假的"失效" */
        const val CHECK_CONCURRENCY = 6
        /** 低于此字数只提示"正文偏短"，不判失败 —— 序章、过渡章本来就短 */
        const val SHORT_CONTENT_CHARS = 100
    }
}
