package com.radium.inkwell.data.repo

import com.radium.inkwell.core.model.ChapterContent
import com.radium.inkwell.core.source.BookSourceEngine
import com.radium.inkwell.core.source.SourceVariables
import com.radium.inkwell.data.db.dao.ChapterDao
import com.radium.inkwell.data.db.entity.BookType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** 缓存哪一段。都从「当前读到的那章」或第一章起算，序号是目录下标 */
enum class OfflineRange(val label: String) {
    NEXT_50("往后 50 章"),
    TO_END("从当前章到最后"),
    WHOLE_BOOK("全本"),
}

/**
 * 一本书的整本缓存进度。[done] 含「本来就缓存好、直接跳过」的章，这样进度条从一开始就反映真实的完成度。
 * 停下来以后仍留在表里（[running] = false），详情页据此说一句结果；再点一次缓存就被新的一轮替换。
 */
data class OfflineDownload(
    val bookId: String,
    val total: Int,
    val done: Int = 0,
    val failed: Int = 0,
    val running: Boolean = true,
    /** 不是跑完、而是中途停下的原因；正常跑完 / 用户取消为 null */
    val stoppedBecause: String? = null,
)

/**
 * 网络书整本 / 成段离线缓存。应用级单例：离开详情页照常跑（同书源校验 SourceChecker 的取舍 ——
 * 不上前台服务与通知，进程在后台被回收就停；回来再点一次，已经缓存的章直接跳过，接着抓剩下的）。
 *
 * 跟阅读页的预取守同样的纪律：
 * - 顺序抓，不并发。几章同时打一个站点只会触发限流，把好书源熬成「加载失败」。
 * - 不开 WebView 渲染（allowJsRender = false）：WebView 只能在主线程跑，后台整本抓会让翻页掉帧。
 *   静态抓不到的章算失败，留给阅读时现抓。
 * - 写之前对 [ChapterContentCache.epoch]：期间换源、删书、同步改源都会清缓存，旧源的正文不能写进去。
 * - 连续失败 [MAX_CONSECUTIVE_FAILURES] 章就停：书源挂了或断网时，别对着几千章挨个超时。
 */
class OfflineDownloader(
    private val bookRepo: BookRepository,
    private val sourceRepo: BookSourceRepository,
    private val chapterDao: ChapterDao,
    private val engine: BookSourceEngine,
    private val cache: ChapterContentCache,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()

    private val _downloads = MutableStateFlow<Map<String, OfflineDownload>>(emptyMap())
    val downloads: StateFlow<Map<String, OfflineDownload>> = _downloads.asStateFlow()

    fun isRunning(bookId: String): Boolean = jobs[bookId]?.isActive == true

    /** 同一本书已经在缓存就不重开 —— 连点两下不该变成两路并发抓同一个站 */
    fun start(bookId: String, range: OfflineRange) {
        if (isRunning(bookId)) return
        val job = scope.launch { download(bookId, range) }
        jobs[bookId] = job
        job.invokeOnCompletion { jobs.remove(bookId, job) }
    }

    fun cancel(bookId: String) {
        jobs[bookId]?.cancel()
    }

    private suspend fun download(bookId: String, range: OfflineRange) {
        val epoch = cache.epoch(bookId)
        val book = bookRepo.getBook(bookId)
        if (book == null || book.type != BookType.NET) return
        val rule = book.sourceId?.let { sourceRepo.getRule(it) }
            ?: return stopEarly(bookId, "书源不存在，先换源")
        val chapters = chapterDao.getByBook(bookId)
        if (chapters.isEmpty()) return stopEarly(bookId, "目录还没加载，先刷新目录")

        val from = if (range == OfflineRange.WHOLE_BOOK) 0 else book.readChapterIndex.coerceIn(0, chapters.lastIndex)
        val to = if (range == OfflineRange.NEXT_50) minOf(from + NEXT_COUNT, chapters.size) else chapters.size
        val targets = chapters.subList(from, to)
        val otherUrls = chapters.mapNotNullTo(HashSet()) { it.url }
        val variables = SourceVariables.decode(book.variable)

        publish(OfflineDownload(bookId, total = targets.size))
        var consecutiveFailures = 0
        var stoppedBecause: String? = null
        try {
            for (chapter in targets) {
                currentCoroutineContext().ensureActive()
                val url = chapter.url
                val ok = when {
                    url == null -> false
                    cache.hasCurrent(bookId, url) -> true
                    else -> try {
                        val remote = engine.getContent(
                            rule, url, otherUrls,
                            chapterVariable = chapter.variable,
                            allowJsRender = false,
                            chapterTitle = chapter.title.orEmpty(),
                            chapterIndex = chapter.index,
                            variables = variables,
                        )
                        if (!cache.writeIfEpoch(bookId, url, ChapterContent(remote.elements), epoch)) {
                            stoppedBecause = "书源换了或缓存被清空，已停止"
                            break
                        }
                        chapterDao.markCachedByUrl(bookId, url, true)
                        true
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        false
                    }
                }
                consecutiveFailures = if (ok) 0 else consecutiveFailures + 1
                _downloads.update { all ->
                    val d = all[bookId] ?: return@update all
                    all + (bookId to d.copy(done = d.done + 1, failed = d.failed + if (ok) 0 else 1))
                }
                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    stoppedBecause = "连续 $MAX_CONSECUTIVE_FAILURES 章没抓到，已停止（网络或书源出了问题）"
                    break
                }
            }
        } finally {
            withContext(NonCancellable) {
                _downloads.update { all ->
                    val d = all[bookId] ?: return@update all
                    all + (bookId to d.copy(running = false, stoppedBecause = stoppedBecause))
                }
            }
        }
    }

    private fun stopEarly(bookId: String, reason: String) {
        publish(OfflineDownload(bookId, total = 0, running = false, stoppedBecause = reason))
    }

    private fun publish(d: OfflineDownload) {
        _downloads.update { it + (d.bookId to d) }
    }

    private companion object {
        const val NEXT_COUNT = 50
        const val MAX_CONSECUTIVE_FAILURES = 10
    }
}
