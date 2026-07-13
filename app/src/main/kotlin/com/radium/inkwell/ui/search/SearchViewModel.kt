package com.radium.inkwell.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radium.inkwell.core.source.BookSourceEngine
import com.radium.inkwell.core.source.BookSourceRule
import com.radium.inkwell.core.source.SearchResult
import com.radium.inkwell.data.repo.BookSourceRepository
import com.radium.inkwell.data.repo.NetBookRepository
import com.radium.inkwell.ui.components.MessageBus
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class SearchUiState(
    val query: String = "",
    val searching: Boolean = false,
    val results: List<SearchResult> = emptyList(),
    val sourceCount: Int = 0,
    val doneCount: Int = 0,
    val addingUrl: String? = null,
    /** 已取到第几页；与发现页一致地支持滚到底加载更多 */
    val page: Int = 1,
    val hasMore: Boolean = false,
    val loadingMore: Boolean = false,
)

class SearchViewModel(
    private val sourceRepo: BookSourceRepository,
    private val netBookRepo: NetBookRepository,
    private val engine: BookSourceEngine,
) : ViewModel() {

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    val messages = MessageBus()

    private var searchJob: Job? = null

    fun setQuery(q: String) {
        _state.value = _state.value.copy(query = q)
    }

    fun search() {
        val keyword = _state.value.query.trim()
        if (keyword.isEmpty()) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            val enabled = sourceRepo.getEnabledRules()
            val rules = enabled.filter { it.search != null }
            if (rules.isEmpty()) {
                // 区分"没有书源"和"启用的都是仅发现页的源"，后者曾误导用户以为启用没生效
                messages.emit(
                    if (enabled.isEmpty()) "没有启用的书源，请先导入并启用书源"
                    else "已启用的 ${enabled.size} 个书源都不支持搜索（仅发现页可用），" +
                        "请到发现页浏览，或导入带搜索规则的书源"
                )
                return@launch
            }
            _state.value = _state.value.copy(
                searching = true, results = emptyList(),
                sourceCount = rules.size, doneCount = 0,
                page = 1, hasMore = false, loadingMore = false,
            )
            val limiter = Semaphore(8) // 并发上限
            val more = rules.map { rule ->
                async {
                    limiter.withPermit {
                        val page = runCatching { engine.search(rule, keyword, page = 1) }.getOrNull()
                        _state.value = _state.value.copy(
                            results = _state.value.results + (page?.items ?: emptyList()),
                            doneCount = _state.value.doneCount + 1,
                        )
                        rule.takeIf { page?.hasMore == true }
                    }
                }
            }.awaitAll().filterNotNull()
            pagingRules = more
            _state.value = _state.value.copy(searching = false, hasMore = more.isNotEmpty())
        }
    }

    /** 还能继续翻页的书源（上一页 hasMore 为真的那些） */
    private var pagingRules: List<BookSourceRule> = emptyList()

    fun loadMore() {
        val s = _state.value
        if (s.searching || s.loadingMore || !s.hasMore || pagingRules.isEmpty()) return
        val keyword = s.query.trim()
        val next = s.page + 1
        viewModelScope.launch {
            _state.value = _state.value.copy(loadingMore = true)
            val limiter = Semaphore(8)
            val seen = _state.value.results.mapTo(HashSet()) { it.sourceId to it.bookUrl }
            val still = pagingRules.map { rule ->
                async {
                    limiter.withPermit {
                        val page = runCatching { engine.search(rule, keyword, page = next) }.getOrNull()
                        val fresh = (page?.items ?: emptyList())
                            .filter { (it.sourceId to it.bookUrl) !in seen }
                        if (fresh.isNotEmpty()) {
                            _state.value = _state.value.copy(results = _state.value.results + fresh)
                        }
                        // 这一页没有新书 = 这个源翻到头了（不少站点越界会一直回吐最后一页）
                        rule.takeIf { page?.hasMore == true && fresh.isNotEmpty() }
                    }
                }
            }.awaitAll().filterNotNull()
            pagingRules = still
            _state.value = _state.value.copy(
                loadingMore = false,
                page = next,
                hasMore = still.isNotEmpty(),
            )
        }
    }

    fun addToShelf(result: SearchResult) {
        viewModelScope.launch {
            _state.value = _state.value.copy(addingUrl = result.bookUrl)
            val rule = sourceRepo.getRule(result.sourceId)
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
