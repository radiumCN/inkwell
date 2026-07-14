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
import com.radium.inkwell.core.model.ChapterContent
import com.radium.inkwell.core.model.charLength
import com.radium.inkwell.reader.render.ScrollChapter
import com.radium.inkwell.core.text.ChineseConverter
import com.radium.inkwell.reader.api.ChineseConvert
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import com.radium.inkwell.core.model.ContentElement
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
            var lastConvert: ChineseConvert? = null
            readerPrefs.settings.collect { s ->
                _state.value = _state.value.copy(settings = s)
                // 简繁一改，已排好的页全部作废（字变了，断行也变了）
                if (lastConvert != null && lastConvert != s.chineseConvert) {
                    engineMutex.withLock {
                        paginated.clear()
                        scrollCache.clear()
                        _scrollChapters.value = emptyList()
                        showPosition(position)
                    }
                }
                lastConvert = s.chineseConvert
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
        // 一点屏幕就停自动翻页：想接着自动翻，再点一次「自动」就是了 ——
        // 反过来（点了没反应、页面还在自己翻）会让人以为应用卡死了
        if (_state.value.autoFlipping) {
            stopAutoFlip()
            return
        }
        _state.value = _state.value.copy(menuVisible = !_state.value.menuVisible)
    }

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
        val raw = src.loadChapter(chapterIndex)
        val purifier = Purifier.lenient(replaceRules.purifyForBook(bookId))
        val purified = if (purifier.isEmpty) raw
        else raw.copy(elements = purifier.apply(raw.elements))

        // 简繁转换放在净化之后：净化规则是用户按**眼前看到的字**写的，
        // 若先转换再净化，他写的规则就对不上了
        return when (_state.value.settings.chineseConvert) {
            ChineseConvert.NONE -> purified
            ChineseConvert.TO_SIMPLIFIED -> purified.copy(
                elements = ChineseConverter.convert(purified.elements, ChineseConverter::toSimplified),
            )
            ChineseConvert.TO_TRADITIONAL -> purified.copy(
                elements = ChineseConverter.convert(purified.elements, ChineseConverter::toTraditional),
            )
        }
    }

    // ---------- 滚动模式 ----------

    /**
     * 滚动模式是**另一条渲染路径**，不复用分页结果：页与页堆叠起来，每屏底部都会留一道
     * 参差的空隙（分页器按整行断页，剩多少空白取决于这一屏排了几行）。
     * 这里给分页器一个"高得放得下整章"的视口，它就只产出一页 —— 那一页的 items
     * 正好是带 y 偏移的全部元素，字体/行距/缩进/对齐仍与翻页模式同一套逻辑。
     */
    private val scrollCache = LinkedHashMap<Int, ScrollChapter>()

    private suspend fun ensureScroll(chapterIndex: Int): ScrollChapter? {
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
        } catch (e: Exception) {
            if (chapterIndex == position.chapterIndex) {
                _state.value = _state.value.copy(loading = false, error = "章节加载失败: ${e.message}")
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
                ensureScroll(center) ?: return@withLock
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

    private suspend fun ensurePaginated(chapterIndex: Int): Paginator.Result? {
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
     * 三条纪律：
     * - **不碰 engineMutex**。预取一占锁，用户翻页就得排队等它 —— 本来是为了不卡顿，
     *   结果反而卡得更死。
     * - 顺序抓，不并发。几章同时打同一个站点只会触发限流，把好书源熬成"加载失败"。
     * - 换页就取消上一轮：用户跳到别处去了，再抓原来那几章纯属浪费流量。
     */
    private fun prefetchAhead(center: Int) {
        val ahead = _state.value.settings.preloadChapters
        if (ahead <= 0) return
        val src = source ?: return
        val total = _state.value.chapterCount
        prefetchJob?.cancel()
        prefetchJob = viewModelScope.launch(Dispatchers.IO) {
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
        /** 滚动模式的"无限高视口"：足够放下任何一章，分页器于是只产出一页 */
        const val SCROLL_VIEWPORT_PX = 2_000_000

        /** 单个书源的换源搜索超时；卡住的站点不能拖住整个面板 */
        const val SOURCE_SEARCH_TIMEOUT_MS = 30_000L

        /** 书名归一化：去掉书名号与空白 */
        val TITLE_NOISE = Regex("[《》〈〉\\s]")
    }
}
