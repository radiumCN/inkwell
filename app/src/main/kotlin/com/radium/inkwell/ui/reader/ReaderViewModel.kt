package com.radium.inkwell.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radium.inkwell.core.source.BookSourceEngine
import com.radium.inkwell.core.source.SearchResult
import com.radium.inkwell.data.db.dao.ChapterDao
import com.radium.inkwell.data.db.entity.BookEntity
import com.radium.inkwell.data.db.entity.BookType
import com.radium.inkwell.data.prefs.ReaderPrefs
import com.radium.inkwell.data.repo.BookRepository
import com.radium.inkwell.data.repo.BookSourceRepository
import com.radium.inkwell.data.repo.ChapterContentCache
import com.radium.inkwell.data.repo.LocalReaderBookSource
import com.radium.inkwell.data.repo.NetBookRepository
import com.radium.inkwell.data.repo.NetReaderBookSource
import com.radium.inkwell.reader.api.FlipDirection
import com.radium.inkwell.reader.api.ReadPosition
import com.radium.inkwell.reader.api.ReaderBookSource
import com.radium.inkwell.reader.api.ReaderSettings
import com.radium.inkwell.reader.measure.TextMeasureFacade
import com.radium.inkwell.reader.paginate.LayoutSpec
import com.radium.inkwell.reader.paginate.Paginator
import com.radium.inkwell.reader.render.RenderablePage
import kotlinx.coroutines.Dispatchers
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

data class TocItem(val index: Int, val title: String)

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
    /** 长按选字是否开启 */
    val textSelectionEnabled: Boolean = true,
    /** 一次性提示（建了净化规则之类） */
    val toast: String? = null,
    /** 换源：null=未打开面板；空列表=搜索中/无结果 */
    val sourceCandidates: List<SearchResult>? = null,
    val searchingSources: Boolean = false,
    val changingSource: Boolean = false,
    /** 换源搜索进度：已回来的书源数 / 总数 */
    val sourcesDone: Int = 0,
    val sourcesTotal: Int = 0,
    /** 换源是否用作者卡人（同 Legado 的 changeSourceCheckAuthor） */
    val checkAuthor: Boolean = true,
)

/**
 * 阅读会话：持有数据源、3 章分页窗口、页游标。
 * 进度真身 = (chapterIndex, charOffset)，页码只是当前排版下的投影。
 */
