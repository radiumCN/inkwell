package com.radium.inkwell.ui.reader

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radium.inkwell.core.source.BookSourceEngine
import com.radium.inkwell.core.source.SearchResult
import com.radium.inkwell.data.db.dao.BookSourceHitDao
import com.radium.inkwell.data.db.dao.ChapterDao
import com.radium.inkwell.data.db.entity.BookEntity
import com.radium.inkwell.data.db.entity.BookSourceHitEntity
import com.radium.inkwell.data.db.entity.BookType
import com.radium.inkwell.data.prefs.ReaderPrefs
import com.radium.inkwell.data.repo.BookRepository
import com.radium.inkwell.data.repo.BookSourceRepository
import com.radium.inkwell.data.repo.ChapterContentCache
import com.radium.inkwell.data.repo.LocalReaderBookSource
import com.radium.inkwell.data.repo.NetBookRepository
import com.radium.inkwell.data.repo.NetReaderBookSource
import com.radium.inkwell.data.repo.AutoSourceSwitcher
import com.radium.inkwell.data.repo.TitleMatch
import com.radium.inkwell.reader.api.FlipDirection
import com.radium.inkwell.reader.api.ReadPosition
import com.radium.inkwell.reader.api.ReaderBookSource
import com.radium.inkwell.reader.api.ReaderSettings
import com.radium.inkwell.reader.measure.TextMeasureFacade
import com.radium.inkwell.reader.paginate.LayoutSpec
import com.radium.inkwell.reader.paginate.Paginator
import com.radium.inkwell.reader.render.RenderablePage
import kotlinx.coroutines.Dispatchers
import com.radium.inkwell.core.model.ChapterContent
import com.radium.inkwell.core.model.charLength
import com.radium.inkwell.reader.render.ScrollChapter
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext
import com.radium.inkwell.core.model.ContentElement
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import com.radium.inkwell.core.source.Purifier
import kotlinx.coroutines.withTimeoutOrNull

/** [cached] 正文已在文件缓存里（网络书才有意义：本地书正文一直都在，UI 侧不展示这个状态） */
data class TocItem(val index: Int, val title: String, val cached: Boolean = false)

/** 全书搜索的一条命中 */
data class ChapterHit(
    val chapterIndex: Int,
    val chapterTitle: String,
    val charOffset: Int,
    val excerpt: String,
)

/** 命中处前后各截一段，给用户看上下文 */
private fun String.snippetAround(at: Int, length: Int, radius: Int = 18): String {
    val from = (at - radius).coerceAtLeast(0)
    val to = (at + length + radius).coerceAtMost(this.length)
    val prefix = if (from > 0) "…" else ""
    val suffix = if (to < this.length) "…" else ""
    return prefix + substring(from, to).replace('\n', ' ') + suffix
}

data class ReaderUiState(
    val bookTitle: String = "",
    val chapterTitle: String = "",
    val chapterIndex: Int = 0,
    val chapterCount: Int = 0,
    val pageInChapter: Int = 0,
    val pageCount: Int = 0,
    val page: RenderablePage? = null,
    /** 相邻页（翻页动画图层用）；邻章未分页完成时为 null（空白占位） */
    val prevPage: RenderablePage? = null,
    val nextPage: RenderablePage? = null,
    val hasPrev: Boolean = false,
    val hasNext: Boolean = false,
    val loading: Boolean = true,
    val error: String? = null,
    val toc: List<TocItem> = emptyList(),
    val settings: ReaderSettings = ReaderSettings(),
    val menuVisible: Boolean = false,
    val atBookEnd: Boolean = false,
    val isNetBook: Boolean = false,
    /** 当前书源名（换源面板顶部显示「当前」用）；本地书为空 */
    val currentSourceName: String = "",
    /** 长按选字是否开启 */
    val textSelectionEnabled: Boolean = true,
    /** 一次性提示（建了净化规则之类） */
    val toast: String? = null,
    /** 自动翻页中 */
    val autoFlipping: Boolean = false,
    /** 全书搜索：null=面板没开 */
    val searchResults: List<ChapterHit>? = null,
    val searching: Boolean = false,
    val searchProgress: Int = 0,
    /** 换源：null=未打开面板；空列表=搜索中/无结果 */
    val sourceCandidates: List<SearchResult>? = null,
    val searchingSources: Boolean = false,
    val changingSource: Boolean = false,
    /** 换源搜索进度：已回来的书源数 / 总数 */
    val sourcesDone: Int = 0,
    val sourcesTotal: Int = 0,
    /** 换源是否用作者卡人（同 Legado 的 changeSourceCheckAuthor） */
    val checkAuthor: Boolean = true,
    /** 正在自动换源（正文读不出来时自动找一个能读的源） */
    val autoChanging: Boolean = false,
    val autoChangeDone: Int = 0,
    val autoChangeTotal: Int = 0,
    /**
     * 刚自动换过源 → 顶部提示条 + 撤销。非空 = 新源的名字。
     *
     * 必须告诉用户：自动换源可能换到删减版/盗版，正文和原来不是一回事。
     * 静默换掉的话，用户只会觉得"这书怎么突然变了"，根本想不到是 App 干的。
     */
    val autoChangedTo: String? = null,
)

/**
 * 正文加载超时。
 *
 * 从前代码里根本没有"超时"这个概念 —— 只能干等 OkHttp 的 10s 读超时抛出来，
 * 而带 JS 渲染回退或多页正文的章节能拖到分钟级都不报错，用户就一直看着转圈。
 * 「书源网络很差」这个场景需要一个明确的放弃点。
 */
private class ContentTimeoutException : Exception("正文加载超时")

/**
 * 阅读会话：持有数据源、3 章分页窗口、页游标。
 * 进度真身 = (chapterIndex, charOffset)，页码只是当前排版下的投影。
 */
