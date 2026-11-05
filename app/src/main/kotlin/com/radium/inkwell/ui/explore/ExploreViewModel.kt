package com.radium.inkwell.ui.explore

import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radium.inkwell.core.source.BookSourceEngine
import com.radium.inkwell.core.source.BookSourceRule
import com.radium.inkwell.core.source.SearchResult
import com.radium.inkwell.data.prefs.AppPrefs
import com.radium.inkwell.data.repo.BookRepository
import com.radium.inkwell.data.repo.BookSourceRepository
import com.radium.inkwell.data.repo.NetBookRepository
import com.radium.inkwell.ui.bookshelf.searchableShelfKeys
import com.radium.inkwell.ui.components.MessageBus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

data class ExploreSource(val id: String, val name: String, val categories: List<String>)

/**
 * 换源后该停在哪个分类：新源的分类里有**同名**的（首尾空白不计）就停在它上面，接不上才回第一个。
 * 不这么做的话，两个源都有「玄幻小说」时每换一次源都得再点一遍分类，切源浏览的心流被切碎。
 */
internal fun matchedCategoryIndex(newCategories: List<String>, previousName: String?): Int {
    if (previousName.isNullOrBlank()) return 0
    val target = previousName.trim()
    return newCategories.indexOfFirst { it.trim() == target }.coerceAtLeast(0)
}

/** 最近用过的书源 id（新→旧）：[id] 提到最前，超出 [limit] 的旧源丢掉 */
internal fun pushRecentSource(current: List<String>, id: String, limit: Int = AppPrefs.RECENT_EXPLORE_SOURCES_MAX): List<String> =
    (listOf(id) + current.filterNot { it == id }).take(limit)

/** 发现页列表里一本书的标识：列表按它做 key，合并分页也按它去重 —— 两处必须是同一个 */
internal val SearchResult.exploreKey: String get() = "$sourceId|$bookUrl"

/** 新的一页里真正新增的那些：与已有列表、以及这一页自身都按 [exploreKey] 去重 */
internal fun newExploreItems(current: List<SearchResult>, page: List<SearchResult>): List<SearchResult> {
    val seen = current.mapTo(HashSet()) { it.exploreKey }
    return page.filter { seen.add(it.exploreKey) }
}

