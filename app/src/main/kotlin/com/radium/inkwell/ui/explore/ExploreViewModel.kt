package com.radium.inkwell.ui.explore

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radium.inkwell.core.source.BookSourceEngine
import com.radium.inkwell.core.source.BookSourceRule
import com.radium.inkwell.core.source.SearchResult
import com.radium.inkwell.data.repo.BookRepository
import com.radium.inkwell.data.repo.BookSourceRepository
import com.radium.inkwell.data.repo.NetBookRepository
import com.radium.inkwell.ui.components.MessageBus
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ExploreSource(val id: String, val name: String, val categories: List<String>)

data class ExploreUiState(
    val sources: List<ExploreSource> = emptyList(),
    val sourceIndex: Int = 0,
    val categoryIndex: Int = 0,
    val books: List<SearchResult> = emptyList(),
    val page: Int = 1,
    val hasMore: Boolean = false,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val addingUrl: String? = null,
    /** 书架已有书的 (书名,作者) 键；列表据此把已在架的书显示为"已加入" */
    val shelfKeys: Set<Pair<String, String>> = emptySet(),
) {
    val currentSource: ExploreSource? get() = sources.getOrNull(sourceIndex)
    val categories: List<String> get() = currentSource?.categories.orEmpty()
}

/** 发现页：浏览书源的分类书单（无需搜索规则的书源也能用） */
class ExploreViewModel(
    private val sourceRepo: BookSourceRepository,
    private val netBookRepo: NetBookRepository,
    private val engine: BookSourceEngine,
    private val bookRepo: BookRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ExploreUiState())
    val state: StateFlow<ExploreUiState> = _state.asStateFlow()

    val messages = MessageBus()

    private var loadJob: Job? = null
    private val ruleCache = mutableMapOf<String, BookSourceRule>()

    init {
        // 书架变动时刷新"已加入"标记
        viewModelScope.launch {
            bookRepo.shelfKeys.collect { keys -> _state.value = _state.value.copy(shelfKeys = keys) }
        }
        viewModelScope.launch {
            val rules = sourceRepo.getEnabledRules().filter { it.explore.isNotEmpty() }
            rules.forEach { ruleCache[it.id] = it }
            _state.value = _state.value.copy(
                sources = rules.map { r ->
                    ExploreSource(r.id, r.name, r.explore.map { it.name })
                }
            )
            if (rules.isNotEmpty()) loadPage(reset = true)
        }
    }

    fun selectSource(index: Int) {
        if (index == _state.value.sourceIndex) return
        _state.value = _state.value.copy(sourceIndex = index, categoryIndex = 0)
        loadPage(reset = true)
    }

    fun selectCategory(index: Int) {
        if (index == _state.value.categoryIndex) return
        _state.value = _state.value.copy(categoryIndex = index)
        loadPage(reset = true)
    }

    fun loadMore() {
        val s = _state.value
        if (!s.hasMore || s.loading || s.loadingMore) return
        loadPage(reset = false)
    }

    fun retry() = loadPage(reset = true)

    private fun loadPage(reset: Boolean) {
        val s = _state.value
        val source = s.currentSource ?: return
        val rule = ruleCache[source.id] ?: return
        val page = if (reset) 1 else s.page + 1

        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.value = _state.value.copy(
                loading = reset,
                loadingMore = !reset,
                books = if (reset) emptyList() else _state.value.books,
            )
            runCatching { engine.explore(rule, s.categoryIndex, page) }
                .onSuccess { result ->
                    _state.value = _state.value.copy(
                        books = if (reset) result.items else _state.value.books + result.items,
                        page = page,
                        hasMore = result.hasMore && result.items.isNotEmpty(),
                        loading = false,
                        loadingMore = false,
                    )
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(loading = false, loadingMore = false)
                    messages.emit("加载失败: ${e.message?.take(80)}")
                }
        }
    }

    fun addToShelf(result: SearchResult) {
        viewModelScope.launch {
            _state.value = _state.value.copy(addingUrl = result.bookUrl)
            val rule = ruleCache[result.sourceId] ?: sourceRepo.getRule(result.sourceId)
            if (rule == null) {
                _state.value = _state.value.copy(addingUrl = null)
                messages.emit("书源不存在")
                return@launch
            }
            netBookRepo.addToShelf(result, rule)
                .onSuccess {
                    _state.value = _state.value.copy(addingUrl = null)
                    messages.emit("已加入书架")
                }
                .onFailure {
                    _state.value = _state.value.copy(addingUrl = null)
                    messages.emit("加入失败: ${it.message?.take(80)}")
                }
        }
    }

}
