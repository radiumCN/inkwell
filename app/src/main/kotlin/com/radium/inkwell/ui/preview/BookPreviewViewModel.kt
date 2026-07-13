package com.radium.inkwell.ui.preview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radium.inkwell.core.source.BookSourceEngine
import com.radium.inkwell.core.source.RemoteBookDetail
import com.radium.inkwell.core.source.RemoteChapter
import com.radium.inkwell.data.repo.BookSourceRepository
import com.radium.inkwell.data.repo.NetBookRepository
import com.radium.inkwell.ui.components.MessageBus
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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
)

/** 网络书籍预览页：先看简介与目录，再决定加书架或直接读 */
class BookPreviewViewModel(
    private val sourceId: String,
    private val bookUrl: String,
    private val sourceRepo: BookSourceRepository,
    private val netBookRepo: NetBookRepository,
    private val engine: BookSourceEngine,
) : ViewModel() {

    private val _state = MutableStateFlow(BookPreviewUiState())
    val state: StateFlow<BookPreviewUiState> = _state.asStateFlow()

    val messages = MessageBus()

    /** 发出 bookId，由页面导航到阅读器 */
    private val _openReader = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val openReader: SharedFlow<String> = _openReader

    private var detail: RemoteBookDetail? = null

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = BookPreviewUiState(loading = true)
            val rule = sourceRepo.getRule(sourceId)
            if (rule == null) {
                _state.value = BookPreviewUiState(loading = false, error = "书源不存在或已被删除")
                return@launch
            }
            runCatching {
                netBookRepo.fetchDetailAndToc(rule, bookUrl)
            }.onSuccess { (d, toc) ->
                detail = d
                _state.value = BookPreviewUiState(
                    loading = false,
                    sourceName = rule.name,
                    title = d.title,
                    author = d.author.orEmpty(),
                    coverUrl = d.coverUrl,
                    intro = d.intro,
                    chapters = toc,
                    inShelf = netBookRepo.shelfBookId(sourceId, bookUrl) != null,
                )
            }.onFailure { e ->
                _state.value = BookPreviewUiState(
                    loading = false,
                    sourceName = rule.name,
                    error = e.message?.take(120) ?: "加载失败",
                )
            }
        }
    }

    fun addToShelf() {
        viewModelScope.launch {
            if (ensureInShelf() != null) messages.emit("已加入书架")
        }
    }

    /** chapterIndex < 0 表示接着上次读（新书即第一章） */
    fun read(chapterIndex: Int = -1) {
        viewModelScope.launch {
            val bookId = ensureInShelf() ?: return@launch
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
        return netBookRepo.addToShelf(sourceId, bookUrl, d, s.chapters)
            .onSuccess { _state.value = _state.value.copy(busy = false, inShelf = true) }
            .onFailure {
                _state.value = _state.value.copy(busy = false)
                messages.emit("加入书架失败: ${it.message?.take(80)}")
            }
            .getOrNull()
    }
}
