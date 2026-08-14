package com.radium.inkwell.ui.preview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radium.inkwell.core.source.RemoteBookDetail
import com.radium.inkwell.core.source.RemoteChapter
import com.radium.inkwell.core.source.SearchResult
import com.radium.inkwell.data.repo.AutoSourceSwitcher
import com.radium.inkwell.data.repo.BookRepository
import com.radium.inkwell.data.repo.BookSourceRepository
import com.radium.inkwell.data.repo.NetBookRepository
import com.radium.inkwell.data.repo.bookKey
import com.radium.inkwell.ui.components.MessageBus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** 换源候选：书源名称 + 网址 */
data class SourceOption(val id: String, val name: String)

data class BookPreviewUiState(
    val loading: Boolean = true,
    /** 详情/目录抓取失败；此时页面只能重试 */
    val error: String? = null,
    val sourceName: String = "",
    val title: String = "",
    val author: String = "",
    val coverUrl: String? = null,
    val intro: String? = null,
    val chapters: List<RemoteChapter> = emptyList(),
    val inShelf: Boolean = false,
    /** 入库/跳转进行中，按钮置灰防重复点击 */
    val busy: Boolean = false,
    /** 有这本书的所有书源（sourceId），供换源；当前用的是 currentSource */
    /** 有这本书的所有书源（供换源）；带名称，光有网址没人认得出是哪个源 */
    val sources: List<SourceOption> = emptyList(),
    val currentSource: Int = 0,
    /** 加载中的说明；试第二个源时换成「正在尝试其他书源」 */
    val loadingLabel: String = "正在获取详情与目录…",
)

/**
 * 换源候选的进程内暂存。与 RssArticleContent 同一套办法，理由也一样。
 *
 * 一本热门书能在几十甚至上百个书源里搜到，整份 `List<SearchResult>` 里最占地方的是每条各自的
 * `intro`（一整段简介）。这份数据从前是塞在导航参数里的，而路由参数会随返回栈进
 * `onSaveInstanceState` —— 那条路走 Binder，撑爆就是 TransactionTooLargeException 崩溃，
 * 而且崩在「切后台再回来」这种用户完全无法自证的时刻。
 *
 * 进程被杀后这里取不到，预览页退化成「只有当前这个源」：页面照常打开、加书架照常，
 * 只是换源列表少了别的候选。比崩溃好得多。
 */
object BookPreviewCandidates {
    /** 返回栈里可能同时躺着好几个预览页，留一小窗；超出按最久未用淘汰，别让它无限涨 */
    private const val MAX_ENTRIES = 16

    private val map = object : LinkedHashMap<String, List<SearchResult>>(0, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, List<SearchResult>>) =
            size > MAX_ENTRIES
    }

    /** 「同一本书」的键，与书架/搜索合并用的是同一套（书名+作者） */
    fun keyOf(r: SearchResult): String = "${r.title.trim()}|${r.author?.trim().orEmpty()}"

    @Synchronized
    fun put(results: List<SearchResult>) {
        results.firstOrNull()?.let { map[keyOf(it)] = results }
    }

    @Synchronized
    fun get(key: String): List<SearchResult>? = map[key]
}

/**
 * 网络书籍预览页：先看简介与目录，再决定加书架或直接读。
 *
 * [result] 是搜索/发现给出的那条结果。不少 JSON API 书源的「详情页」其实只是目录接口，
 * 解析不出书名/作者/封面 —— 这些字段一律回落到它。
 */