data class ExploreUiState(
    val sources: List<ExploreSource> = emptyList(),
    /**
     * 书源列表已从库里读过一遍。在这之前 [sources] 的空不代表「没有可用的发现来源」——
     * 不分开的话，每次进页都先闪一下那个空态（连同「去导入规则」按钮），库读回来才换成内容。
     */
    val sourcesLoaded: Boolean = false,
    val sourceIndex: Int = 0,
    val categoryIndex: Int = 0,
    val books: List<SearchResult> = emptyList(),
    val page: Int = 1,
    val hasMore: Boolean = false,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    /**
     * 首页加载失败的原因；非空时页面显示错误态而不是「这个分类没有内容」。
     * 从前失败也落到空态，只在底部闪一条 Snackbar —— 提示一消失，满屏写的是「没有内容」，
     * 被掐断的网络看起来像是分类本来就空。加载更多失败不进这里：已有的书还在，弹提示即可
     */
    val error: String? = null,
    /**
     * 「加载更多」失败了，停在这里等用户点列表底部的重试。
     *
     * 不能只靠 hasMore：失败后它还是 true，loadingMore 一落回 false，页面那条「滑到底就加载」的
     * LaunchedEffect 立刻重跑，人还停在底部 —— 于是无限重试、每轮弹一条「加载失败」，离线时几乎是死循环。
     */
    val loadMoreFailed: Boolean = false,
    /** 正在加入书架的那几本（bookUrl）。可以同时加好几本 */
    val addingUrls: Set<String> = emptySet(),
    /** 书架已有书的 (书名,作者) 键；列表据此把已在架的书显示为"已加入"（隐藏区上锁时不含隐藏的书） */
    val shelfKeys: Set<Pair<String, String>> = emptySet(),
    /** 最近用过的书源 id（新→旧，最多 3 个）。选择器「最近」区按它置顶 */
    val recentSourceIds: List<String> = emptyList(),
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
) : ViewModel(), KoinComponent {

    /** 只为读「隐藏区上没上锁」这一个开关，同 BookPreviewViewModel 从 Koin 现取 */
    private val appPrefs: AppPrefs by inject()

    private val _state = MutableStateFlow(ExploreUiState())
    val state: StateFlow<ExploreUiState> = _state.asStateFlow()

    val messages = MessageBus()

    private var loadJob: Job? = null
    private val ruleCache = mutableMapOf<String, BookSourceRule>()

    init {
        // 书架变动时刷新"已加入"标记。隐藏区上了锁时隐藏的书不算在架，见 searchableShelfKeys
        viewModelScope.launch {
            searchableShelfKeys(bookRepo, appPrefs).collect { keys ->
                _state.value = _state.value.copy(shelfKeys = keys)
            }
        }
        // 书源跟着库走，而不是进页时读一次：从「没有可用的发现源」点「去导入规则」导入回来、
        // 或在书源管理里启停之后，发现页还停在旧的源列表上（甚至一直是空态）。
        // 只在启用的源或其规则（id / updatedAt）变了才重读：校验结果写回不该让发现页重载。
        viewModelScope.launch {
            sourceRepo.sources
                .map { list -> list.filter { it.enabled }.map { it.id to it.updatedAt } }
                .distinctUntilChanged()
                .collectLatest { reloadSources() }
        }
        // 「最近」的初值从盘上读回来：选择器面板一打开就该有，不能等第一次切源才出现
        viewModelScope.launch {
            appPrefs.recentExploreSources.collectLatest { ids ->
                if (ids != _state.value.recentSourceIds) {
                    _state.value = _state.value.copy(recentSourceIds = ids)
                }
            }
        }
    }

    private suspend fun reloadSources() {
        val rules = sourceRepo.getEnabledRules().filter { it.explore.isNotEmpty() }
        ruleCache.clear()
        rules.forEach { ruleCache[it.id] = it }
        val sources = rules.map { r -> ExploreSource(r.id, r.name, r.explore.map { it.name }) }
        val prev = _state.value
        // 正看着的源还在就留在它上面，别因为别处启停了一个源就把人甩回第一个
        val keep = sources.indexOfFirst { it.id == prev.currentSource?.id }
        val sameView = keep >= 0 && prev.categoryIndex < sources[keep].categories.size
        _state.value = prev.copy(
            sources = sources,
            sourcesLoaded = true,
            sourceIndex = keep.coerceAtLeast(0),
            categoryIndex = if (sameView) prev.categoryIndex else 0,
        )
        when {
            sources.isEmpty() -> {
                loadJob?.cancel()
                _state.value = _state.value.copy(
                    books = emptyList(), hasMore = false, loading = false, loadingMore = false,
                )
            }
            // 还是同一个源同一个分类、列表也已经有了：别把用户翻到第几页的内容冲掉
            !sameView || prev.books.isEmpty() -> loadPage(reset = true)
        }
    }

    fun selectSource(index: Int) {
        val prev = _state.value
        if (index == prev.sourceIndex) return
        val source = prev.sources.getOrNull(index) ?: return
        // 同名分类跟着走（见 matchedCategoryIndex）；顺手把源记进「最近」
        val recent = pushRecentSource(prev.recentSourceIds, source.id)
        _state.value = prev.copy(
            sourceIndex = index,
            categoryIndex = matchedCategoryIndex(source.categories, prev.categories.getOrNull(prev.categoryIndex)),
            recentSourceIds = recent,
        )
        // 跨启动记住逛过哪些源。写失败不该影响切源本身 —— 最多下次面板里「最近」少一个
        viewModelScope.launch { runCatching { appPrefs.setRecentExploreSources(recent) } }
        loadPage(reset = true)
    }

    fun selectCategory(index: Int) {
        if (index == _state.value.categoryIndex) return
        _state.value = _state.value.copy(categoryIndex = index)
        loadPage(reset = true)
    }

    fun loadMore() {
        val s = _state.value
        if (!s.hasMore || s.loading || s.loadingMore || s.loadMoreFailed) return
        loadPage(reset = false)
    }

    fun retry() = loadPage(reset = true)

    /** 列表底部「加载失败，点此重试」 */
    fun retryMore() {
        val s = _state.value
        if (!s.hasMore || s.loading || s.loadingMore) return
        loadPage(reset = false)
    }

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
                error = if (reset) null else _state.value.error,
                loadMoreFailed = false,
            )
            runCatching { engine.explore(rule, s.categoryIndex, page) }
                .onSuccess { result ->
                    // 站点的分类书单在两次翻页之间会错位（「最近更新」这类最常见）：第 2 页开头常常
                    // 就是第 1 页末尾那几本，一页里也可能把同一本书列两次。列表按 exploreKey 做 key，
                    // 重复一条就是「Key was already used」崩溃 —— 合并时按同一个 key 去重。
                    val current = if (reset) emptyList() else _state.value.books
                    val added = newExploreItems(current, result.items)
                    _state.value = _state.value.copy(
                        books = current + added,
                        page = page,
                        // 一本新书都没带来就当到头了：超出页码时仍回最后一页的站点，
                        // 会让「加载更多」永远有下一页，列表无休止地追加重复内容
                        hasMore = result.hasMore && added.isNotEmpty(),
                        loading = false,
                        loadingMore = false,
                    )
                }
                .onFailure { e ->
                    // 换源/换分类会 cancel 掉上一轮，runCatching 连 CancellationException 一起吞。
                    // 不先摘出来的话：用户每切一次分类就弹一条"加载失败"，而且这条僵尸协程还会把
                    // loading 抹成 false —— 新一轮明明在转圈，转圈却没了。
                    if (e is CancellationException) throw e
                    // 抓取层已记下地址与异常链（SourceHttp），这里补上是哪个源、哪个分类、第几页
                    Log.w(TAG, "发现页加载失败：书源「${source.name}」分类「${s.categories.getOrNull(s.categoryIndex)}」第 $page 页", e)
                    val reason = e.message?.take(80) ?: e.javaClass.simpleName
                    _state.value = _state.value.copy(
                        loading = false,
                        loadingMore = false,
                        error = if (reset) reason else _state.value.error,
                        loadMoreFailed = !reset,
                    )
                    if (!reset) messages.emit("加载失败: $reason")
                }
        }
    }

    /**
     * 可以同时加好几本。从前只记一个 addingUrl：A 还在加时点 B，A 先完成就把 B 的转圈也抹掉了。
     */
    fun addToShelf(result: SearchResult) {
        val url = result.bookUrl
        if (url in _state.value.addingUrls) return // 同一行连点
        _state.update { it.copy(addingUrls = it.addingUrls + url) }
        viewModelScope.launch {
            try {
                val rule = ruleCache[result.sourceId] ?: sourceRepo.getRule(result.sourceId)
                if (rule == null) {
                    messages.emit("书源不存在")
                    return@launch
                }
                // 同搜索页：书源卡住时别让这一行一直转圈。用 withTimeoutOrNull 而不是 withTimeout ——
                // 后者抛的是 CancellationException，会和「离开页面」的取消混在一起
                val outcome = withTimeoutOrNull(ADD_TIMEOUT_MS) { netBookRepo.addToShelf(result, rule) }
                if (outcome == null) {
                    messages.emit("加入失败：书源响应超时")
                    return@launch
                }
                outcome
                    .onSuccess { messages.emit("已加入书架") }
                    .onFailure {
                        if (it is CancellationException) throw it
                        messages.emit("加入失败: ${it.message?.take(80)}")
                    }
            } finally {
                _state.update { it.copy(addingUrls = it.addingUrls - url) }
            }
        }
    }

    private companion object {
        const val TAG = "Explore"
        /** 详情+目录两个请求，同 SearchViewModel 的加入超时 */
        const val ADD_TIMEOUT_MS = 20_000L
    }
}
