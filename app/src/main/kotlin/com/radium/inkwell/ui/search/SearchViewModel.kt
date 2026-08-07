package com.radium.inkwell.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radium.inkwell.core.source.BookSourceEngine
import com.radium.inkwell.core.source.BookSourceRule
import com.radium.inkwell.core.source.SearchPage
import com.radium.inkwell.core.source.SearchResult
import com.radium.inkwell.data.repo.BookRepository
import com.radium.inkwell.data.repo.BookSourceRepository
import com.radium.inkwell.data.repo.NetBookRepository
import com.radium.inkwell.ui.components.MessageBus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.Collator
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * 一本书；同名同作者的结果跨书源合并成一条。
 * 保留每个书源各自的结果（bookUrl 各不相同）—— 预览页要靠它换源：
 * 代表书源挂了还有别的可用，否则用户就卡死在报错页。
 */
data class SearchHit(val results: List<SearchResult>) {
    /** 代表条目：优先带字数/分类的那条，列表副标题才有东西可看 */
    val result: SearchResult
        get() = results.firstOrNull { !it.wordCount.isNullOrBlank() || !it.kind.isNullOrBlank() }
            ?: results.first()
    val origins: Set<String> get() = results.mapTo(LinkedHashSet()) { it.sourceId }
}

/** 搜索结果排序。默认相关度 —— 换别的会打乱「最像关键词」的优先顺序，所以选项里写清楚。 */
enum class SearchSort(val label: String) {
    RELEVANCE("相关度"),
    WORD_COUNT("字数"),
    TITLE_PINYIN("书名"),
    AUTHOR_PINYIN("作者"),
    UPDATE_TIME("更新日期"),
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
    /** 换排序也 +1，同样滚回顶部 —— 否则人还停在旧位置，不知道列表已经重排了 */
    val sortId: Int = 0,
    val sort: SearchSort = SearchSort.RELEVANCE,
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
    private var sortJob: Job? = null
    /** 防抖发布：每个源回来就全量重排+刷 UI 会把主线程打满 */
    private var publishJob: Job? = null
    private val hitsMutex = Mutex()

    /**
     * 列表滚动位置放 ViewModel 里 —— 离开搜索 entry 会拆掉 Composable，
     * remember / rememberSaveable 都不可靠；更糟的是返回时 LaunchedEffect(searchId)
     * 会再跑一遍并 scrollToItem(0)，把刚恢复的位置冲掉。
     */
    var listIndex: Int = 0
        private set
    var listOffset: Int = 0
        private set
    var userScrolled: Boolean = false
        private set
    private var pinnedScrollKey: Pair<Int, Int>? = null

    fun noteScroll(index: Int, offset: Int) {
        listIndex = index
        listOffset = offset
    }

    fun noteUserScrolled() {
        userScrolled = true
    }

    /**
     * 仅当 searchId/sortId **相对上次钉顶**变了才需要滚回顶部。
     * 返回本页时 key 不变 → false，保留 [listIndex]/[listOffset]。
     */
    fun consumeScrollToTopIfNeeded(searchId: Int, sortId: Int): Boolean {
        val key = searchId to sortId
        if (pinnedScrollKey == key) return false
        pinnedScrollKey = key
        userScrolled = false
        listIndex = 0
        listOffset = 0
        return true
    }

    init {
        // 书架变动时刷新"已加入"标记（本页加书、别处加/删、跨书源加了同名书都算）
        viewModelScope.launch {
            bookRepo.shelfKeys.collect { keys -> _state.value = _state.value.copy(shelfKeys = keys) }
        }
    }

    fun setQuery(q: String) {
        _state.value = _state.value.copy(query = q)
    }

    fun setSort(sort: SearchSort) {
        if (sort == _state.value.sort) return
        sortJob?.cancel()
        publishJob?.cancel()
        val keyword = _state.value.query.trim()
        val sortId = _state.value.sortId + 1
        // 先拍快照再丢到 Default：Collator 拼音排几百上千条会卡住主线程，
        // 底部面板关动画也会跟着一顿。标签先切过去，列表排完再换。
        _state.value = _state.value.copy(sort = sort, sortId = sortId)
        sortJob = viewModelScope.launch {
            val snapshot = hitsMutex.withLock { hits.values.toList() }
            val sorted = withContext(Dispatchers.Default) {
                ordered(snapshot, keyword, sort)
            }
            if (_state.value.sortId != sortId) return@launch
            _state.value = _state.value.copy(results = sorted)
        }
    }