class BookPreviewViewModel(
    /** 同一本书在各个书源下的搜索结果；首个是代表书源 */
    private val candidates: List<SearchResult>,
    private val sourceRepo: BookSourceRepository,
    private val netBookRepo: NetBookRepository,
    private val bookRepo: BookRepository,
) : ViewModel() {

    /** 当前用的是第几个书源；[ordered] 已按健康度排过，0 就是优先试的那个 */
    private var current = 0
    private var ordered: List<SearchResult> = candidates
    private var didRank = false
    /** 只有首次加载才自动试后面的源；用户手动换源就尊重他的选择 */
    private var autoTryOthers = true

    private val result: SearchResult get() = ordered[current]

    private fun sourceOptions() = ordered.map {
        SourceOption(id = it.sourceId, name = it.sourceName.ifBlank { it.sourceId })
    }

    /** 换到另一个书源重新加载 —— 一个源挂了不该让人卡死在报错页 */
    fun switchSource(index: Int) {
        if (index !in ordered.indices || index == current) return
        current = index
        autoTryOthers = false
        detail = null
        load()
    }

    private val _state = MutableStateFlow(BookPreviewUiState())
    val state: StateFlow<BookPreviewUiState> = _state.asStateFlow()

    val messages = MessageBus()

    /** 发出 bookId，由页面导航到阅读器 */
    private val _openReader = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val openReader: SharedFlow<String> = _openReader

    private var detail: RemoteBookDetail? = null
    private var loadJob: kotlinx.coroutines.Job? = null

    init {
        load()
        // 书架变动时实时刷新"已在书架"（本页刚加、别处加删、跨书源加了同名书都算）
        viewModelScope.launch {
            bookRepo.shelfKeys.collect { keys ->
                val onShelf = bookKey(_state.value.title, _state.value.author) in keys
                if (onShelf != _state.value.inShelf) {
                    _state.value = _state.value.copy(inShelf = onShelf)
                }
            }
        }
    }

    fun load() {
        // 快速换源时掐掉上一个源的慢请求：否则它后到会把旧源详情盖在新源标签下
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            if (!didRank) {
                ordered = AutoSourceSwitcher.rankSearchResults(
                    candidates,
                    sourceRepo.getEnabledForSwitch(),
                )
                didRank = true
                current = 0
            }
            val tryIndices = if (autoTryOthers) {
                ordered.indices.drop(current).take(MAX_SOURCE_TRIES)
            } else {
                listOf(current)
            }
            var lastError: String? = null
            var lastRuleName = ""
            for (index in tryIndices) {
                current = index
                val r = ordered[index]
                // 详情还没到手时先用搜索结果占位，页面不至于空着
                _state.value = BookPreviewUiState(
                    loading = true,
                    title = r.title,
                    author = r.author.orEmpty(),
                    coverUrl = r.coverUrl,
                    intro = r.intro,
                    sources = sourceOptions(),
                    currentSource = current,
                    loadingLabel = if (index != tryIndices.first()) {
                        "正在尝试其他书源…"
                    } else {
                        "正在获取详情与目录…"
                    },
                )
                val rule = sourceRepo.getRule(r.sourceId)
                if (rule == null) {
                    lastError = "书源不存在或已被删除"
                    lastRuleName = r.sourceName
                    continue
                }
                lastRuleName = rule.name
                // 超时必须跟 fetch 脱钩：withTimeoutOrNull { fetch() } 会等被取消的 fetch
                // 真正停下来。书源 JS / 灾难性正则不理会取消，这一等就是「一直加载」。
                // 丢到旁边的 async 里，超时只取消 await，页面立刻试下一个；
                // TimeoutCancellationException 是 CancellationException 的子类，
                // 当成整页取消往外抛会让 loading 停在 true（WebView 渲染超时就会这样）。
                val deferred = async {
                    try {
                        Result.success(netBookRepo.fetchDetailAndToc(rule, r.bookUrl))
                    } catch (e: TimeoutCancellationException) {
                        Result.failure(e)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Result.failure(e)
                    }
                }
                val outcome = withTimeoutOrNull(DETAIL_TIMEOUT_MS) { deferred.await() }
                if (outcome == null) {
                    deferred.cancel()
                    lastError = "书源响应超时"
                    continue
                }
                val fetched = outcome.getOrElse { e ->
                    lastError = e.message?.take(120) ?: "加载失败"
                    null
                } ?: continue
                try {
                    val (d, toc) = fetched
                    if (toc.isEmpty()) {
                        lastError = "目录解析为空"
                        continue
                    }
                    detail = d
                    val title = d.title.ifBlank { r.title }
                    val author = d.author ?: r.author.orEmpty()
                    _state.value = _state.value.copy(
                        loading = false,
                        error = null,
                        sourceName = rule.name,
                        title = title,
                        author = author,
                        coverUrl = d.coverUrl ?: r.coverUrl,
                        intro = d.intro ?: r.intro,
                        chapters = toc,
                        // 按 书名+作者 判断，而不是只认当前书源的 (sourceId,bookUrl)：同一本书跨书源
                        // 合并、代表书源每次搜索可能不同，只认当前源会漏判成"未加入"
                        inShelf = bookRepo.shelfBookIdByKey(title, author) != null,
                        currentSource = current,
                        sources = sourceOptions(),
                    )
                    return@launch
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastError = e.message?.take(120) ?: "加载失败"
                }
            }
            // 自动试源全失败：回到质量最好的那个，重试从它再走一遍
            if (autoTryOthers) current = 0
            val r = ordered.getOrNull(current)
            _state.value = BookPreviewUiState(
                loading = false,
                error = lastError ?: "加载失败",
                sourceName = lastRuleName,
                title = r?.title.orEmpty(),
                author = r?.author.orEmpty(),
                coverUrl = r?.coverUrl,
                intro = r?.intro,
                sources = sourceOptions(),
                currentSource = current,
            )
        }
    }

    fun addToShelf() {
        viewModelScope.launch {
            // 同名书已在架（哪怕是别的书源加的）就别再加一份
            if (bookRepo.shelfBookIdByKey(_state.value.title, _state.value.author) != null) {
                _state.value = _state.value.copy(inShelf = true)
                messages.emit("已在书架")
                return@launch
            }
            if (ensureInShelf() != null) messages.emit("已加入书架")
        }
    }

    /** chapterIndex < 0 表示接着上次读（新书即第一章） */
    fun read(chapterIndex: Int = -1) {
        viewModelScope.launch {
            val s = _state.value
            // 已在书架（可能是别的书源加的）就直接开那本，不再入库一份重复的
            val bookId = bookRepo.shelfBookIdByKey(s.title, s.author) ?: ensureInShelf() ?: return@launch
            if (chapterIndex >= 0) netBookRepo.setReadPosition(bookId, chapterIndex)
            _openReader.emit(bookId)
        }
    }

    /** 入库（已在书架则直接返回其 bookId）；失败发消息并返回 null */
    private suspend fun ensureInShelf(): String? {
        val s = _state.value
        if (s.busy) return null
        val d = detail ?: return null
        _state.value = s.copy(busy = true)
        return netBookRepo.addToShelf(result.sourceId, result.bookUrl, d, s.chapters, fallback = result)
            .onSuccess { _state.value = _state.value.copy(busy = false, inShelf = true) }
            .onFailure {
                if (it is CancellationException) throw it
                _state.value = _state.value.copy(busy = false)
                messages.emit("加入书架失败: ${it.message?.take(80)}")
            }
            .getOrNull()
    }

    companion object {
        /**
         * 详情+目录两个请求。对齐正文 15s：再宽三个源串起来会超过一分钟。
         * 超时后立刻试下一个，不等挂死的 JS/正则自己结束。
         */
        private const val DETAIL_TIMEOUT_MS = 15_000L
        private const val MAX_SOURCE_TRIES = 3
    }
}
