package com.radium.inkwell.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radium.inkwell.core.source.BookSourceEngine
import com.radium.inkwell.core.source.BookSourceRule
import com.radium.inkwell.core.source.SearchPage
import com.radium.inkwell.core.source.SearchResult
import com.radium.inkwell.data.repo.AutoSourceSwitcher
import com.radium.inkwell.data.repo.BookRepository
import com.radium.inkwell.data.repo.BookSourceRepository
import com.radium.inkwell.data.repo.NetBookRepository
import com.radium.inkwell.ui.components.MessageBus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
    /**
     * 打开详情 / 加入书架时优先用的源。[results] 按书源健康度排过之后即第一条；
     * 和 [result] 分开，是因为副标题要字数，打开却要快的活源。
     */
    val preferred: SearchResult get() = results.first()
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
    /**
     * 进了详情页后后台搜索被掐掉。不自动接着搜 —— 返回列表后由用户点「继续搜索」。
     * 自动恢复会跟详情页抢网，刚打开的书又开始转圈。
     */
    val searchPaused: Boolean = false,
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
    private var addJob: Job? = null
    /** 防抖发布：每个源回来就全量重排+刷 UI 会把主线程打满 */
    private var publishJob: Job? = null
    private val hitsMutex = Mutex()

    /**
     * 本轮搜索时的书源健康度。打开详情 / 加入时按它挑源；
     * 用 [BookSourceRepository.getEnabledForSwitch] 一次拿齐，避免列表点一下再查一遍库。
     */
    private var sourceMeta: List<BookSourceRepository.EnabledSource> = emptyList()

    private val pendingIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val moreAcc = java.util.Collections.synchronizedList(mutableListOf<BookSourceRule>())
    private var roundRules: List<BookSourceRule> = emptyList()
    private var inFlightPage: Int = 1
    private val doneCounter = AtomicInteger(0)
    /** 每一轮搜索 +1。被 cancel 的旧协程 finally 里不许再动下一轮的 pending / moreAcc */
    private var roundGen: Int = 0

    /** 暂停时拍下还没跑完的源；返回列表后 [resumeSearch] 接着跑，不重开一轮、不清已有结果 */
    private var paused: PausedSearch? = null

    private data class PausedSearch(
        val remaining: List<BookSourceRule>,
        val alreadyMore: List<BookSourceRule>,
        val fetchPage: Int,
        val keyword: String,
        val searchId: Int,
        val sort: SearchSort,
    )

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
        paused = null
        searchJob?.cancel()
        sortJob?.cancel()
        publishJob?.cancel()
        // 上一轮的「加载更多」还在飞时开新搜索：不掐掉它，它回来会把旧关键词的结果 merge 进
        // 刚清空的 hits，串进新搜索列表
        pagingJob?.cancel()
        searchJob = viewModelScope.launch {
            val enabled = sourceRepo.getEnabledForSwitch()
            sourceMeta = enabled
            val rules = enabled.map { it.rule }.filter { it.search != null }
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
            moreAcc.clear()
            pagingRules = emptyList()
            doneCounter.set(0)
            val sort = _state.value.sort
            val searchId = _state.value.searchId + 1
            inFlightPage = 1
            _state.value = _state.value.copy(
                searching = true,
                searchPaused = false,
                results = emptyList(),
                sourceCount = rules.size, doneCount = 0,
                page = 1, hasMore = false, loadingMore = false,
                searchId = searchId,
            )
            searchRound(rules, keyword, page = 1, sort, searchId, countProgress = true)
            finishRound(searchId, keyword, sort, fetchPage = 1)
        }
    }

    /**
     * 进详情页时掐掉还在飞的搜索。先拍下没跑完的源再 cancel ——
     * 协程的 finally 会把 pending 清掉，cancel 后再读就只剩空的，返回来没法继续。
     * 不自动 resume：详情页还在加载，后台接着搜会跟它抢网。
     */
    fun pauseSearch() {
        val s = _state.value
        if (!s.searching && !s.loadingMore) return
        val remaining = roundRules.filter { it.id in pendingIds }
        paused = PausedSearch(
            remaining = remaining,
            alreadyMore = moreAcc.toList(),
            fetchPage = inFlightPage,
            keyword = s.query.trim(),
            searchId = s.searchId,
            sort = s.sort,
        ).takeIf { remaining.isNotEmpty() }
        // 让还在飞的旧协程别再往 moreAcc / pending 里写；searchId 没变，已完成的 merge 仍可进 hits
        roundGen++
        searchJob?.cancel()
        pagingJob?.cancel()
        publishJob?.cancel()
        _state.update {
            it.copy(
                searching = false,
                loadingMore = false,
                searchPaused = remaining.isNotEmpty(),
                // 暂停期间不准翻页：pagingRules 可能还是上一轮残留，和当前关键词对不上
                hasMore = false,
            )
        }
        val searchId = s.searchId
        val keyword = s.query.trim()
        val sort = s.sort
        viewModelScope.launch {
            val snapshot = hitsMutex.withLock { hits.values.toList() }
            val sorted = withContext(Dispatchers.Default) { ordered(snapshot, keyword, sort) }
            if (_state.value.searchId != searchId) return@launch
            _state.update { it.copy(results = sorted) }
        }
    }

    fun resumeSearch() {
        val p = paused ?: return
        paused = null
        if (p.fetchPage == 1) {
            searchJob?.cancel()
            searchJob = viewModelScope.launch { resumeRound(p) }
        } else {
            pagingJob?.cancel()
            pagingJob = viewModelScope.launch { resumeRound(p) }
        }
    }

    private suspend fun resumeRound(p: PausedSearch) {
        moreAcc.clear()
        moreAcc.addAll(p.alreadyMore)
        inFlightPage = p.fetchPage
        _state.update {
            it.copy(
                searching = p.fetchPage == 1,
                loadingMore = p.fetchPage > 1,
                searchPaused = false,
            )
        }
        searchRound(p.remaining, p.keyword, p.fetchPage, p.sort, p.searchId, countProgress = p.fetchPage == 1)
        finishRound(p.searchId, p.keyword, p.sort, p.fetchPage)
    }

    /** 打开详情前把多源结果按健康度排好，预览页用第一条当代表源 */
    fun rankedResults(results: List<SearchResult>): List<SearchResult> =
        AutoSourceSwitcher.rankSearchResults(results, sourceMeta)

    private suspend fun searchRound(
        rules: List<BookSourceRule>,
        keyword: String,
        page: Int,
        sort: SearchSort,
        searchId: Int,
        countProgress: Boolean,
    ) = coroutineScope {
        val gen = ++roundGen
        roundRules = rules.toList()
        pendingIds.clear()
        pendingIds.addAll(roundRules.map { it.id })
        val limiter = Semaphore(8)
        roundRules.map { rule ->
            async {
                try {
                    limiter.withPermit {
                        val pageResult = searchPage(rule, keyword, page)
                        if (_state.value.searchId != searchId) return@withPermit
                        if (countProgress) {
                            hitsMutex.withLock { merge(pageResult?.items.orEmpty()) }
                            if (gen != roundGen) return@withPermit
                            val d = doneCounter.incrementAndGet()
                            _state.update { it.copy(doneCount = d) }
                            schedulePublish(keyword, sort, searchId)
                            if (pageResult?.hasMore == true) moreAcc += rule
                        } else {
                            val gotNew = hitsMutex.withLock {
                                val before = hits.size
                                merge(pageResult?.items.orEmpty())
                                hits.size > before
                            }
                            if (gen != roundGen) return@withPermit
                            if (gotNew) schedulePublish(keyword, sort, searchId)
                            if (pageResult?.hasMore == true && gotNew) moreAcc += rule
                        }
                    }
                } finally {
                    if (gen == roundGen) pendingIds.remove(rule.id)
                }
            }
        }.awaitAll()
    }

    private suspend fun finishRound(
        searchId: Int,
        keyword: String,
        sort: SearchSort,
        fetchPage: Int,
    ) {
        publishJob?.cancel()
        val snapshot = hitsMutex.withLock { hits.values.toList() }
        val sorted = withContext(Dispatchers.Default) { ordered(snapshot, keyword, sort) }
        val more = moreAcc.toList()
        pagingRules = more
        if (_state.value.searchId != searchId) return
        _state.value = _state.value.copy(
            results = sorted,
            searching = false,
            loadingMore = false,
            searchPaused = false,
            hasMore = more.isNotEmpty(),
            page = fetchPage,
            doneCount = doneCounter.get(),
        )
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
        val ranked = items.map { rankHit(it) }
        val collator = Collator.getInstance(Locale.CHINA).apply { strength = Collator.PRIMARY }
        return when (sort) {
            SearchSort.RELEVANCE -> ranked.sortedWith(
                compareBy<SearchHit> { tier(it.result, keyword) }
                    .thenByDescending { it.origins.size },
            )
            SearchSort.WORD_COUNT -> ranked.sortedWith(
                compareByDescending<SearchHit> { wordCountOf(it) }
                    .thenBy(collator) { hit: SearchHit -> hit.result.title },
            )
            SearchSort.TITLE_PINYIN -> ranked.sortedWith(
                compareBy(collator) { hit: SearchHit -> hit.result.title.trim() }
                    .thenByDescending { it.origins.size },
            )
            SearchSort.AUTHOR_PINYIN -> ranked.sortedWith(
                compareBy(collator) { hit: SearchHit ->
                    hit.result.author?.trim().orEmpty().ifEmpty { "\uFFFF" }
                }.thenBy(collator) { hit: SearchHit -> hit.result.title },
            )
            SearchSort.UPDATE_TIME -> ranked.sortedWith(
                compareByDescending<SearchHit> { updateEpochOf(it) }
                    .thenBy(collator) { hit: SearchHit -> hit.result.title },
            )
        }
    }

    private fun rankHit(hit: SearchHit): SearchHit {
        if (hit.results.size <= 1) return hit
        return SearchHit(AutoSourceSwitcher.rankSearchResults(hit.results, sourceMeta))
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
        if (s.searching || s.loadingMore || s.searchPaused || !s.hasMore || pagingRules.isEmpty()) return
        val keyword = s.query.trim()
        val next = s.page + 1
        val sort = s.sort
        val rules = pagingRules.toList()
        pagingJob?.cancel()
        publishJob?.cancel()
        val searchId = s.searchId
        pagingJob = viewModelScope.launch {
            moreAcc.clear()
            inFlightPage = next
            _state.value = _state.value.copy(loadingMore = true)
            searchRound(rules, keyword, next, sort, searchId, countProgress = false)
            finishRound(searchId, keyword, sort, next)
        }
    }

    fun addToShelf(hit: SearchHit) {
        addJob?.cancel()
        addJob = viewModelScope.launch {
            val ranked = rankedResults(hit.results)
            val rowUrl = hit.result.bookUrl
            _state.update { it.copy(addingUrl = rowUrl) }
            try {
                var lastMsg: String? = null
                for (r in ranked.take(MAX_ADD_TRIES)) {
                    val rule = sourceRepo.getRule(r.sourceId)
                    if (rule == null) {
                        lastMsg = "书源不存在"
                        continue
                    }
                    val outcome = try {
                        withTimeoutOrNull(ADD_TIMEOUT_MS) { netBookRepo.addToShelf(r, rule) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Result.failure(e)
                    }
                    val cancelled = outcome?.exceptionOrNull() as? CancellationException
                    if (cancelled != null) throw cancelled
                    when {
                        outcome == null -> lastMsg = "书源响应超时"
                        outcome.isSuccess -> {
                            messages.emit("已加入书架")
                            return@launch
                        }
                        else -> lastMsg = outcome.exceptionOrNull()?.message
                    }
                }
                messages.emit("加入失败: ${lastMsg?.take(80) ?: "没有能用的书源"}")
            } finally {
                _state.update { s -> if (s.addingUrl == rowUrl) s.copy(addingUrl = null) else s }
            }
        }
    }

    companion object {
        private const val SEARCH_TIMEOUT_MS = 30_000L
        private const val PUBLISH_DEBOUNCE_MS = 250L
        /** 详情+目录两个请求；三个源串起来不超过一分钟 */
        private const val ADD_TIMEOUT_MS = 20_000L
        private const val MAX_ADD_TRIES = 3
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
