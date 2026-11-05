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
        _progress.value = CheckProgress(0, targets.size, pending = targets.toSet())
        // LAZY：先把 job 记好再开跑，协程里拿它跟自己比才有意义
        val run = scope.launch(start = CoroutineStart.LAZY) {
            val self = coroutineContext[Job]
            // 计数和两张名单（正在跑的、还没出结果的）一起改、一起发：后台作用域是多线程的，
            // 分开的几个原子量拼不出一致的一帧（页面上会出现「done 涨了、那一行还在转圈」）
            val lock = Any()
            val running = HashSet<String>()
            val pending = HashSet(targets)
            var done = 0
            var bad = 0
            fun publishLocked() {
                if (job === self) {
                    _progress.value = CheckProgress(done, targets.size, bad, running.toSet(), pending.toSet())
                }
            }
            val limiter = Semaphore(CHECK_CONCURRENCY)
            try {
                coroutineScope {
                    targets.map { id ->
                        async {
                            limiter.withPermit {
                                synchronized(lock) {
                                    running += id
                                    publishLocked()
                                }
                                val check = checkOne(id, opts)
                                sourceRepo.saveCheck(id, check.ok, check.message, check.respondMs)
                                synchronized(lock) {
                                    running -= id
                                    pending -= id
                                    done++
                                    if (!check.ok) bad++
                                    publishLocked()
                                }
                            }
                        }
                    }.awaitAll()
                }
                // 按这一轮的实际结果计数：从前数的是列表流里的 checkStatus，最后几条 saveCheck
                // 还没回流到 sources 时就少算了失效数
                val failed = synchronized(lock) { bad }
                _finished.tryEmit("校验完成：可用 ${targets.size - failed}，失效 $failed")
            } finally {
                if (job === self) _progress.value = null
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
        if (rule.search == null) {
            // 「新建书源」生成的源连发现页也没有：说「仅发现页」就是错的，用户会去发现页里找它
            val what = if (rule.explore.isEmpty()) "无搜索规则，按网址加书" else "仅发现页"
            // 耗时记 -1（没测）而不是 0：从前的 0 让「按响应速度」把这些搜不了的源排在了最前面
            return SourceCheck(true, "$what${CheckMessages.SEARCH_SKIPPED}", respondMs = -1)
        }
        val startedAt = System.currentTimeMillis()
        // 走到哪一步了：失败原因前面写上它（「详情页被拒」「正文超时」）。超时是外层 withTimeout 掐断的，
        // 掐断时停在哪一步也只有靠它知道
        var step = CheckStep.SEARCH
        return runCatching {
            withTimeout(opts.timeoutMs) {
                val hit = engine.search(rule, opts.keyword).items.firstOrNull()
                    ?: throw CheckFailed("搜索无结果")
                if (!opts.checkDetail && !opts.checkToc && !opts.checkContent) {
                    return@withTimeout ok(chars = -1, startedAt)
                }

                step = CheckStep.DETAIL
                val (detail, toc) = netBookRepo.fetchDetailAndToc(rule, hit.bookUrl) { step = CheckStep.TOC }
                if (!opts.checkToc && !opts.checkContent) return@withTimeout ok(chars = -1, startedAt)
                if (toc.isEmpty()) throw CheckFailed("目录为空")
                if (!opts.checkContent) return@withTimeout ok(chars = -1, startedAt)

                step = CheckStep.CONTENT
                val urls = toc.mapTo(HashSet()) { it.url }
                val probes = listOfNotNull(
                    toc.first(),
                    toc.getOrNull(toc.size / 2).takeIf { toc.size > 1 },
                )
                var best = 0
                var lastError: Throwable? = null
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
                        lastError = it
                        0
                    }
                    if (chars > best) best = chars
                    if (best >= SHORT_CONTENT_CHARS) break
                }
                // 两章都抓不到：有报错就拿报错归类（「正文被拒」「正文超时」），没报错才是真的空
                if (best == 0) throw (lastError ?: CheckFailed("正文为空"))
                ok(best, startedAt)
            }
        }.getOrElse {
            // 用户点「取消校验」时，在飞的这些源抛的是 CancellationException。把它当成判定结果
            // 记下来的话，一次取消就能把几十个好书源写成"失效"，而校验一轮要好几分钟、
            // 结果又是落库的 —— 用户得重跑一遍才能洗掉。取消不是判定结果，原样抛出去。
            // 但 withTimeout 的 TimeoutCancellationException 是判定结果（这个源太慢），要留下。
            if (it is CancellationException && it !is TimeoutCancellationException) throw it
            // 从前存的是异常原文截 60 字（「Timed out waiting for 60000 ms」「HTTP 403: https://…」），
            // 现在归类说人话、带上哪一步，见 CheckMessages
            val message = if (it is CheckFailed) it.message.orEmpty() else CheckMessages.failure(step, it)
            SourceCheck(false, message, System.currentTimeMillis() - startedAt)
        }
    }

    /**
     * 章数不再记：那是拿关键词随手搜到的第一本书的章数，跟书源好坏无关。
     * [chars] 是探到的正文字数，-1 = 没校验正文。
     */
    private fun ok(chars: Int, startedAt: Long): SourceCheck {
        val ms = System.currentTimeMillis() - startedAt
        return SourceCheck(true, CheckMessages.ok(ms, shortContent = chars in 1 until SHORT_CONTENT_CHARS), ms)
    }

    /** 校验自己下的结论（搜索无结果、目录为空、正文为空）：原样当失败原因，不再归类、不加步骤 */
    private class CheckFailed(message: String) : Exception(message)

    private companion object {
        /** 并发上限。几百个源同时打网站只会集体被限流，反而校验出一堆假的"失效" */
        const val CHECK_CONCURRENCY = 6
        /** 低于此字数只提示"正文偏短"，不判失败 —— 序章、过渡章本来就短 */
        const val SHORT_CONTENT_CHARS = 100
    }
}