    fun search() {
        val keyword = _state.value.query.trim()
        if (keyword.isEmpty()) return
        searchJob?.cancel()
        sortJob?.cancel()
        publishJob?.cancel()
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
            hitsMutex.withLock { hits.clear() }
            val sort = _state.value.sort
            val searchId = _state.value.searchId + 1
            val done = AtomicInteger(0)
            _state.value = _state.value.copy(
                searching = true, results = emptyList(),
                sourceCount = rules.size, doneCount = 0,
                page = 1, hasMore = false, loadingMore = false,
                searchId = searchId,
            )
            val limiter = Semaphore(8) // 并发上限
            val more = rules.map { rule ->
                async {
                    limiter.withPermit {
                        val page = searchPage(rule, keyword, page = 1)
                        hitsMutex.withLock { merge(page?.items.orEmpty()) }
                        val d = done.incrementAndGet()
                        // 进度条立刻动；列表重排防抖，避免 N 源 × 全量 Collator 打爆主线程
                        // update 避免与 schedulePublish 并发 copy 时把 results 盖回旧快照
                        _state.update { it.copy(doneCount = d) }
                        schedulePublish(keyword, sort, searchId)
                        rule.takeIf { page?.hasMore == true }
                    }
                }
            }.awaitAll().filterNotNull()
            pagingRules = more
            // 收尾强制发一版最终排序，别停在防抖窗口里的半截
            publishJob?.cancel()
            val snapshot = hitsMutex.withLock { hits.values.toList() }
            val sorted = withContext(Dispatchers.Default) {
                ordered(snapshot, keyword, sort)
            }
            if (_state.value.searchId == searchId) {
                _state.value = _state.value.copy(
                    results = sorted,
                    searching = false,
                    hasMore = more.isNotEmpty(),
                    doneCount = done.get(),
                )
            }
        }
    }

    /**
     * 把 hits 快照排序后刷到 UI。多个源几乎同时回来时合并成一次重排。
     * 新搜索会 cancel [publishJob]，这里靠 searchId 再挡一层串台。
     */
    private fun schedulePublish(keyword: String, sort: SearchSort, searchId: Int) {
        publishJob?.cancel()
        publishJob = viewModelScope.launch {
            delay(PUBLISH_DEBOUNCE_MS)
            if (_state.value.searchId != searchId) return@launch
            val snapshot = hitsMutex.withLock { hits.values.toList() }
            val sorted = withContext(Dispatchers.Default) {
                ordered(snapshot, keyword, sort)
            }
            if (_state.value.searchId != searchId) return@launch
            _state.update { it.copy(results = sorted) }
        }
    }

    // ---- 合并与排序 ----

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
     * 对快照排序。Collator 非线程安全，每次现场建一份；
     * 调用方先 [Collection.toList] 再丢进 Default，避免边 merge 边排。
     */
    private fun ordered(
        items: List<SearchHit>,
        keyword: String,
        sort: SearchSort,
    ): List<SearchHit> {
        val collator = Collator.getInstance(Locale.CHINA).apply { strength = Collator.PRIMARY }
        return when (sort) {
            SearchSort.RELEVANCE -> items.sortedWith(
                compareBy<SearchHit> { tier(it.result, keyword) }
                    .thenByDescending { it.origins.size },
            )
            SearchSort.WORD_COUNT -> items.sortedWith(
                compareByDescending<SearchHit> { wordCountOf(it) }
                    .thenBy(collator) { hit: SearchHit -> hit.result.title },
            )
            SearchSort.TITLE_PINYIN -> items.sortedWith(
                compareBy(collator) { hit: SearchHit -> hit.result.title.trim() }
                    .thenByDescending { it.origins.size },
            )
            SearchSort.AUTHOR_PINYIN -> items.sortedWith(
                compareBy(collator) { hit: SearchHit ->
                    hit.result.author?.trim().orEmpty().ifEmpty { "\uFFFF" }
                }.thenBy(collator) { hit: SearchHit -> hit.result.title },
            )
            SearchSort.UPDATE_TIME -> items.sortedWith(
                compareByDescending<SearchHit> { updateEpochOf(it) }
                    .thenBy(collator) { hit: SearchHit -> hit.result.title },
            )
        }
    }

    /**
     * 相关度排序：书名/作者与关键词完全相等 > 包含关键词 > 其余；
     * 同档内按「有这本书的书源数」降序 —— 越多书源都收录，越可能就是要找的那本。
     *
     * 从前是哪个书源先返回就排在前面，顺序完全由网络快慢决定，
     * 于是精确命中的书常被沉到列表底部，同一本书还会按书源数重复几十行。
     */
    private fun tier(r: SearchResult, keyword: String): Int = when {
        r.title == keyword || r.author == keyword -> 0
        r.title.contains(keyword) || r.author?.contains(keyword) == true -> 1
        else -> 2
    }

    /** 合并条目取各源最大字数；解不出的当 -1，降序时沉底 */
    private fun wordCountOf(hit: SearchHit): Long =
        hit.results.maxOf { parseWordCount(it.wordCount) }

    /** 合并条目取各源能解出的最新日期；解不出当 Long.MIN_VALUE，降序沉底 */
    private fun updateEpochOf(hit: SearchHit): Long =
        hit.results.maxOf { parseUpdateEpoch(it.kind, it.intro) }

    /**
     * 单个书源搜一页：网络/规则错误按「这个源没结果」降级，但**取消必须往外抛**。
     *
     * 从前这里是 `runCatching { … }.getOrNull()`，它连 CancellationException 一起吞。
     * 后果是被取代的那一轮不会就地停下，而是带着 null 继续往下跑 merge 和写状态 ——
     * 旧关键词的结果会串进刚 clear 过的 hits，翻页那一轮还会把过期的 page/hasMore 盖回去
     * （新搜索刚设成 page=1，僵尸协程一句 `page = next` 就把它推到 2）。
     */
    private suspend fun searchPage(rule: BookSourceRule, keyword: String, page: Int): SearchPage? =
        try {
            // 与换源单源超时对齐：吊源别占着 Semaphore 名额拖整轮搜索
            withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
                engine.search(rule, keyword, page = page)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    /** 还能继续翻页的书源（上一页 hasMore 为真的那些） */
    private var pagingRules: List<BookSourceRule> = emptyList()

    fun loadMore() {
        val s = _state.value
        if (s.searching || s.loadingMore || !s.hasMore || pagingRules.isEmpty()) return
        val keyword = s.query.trim()
        val next = s.page + 1
        val sort = s.sort
        pagingJob?.cancel()
        publishJob?.cancel()
        val searchId = s.searchId
        pagingJob = viewModelScope.launch {
            _state.value = _state.value.copy(loadingMore = true)
            val limiter = Semaphore(8)
            val still = pagingRules.map { rule ->
                async {
                    limiter.withPermit {
                        val page = searchPage(rule, keyword, page = next)
                        val gotNew = hitsMutex.withLock {
                            val before = hits.size
                            merge(page?.items.orEmpty())
                            hits.size > before
                        }
                        if (gotNew) schedulePublish(keyword, sort, searchId)
                        // 这一页没带来新书 = 这个源翻到头了（不少站点越界会一直回吐最后一页）
                        rule.takeIf { page?.hasMore == true && gotNew }
                    }
                }
            }.awaitAll().filterNotNull()
            pagingRules = still
            publishJob?.cancel()
            val snapshot = hitsMutex.withLock { hits.values.toList() }
            val sorted = withContext(Dispatchers.Default) {
                ordered(snapshot, keyword, sort)
            }
            if (_state.value.searchId == searchId) {
                _state.value = _state.value.copy(
                    results = sorted,
                    loadingMore = false,
                    page = next,
                    hasMore = still.isNotEmpty(),
                )
            }
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

    companion object {
        private const val SEARCH_TIMEOUT_MS = 30_000L
        private const val PUBLISH_DEBOUNCE_MS = 250L
    }
}

/** 「12万字」「1.5万」「12345字」→ 字数；解不出返回 -1 */
internal fun parseWordCount(raw: String?): Long {
    if (raw.isNullOrBlank()) return -1L
    val t = raw.trim().replace(",", "").replace("，", "").replace(" ", "")
    Regex("""([\d.]+)\s*亿""").find(t)?.groupValues?.getOrNull(1)?.toDoubleOrNull()?.let {
        return (it * 100_000_000L).toLong()
    }
    Regex("""([\d.]+)\s*万""").find(t)?.groupValues?.getOrNull(1)?.toDoubleOrNull()?.let {
        return (it * 10_000L).toLong()
    }
    Regex("""(\d+)""").find(t)?.groupValues?.getOrNull(1)?.toLongOrNull()?.let { return it }
    return -1L
}

/**
 * 从分类/简介里抠日期。Legado 搜索规则没有独立 updateTime，
 * 常见写法是 kind 里夹「2024-01-15」或「2024年1月15日」。
 */
internal fun parseUpdateEpoch(vararg blobs: String?): Long {
    var best = Long.MIN_VALUE
    for (blob in blobs) {
        if (blob.isNullOrBlank()) continue
        for (m in DATE_YMD.findAll(blob)) {
            val y = m.groupValues[1].toIntOrNull() ?: continue
            val mo = m.groupValues[2].toIntOrNull() ?: continue
            val d = m.groupValues[3].toIntOrNull() ?: continue
            if (y !in 1990..2100 || mo !in 1..12 || d !in 1..31) continue
            val cal = Calendar.getInstance().apply {
                set(Calendar.MILLISECOND, 0)
                set(y, mo - 1, d, 0, 0, 0)
            }
            best = maxOf(best, cal.timeInMillis)
        }
    }
    return best
}

private val DATE_YMD = Regex("""(\d{4})\s*[-/.年]\s*(\d{1,2})\s*[-/.月]\s*(\d{1,2})""")