class ReaderViewModel(
    private val bookId: String,
    private val bookRepo: BookRepository,
    private val chapterDao: ChapterDao,
    private val readerPrefs: ReaderPrefs,
    private val sourceRepo: BookSourceRepository,
    private val netBookRepo: NetBookRepository,
    private val engine: BookSourceEngine,
    private val contentCache: ChapterContentCache,
    private val appPrefs: com.radium.inkwell.data.prefs.AppPrefs,
    private val replaceRules: com.radium.inkwell.data.repo.ReplaceRuleRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ReaderUiState())
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
    }

    private suspend fun loadSession() {
        try {
            (source as? AutoCloseable)?.close()
            val b = bookRepo.getBook(bookId) ?: error("书籍不存在")
            book = b
            position = ReadPosition(b.readChapterIndex, b.readCharOffset)
            val chapters = chapterDao.getByBook(bookId)
            val src = if (b.type == BookType.NET) {
                val rule = b.sourceId?.let { sourceRepo.getRule(it) }
                    ?: error("书源不存在，请换源后阅读")
                NetReaderBookSource(bookId, chapters, rule, engine, contentCache, chapterDao)
            } else {
                withContext(Dispatchers.IO) { LocalReaderBookSource(bookRepo.openLocal(b)) }
            }
            source = src
            _state.value = _state.value.copy(
                bookTitle = b.title,
                chapterCount = src.chapterCount,
                chapterIndex = position.chapterIndex,
                toc = chapters.map { TocItem(it.index, it.title) },
                isNetBook = b.type == BookType.NET,
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
        _state.value = _state.value.copy(menuVisible = !_state.value.menuVisible)
    }

    fun updateSettings(settings: ReaderSettings) {
        viewModelScope.launch { readerPrefs.update(settings) }
    }

    /** 正文加载失败后重试：清掉分页缓存重来一遍（站点抽风、临时封 IP 都可能只是一次性的） */
    fun retry() {
        viewModelScope.launch {
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
            val all = sourceRepo.getEnabledRules()
                .filter { it.search != null && it.id != b.sourceId }
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
                        val hit = withTimeoutOrNull(SOURCE_SEARCH_TIMEOUT_MS) {
                            runCatching { engine.search(rule, b.title).items }
                                .getOrDefault(emptyList())
                                .firstOrNull { titleMatches(it.title, b.title) }
                        }
                        if (hit != null) rawCandidates += hit
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

    /**
     * 书名判定。归一化掉书名号与空白：书源常返回「《武动乾坤》」，
     * 直接比字符串会判死。双向包含，「武动乾坤」与「武动乾坤（精校版）」互相认得。
     */
    private fun titleMatches(candidate: String, want: String): Boolean {
        val a = normTitle(candidate)
        val b = normTitle(want)
        if (a.isEmpty() || b.isEmpty()) return false
        return a == b || a.contains(b) || (b.contains(a) && a.length >= 2)
    }

    private fun normTitle(s: String) = s.trim().replace(TITLE_NOISE, "")

    /**
     * 作者判定与 Legado 对齐：用**包含**而非相等 —— 书源返回的作者常带前缀
     * （「作者：天蚕土豆」）或含多个作者，一律要求相等会把绝大多数源判死。
     * 任一边为空时不拿作者卡人。
     */
    private fun authorMatches(candidate: String?, want: String): Boolean {
        val a = candidate?.trim().orEmpty()
        if (want.isBlank() || a.isBlank()) return true
        return a.contains(want) || want.contains(a)
    }

    fun applyChangeSource(candidate: SearchResult) {
        val b = book ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(changingSource = true)
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

    fun dismissSourcePanel() {
        sourceSearchJob?.cancel()
        rawCandidates.clear()
        _state.value = _state.value.copy(sourceCandidates = null, searchingSources = false)
    }

    // ---------- 内部 ----------

    /** 定位到指定位置：必要时加载+分页该章，并预取相邻章 */
    private suspend fun showPosition(target: ReadPosition) {
        val result = ensurePaginated(target.chapterIndex) ?: return
        val pageIdx = if (target.charOffset == Int.MAX_VALUE) {
            result.chapter.pages.lastIndex
        } else {
            result.chapter.pageIndexFor(target.charOffset)
        }
        showPage(target.chapterIndex, pageIdx)
        preloadNeighbors(target.chapterIndex)
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

    private fun showPage(chapterIndex: Int, pageIndex: Int) {
        val result = paginated[chapterIndex] ?: return
        val page = renderable(result, pageIndex) ?: return
        position = ReadPosition(chapterIndex, page.spec.startCharOffset)
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

    private suspend fun ensurePaginated(chapterIndex: Int): Paginator.Result? {
        paginated[chapterIndex]?.let { return it }
        if (chapterIndex !in 0 until (_state.value.chapterCount)) return null
        val src = source ?: return null
        val facade = facade ?: return null
        val spec = spec ?: return null
        return try {
            val raw = src.loadChapter(chapterIndex)
            // 书内净化在这里应用，而不是抓取时：用户在阅读页长按选中一句话建规则，
            // 指望的是眼前这一页立刻干净 —— 而这一页早就缓存好了，抓取时的净化
            // 再也不会跑到它头上。
            val purifier = Purifier.lenient(replaceRules.purifyForBook(bookId))
            val content = if (purifier.isEmpty) raw
            else raw.copy(elements = purifier.apply(raw.elements))
            val title = src.chapterTitle(chapterIndex) ?: ""
            val result = withContext(Dispatchers.Default) {
                Paginator(facade).paginate(chapterIndex, title, content, spec)
            }
            paginated[chapterIndex] = result
            // 目录跳章时 position 还停在旧章：若只按旧章号裁窗口，刚分好页的目标章会被
            // 当场剔除，随后 showPage 取不到分页结果直接 return，页面永远停在转圈。
            trimWindow(position.chapterIndex, alsoKeep = chapterIndex)
            result
        } catch (e: Exception) {
            if (chapterIndex == position.chapterIndex) {
                _state.value = _state.value.copy(loading = false, error = "章节加载失败: ${e.message}")
            }
            null
        }
    }

    private fun preloadNeighbors(center: Int) {
        viewModelScope.launch {
            engineMutex.withLock {
                ensurePaginated(center + 1)
                ensurePaginated(center - 1)
            }
            refreshNeighbors()
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
        /** 单个书源的换源搜索超时；卡住的站点不能拖住整个面板 */
        const val SOURCE_SEARCH_TIMEOUT_MS = 30_000L

        /** 书名归一化：去掉书名号与空白 */
        val TITLE_NOISE = Regex("[《》〈〉\\s]")
    }
}
