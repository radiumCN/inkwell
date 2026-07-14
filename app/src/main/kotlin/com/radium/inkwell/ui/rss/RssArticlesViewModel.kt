package com.radium.inkwell.ui.rss

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radium.inkwell.core.source.rss.RssArticle
import com.radium.inkwell.core.source.rss.RssEngine
import com.radium.inkwell.core.source.rss.RssSort
import com.radium.inkwell.core.source.rss.RssSourceRule
import com.radium.inkwell.data.repo.RssRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

data class RssArticlesUiState(
    val sourceName: String = "",
    val sorts: List<RssSort> = emptyList(),
    val currentSort: Int = 0,
    val articles: List<RssArticle> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

class RssArticlesViewModel(
    private val sourceId: String,
    private val repo: RssRepository,
) : ViewModel(), KoinComponent {

    private val engine: RssEngine by inject()

    private val _state = MutableStateFlow(RssArticlesUiState())
    val state: StateFlow<RssArticlesUiState> = _state.asStateFlow()

    private var rule: RssSourceRule? = null
    private var job: Job? = null

    init {
        viewModelScope.launch {
            val r = repo.getRule(sourceId)
            if (r == null) {
                _state.value = _state.value.copy(loading = false, error = "订阅源不存在")
                return@launch
            }
            rule = r
            _state.value = _state.value.copy(sourceName = r.name, sorts = r.sortsOrDefault())
            load(0)
        }
    }

    fun selectSort(index: Int) {
        if (index == _state.value.currentSort) return
        load(index)
    }

    fun refresh() = load(_state.value.currentSort)

    private fun load(sortIndex: Int) {
        val r = rule ?: return
        val sort = r.sortsOrDefault().getOrNull(sortIndex) ?: return
        job?.cancel()
        job = viewModelScope.launch {
            _state.value = _state.value.copy(
                currentSort = sortIndex,
                loading = true,
                error = null,
                // 切分类时先清空：留着上一个分类的文章，用户会以为新分类就是这些
                articles = emptyList(),
            )
            runCatching { engine.articles(r, sort.url) }
                .onSuccess { page ->
                    _state.value = _state.value.copy(loading = false, articles = page.items)
                }
                .onFailure {
                    _state.value = _state.value.copy(
                        loading = false,
                        error = it.message?.take(120) ?: "加载失败",
                    )
                }
        }
    }
}