class ReaderViewModel(
    private val bookId: String,
    private val bookRepo: BookRepository,
    private val chapterDao: ChapterDao,
    private val hitDao: BookSourceHitDao,
    private val readerPrefs: ReaderPrefs,
    private val sourceRepo: BookSourceRepository,
    private val netBookRepo: NetBookRepository,
    private val engine: BookSourceEngine,
    private val contentCache: ChapterContentCache,
    private val appPrefs: com.radium.inkwell.data.prefs.AppPrefs,
    private val replaceRules: com.radium.inkwell.data.repo.ReplaceRuleRepository,
    private val autoSwitcher: com.radium.inkwell.data.repo.AutoSourceSwitcher,
) : ViewModel() {

    // 用已预热的真实设置播种首帧，避免进书先闪一帧浅色默认主题再切深色
    private val _state = MutableStateFlow(ReaderUiState(settings = readerPrefs.settings.value))
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    private var book: BookEntity? = null
    private var source: ReaderBookSource? = null
    private var position = ReadPosition(0, 0)

    // 排版环境（视口就绪后由 UI 注入）
    private var facade: TextMeasureFacade? = null
    private var spec: LayoutSpec? = null

    // 分页缓存：chapterIndex → 结果；spec 变化即整体作废
    private val paginated = LinkedHashMap<Int, Paginator.Result>()
    private var paginateJob: Job? = null
    private val engineMutex = Mutex()

    init {
        viewModelScope.launch {
            loadSession()
            readerPrefs.settings.collect { s ->
                _state.value = _state.value.copy(settings = s)
            }
        }
        viewModelScope.launch {
            appPrefs.textSelectionEnabled.collect { on ->
                _state.value = _state.value.copy(textSelectionEnabled = on)
            }
        }
        observeBookRules()
        observeToc()
    }

    /**
     * 目录跟着库走，而不是进书时拍一张快照。
     *
     * isCached 由 NetReaderBookSource 在正文落盘后 markCached 写库，是边读边变的；
     * 只在 loadSession 里 map 一次的话，目录里的缓存指示永远停在进书那一刻。
     */
    private fun observeToc() {
        viewModelScope.launch {
            chapterDao.observeByBook(bookId).collect { chapters ->
                _state.value = _state.value.copy(
                    toc = chapters.map { TocItem(it.index, it.title, it.isCached) },
                )
            }
        }
    }

    private suspend fun loadSession() {
        try {
            (source as? AutoCloseable)?.close()
            val b = bookRepo.getBook(bookId) ?: error("书籍不存在")
            book = b
            // 打开即已知晓：书架上那个"有 N 章新的"红点该灭了
            if (b.newChapterCount > 0) bookRepo.clearNewChapters(bookId)
            position = ReadPosition(b.readChapterIndex, b.readCharOffset)
            var chapters = chapterDao.getByBook(bookId)
            var currentSourceName = ""
            val src = if (b.type == BookType.NET) {
                val rule = b.sourceId?.let { sourceRepo.getRule(it) }
                    ?: error("书源不存在，请换源后阅读")
                currentSourceName = rule.name
                // WebDAV 同步下来的网络书只有书行、没有目录行（目录不进备份）。此时不补抓的话
                // 章节数为 0，ensurePaginated 静默返回 null，页面永远转圈。先拉一次目录落库。
                if (chapters.isEmpty()) {
                    netBookRepo.refreshToc(b, rule).getOrThrow()
                    chapters = chapterDao.getByBook(bookId)
                }
                NetReaderBookSource(bookId, chapters, rule, engine, contentCache, chapterDao)
            } else {
                withContext(Dispatchers.IO) { LocalReaderBookSource(bookRepo.openLocal(b)) }
            }
            source = src
            _state.value = _state.value.copy(
                bookTitle = b.title,
                chapterCount = src.chapterCount,
                chapterIndex = position.chapterIndex,
                toc = chapters.map { TocItem(it.index, it.title, it.isCached) },
                isNetBook = b.type == BookType.NET,
                currentSourceName = currentSourceName,
                error = null,
            )
            // 排版环境已就绪（换源重载场景）→ 立即重新分页
            if (facade != null && spec != null) {
                engineMutex.withLock {
                    paginated.clear()
                    showPosition(position)
                }
            }
        } catch (e: Exception) {
            _state.value = _state.value.copy(loading = false, error = e.message ?: "打开书籍失败")
            // 书源被删了、目录拉不到 —— 这两种进书就废的情况，Legado 原生也自动换源
            maybeAutoChangeSource(e.message ?: "打开书籍失败")
        }
    }

    /** 视口/设置变化时由 UI 调用；重建排版环境并按 charOffset 恢复位置 */
    fun onLayoutReady(facade: TextMeasureFacade, spec: LayoutSpec) {
        if (this.spec == spec && this.facade != null) return
        this.facade = facade
        this.spec = spec
        paginateJob?.cancel()
        paginateJob = viewModelScope.launch {
            engineMutex.withLock {
                paginated.clear()
                showPosition(position)
            }
        }
    }

    fun flip(direction: FlipDirection) {
        val s = _state.value
        val chapter = paginated[s.chapterIndex]?.chapter ?: return
        when (direction) {
            FlipDirection.FORWARD -> {
                if (s.pageInChapter + 1 < chapter.pages.size) {
                    showPage(s.chapterIndex, s.pageInChapter + 1)
                } else if (s.chapterIndex + 1 < s.chapterCount) {
                    gotoChapter(s.chapterIndex + 1, charOffset = 0)
                } else {
                    _state.value = s.copy(atBookEnd = true)
                }
            }
            FlipDirection.BACKWARD -> {
                if (s.pageInChapter > 0) {
                    showPage(s.chapterIndex, s.pageInChapter - 1)
                } else if (s.chapterIndex > 0) {
                    gotoChapter(s.chapterIndex - 1, charOffset = Int.MAX_VALUE) // 上一章最后一页
                }
            }
        }
    }

    fun gotoChapter(index: Int, charOffset: Int = 0) {
        val count = _state.value.chapterCount
        if (index !in 0 until count) return
        _state.value = _state.value.copy(loading = paginated[index] == null)
        viewModelScope.launch {
            engineMutex.withLock {
                showPosition(ReadPosition(index, charOffset))
            }
        }
    }

    fun seekToPercent(percent: Float) {
        val s = _state.value
        val chapter = paginated[s.chapterIndex]?.chapter ?: return
        val offset = (percent.coerceIn(0f, 1f) * chapter.totalChars).toInt()
        showPage(s.chapterIndex, chapter.pageIndexFor(offset))
    }

    fun toggleMenu() {
        // 一点屏幕就停自动翻页：想接着自动翻，再点一次「自动」就是了 ——
        // 反过来（点了没反应、页面还在自己翻）会让人以为应用卡死了
        if (_state.value.autoFlipping) {
            stopAutoFlip()
            return
        }
        _state.value = _state.value.copy(menuVisible = !_state.value.menuVisible)
    }

    /** UI 层按 App 主题模式 + 系统日夜算出后调这个，阅读纸张随之切日/夜槽 */
    fun setDarkActive(dark: Boolean) = readerPrefs.setDarkActive(dark)

    fun updateSettings(settings: ReaderSettings) {
        viewModelScope.launch { readerPrefs.update(settings) }
    }

    // ---------- 全书搜索 ----------

    private var searchJob: Job? = null

    /**
     * 全书搜索。逐章扫，命中就往外冒 —— 不等全书扫完再一次性出结果：
     * 几千章的书要抓好几分钟，而用户想找的那句话八成就在前几章。
     */
    fun searchInBook(keyword: String) {
        val key = keyword.trim()
        if (key.isEmpty()) return
        searchJob?.cancel()
        val src = source ?: return
        searchJob = viewModelScope.launch {
            _state.value = _state.value.copy(searchResults = emptyList(), searching = true, searchProgress = 0)
            val total = _state.value.chapterCount
            for (i in 0 until total) {
                if (!isActive) return@launch
                val content = runCatching { loadPurified(src, i) }.getOrNull()
                if (content != null) {
                    var offset = 0
                    for (el in content.elements) {
                        val text = when (el) {
                            is ContentElement.Paragraph -> el.text
                            is ContentElement.Heading -> el.text
                            else -> ""
                        }
                        var from = 0
                        while (true) {
                            val at = text.indexOf(key, from, ignoreCase = true)
                            if (at < 0) break
                            val hit = ChapterHit(
                                chapterIndex = i,
                                chapterTitle = src.chapterTitle(i) ?: "",
                                charOffset = offset + at,
                                excerpt = text.snippetAround(at, key.length),
                            )
                            _state.value = _state.value.copy(
                                searchResults = (_state.value.searchResults ?: emptyList()) + hit,
                            )
                            from = at + key.length
                        }
                        offset += el.charLength
                    }
                }
                _state.value = _state.value.copy(searchProgress = i + 1)
            }
            _state.value = _state.value.copy(searching = false)
        }
    }

    /** 只打开面板，不搜 —— 关键词还没输呢 */
    fun openSearchPanel() {
        _state.value = _state.value.copy(searchResults = emptyList(), searching = false, searchProgress = 0)
    }

    fun cancelSearch() {
        searchJob?.cancel()
        _state.value = _state.value.copy(searching = false)
    }

    fun dismissSearch() {
        searchJob?.cancel()
        _state.value = _state.value.copy(searchResults = null, searching = false)
    }

    fun gotoHit(hit: ChapterHit) {
        _state.value = _state.value.copy(searchResults = null, menuVisible = false)
        gotoChapter(hit.chapterIndex, hit.charOffset)
    }

    // ---------- 自动翻页 ----------

    private var autoFlipJob: Job? = null

    fun toggleAutoFlip() {
        if (_state.value.autoFlipping) {
            stopAutoFlip()
            return
        }
        _state.value = _state.value.copy(autoFlipping = true, menuVisible = false)
        autoFlipJob = viewModelScope.launch {
            while (true) {
                delay(_state.value.settings.autoFlipSeconds.coerceAtLeast(3) * 1000L)
                val s = _state.value
                // 到书末就停下，而不是每隔几秒弹一次"已经是最后一页了"
                if (!s.hasNext) {
                    stopAutoFlip()
                    break
                }
                flip(FlipDirection.FORWARD)
            }
        }
    }

    fun stopAutoFlip() {
        autoFlipJob?.cancel()
        autoFlipJob = null
        _state.value = _state.value.copy(autoFlipping = false)
    }

    /** 正文加载失败后重试：清掉分页缓存重来一遍（站点抽风、临时封 IP 都可能只是一次性的） */
    fun retry() {
        viewModelScope.launch {
            // 手动重试 = 用户想再给一次机会（可能网络恢复了）；自动换源的额度跟着复位
        autoChangeUsed = false
        _state.value = _state.value.copy(error = null, loading = true)
            engineMutex.withLock {
                paginated.clear()
                showPosition(position)
            }
        }
    }

    fun clearBookEnd() {
        _state.value = _state.value.copy(atBookEnd = false)
    }

    /** 书内净化规则变了 → 已分好的页全部作废，重排当前章 */
    private fun observeBookRules() {
        viewModelScope.launch {
            var first = true
            replaceRules.observeForBook(bookId).collect {
                if (first) { first = false; return@collect } // 首帧是初始值，别白重排一次
                engineMutex.withLock {
                    paginated.clear()
                    showPosition(position)
                }
            }
        }
    }

    // ---------- 长按选字 ----------

    fun setTextSelectionEnabled(on: Boolean) {
        viewModelScope.launch { appPrefs.setTextSelectionEnabled(on) }
    }

    /** 用选中的文字建一条只对本书生效的净化规则 */
    fun purifySelection(selected: String, replacement: String = "") {
        val text = selected.trim()
        if (text.isEmpty()) return
        viewModelScope.launch {
            replaceRules.createFromSelection(bookId, text, replacement)
            _state.value = _state.value.copy(
                error = null,
                toast = if (replacement.isEmpty()) "已删除「${text.take(12)}」" else "已替换「${text.take(12)}」",
            )
        }
    }

    fun clearToast() {
        _state.value = _state.value.copy(toast = null)
    }

    // ---------- 换源 ----------

    private var sourceSearchJob: Job? = null

    /** 书名已命中的全部结果（未按作者过滤）；作者开关一拨就地重筛，不必重搜 */
    private val rawCandidates = mutableListOf<SearchResult>()

    /**
     * 用书名在其他启用书源中并发搜索，结果**边搜边出**。
     *
     * 从前是 awaitAll 等所有书源跑完才一次性出结果：几百个源里只要有一个站点吊着不回，
     * 整个换源面板就一直转圈；而且没有超时，那个源可能永远不回。
     */
    fun searchOtherSources() {
        val b = book ?: return
        sourceSearchJob?.cancel()
        sourceSearchJob = viewModelScope.launch {
            val checkAuthor = appPrefs.changeSourceCheckAuthor.first()
            // 排序而不是照库里的原顺序：面板照样要搜完所有源（用户点换源就是想看全部候选），
            // 但让「以前有这本书的」「校验通过的」「响应快的」先占住那 8 个并发名额，
            // 能用的结果就会更早出现在面板上，不必等到最后。
            // 自动换源一直是这么做的（AutoSourceSwitcher.rank），手动面板从前却用
            // getEnabledRules()，那个版本把 checkStatus/respondTime 全丢了。
            val all = AutoSourceSwitcher.rank(
                sourceRepo.getEnabledForSwitch(),
                exclude = b.sourceId,
                bookHits = bookHits(),
            )
            rawCandidates.clear()
            _state.value = _state.value.copy(
                sourceCandidates = emptyList(),
                searchingSources = true,
                sourcesDone = 0,
                sourcesTotal = all.size,
                checkAuthor = checkAuthor,
            )
            // 与搜索页同样限流：几百个书源同时发请求只会集体超时/被限流
            val limiter = Semaphore(8)
            all.map { rule ->
                async {
                    limiter.withPermit {
                        // 三种结局必须分开记：搜到了 / 搜索跑通但没这本书 / 压根没搜成。
                        // 只有前两种是关于"这个源有没有这本书"的证据；超时和报错什么都没证明。
                        // 从前 getOrDefault(emptyList()) 把「报错」和「真的没有」压成同一个空结果 ——
                        // 照那样记忆的话，一次网络抖动就能把一个好源永久打入冷宫。
                        val outcome = withTimeoutOrNull(SOURCE_SEARCH_TIMEOUT_MS) {
                            runCatching { engine.search(rule, b.title).items }.fold(
                                onSuccess = { items ->
                                    val m = items.firstOrNull { titleMatches(it.title, b.title) }
                                    if (m != null) SearchOutcome.Hit(m) else SearchOutcome.Miss
                                },
                                onFailure = { SearchOutcome.Failed },
                            )
                        } ?: SearchOutcome.Failed
                        // 本轮搜索已被取代（重新点了换源）或被换源/关面板取消时，别再写状态：
                        // 协程取消只在挂起点抛出，越过 withTimeoutOrNull 的僵尸任务会把脏结果写进
                        // 新一轮刚清空的 rawCandidates、乱跳计数，还一起往 Main 刷 state 造成卡死。
                        // 也挡住了 runCatching 吞掉的 CancellationException。
                        coroutineContext.ensureActive()
                        when (outcome) {
                            is SearchOutcome.Hit -> {
                                rawCandidates += outcome.result
                                recordHit(rule.id, true, outcome.result.bookUrl)
                            }
                            SearchOutcome.Miss -> recordHit(rule.id, false, null)
                            SearchOutcome.Failed -> Unit // 什么都没证明，不写库
                        }
                        _state.value = _state.value.copy(
                            sourceCandidates = filtered(),
                            sourcesDone = _state.value.sourcesDone + 1,
                        )
                    }
                }
            }.awaitAll()
            _state.value = _state.value.copy(searchingSources = false)
        }
    }

    /** 换源搜索单个源的三种结局。**别退化成可空类型** —— 那正是从前把「报错」和「没这本书」混为一谈的原因 */
    private sealed interface SearchOutcome {
        data class Hit(val result: SearchResult) : SearchOutcome
        /** 搜索跑通了，但这个源确实没这本书 —— 可信，值得记 */
        data object Miss : SearchOutcome
        /** 超时/报错。什么都没证明，不记 */
        data object Failed : SearchOutcome
    }

    /** sourceId → 这本书在该源搜到过没有；没有条目 = 没搜过 */
    private suspend fun bookHits(): Map<String, Boolean> =
        runCatching { hitDao.getByBook(bookId).associate { it.sourceId to it.hit } }
            .getOrDefault(emptyMap())

    private fun recordHit(sourceId: String, hit: Boolean, bookUrl: String?) {
        // 记忆是纯粹的加速手段，写失败了不该影响换源本身 —— 顶多下次少一点先验
        viewModelScope.launch(NonCancellable) {
            runCatching {
                hitDao.upsert(
                    BookSourceHitEntity(
                        bookId = bookId,
                        sourceId = sourceId,
                        hit = hit,
                        bookUrl = bookUrl,
                        checkedAt = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }

    /** 拨动「匹配作者」：就地重筛已搜到的结果，不重新发请求 */
    fun setCheckAuthor(on: Boolean) {
        viewModelScope.launch { appPrefs.setChangeSourceCheckAuthor(on) }
        _state.value = _state.value.copy(checkAuthor = on)
        _state.value = _state.value.copy(sourceCandidates = filtered())
    }

    private fun filtered(): List<SearchResult> {
        val author = book?.author.orEmpty()
        return if (!_state.value.checkAuthor) rawCandidates.toList()
        else rawCandidates.filter { authorMatches(it.author, author) }
    }

    // 书名/作者判定见 TitleMatch —— 手动换源与自动换源必须用同一套标准，
    // 各写一份迟早写出分歧（列表里明明列着的源，自动换源却说找不到）
    private fun titleMatches(candidate: String, want: String) = TitleMatch.matches(candidate, want)

    private fun authorMatches(candidate: String?, want: String) =
        TitleMatch.authorMatches(candidate, want)

    fun applyChangeSource(candidate: SearchResult) {
        val b = book ?: return
        // 正在换源就别再受理下一次点击，避免同一本书的 changeSource 交错执行
        if (_state.value.changingSource) return
        // 选定了源，换源搜索就该停 —— 否则它每搜完一个源就把 sourceCandidates 写回非空，
        // 面板关不掉、还一直显示「搜索中」，直到几百个源全跑完。
        sourceSearchJob?.cancel()
        viewModelScope.launch {
            _state.value = _state.value.copy(
                changingSource = true,
                autoChanging = false,
                searchingSources = false,
            )
            // 用户亲自选了源：掐掉可能还在跑的自动换源，否则它探测成功后会把用户的选择覆盖掉
            autoChangeJob?.cancel()
            autoChangeUsed = true
            // 旧源的预取还在飞：清完缓存后它们回来会把旧正文写进新源的缓存目录
            prefetchJob?.cancel()
            val rule = sourceRepo.getRule(candidate.sourceId)
            if (rule == null) {
                _state.value = _state.value.copy(changingSource = false, error = "书源不存在")
                return@launch
            }
            netBookRepo.changeSource(b, candidate, rule)
                .onSuccess {
                    _state.value = _state.value.copy(
                        changingSource = false,
                        sourceCandidates = null,
                        menuVisible = false,
                        loading = true,
                        // 换源多半就是为了绕开上一个源的报错；不清掉的话新源加载成功了
                        // 还挂着旧错误，用户以为换源没生效
                        error = null,
                    )
                    // 分页缓存由 loadSession 在互斥锁内清掉 —— 在这里清会与在飞的预加载抢
                    loadSession()
                }
                .onFailure {
                    _state.value = _state.value.copy(
                        changingSource = false,
                        error = "换源失败: ${it.message}",
                    )
                }
        }
    }

    // ---------- 自动换源 ----------

    /** 换源前的现场：撤销要靠它原样退回去 */
    private data class UndoSnapshot(
        val sourceId: String,
        val bookUrl: String,
        val anchor: NetBookRepository.ReadAnchor,
    )

    private var undoSnapshot: UndoSnapshot? = null
    private var autoChangeJob: Job? = null

    /**
     * 一次阅读会话最多自动换一次。
     *
     * 探测时已经把这一章的正文真抓下来过了 —— 换过去还失败，说明问题不在书源
     * （网断了、规则被净化规则吃空了……），再换一个只会把用户在几个源之间反复甩，
     * 而且每换一次就清一次正文缓存。到此为止，把选择权交回给用户。
     */
    private var autoChangeUsed = false

    /**
     * 正文/目录读不出来 → 去找一个真能读的源。
     *
     * 必须**异步启动**：调用点（ensurePaginated / ensureScroll）是在 engineMutex 里跑的，
     * 而换完源要走 loadSession，它也要拿这把锁 —— 同步调用就是自锁。
     */
    private fun maybeAutoChangeSource(cause: String) {
        val b = book ?: return
        if (b.type != BookType.NET) return // 本地书没有"源"可换
        if (autoChangeUsed || autoChangeJob?.isActive == true) return

        autoChangeJob = viewModelScope.launch {
            if (!appPrefs.autoChangeSource.first()) return@launch
            autoChangeUsed = true

            val chapterTitle = chapterDao.get(bookId, position.chapterIndex)?.title
            _state.value = _state.value.copy(
                autoChanging = true,
                autoChangeDone = 0,
                autoChangeTotal = 0,
            )

            val probe = autoSwitcher.findWorkingSource(
                title = b.title,
                author = b.author.orEmpty(),
                exclude = b.sourceId,
                target = AutoSourceSwitcher.Target(position.chapterIndex, chapterTitle),
                checkAuthor = appPrefs.changeSourceCheckAuthor.first(),
                bookHits = bookHits(),
                onProgress = { done, total ->
                    _state.value = _state.value.copy(autoChangeDone = done, autoChangeTotal = total)
                },
            )

            if (probe == null) {
                _state.value = _state.value.copy(
                    autoChanging = false,
                    loading = false,
                    error = "$cause\n自动换源失败：其他书源也读不出这一章",
                )
                return@launch
            }

            // 旧源的预取还在天上飞。不掐掉的话，changeSource 清完缓存之后它们才回来，
            // 会把**旧源的正文**写进新源的缓存目录，并给新目录的同序号章节打上"已缓存"。
            prefetchJob?.cancel()

            val snapshot = b.sourceId?.let { sid ->
                b.bookUrl?.let { url ->
                    UndoSnapshot(sid, url, NetBookRepository.ReadAnchor(position.chapterIndex, position.charOffset))
                }
            }

            netBookRepo.changeSource(b, probe.result, probe.rule)
                .onSuccess {
                    undoSnapshot = snapshot
                    _state.value = _state.value.copy(
                        autoChanging = false,
                        loading = true,
                        error = null,
                        autoChangedTo = probe.rule.name,
                    )
                    loadSession()
                }
                .onFailure {
                    _state.value = _state.value.copy(
                        autoChanging = false,
                        loading = false,
                        error = "$cause\n自动换源失败: ${it.message}",
                    )
                }
        }
    }

    /**
     * 撤销自动换源，退回原来的源和原来读到的那个字。
     *
     * 自动换源可能换到删减版或另一个译本 —— 正文跟原来根本不是一回事。用户得有一键退回的路，
     * 否则"帮了倒忙"就成了不可逆的。
     */
    fun undoAutoChange() {
        val snap = undoSnapshot ?: return
        val b = book ?: return
        viewModelScope.launch {
            val rule = sourceRepo.getRule(snap.sourceId)
            if (rule == null) {
                undoSnapshot = null
                _state.value = _state.value.copy(
                    autoChangedTo = null,
                    toast = "原书源已不存在，无法退回",
                )
                return@launch
            }
            _state.value = _state.value.copy(changingSource = true)
            prefetchJob?.cancel()

            val back = SearchResult(
                title = b.title,
                bookUrl = snap.bookUrl,
                author = b.author,
                sourceId = rule.id,
                sourceName = rule.name,
            )
            netBookRepo.changeSource(b, back, rule, restore = snap.anchor)
                .onSuccess {
                    undoSnapshot = null
                    // autoChangeUsed 保持 true：用户明确表示要待在这个源上。
                    // 放开的话，回来立刻又读不出正文，转头就被自动换走 —— 撤销等于没撤。
                    _state.value = _state.value.copy(
                        changingSource = false,
                        autoChangedTo = null,
                        loading = true,
                        error = null,
                    )
                    loadSession()
                }
                .onFailure {
                    _state.value = _state.value.copy(
                        changingSource = false,
                        toast = "退回失败: ${it.message}",
                    )
                }
        }
    }

    /** 用户看到了提示条、不想撤销 */
    fun dismissAutoChanged() {
        undoSnapshot = null
        _state.value = _state.value.copy(autoChangedTo = null)
    }

    fun dismissSourcePanel() {
        sourceSearchJob?.cancel()
        rawCandidates.clear()
        _state.value = _state.value.copy(sourceCandidates = null, searchingSources = false)
    }

    // ---------- 内部 ----------

    /** 定位到指定位置：必要时加载+分页该章，并预取相邻章 */
    private suspend fun showPosition(target: ReadPosition) {
        // 这条永远是"用户在等的那一章"（进书、翻章、跳目录、重试都走这里），预取不走这里
        val result = ensurePaginated(target.chapterIndex, userFacing = true) ?: run {
            // ensurePaginated 的前置守卫是静默返回 null 的。其中"排版环境还没就绪"
            // （source/facade/spec 为 null）属于正常启动时序 —— loadSession/onLayoutReady
            // 随后会再来一次，这里报错只会闪一下假错误。
            // 但环境已就绪却仍拿不到结果（如目录为空导致章号越界），就是真读不出来：
            // 必须让 loading 落地，否则又是一个"永远转圈且没有出口"。
            if (source != null && facade != null && spec != null && _state.value.error == null) {
                Log.w(TAG, "第 ${target.chapterIndex} 章取不到分页结果，且排版环境已就绪")
                _state.value = _state.value.copy(
                    loading = false,
                    error = "章节加载失败：第 ${target.chapterIndex + 1} 章读不出来",
                )
            }
            return
        }
        val pageIdx = if (target.charOffset == Int.MAX_VALUE) {
            result.chapter.pages.lastIndex
        } else {
            result.chapter.pageIndexFor(target.charOffset)
        }
        showPage(target.chapterIndex, pageIdx, keepOffset = target.charOffset)
        preloadNeighbors(target.chapterIndex)
    }

    /**
     * 取正文并应用书内净化。
     *
     * 书内净化在这里做、而不是抓取时：用户在阅读页长按选中一句话建规则，指望的是眼前
     * 这一页立刻干净 —— 而这一页早就缓存好了，抓取时的净化再也不会跑到它头上。
     */
    private suspend fun loadPurified(src: ReaderBookSource, chapterIndex: Int): ChapterContent {
        // 慢到这个份上的源基本没救（正常源 1-3 秒）。用 withTimeoutOrNull 而不是 withTimeout：
        // 后者抛的是 CancellationException，会被下游的 catch(Exception) 吞掉，还会跟"协程被正常
        // 取消"混为一谈 —— 翻页时取消上一次加载也会被当成超时，进而触发自动换源。
        val raw = withTimeoutOrNull(CONTENT_TIMEOUT_MS) { src.loadChapter(chapterIndex) }
            ?: throw ContentTimeoutException()
        val purifier = Purifier.lenient(replaceRules.purifyForBook(bookId))
        return if (purifier.isEmpty) raw
        else raw.copy(elements = purifier.apply(raw.elements))
    }

    // ---------- 滚动模式 ----------

    /**
     * 滚动模式是**另一条渲染路径**，不复用分页结果：页与页堆叠起来，每屏底部都会留一道
     * 参差的空隙（分页器按整行断页，剩多少空白取决于这一屏排了几行）。
     * 这里给分页器一个"高得放得下整章"的视口，它就只产出一页 —— 那一页的 items
     * 正好是带 y 偏移的全部元素，字体/行距/缩进/对齐仍与翻页模式同一套逻辑。
     */
    private val scrollCache = LinkedHashMap<Int, ScrollChapter>()

    /** [userFacing] 含义同 [ensurePaginated]：由调用方声明，别靠 position 猜 */
    private suspend fun ensureScroll(chapterIndex: Int, userFacing: Boolean = false): ScrollChapter? {
        scrollCache[chapterIndex]?.let { return it }
        if (chapterIndex !in 0 until _state.value.chapterCount) return null
        val src = source ?: return null
        val facade = facade ?: return null
        val baseSpec = spec ?: return null
        return try {
            val content = loadPurified(src, chapterIndex)
            val title = src.chapterTitle(chapterIndex) ?: ""
            val tall = baseSpec.copy(
                viewportHeightPx = SCROLL_VIEWPORT_PX,
                headerHeightPx = 0f,
                footerHeightPx = 0f,
            )
            val result = withContext(Dispatchers.Default) {
                Paginator(facade).paginate(chapterIndex, title, content, tall)
            }
            val page = result.chapter.pages.firstOrNull() ?: return null

            // elementIndex → 章内字符偏移。元素表与 Paginator 里一致：标题在最前，正文依次跟上
            val offsets = HashMap<Int, Int>()
            var acc = 0
            offsets[0] = 0
            acc += title.length
            content.elements.forEachIndexed { i, el ->
                offsets[i + 1] = acc
                acc += el.charLength
            }

            ScrollChapter(
                chapterIndex = chapterIndex,
                title = title,
                items = page.items,
                measured = result.measured,
                charOffsets = offsets,
            ).also {
                scrollCache[chapterIndex] = it
                trimScrollWindow(center = position.chapterIndex, alsoKeep = chapterIndex)
            }
        } catch (e: CancellationException) {
            // 翻页/换视口取消了上一次加载，不是"章节读不出来"。吞掉当失败会误触发自动换源。
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "第 $chapterIndex 章滚动排版失败（userFacing=$userFacing）", e)
            if (userFacing) {
                _state.value = _state.value.copy(loading = false, error = "章节加载失败: ${e.message}")
                maybeAutoChangeSource("章节加载失败: ${e.message}")
            }
            null
        }
    }

    /**
     * 只留当前章前后各一章：一章的 TextLayoutResult 很占内存，整本书攒下来会 OOM。
     * alsoKeep 是刚排好的那一章 —— 跳章时 position 还停在旧章，只按旧章裁的话，
     * 刚排好的目标章会被当场剔掉。
     */
    private fun trimScrollWindow(center: Int, alsoKeep: Int) {
        val keep = setOf(center - 1, center, center + 1, alsoKeep)
        val drop = scrollCache.keys.filterNot { it in keep }
        drop.forEach { scrollCache.remove(it) }
        _scrollChapters.value = scrollCache.values.sortedBy { it.chapterIndex }
    }

    private val _scrollChapters = MutableStateFlow<List<ScrollChapter>>(emptyList())
    val scrollChapters: StateFlow<List<ScrollChapter>> = _scrollChapters.asStateFlow()

    /** 进入滚动模式 / 跳章时：把当前章及邻章排好 */
    fun prepareScroll(center: Int = position.chapterIndex) {
        viewModelScope.launch {
            engineMutex.withLock {
                // center 是用户正在看的那一章；上下邻章只是预排，失败不该打扰用户
                ensureScroll(center, userFacing = true) ?: return@withLock
                _state.value = _state.value.copy(loading = false, error = null)
            }
            engineMutex.withLock { ensureScroll(center - 1) }
            engineMutex.withLock { ensureScroll(center + 1) }
        }
    }

    /** 滚到某章某元素：记进度、必要时续排下一章 */
    fun onScrollTo(chapterIndex: Int, elementIndex: Int) {
        val chapter = scrollCache[chapterIndex] ?: return
        val offset = chapter.charOffsets[elementIndex] ?: 0
        position = ReadPosition(chapterIndex, offset)
        _state.value = _state.value.copy(
            chapterIndex = chapterIndex,
            chapterTitle = chapter.title,
        )
        saveProgress()
        prepareScroll(chapterIndex)
    }

    private fun renderable(result: Paginator.Result, pageIndex: Int): RenderablePage? {
        val page = result.chapter.pages.getOrNull(pageIndex) ?: return null
        return RenderablePage(
            spec = page,
            measured = result.measured,
            header = result.chapter.title,
            footer = "${pageIndex + 1}/${result.chapter.pages.size}",
        )
    }

    private fun neighborPages(chapterIndex: Int, pageIndex: Int): Pair<RenderablePage?, RenderablePage?> {
        val result = paginated[chapterIndex] ?: return null to null
        val prev = when {
            pageIndex > 0 -> renderable(result, pageIndex - 1)
            chapterIndex > 0 -> paginated[chapterIndex - 1]?.let { renderable(it, it.chapter.pages.lastIndex) }
            else -> null
        }
        val next = when {
            pageIndex + 1 < result.chapter.pages.size -> renderable(result, pageIndex + 1)
            chapterIndex + 1 < _state.value.chapterCount -> paginated[chapterIndex + 1]?.let { renderable(it, 0) }
            else -> null
        }
        return prev to next
    }

    /**
     * [keepOffset] 定位时保留的原始字符偏移。
     *
     * 不保留会丢进度：showPage 默认把 position 吸附到所在页的**起始**偏移，这在分页稳定时
     * 无损。可重进阅读页时视口要测两次（系统栏隐藏前 / 后），分页就跑了两遍 ——
     * 第一遍用较矮的视口，保存的偏移落进某一页后被吸附到那页开头（比原偏移更靠前），
     * 还顺手存回了库；第二遍视口变高、重新分页，再拿这个已经退化的偏移去定位，就退了一页。
     * 用户看到的正是「返回再进来，退回上一页」。
     */
    private fun showPage(chapterIndex: Int, pageIndex: Int, keepOffset: Int? = null) {
        val result = paginated[chapterIndex] ?: return
        val page = renderable(result, pageIndex) ?: return
        val offset = keepOffset
            ?.takeIf { it != Int.MAX_VALUE && it >= page.spec.startCharOffset && it < page.spec.endCharOffset }
            ?: page.spec.startCharOffset
        position = ReadPosition(chapterIndex, offset)
        val (prev, next) = neighborPages(chapterIndex, pageIndex)
        _state.value = _state.value.copy(
            chapterIndex = chapterIndex,
            chapterTitle = result.chapter.title,
            pageInChapter = pageIndex,
            pageCount = result.chapter.pages.size,
            page = page,
            prevPage = prev,
            nextPage = next,
            hasPrev = pageIndex > 0 || chapterIndex > 0,
            hasNext = pageIndex + 1 < result.chapter.pages.size ||
                chapterIndex + 1 < _state.value.chapterCount,
            loading = false,
            error = null,
            atBookEnd = false,
        )
        saveProgress()
        if (pageIndex == result.chapter.pages.lastIndex || pageIndex == 0) {
            preloadNeighbors(chapterIndex)
        }
    }

    /** 邻章分页完成后刷新相邻页图层，不动游标 */
    private fun refreshNeighbors() {
        val s = _state.value
        if (paginated[s.chapterIndex] == null) return
        val (prev, next) = neighborPages(s.chapterIndex, s.pageInChapter)
        _state.value = s.copy(prevPage = prev, nextPage = next)
    }

    /**
     * [userFacing] 这一章是不是用户正在等的那一章 —— **由调用方声明，不要再靠 position 去猜**。
     *
     * 从前这里判的是 `chapterIndex == position.chapterIndex`，本意没错：邻章预取失败不该弹错误、
     * 更不该触发自动换源。但 position 只在 showPage **成功之后**才更新，用户从第 N 章翻向 N+1 时
     * 它还停在 N —— 于是"用户正翻向的这一章失败了"被误判成"后台预取失败"，一并吞掉：
     * loading 停在 true、error 是 null，页面永远转圈，连「重试/换源」的出口都不会出现
     * （ReaderScreen 的错误分支只在 error != null 时才渲染）。
     * 这也正是"上一秒还在看，翻到下一章就一直转圈"的成因。
     */
    private suspend fun ensurePaginated(
        chapterIndex: Int,
        userFacing: Boolean = false,
    ): Paginator.Result? {
        paginated[chapterIndex]?.let { return it }
        if (chapterIndex !in 0 until (_state.value.chapterCount)) return null
        val src = source ?: return null
        val facade = facade ?: return null
        val spec = spec ?: return null
        return try {
            val content = loadPurified(src, chapterIndex)
            val title = src.chapterTitle(chapterIndex) ?: ""
            val result = withContext(Dispatchers.Default) {
                Paginator(facade).paginate(chapterIndex, title, content, spec)
            }
            paginated[chapterIndex] = result
            // 目录跳章时 position 还停在旧章：若只按旧章号裁窗口，刚分好页的目标章会被
            // 当场剔除，随后 showPage 取不到分页结果直接 return，页面永远停在转圈。
            trimWindow(position.chapterIndex, alsoKeep = chapterIndex)
            result
        } catch (e: CancellationException) {
            // 翻页/换视口取消了上一次加载，不是"章节读不出来"。吞掉当失败会误触发自动换源。
            throw e
        } catch (e: Exception) {
            // 预取失败（userFacing=false）不打扰用户，但**必须留痕**：从前这里把 e 整个丢掉，
            // 结果"预加载为什么没成功"在应用内外都查不到任何线索，只能看着转圈干瞪眼。
            Log.w(TAG, "第 $chapterIndex 章分页失败（userFacing=$userFacing）", e)
            if (userFacing) {
                _state.value = _state.value.copy(loading = false, error = "章节加载失败: ${e.message}")
                maybeAutoChangeSource("章节加载失败: ${e.message}")
            }
            null
        }
    }

    private fun preloadNeighbors(center: Int) {
        viewModelScope.launch {
            engineMutex.withLock {
                // 邻章预取：失败了不弹错误、不换源，只记日志（见 ensurePaginated 的 userFacing）
                ensurePaginated(center + 1)
                ensurePaginated(center - 1)
            }
            refreshNeighbors()
        }
        prefetchAhead(center)
    }

    private var prefetchJob: Job? = null

    /**
     * 往后预取正文进缓存。
     *
     * 翻到章末才去抓下一章，网络书必然卡一下 —— 提前抓好就没有这个停顿。
     * 邻章（center±1）由 preloadNeighbors 顺带排好版了，这里管的是**更远**的那几章：
     * 只把正文拉进缓存，不排版（排版结果持有 TextLayoutResult，很重，攒几章就 OOM）。
     *
     * 四条纪律：
     * - **不碰 engineMutex**。预取一占锁，用户翻页就得排队等它 —— 本来是为了不卡顿，
     *   结果反而卡得更死。
     * - 顺序抓，不并发。几章同时打同一个站点只会触发限流，把好书源熬成"加载失败"。
     * - 换页就取消上一轮：用户跳到别处去了，再抓原来那几章纯属浪费流量。
     * - **先等 [PREFETCH_LEAD_IN_MS]，让开正在播的动画**，理由见下。
     *
     * 那条等待不是保守，是量出来的。进书时缓存若是热的，每章十几毫秒就读完，5 章全挤在
     * 进书后 ~100ms 内 —— 读文件、切行、建元素表，全是密集分配。GC 一来**停的是所有线程，
     * 主线程也在内**，而这一坨正好砸在 260ms 入场动画的正中间：实测掉帧全部落在进书后
     * 56~147ms，之后 250ms 一帧不掉；同一本书清掉缓存（预取被迫走网络、挪出这个窗口）
     * 则一帧不掉。
     *
     * 预取的定义就是"**以后**可能用得上"，而动画是"用户**此刻**正在看"。让前者给后者让路，
     * 代价是零：用户还在看当前这页，几百毫秒之后才可能翻到预取的那几章。
     */
    private fun prefetchAhead(center: Int) {
        val ahead = _state.value.settings.preloadChapters
        if (ahead <= 0) return
        val src = source ?: return
        val total = _state.value.chapterCount
        prefetchJob?.cancel()
        prefetchJob = viewModelScope.launch(Dispatchers.IO) {
            delay(PREFETCH_LEAD_IN_MS)
            for (i in (center + 2)..(center + 1 + ahead)) {
                if (i >= total) break
                if (!isActive) return@launch
                // 已缓存的话 loadChapter 直接命中缓存，几乎不花钱；没缓存才会真去抓
                runCatching { src.loadChapter(i) }
            }
        }
    }

    /** 只保留 center±1 的分页结果（测量结果持有 TextLayoutResult，较重） */
    private fun trimWindow(center: Int, alsoKeep: Int = center) {
        val keep = ((center - 1)..(center + 1)).toSet() + alsoKeep
        paginated.keys.retainAll { it in keep }
    }

    private fun saveProgress() {
        val p = position
        viewModelScope.launch(NonCancellable) {
            bookRepo.saveProgress(bookId, p.chapterIndex, p.charOffset)
        }
    }

    override fun onCleared() {
        (source as? AutoCloseable)?.close()
    }

    private companion object {
        const val TAG = "ReaderViewModel"

        /** 滚动模式的"无限高视口"：足够放下任何一章，分页器于是只产出一页 */
        const val SCROLL_VIEWPORT_PX = 2_000_000

        /**
         * 预取前先等多久，理由见 [prefetchAhead]。
         *
         * 比入场动画（Motion.NAV_ENTER_MS = 260）多留一截：动画结束那一帧往往还在收尾，
         * 紧贴着开工等于没让。翻页时这条等待同样有益（让开翻页动画），而且不会饿着预取——
         * 用户读完一页远不止 400ms。
         */
        const val PREFETCH_LEAD_IN_MS = 400L

        /** 单个书源的换源搜索超时；卡住的站点不能拖住整个面板 */
        const val SOURCE_SEARCH_TIMEOUT_MS = 30_000L

        /**
         * 单章正文加载上限。超过就判这个源"网络很差"，触发自动换源。
         * 正常源 1-3 秒；15 秒还没回来的基本没救，继续等只是让用户对着转圈发呆。
         */
        const val CONTENT_TIMEOUT_MS = 15_000L
    }
}
