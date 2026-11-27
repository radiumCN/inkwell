package com.radium.inkwell.data.repo

import com.radium.inkwell.core.model.ChapterContent
import com.radium.inkwell.core.model.ContentElement
import com.radium.inkwell.core.source.BookSourceEngine
import com.radium.inkwell.core.source.SourceVariables
import com.radium.inkwell.data.db.dao.ChapterDao
import com.radium.inkwell.data.db.entity.BookType
import com.radium.inkwell.data.db.entity.ChapterEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/** 缓存哪一段。都从「当前读到的那章」或第一章起算，序号是目录下标 */
enum class OfflineRange(val label: String) {
    NEXT_50("往后 50 章"),
    TO_END("从当前章到最后"),
    WHOLE_BOOK("全本"),
}

/**
 * [done] 是已缓存完成的章节数。普通任务包含插图，[textOnly] 任务只要求正文就绪。
 */
data class OfflineDownload(
    val bookId: String,
    val total: Int,
    val done: Int = 0,
    val failed: Int = 0,
    val imageFailures: Int = 0,
    val running: Boolean = true,
    val canResume: Boolean = false,
    val stoppedBecause: String? = null,
    val textOnly: Boolean = false,
)

@Serializable
internal data class OfflinePlan(val sourceId: String, val bookUrl: String?, val urls: List<String>) {
    fun currentTargets(chapterUrls: Set<String>): OfflinePlan = copy(urls = urls.filter(chapterUrls::contains))
}

