package com.radium.inkwell.ui.rss

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radium.inkwell.core.source.rss.RssSort
import com.radium.inkwell.core.source.rss.RssSourceRule
import com.radium.inkwell.data.repo.RssFeed
import com.radium.inkwell.data.repo.RssFeedRepository
import com.radium.inkwell.data.repo.RssRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 订阅页上的一个源（chip 一个） */
data class RssTimelineSource(val id: String, val name: String, val sorts: List<RssSort>)

data class RssTimelineUiState(
    /** 开着的、有文章列表的订阅源；null = 还没从库里读回来（先什么都不画，别闪一下空态） */
    val sources: List<RssTimelineSource>? = null,
    /** 开着的订阅源里有网页类的：它们没有文章列表，不进时间线。全是网页类时空态得说清楚 */
    val hasWebSources: Boolean = false,
    /** 只看哪个源；null = 全部 */
    val selectedSource: String? = null,
    val sortIndex: Int = 0,
    val sections: List<TimelineSection> = emptyList(),
    /** 缓存从库里读回来过没有（第一次读回来之前不画空态） */
    val articlesLoaded: Boolean = false,
    /** 当前这几条列表有正在取的（或马上要取的） */
    val fetching: Boolean = false,
    /** 下拉刷新的转圈：只跟着用户自己拉的那一次 */
    val userRefreshing: Boolean = false,
    /** 当前这几条列表里取失败的：源名 → 原因 */
    val failures: List<Pair<String, String>> = emptyList(),
) {
    val selected: RssTimelineSource? get() = sources?.firstOrNull { it.id == selectedSource }
    val isEmpty: Boolean get() = sections.isEmpty()
}

