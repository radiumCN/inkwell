package com.radium.inkwell.ui.search

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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * 一本书；同名同作者的结果跨书源合并成一条。
 * 保留每个书源各自的结果（bookUrl 各不相同）—— 预览页要靠它换源：
 * 代表书源挂了还有别的可用，否则用户就卡死在报错页。
 */
data class SearchHit(val results: List<SearchResult>) {
    /** 代表条目：首个返回它的书源 */
    val result: SearchResult get() = results.first()
    val origins: Set<String> get() = results.mapTo(LinkedHashSet()) { it.sourceId }
}

data class SearchUiState(
    val query: String = "",
    val searching: Boolean = false,
    val results: List<SearchHit> = emptyList(),
    val sourceCount: Int = 0,
    val doneCount: Int = 0,
    val addingUrl: String? = null,
    /** 已取到第几页；与发现页一致地支持滚到底加载更多 */
    val page: Int = 1,
    val hasMore: Boolean = false,
    val loadingMore: Boolean = false,
    /** 每次新搜索 +1；界面靠它把列表滚回顶部 */
    val searchId: Int = 0,
    /** 书架已有书的 (书名,作者) 键；列表据此把已在架的书显示为"已加入" */
    val shelfKeys: Set<Pair<String, String>> = emptySet(),
)

class SearchViewModel(
    private val sourceRepo: BookSourceRepository,
    private val netBookRepo: NetBookRepository,
    private val engine: BookSourceEngine,
    private val bookRepo: BookRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    val messages = MessageBus()

    private var searchJob: Job? = null
    private var pagingJob: Job? = null

    init {
        // 书架变动时刷新"已加入"标记（本页加书、别处加/删、跨书源加了同名书都算）
        viewModelScope.launch {
            bookRepo.shelfKeys.collect { keys -> _state.value = _state.value.copy(shelfKeys = keys) }
        }
    }

    fun setQuery(q: String) {
        _state.value = _state.value.copy(query = q)
    }

    fun search() {
        val keyword = _state.value.query.trim()
        if (keyword.isEmpty()) return
        searchJob?.cancel()
        // 上一轮的「加载更多」还在飞时开新搜索：不掐掉它，它回来会把旧关键词的结果 merge 进
        // 刚清空的 hits，串进新搜索列表
        pagingJob?.cancel()
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
            hits.clear()
            _state.value = _state.value.copy(
                searching = true, results = emptyList(),
                sourceCount = rules.size, doneCount = 0,
                page = 1, hasMore = false, loadingMore = false,
                searchId = _state.value.searchId + 1,
            )
            val limiter = Semaphore(8) // 并发上限
            val more = rules.map { rule ->
                async {
                    limiter.withPermit {
                        val page = runCatching { engine.search(rule, keyword, page = 1) }.getOrNull()
                        merge(page?.items.orEmpty())
                        _state.value = _state.value.copy(
                            results = ranked(keyword),
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

    // ---- 合并与相关度排序 ----

    /** 同名同作者视为同一本书；键的顺序即首次出现的顺序，用于同档同源数时保持稳定 */
    private val hits = LinkedHashMap<Pair<String, String>, SearchHit>()

    private fun merge(items: List<SearchResult>) {
        for (r in items) {
            val key = r.title.trim() to r.author?.trim().orEmpty()
            val old = hits[key]
            hits[key] = when {
                old == null -> SearchHit(listOf(r))
                old.results.any { it.sourceId == r.sourceId } -> old
                else -> SearchHit(old.results + r)
            }
        }
    }

    /**
     * 相关度排序：书名/作者与关键词完全相等 > 包含关键词 > 其余；
     * 同档内按「有这本书的书源数」降序 —— 越多书源都收录，越可能就是要找的那本。
     *
     * 从前是哪个书源先返回就排在前面，顺序完全由网络快慢决定，
     * 于是精确命中的书常被沉到列表底部，同一本书还会按书源数重复几十行。
     */
    private fun ranked(keyword: String): List<SearchHit> =
        hits.values.sortedWith(
            compareBy<SearchHit> { tier(it.result, keyword) }
                .thenByDescending { it.origins.size }
        )

    private fun tier(r: SearchResult, keyword: String): Int = when {
        r.title == keyword || r.author == keyword -> 0
        r.title.contains(keyword) || r.author?.contains(keyword) == true -> 1
        else -> 2
    }

    /** 还能继续翻页的书源（上一页 hasMore 为真的那些） */
    private var pagingRules: List<BookSourceRule> = emptyList()

    fun loadMore() {
        val s = _state.value
        if (s.searching || s.loadingMore || !s.hasMore || pagingRules.isEmpty()) return
        val keyword = s.query.trim()
        val next = s.page + 1
        pagingJob?.cancel()
        pagingJob = viewModelScope.launch {
            _state.value = _state.value.copy(loadingMore = true)
            val limiter = Semaphore(8)
            val still = pagingRules.map { rule ->
                async {
                    limiter.withPermit {
                        val page = runCatching { engine.search(rule, keyword, page = next) }.getOrNull()
                        val before = hits.size
                        merge(page?.items.orEmpty())
                        val gotNew = hits.size > before
                        if (gotNew) _state.value = _state.value.copy(results = ranked(keyword))
                        // 这一页没带来新书 = 这个源翻到头了（不少站点越界会一直回吐最后一页）
                        rule.takeIf { page?.hasMore == true && gotNew }
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
