package com.radium.inkwell.ui.preview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radium.inkwell.core.source.RemoteBookDetail
import com.radium.inkwell.core.source.RemoteChapter
import com.radium.inkwell.core.source.SearchResult
import com.radium.inkwell.data.repo.BookRepository
import com.radium.inkwell.data.repo.BookSourceRepository
import com.radium.inkwell.data.repo.NetBookRepository
import com.radium.inkwell.data.repo.bookKey
import com.radium.inkwell.ui.components.MessageBus
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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
)

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

    /** 当前用的是第几个书源 */
    private var current = 0

    private val result: SearchResult get() = candidates[current]

    /** 换到另一个书源重新加载 —— 一个源挂了不该让人卡死在报错页 */
    fun switchSource(index: Int) {
        if (index !in candidates.indices || index == current) return
        current = index
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
            // 详情还没到手时先用搜索结果占位，页面不至于空着
            _state.value = BookPreviewUiState(
                loading = true,
                title = result.title,
                author = result.author.orEmpty(),
                coverUrl = result.coverUrl,
                intro = result.intro,
                sources = candidates.map {
                    SourceOption(id = it.sourceId, name = it.sourceName.ifBlank { it.sourceId })
                },
                currentSource = current,
            )
            val rule = sourceRepo.getRule(result.sourceId)
            if (rule == null) {
                _state.value = _state.value.copy(loading = false, error = "书源不存在或已被删除")
                return@launch
            }
            runCatching {
                netBookRepo.fetchDetailAndToc(rule, result.bookUrl)
            }.onSuccess { (d, toc) ->
                detail = d
                val title = d.title.ifBlank { result.title }
                val author = d.author ?: result.author.orEmpty()
                _state.value = _state.value.copy(
                    loading = false,
                    error = null,
                    sourceName = rule.name,
                    title = title,
                    author = author,
                    coverUrl = d.coverUrl ?: result.coverUrl,
                    intro = d.intro ?: result.intro,
                    chapters = toc,
                    // 按 书名+作者 判断，而不是只认当前书源的 (sourceId,bookUrl)：同一本书跨书源
                    // 合并、代表书源每次搜索可能不同，只认当前源会漏判成"未加入"
                    inShelf = bookRepo.shelfBookIdByKey(title, author) != null,
                )
            }.onFailure { e ->
                _state.value = _state.value.copy(
                    loading = false,
                    sourceName = rule.name,
                    error = e.message?.take(120) ?: "加载失败",
                )
            }
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
                _state.value = _state.value.copy(busy = false)
                messages.emit("加入书架失败: ${it.message?.take(80)}")
            }
            .getOrNull()
    }
}