/**
 * 应用级缓存任务。目标 URL 保存在缓存目录中，重启后可继续原范围；清缓存或换源时同时失效。
 *
 * 跟阅读页的预取守同样的纪律：
 * - 顺序抓，不并发。几章同时打一个站点只会触发限流，把好书源熬成「加载失败」。
 * - 不开 WebView 渲染（allowJsRender = false）：WebView 只能在主线程跑，后台整本抓会让翻页掉帧。
 *   静态抓不到的章算失败，留给阅读时现抓。
 * - 写之前对 [ChapterContentCache.epoch]：期间换源、删书、同步改源都会清缓存，旧源的正文不能写进去。
 * - 连续 [MAX_CONSECUTIVE_BODY_FAILURES] 章拿不到正文时停止；插图失败留给下次续跑。
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

    fun isRunning(bookId: String): Boolean = jobs.containsKey(bookId)

    fun start(bookId: String, range: OfflineRange) {
        enqueue(bookId, range)
    }

    fun resume(bookId: String) {
        enqueue(bookId, null, textOnly = downloads.value[bookId]?.textOnly == true)
    }

    internal fun activeJob(bookId: String): Job? = jobs[bookId]

    internal fun startText(bookId: String, urls: List<String>, epoch: Long): Job? =
        enqueue(bookId, null, urls, epoch, textOnly = true)

    internal fun resumeText(bookId: String, epoch: Long): Job? =
        enqueue(bookId, null, expectedEpoch = epoch, textOnly = true)

    private fun enqueue(
        bookId: String,
        range: OfflineRange?,
        textUrls: List<String>? = null,
        expectedEpoch: Long? = null,
        textOnly: Boolean = false,
    ): Job? {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                download(bookId, range, textUrls, expectedEpoch, textOnly)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                stopEarly(
                    bookId, "无法继续离线缓存，请重试", canResume = readPlan(bookId, textOnly) != null,
                    textOnly = textOnly,
                )
            }
        }
        if (jobs.putIfAbsent(bookId, job) != null) {
            job.cancel()
            return null
        }
        job.invokeOnCompletion { jobs.remove(bookId, job) }
        job.start()
        return job
    }

    fun cancel(bookId: String) {
        jobs[bookId]?.cancel()
    }

    suspend fun restore(bookId: String, textOnly: Boolean = false) = withContext(Dispatchers.IO) {
        val epoch = cache.epoch(bookId)
        val plan = readPlan(bookId, textOnly)
        _downloads.update { all ->
            if (isRunning(bookId) || epoch != cache.epoch(bookId)) return@update all
            val current = all[bookId]
            if (plan == null) {
                return@update if (current?.canResume == true && current.textOnly == textOnly) all - bookId else all
            }
            if (current?.textOnly == textOnly) return@update all
            all + (bookId to OfflineDownload(
                bookId, plan.urls.size, running = false, canResume = true, textOnly = textOnly,
            ))
        }
    }

    private fun readPlan(bookId: String, textOnly: Boolean): OfflinePlan? = runCatching {
        cache.readOfflinePlan(bookId, textOnly)?.let { Json.decodeFromString<OfflinePlan>(it) }
    }.getOrNull()

    private suspend fun download(
        bookId: String,
        range: OfflineRange?,
        textUrls: List<String>?,
        expectedEpoch: Long?,
        textOnly: Boolean,
    ) {
        val epoch = cache.epoch(bookId)
        if (expectedEpoch != null && epoch != expectedEpoch) {
            return stopEarly(bookId, "来源换了或缓存被清空，已停止", textOnly = true)
        }
        val book = bookRepo.getBook(bookId)
        if (book == null || book.deleted || book.type != BookType.NET) return
        val saved = if (range == null && textUrls == null) {
            readPlan(bookId, textOnly)
                ?: return stopEarly(bookId, "原缓存任务已清除，请重新选择范围", textOnly = textOnly)
        } else null
        if (saved != null && (saved.sourceId != book.sourceId || saved.bookUrl != book.bookUrl)) {
            cache.clearOfflinePlan(bookId, epoch, textOnly)
            return stopEarly(bookId, "来源已变更，请重新选择缓存范围", textOnly = textOnly)
        }
        val sourceId = book.sourceId ?: return stopEarly(bookId, "来源不存在，先换源", textOnly = textOnly)
        val rule = sourceRepo.getRule(sourceId)
            ?: return stopEarly(bookId, "来源不存在，先换源", canResume = saved != null, textOnly = textOnly)
        val chapters = chapterDao.getByBook(bookId)
        if (chapters.isEmpty()) {
            return stopEarly(bookId, "目录还没加载，先刷新目录", canResume = saved != null, textOnly = textOnly)
        }

        val byUrl = chapters.mapNotNull { chapter -> chapter.url?.let { it to chapter } }.toMap()
        val plan = (saved ?: OfflinePlan(
            sourceId, book.bookUrl,
            textUrls ?: offlineTargetUrls(chapters, book.readChapterIndex, checkNotNull(range)),
        )).currentTargets(byUrl.keys)
        currentCoroutineContext().ensureActive()
        if (plan.urls.isEmpty()) {
            cache.clearOfflinePlan(bookId, epoch, textOnly)
            return stopEarly(bookId, "所选范围已无可缓存的章节，请重新选择范围", textOnly = textOnly)
        }
        if (plan != saved && !cache.writeOfflinePlan(bookId, Json.encodeToString(plan), epoch, textOnly)) {
            return stopEarly(bookId, "来源换了或缓存被清空，已停止", textOnly = textOnly)
        }
        val otherUrls = byUrl.keys
        val variables = SourceVariables.decode(book.variable)

        var status = OfflineDownload(bookId, total = plan.urls.size, textOnly = textOnly)
        publish(status)
        var consecutiveBodyFailures = 0
        try {
            for (url in plan.urls) {
                currentCoroutineContext().ensureActive()
                if (cache.epoch(bookId) != epoch) break
                val chapter = byUrl.getValue(url)
                var bodyReady = false
                val complete = try {
                    val cached = cache.readEntry(bookId, url)
                    bodyReady = cached != null
                    val content = if (cached != null && (textOnly || cached.withImages)) cached.content else {
                        val remote = engine.getContent(
                            rule, url, otherUrls,
                            chapterVariable = chapter.variable,
                            allowJsRender = false,
                            chapterTitle = chapter.title,
                            chapterIndex = chapter.index,
                            variables = variables,
                        )
                        currentCoroutineContext().ensureActive()
                        val fetched = ChapterContent(remote.elements)
                        if (!cache.writeIfEpoch(bookId, url, fetched, epoch)) break
                        chapterDao.markCachedByUrl(bookId, url, true)
                        fetched
                    }
                    bodyReady = true
                    textOnly || cache.cacheOfflineImages(bookId, content, epoch) { imageUrl ->
                        engine.getImage(rule, imageUrl)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    false
                }
                if (cache.epoch(bookId) != epoch) break
                consecutiveBodyFailures = if (bodyReady) 0 else consecutiveBodyFailures + 1
                status = if (complete) status.copy(done = status.done + 1) else status.copy(
                    failed = status.failed + 1,
                    imageFailures = status.imageFailures + if (bodyReady) 1 else 0,
                )
                publish(status)
                if (consecutiveBodyFailures >= MAX_CONSECUTIVE_BODY_FAILURES) {
                    status = status.copy(stoppedBecause = "连续 $MAX_CONSECUTIVE_BODY_FAILURES 章正文未能缓存，已停止")
                    break
                }
            }
        } finally {
            withContext(NonCancellable) {
                val invalidated = cache.epoch(bookId) != epoch
                if (status.done == status.total) cache.clearOfflinePlan(bookId, epoch, textOnly)
                publish(
                    status.copy(
                        running = false,
                        canResume = !invalidated && status.done < status.total,
                        stoppedBecause = if (invalidated) "来源换了或缓存被清空，已停止" else status.stoppedBecause,
                    ),
                )
            }
        }
    }

    private fun stopEarly(bookId: String, reason: String, canResume: Boolean = false, textOnly: Boolean = false) {
        publish(OfflineDownload(bookId, total = 0, running = false, canResume = canResume, stoppedBecause = reason, textOnly = textOnly))
    }

    private fun publish(d: OfflineDownload) {
        _downloads.update { it + (d.bookId to d) }
    }

    private companion object {
        const val MAX_CONSECUTIVE_BODY_FAILURES = 10
    }
}

internal suspend fun ChapterContentCache.cacheOfflineImages(
    bookId: String,
    content: ChapterContent,
    expectedEpoch: Long,
    fetch: suspend (String) -> ByteArray,
): Boolean {
    var complete = true
    for (image in content.elements.filterIsInstance<ContentElement.Image>().distinctBy { it.resourceId }) {
        currentCoroutineContext().ensureActive()
        if (epoch(bookId) != expectedEpoch) return false
        val url = image.resourceId
        if (hasImage(bookId, url)) continue
        try {
            val bytes = fetch(url)
            currentCoroutineContext().ensureActive()
            if (bytes.isEmpty()) {
                complete = false
                continue
            }
            if (!writeImageIfEpoch(bookId, url, bytes, expectedEpoch)) return false
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            complete = false
        }
    }
    return complete
}

internal fun offlineTargetUrls(chapters: List<ChapterEntity>, readIndex: Int, range: OfflineRange): List<String> {
    if (chapters.isEmpty()) return emptyList()
    val start = when (range) {
        OfflineRange.WHOLE_BOOK -> 0
        else -> chapters.indexOfFirst { it.index >= readIndex }.takeIf { it >= 0 } ?: chapters.lastIndex
    }
    val end = if (range == OfflineRange.NEXT_50) minOf(start + 50, chapters.size) else chapters.size
    return chapters.subList(start, end).mapNotNull { it.url?.takeIf(String::isNotBlank) }.distinct()
}