/**
 * 订阅页：所有开着的订阅源的文章排成一条（「全部」），或者只看一个源（它有分类就再选分类）。
 *
 * 进页先显示库里缓存的，同时把这次打开 App 还没取过的列表取一遍；取过的不再取，下拉才重取（见 [RssFeedRepository]）。
 * 「全部」里每个源只取第一个分类 —— 分类多的源（书单、排行）全取一遍要好几倍的请求，时间线也会被一个源刷屏。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RssTimelineViewModel(
    initialSourceId: String?,
    private val repo: RssRepository,
    private val feeds: RssFeedRepository,
) : ViewModel() {

    private data class Selection(val sourceId: String?, val sortIndex: Int)

    /** 开着的源解析好的规则（解析 JSON 不便宜，订阅源变了才重算） */
    private data class Catalog(
        val sources: List<RssTimelineSource>,
        val rules: Map<String, RssSourceRule>,
        val hasWeb: Boolean,
    )

    private val selection = MutableStateFlow(Selection(initialSourceId, 0))

    private val catalog: StateFlow<Catalog?> = repo.sources
        .map { list ->
            val parsed = list.filter { it.enabled }.mapNotNull { e -> repo.ruleOf(e)?.let { e to it } }
            val feedRules = parsed.filter { (_, r) -> !r.singleUrl }
            Catalog(
                sources = feedRules.map { (e, r) -> RssTimelineSource(e.id, e.name, r.sortsOrDefault()) },
                rules = feedRules.associate { (e, r) -> e.id to r },
                hasWeb = parsed.any { (_, r) -> r.singleUrl },
            )
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** 当前看的是什么：哪几条列表、是不是只看一个源（选中的源关掉、删掉了就回到「全部」） */
    private data class View(val feeds: List<RssFeed>, val single: Boolean) {
        val keys: List<String> get() = feeds.map { it.key }
    }

    private val view: StateFlow<View?> = combine(catalog.filterNotNull(), selection) { c, sel ->
        val one = sel.sourceId?.let { c.rules[it] }
        if (one != null) {
            View(listOfNotNull(one.sortsOrDefault().getOrNull(sel.sortIndex)?.let { RssFeed(one, it) }), single = true)
        } else {
            View(c.sources.mapNotNull { s -> c.rules[s.id]?.let { r -> RssFeed(r, r.sortsOrDefault().first()) } }, single = false)
        }
    }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** 正在取的列表（feed key）；取的过程跨越切换 chip，不因为换了看哪个就掐掉 */
    private val inFlight = MutableStateFlow<Set<String>>(emptySet())

    /** 取失败的列表：feed key → 原因。成功一次就去掉 */
    private val failed = MutableStateFlow<Map<String, String>>(emptyMap())

    private val userRefreshing = MutableStateFlow(false)

    private data class Articles(val feeds: List<String>, val sections: List<TimelineSection>)

    private val articles: StateFlow<Articles?> = view.filterNotNull()
        .flatMapLatest { v ->
            val keys = v.keys
            if (keys.isEmpty()) return@flatMapLatest flowOf(Articles(keys, emptyList()))
            val names = catalog.value?.sources?.associate { it.id to it.name }.orEmpty()
            // 表按整张失效：只看一个源时别的源写库、点开一篇标成读过（同一篇在别的分类里那行）也会再查一遍，
            // 查回来一模一样就别重排、别让整页重组
            feeds.observe(keys).distinctUntilChanged().map { rows -> Articles(keys, buildTimeline(rows, names, v.single)) }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private data class Status(val inFlight: Set<String>, val failed: Map<String, String>, val userRefreshing: Boolean)

    private val status = combine(inFlight, failed, userRefreshing) { a, b, c -> Status(a, b, c) }

    val state: StateFlow<RssTimelineUiState> =
        combine(catalog, selection, view, articles, status) { c, sel, v, arts, st ->
            val visible = v?.feeds
            val visibleKeys = v?.keys.orEmpty()
            val names = c?.sources?.associate { it.id to it.name }.orEmpty()
            RssTimelineUiState(
                sources = c?.sources,
                hasWebSources = c?.hasWeb == true,
                // 选中的源不在了（关掉、删掉）就回到「全部」；订阅源还没读回来时先照进来时的选
                selectedSource = if (v == null) sel.sourceId else sel.sourceId.takeIf { v.single },
                sortIndex = sel.sortIndex,
                // 换 chip 时新一组从库里读回来要几毫秒：这期间接着摆上一组，别整页先白一下
                sections = arts?.sections.orEmpty(),
                articlesLoaded = arts != null,
                // 这次还没取过、也没失败的算「要取」：进页第一帧取的协程还没起来，不算的话空缓存先闪一下「还没有文章」
                fetching = visibleKeys.any { it in st.inFlight || (it !in st.failed && !feeds.fetchedThisSession(it)) },
                userRefreshing = st.userRefreshing,
                failures = visible.orEmpty().mapNotNull { f ->
                    st.failed[f.key]?.let { reason -> (names[f.rule.id] ?: f.rule.name) to reason }
                },
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RssTimelineUiState(selectedSource = initialSourceId))

    init {
        // 看的东西一变（进页、切 chip、新开了一个源），把这次还没取过的取一遍
        viewModelScope.launch {
            view.filterNotNull().collect { v -> fetch(v.feeds, force = false) }
        }
    }

    fun selectSource(sourceId: String?) {
        if (sourceId == selection.value.sourceId) return
        selection.value = Selection(sourceId, 0)
    }

    fun selectSort(index: Int) {
        selection.update { it.copy(sortIndex = index) }
    }

    /** 下拉刷新：当前看的这几条全部重取 */
    fun refresh() {
        val list = view.value?.feeds ?: return
        viewModelScope.launch {
            userRefreshing.value = true
            try {
                fetch(list, force = true).join()
            } finally {
                userRefreshing.value = false
            }
        }
    }

    /** 「没更新上」那一行的重试：只重取失败的 */
    fun retryFailed() {
        val list = view.value?.feeds?.filter { it.key in failed.value } ?: return
        fetch(list, force = true)
    }

    /** 点开一篇：标成读过（标题变灰），返回文章页要的参数。全文不随参数走，文章页按 feed + articleKey 从库里取 */
    fun open(item: TimelineItem): RssArticleArgs {
        val row = item.row
        viewModelScope.launch { feeds.markRead(row.sourceId, row.articleKey) }
        return RssArticleArgs(
            sourceId = row.sourceId,
            title = row.title,
            link = row.link,
            pubDate = row.pubDate,
            feed = row.feed,
            articleKey = row.articleKey,
        )
    }

    /**
     * 取 [list] 里该取的那些。已经在取的不重复发（切走又切回来、下拉时自动取还没完），
     * 各条成功的去掉失败记号、失败的记下原因。
     */
    private fun fetch(list: List<RssFeed>, force: Boolean) = viewModelScope.launch {
        val todo = list.filter { f ->
            f.key !in inFlight.value && (force || !feeds.fetchedThisSession(f.key))
        }
        if (todo.isEmpty()) return@launch
        val keys = todo.map { it.key }.toSet()
        inFlight.update { it + keys }
        try {
            val errors = feeds.refresh(todo, force = true)
            failed.update { old ->
                (old - keys) + todo.mapNotNull { f -> errors[f.rule.id]?.let { f.key to it } }
            }
        } finally {
            inFlight.update { it - keys }
        }
    }
}
