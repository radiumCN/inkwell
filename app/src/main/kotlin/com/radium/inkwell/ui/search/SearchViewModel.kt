package com.radium.inkwell.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radium.inkwell.core.source.BookSourceEngine
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
            )
            val limiter = Semaphore(8) // 并发上限
            rules.map { rule ->
                async {
                    limiter.withPermit {
                        val items = runCatching { engine.search(rule, keyword).items }
                            .getOrDefault(emptyList())
                        _state.value = _state.value.copy(
                            results = _state.value.results + items,
                            doneCount = _state.value.doneCount + 1,
                        )
                    }
                }
            }.awaitAll()
            _state.value = _state.value.copy(searching = false)
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
