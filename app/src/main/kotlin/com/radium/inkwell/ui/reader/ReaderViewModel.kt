package com.radium.inkwell.ui.reader

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radium.inkwell.core.source.BookSourceEngine
import com.radium.inkwell.core.source.SearchResult
import com.radium.inkwell.core.source.SourceVariables
import com.radium.inkwell.data.db.dao.BookSourceHitDao
import com.radium.inkwell.data.db.dao.ChapterDao
import com.radium.inkwell.data.db.entity.BookEntity
import com.radium.inkwell.data.db.entity.BookSourceHitEntity
import com.radium.inkwell.data.db.entity.BookType
import com.radium.inkwell.data.db.entity.ChapterEntity
import com.radium.inkwell.data.prefs.AppPrefs
import com.radium.inkwell.data.prefs.ReaderPrefs
import com.radium.inkwell.data.prefs.LayoutScheme
import com.radium.inkwell.data.repo.ReaderFontStore
import com.radium.inkwell.data.repo.ReadingClock
import com.radium.inkwell.data.repo.ReadingStatsRepository
import com.radium.inkwell.data.repo.UserFont
import com.radium.inkwell.data.repo.BookRepository
import com.radium.inkwell.data.repo.BookSourceRepository
import com.radium.inkwell.data.repo.ChapterContentCache
import com.radium.inkwell.data.repo.LocalReaderBookSource
import com.radium.inkwell.data.repo.NetBookRepository
import com.radium.inkwell.data.repo.NetReaderBookSource
import com.radium.inkwell.data.repo.AutoSourceSwitcher
import com.radium.inkwell.data.repo.TitleMatch
import com.radium.inkwell.reader.api.FlipAnimation
import com.radium.inkwell.reader.api.FlipDirection
import com.radium.inkwell.reader.api.ReadPosition
import com.radium.inkwell.reader.api.ReaderBookSource
import com.radium.inkwell.reader.api.ReaderSettings
import com.radium.inkwell.reader.measure.TextMeasureFacade
import com.radium.inkwell.reader.paginate.LayoutSpec
import com.radium.inkwell.reader.paginate.PageItem
import com.radium.inkwell.reader.paginate.PageSpec
import com.radium.inkwell.reader.paginate.Paginator
import com.radium.inkwell.reader.render.RenderablePage
import androidx.compose.ui.graphics.ImageBitmap
import com.radium.inkwell.core.model.ContentElement
import com.radium.inkwell.ui.components.MessageBus
import kotlinx.coroutines.Dispatchers
import com.radium.inkwell.core.model.ChapterContent
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import com.radium.inkwell.core.source.Purifier
import com.radium.inkwell.core.source.PurifyTimeoutException
import com.radium.inkwell.core.util.RepeatedTitle
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger

/** [cached] 正文已在文件缓存里（网络书才有意义：本地书正文一直都在，UI 侧不展示这个状态） */
data class TocItem(val index: Int, val title: String, val cached: Boolean = false)

data class ReaderUiState(
    val bookTitle: String = "",
    /** 书行上的作者。换源面板拿它认「作者对不上」的候选 */
    val bookAuthor: String = "",
    /** 进书 splash 用：正文还没就位时，在纸面上显示这本书的封面（见 ReaderScreen 加载分支的 splash） */
    val coverPath: String? = null,
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
    /** 自动翻页中 */
    val autoFlipping: Boolean = false,
    /** 换源：null=未打开面板；空列表=搜索中/无结果 */
    val sourceCandidates: List<SearchResult>? = null,
    val searchingSources: Boolean = false,
    val changingSource: Boolean = false,
    /** 换源搜索进度：已回来的书源数 / 总数 */
    val sourcesDone: Int = 0,
    val sourcesTotal: Int = 0,
    /** 换源是否用作者卡人（同 Legado 的 changeSourceCheckAuthor） */
    val checkAuthor: Boolean = true,
    /**
     * 开着「只看同作者」时被筛掉的候选数（找到了同名书、作者对不上）。
     * 面板据此说「另有 N 个书源……作者对不上 · 显示」，而不是让它们悄悄消失
     */
    val authorMismatched: Int = 0,
    /** 手动换源正在换到的书源（[SearchResult.sourceId]）：面板上那一行就地转圈。null = 没在手动换源 */
    val changingTo: String? = null,
    /** 换源面板「当前」卡上写的最新章：开面板时取本机目录的最后一章，目录还没有就取书行记的 */
    val currentLatestChapter: String? = null,
    /** 正在自动换源（正文读不出来时自动找一个能读的源） */
    val autoChanging: Boolean = false,
    val autoChangeDone: Int = 0,
    val autoChangeTotal: Int = 0,
    /**
     * 刚自动换过源 → 底部提示胶囊 + 撤销（AutoChangedSourceHint）。非空 = 新源的名字。
     *
     * 必须告诉用户：自动换源可能换到删减版/盗版，正文和原来不是一回事。
     * 静默换掉的话，用户只会觉得"这书怎么突然变了"，根本想不到是 App 干的。
     */
    val autoChangedTo: String? = null,
    /**
     * 阅读菜单上的目录 / 设置 sheet 是否正开着。
     * 翻页手势得跟着关：sheet 一出来就把 pointerInput 停掉，关掉后再开，
     * 否则 ModalBottomSheet 容易把 down 留下、此后拖拽翻页全没反应。
     */
    val readerSheetOpen: Boolean = false,
    /**
     * 是否已在书架。默认 true：进书前还没读到书行时不要误弹「加入书架」。
     * 预览直读落库为试读后这里会变成 false，退出时再问。
     */
    val inShelf: Boolean = true,
)

/**
 * 阅读 UI 拆成三份 StateFlow：翻页只动 [ReaderPageUi]，菜单开关只动 [ReaderOverlayUi]。
 * 一份大 data class 每次 copy 都会让整棵阅读树重组 —— 目录 LazyColumn、换源面板都跟着刷。
 */
data class ReaderPageUi(
    val chapterTitle: String = "",
    val chapterIndex: Int = 0,
    val pageInChapter: Int = 0,
    val pageCount: Int = 0,
    val page: RenderablePage? = null,
    val prevPage: RenderablePage? = null,
    val nextPage: RenderablePage? = null,
    val hasPrev: Boolean = false,
    val hasNext: Boolean = false,
    val loading: Boolean = true,
    val error: String? = null,
    val atBookEnd: Boolean = false,
)

data class ReaderSessionUi(
    val bookTitle: String = "",
    val coverPath: String? = null,
    val chapterCount: Int = 0,
    val settings: ReaderSettings = ReaderSettings(),
    val isNetBook: Boolean = false,
    val currentSourceName: String = "",
    val textSelectionEnabled: Boolean = true,
    /** 未上架的试读：退出阅读时提示是否加入书架 */
    val inShelf: Boolean = true,
    val bookAuthor: String = "",
)

data class ReaderOverlayUi(
    val menuVisible: Boolean = false,
    val autoFlipping: Boolean = false,
    val sourceCandidates: List<SearchResult>? = null,
    val searchingSources: Boolean = false,
    val changingSource: Boolean = false,
    val sourcesDone: Int = 0,
    val sourcesTotal: Int = 0,
    val checkAuthor: Boolean = true,
    val autoChanging: Boolean = false,
    val autoChangeDone: Int = 0,
    val autoChangeTotal: Int = 0,
    val autoChangedTo: String? = null,
    val readerSheetOpen: Boolean = false,
    val authorMismatched: Int = 0,
    val changingTo: String? = null,
    val currentLatestChapter: String? = null,
)

private fun ReaderUiState.toPage() = ReaderPageUi(
    chapterTitle, chapterIndex, pageInChapter, pageCount,
    page, prevPage, nextPage, hasPrev, hasNext, loading, error, atBookEnd,
)

private fun ReaderUiState.toSession() = ReaderSessionUi(
    bookTitle, coverPath, chapterCount, settings, isNetBook, currentSourceName, textSelectionEnabled, inShelf,
    bookAuthor,
)

private fun ReaderUiState.toOverlay() = ReaderOverlayUi(
    menuVisible, autoFlipping, sourceCandidates, searchingSources, changingSource,
    sourcesDone, sourcesTotal, checkAuthor, autoChanging, autoChangeDone, autoChangeTotal,
    autoChangedTo, readerSheetOpen, authorMismatched, changingTo, currentLatestChapter,
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
 * 网上的书、关着「正文显示图片」时，这一章只有图没有字。从前解析时就抹掉了图，这种章是一章空正文、
 * 报「读不出来」、触发自动换源（不少站拿图片当正文防抓取，换个源多半就有字了）—— 关着开关时照旧这么走，
 * 只是把话说清楚：开了开关就看得到。
 */
private class ImageOnlyChapterException :
    Exception("本章只有图片。在阅读设置「更多」里打开「正文显示图片」就能看到")

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
    private val appPrefs: AppPrefs,
    private val replaceRules: com.radium.inkwell.data.repo.ReplaceRuleRepository,
    private val autoSwitcher: com.radium.inkwell.data.repo.AutoSourceSwitcher,
    private val fontStore: ReaderFontStore,
    private val readingStats: ReadingStatsRepository,
) : ViewModel() {

    // 用已预热的真实设置播种首帧，避免进书先闪一帧浅色默认主题再切深色
    private val _state = MutableStateFlow(ReaderUiState(settings = readerPrefs.settings.value))
    val page: StateFlow<ReaderPageUi> = _state
        .map { it.toPage() }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, _state.value.toPage())
    val session: StateFlow<ReaderSessionUi> = _state
        .map { it.toSession() }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, _state.value.toSession())
    val overlay: StateFlow<ReaderOverlayUi> = _state
        .map { it.toOverlay() }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, _state.value.toOverlay())
    /**
     * 目录单独一条流：预取落盘会反复改 isCached，不能跟 session 绑在阅读页根上，
     * 否则整棵翻页树跟着重组。只给菜单的目录面板收集。
     */
    val toc: StateFlow<List<TocItem>> = _state
        .map { it.toc }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, _state.value.toc)

    /**
     * 一次性提示（建了净化规则、换源失败、本章未净化……），由阅读页按纸张主题浮在页顶。
     *
     * 从前是 overlay 里的一个 `toast: String?` 字段 —— StateFlow 对相同值去重：连着删两次同一句话、
     * 连着两次「换源失败: 超时」，第二条直接没了，用户以为没点上。走 [MessageBus]（SharedFlow）每条都送达。
     */
    private val messageBus = MessageBus()
    val messages: Flow<String> = messageBus.messages

    private var book: BookEntity? = null
    private var source: ReaderBookSource? = null
    private var position = ReadPosition(0, 0)
    /**
     * 用户要去、但还没显示出来的位置（进书/翻章/跳目录都经 [showPosition] 记下，[showPage] 成功即清）。
     *
     * [position] 只在 showPage **成功之后**才更新：从第 N 章翻向 N+1 失败时，它还停在 N。
     * 重试、自动换源、排到一半换了视口的重排，要的都是这一个 —— 拿 position 去，
     * 重试会悄悄重载一遍读得出来的第 N 章；自动换源会拿第 N 章去探测（必然"成功"），
     * 换完再按 N 对齐，把人送回刚读完那章的开头，还白白用掉本次会话唯一一次自动换源。
     */
    private var pendingTarget: ReadPosition? = null
    /**
     * [loadSession] 是否走完。为 false 时 [source] 可能是 null、是换源前已关闭的旧源、或是零章的源，
     * 都不能拿来重排 —— [retry] 据此决定从进书重来还是只重排当前章。
     */
    private var sessionReady = false

    // 排版环境（视口就绪后由 UI 注入）
    private var facade: TextMeasureFacade? = null
    private var spec: LayoutSpec? = null

    // 分页缓存：chapterIndex → 结果；spec 变化即整体作废
    private val paginated = java.util.concurrent.ConcurrentHashMap<Int, Paginator.Result>()
    private val engineMutex = Mutex()
    /** 测量层不能并发；与 [engineMutex] 拆开，邻章排版不再堵住用户正在等的那一章的 IO */
    private val measureMutex = Mutex()

    /** 「用户在等的那一次定位」，见 [navigate]。同一时刻只留一个 */
    private var navJob: Job? = null
    /** [navJob] 要去的章节位置；只有目标固定的跳章（[gotoChapter]）才记，用来认出「同一个目标连点」 */
    private var navTarget: ReadPosition? = null

    /**
     * 会话目录换过几次（阅读中后台追更、序号整体错位时 +1，见 [syncSessionToc]）。
     *
     * 换目录之前就开始加载的章节，手里拿的是**旧序号**：排完写回 [paginated]，就成了新目录下另一章的缓存 ——
     * 翻到那一章看到的是别的章的正文。[ensurePaginated] 开工时记下它，排完对不上就丢掉结果。
     * 部分结果回调跑在 Default 上，所以是 volatile。
     */
    @Volatile
    private var tocGeneration = 0

    /**
     * 正在换源（手动 / 自动 / 撤销自动换源），计数而不是布尔：手动换源会掐掉还在跑的自动换源，
     * 后者的 finally 晚一拍才执行，布尔的话它会把前者的「进行中」一起清掉。全在主线程上读写。
     */
    private var sourceChangesInFlight = 0
    private val sourceChanging: Boolean get() = sourceChangesInFlight > 0

    /**
     * 书内净化超时、正文按未净化显示的章（章号）。提示只在这一章**显示出来**时弹：从前在加载处直接弹，
     * 邻章预载撞上超时，用户眼前这一章明明净化好了，却冒出一句「本章未净化」。
     * 预载与显示不在同一线程，用并发集合。
     */
    private val purifySkipped: MutableSet<Int> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** 正文插图：排版前量尺寸、画的时候解码、看大图（见 [ReaderImages]） */
    private val readerImages = ReaderImages()

    /** 等着解码的图（按要的先后：当前页先、前后页后），见 [imagesFor]。只在主线程上碰 */
    private val pendingDecodes = LinkedHashMap<String, PageItem.ImageBox>()
    private var decodeJob: Job? = null

    /** 正在重试的图：连点几下「点按重试」只算一次 */
    private val retryingImages = HashSet<String>()

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
     *
     * 目录本身也会变：阅读中后台追更会整本换掉目录行。面板读的是这里的新目录，阅读会话手里却还是
     * 进书时那一份 —— 两边序号一错开，点新章没反应、点第 i 章打开的是旧目录的第 i 章。
     * 所以每来一份新目录，也交给 [syncSessionToc] 把会话对齐过去。
     *
     * 怎么盯：只听「chapter 表被写过」那一声（[BookRepository.chapterTableChanges]），每 [TOC_REFRESH_MS]
     * 至多读一次轻量投影（[TocRow]）；目录结构和会话手里的一样（绝大多数时候，变的只是 isCached），
     * 就只换面板上的缓存标记，不读整行。
     *
     * 从前直接订阅 `SELECT *` 的目录流：Room 按整张表失效，预取每落一章、离线缓存每存一章（别的书也算），
     * 就把这本书几千行目录整读一遍、建几千个对象 —— 后面的防抖挡得住处理，挡不住查询本身。
     * 这些分配招来的 GC 停的是所有线程，翻页动画也在内（同 [prefetchAhead] 里量过的那回事）。
     */
    private fun observeToc() {
        viewModelScope.launch {
            bookRepo.chapterTableChanges()
                // 读的时候又写了几回：攒成一声，读完再隔一个间隔读最新的。不用 debounce：离线缓存一章接一章地写，
                // 防抖一直等不到安静，面板上的缓存标记会一直停着，直到整本缓存完
                .conflate()
                .transform { delay(TOC_REFRESH_MS); emit(it) }
                .map { chapterDao.getTocRows(bookId) }
                .distinctUntilChanged()
                .collect { rows ->
                    _state.value = _state.value.copy(toc = rows.map { TocItem(it.index, it.title, it.isCached) })
                    // 目录结构变了（后台追更）才把整行读出来，交给会话去对齐。条件同 syncSessionToc 开头那几条，
                    // 先在这里挡掉，免得换源途中每一声都白读一遍整本目录
                    val src = source as? NetReaderBookSource
                    if (sessionReady && !sourceChanging && src != null && rows.isNotEmpty() &&
                        !sameReadingToc(src.chapters, rows)
                    ) {
                        syncSessionToc(chapterDao.getByBook(bookId))
                    }
                }
        }
    }

    /**
     * 库里的目录换了（后台追更），把阅读会话对齐过去：数据源换上新目录，所有按章号记的状态
     * （当前位置、待显示的目标、分页缓存、未净化标记）按章节 URL 换算到新序号。
     *
     * 不跟着改的后果：目录面板是新的、会话是旧的 —— 新章点了没反应（越界被挡掉），下一章/书末按旧章数判，
     * 前面插了章的话第 i 项打开旧目录的第 i 章，下一次存进度还会把追更刚对齐好的进度盖回旧序号。
     *
     * 换源进行中不动：那时库里已是**新源**的目录，而数据源还是旧源 —— 拿旧源的规则去抓新源的地址只会全失败；
     * 换完 loadSession 会按新目录整个重建会话。
     */
    private fun syncSessionToc(rows: List<ChapterEntity>) {
        if (!sessionReady || sourceChanging) return
        val src = source as? NetReaderBookSource ?: return
        // 删书时目录会被清空；追更本身不会写空目录（解析为空直接失败）。别把正在读的会话清空
        if (rows.isEmpty()) return
        val old = src.chapters
        if (sameReadingToc(old, rows)) return
        val remap = TocRemap(old, rows)
        val s = _state.value
        src.updateChapters(rows)
        // 只在末尾追加了新章（连载追更的常态）：手里记着的序号一个都没动，补上章数、刷新「下一章/书末」即可
        val held = buildSet {
            add(position.chapterIndex)
            add(s.chapterIndex)
            pendingTarget?.let { add(it.chapterIndex) }
            addAll(paginated.keys)
            addAll(purifySkipped)
        }
        if (held.all { remap.map(it) == it }) {
            _state.value = s.copy(chapterCount = rows.size)
            if (navJob?.isActive != true && s.error == null && paginated[s.chapterIndex] != null) {
                // 原地重出这一页：hasNext、邻页图层按新章数重算，到了章末还会去预载新出现的下一章
                showPage(s.chapterIndex, s.pageInChapter, keepOffset = position.charOffset)
            }
            return
        }
        // 序号整体错位了：此前开工、手里拿着旧序号的加载，结果一律作废（见 tocGeneration）
        tocGeneration++
        position = remap.map(position)
        pendingTarget = pendingTarget?.let { remap.map(it) }
        val rekeyed = paginated.mapNotNull { (index, result) ->
            remap.map(index)?.let { it to result.withChapterIndex(it) }
        }
        paginated.clear()
        paginated.putAll(rekeyed)
        val skipped = purifySkipped.mapNotNull { remap.map(it) }
        purifySkipped.clear()
        purifySkipped.addAll(skipped)
        // 眼前这一章在新目录里的序号；它从目录里没了（null）就只能跟着 position 的兜底走、重新加载
        val shownMapped = remap.map(s.chapterIndex)
        _state.value = s.copy(chapterCount = rows.size, chapterIndex = shownMapped ?: position.chapterIndex)
        when {
            // 报错页上不自动重试：只把序号换好，用户点「重试」时去的就是新目录里的那一章
            s.error != null -> Unit
            // 眼前这一页还在缓存里（换算后）：原地按新序号重出，进度随之按新序号存下
            navJob?.isActive != true && pendingTarget == null &&
                shownMapped != null && paginated[shownMapped] != null ->
                showPage(shownMapped, s.pageInChapter, keepOffset = position.charOffset)
            // 还有一次跳章在路上：它手里是旧序号，结果会被丢掉 —— 按换算后的目标重新发起
            else -> navigate { pendingTarget ?: position }
        }
    }

    /** 同一份分页结果挂到新章号下（目录换过、序号整体平移时用）：页的内容不动，只改各处记着的章号 */
    private fun Paginator.Result.withChapterIndex(index: Int): Paginator.Result =
        if (chapter.chapterIndex == index) this
        else Paginator.Result(
            chapter = chapter.copy(
                chapterIndex = index,
                pages = chapter.pages.map { it.copy(chapterIndex = index) },
            ),
            measured = measured,
            complete = complete,
        )

    private suspend fun loadSession() {
        try {
            sessionReady = false
            (source as? AutoCloseable)?.close()
            var b = bookRepo.getBook(bookId) ?: error("书籍不存在")
            book = b
            // 打开即已知晓：书架上那个"有 N 章新的"红点该灭了；打开时间也记下来，书架按它排「最近阅读」
            bookRepo.markOpened(bookId)
            position = ReadPosition(b.readChapterIndex, b.readCharOffset)
            // 新会话以库里的进度为准。上一会话（换源前）没落地的目标是旧目录的序号，留着会被
            // 随后的重排（onLayoutReady 取 pendingTarget ?: position）拿去，在新目录里打开一个不相干的章
            pendingTarget = null
            // splash 的书名/封面第一时间交出去：下面 WebDAV 无目录的冷路径要先抓一次目录
            // （网络往返），而那正是 splash 必然出场的场景 —— 拖到目录回来才随全量 state 写入，
            // 出场的就是一块无名素色块，起不到「交代在开哪本书」的作用。
            _state.value = _state.value.copy(
                bookTitle = b.title,
                bookAuthor = b.author,
                coverPath = b.coverPath,
                inShelf = b.inShelf && !b.deleted,
            )
            var chapters = chapterDao.getByBook(bookId)
            var currentSourceName = ""
            val src = if (b.type == BookType.NET) {
                val rule = b.sourceId?.let { sourceRepo.getRule(it) }
                    ?: error("来源不存在，请换源后阅读")
                currentSourceName = rule.name
                // WebDAV 同步下来的网络书只有书行、没有目录行（目录不进备份）。此时不补抓的话
                // 章节数为 0，ensurePaginated 静默返回 null，页面永远转圈。先拉一次目录落库。
                // 进度章号超出本地目录也补抓：另一台设备追更后读到了新章，同步来的是新进度，
                // 本机目录却还是旧的。这一支抓不到不致命（下面会夹取到末章），所以不 throw。
                if (chapters.isEmpty() || b.readChapterIndex >= chapters.size) {
                    val refreshed = netBookRepo.refreshToc(b, rule)
                    if (chapters.isEmpty()) refreshed.getOrThrow()
                    if (refreshed.isSuccess) {
                        chapters = chapterDao.getByBook(bookId)
                        // 追更按新目录对齐了进度、累加了红点：以库里最新行为准
                        b = bookRepo.getBook(bookId) ?: b
                        book = b
                        position = ReadPosition(b.readChapterIndex, b.readCharOffset)
                        if (b.newChapterCount > 0) bookRepo.markOpened(bookId)
                    }
                }
                NetReaderBookSource(
                    bookId, chapters, rule, engine, contentCache, chapterDao,
                    bookVariables = SourceVariables.decode(b.variable),
                )
            } else {
                withContext(Dispatchers.IO) { LocalReaderBookSource(bookRepo.openLocal(b)) }
            }
            source = src
            (src as? NetReaderBookSource)?.imagesWanted = spec?.showImages ?: _state.value.settings.showImages
            // 上个会话（换源前）没取到的图不再记着：新会话里它们该有机会重新取；排着队等解码的是旧源的图，作废
            readerImages.forgetFailures()
            pendingDecodes.clear()
            // 零章节的书进了阅读器就是个死局：chapterCount == 0 时 ensurePaginated 的越界守卫
            // 静默返回 null，showPosition 直接 return —— 页面永远转圈，连报错出口都不给。
            // 网络书在上面 chapters 为空时会补抓一次目录，本地书走的是 else 分支，从前没有
            // 任何检查：某个 txt/epub 解析出 0 章就直接卡死在转圈上。这里统一拦一道。
            if (src.chapterCount == 0) {
                error(
                    if (b.type == BookType.NET) "这本书没有任何章节，换个来源试试"
                    else "这本书解析不出任何章节，可能文件已损坏或格式不支持"
                )
            }
            // 补抓之后仍可能越界（离线抓不到目录、本地书文件换成了更短的版本）。越界时
            // ensurePaginated 静默返回 null，报「第 N 章读不出来」，重试还是同一个位置 —— 死路。
            progressHeld = false
            if (position.chapterIndex !in 0 until src.chapterCount) {
                position = ReadPosition(position.chapterIndex.coerceIn(0, src.chapterCount - 1), 0)
                // 网络书走到这里，是同步来的进度比本机目录新、补抓又没成：夹出来的只是占位，别写回去。
                // 本地书是文件换成了更短的版本，目录就是权威，夹完照常保存
                progressHeld = b.type == BookType.NET
            }
            _state.value = _state.value.copy(
                bookTitle = b.title,
                bookAuthor = b.author,
                coverPath = b.coverPath,
                chapterCount = src.chapterCount,
                chapterIndex = position.chapterIndex,
                toc = chapters.map { TocItem(it.index, it.title, it.isCached) },
                isNetBook = b.type == BookType.NET,
                currentSourceName = currentSourceName,
                inShelf = b.inShelf && !b.deleted,
                error = null,
            )
            sessionReady = true
            // 网络书：首章加载别等清理。进度之前且超龄的正文缓存顺手清掉，目录 isCached 一并 sync。
            // 先让开进书动画（同预取的 PREFETCH_LEAD_IN_MS）：清理要给全书每章算一遍文件名摘要、
            // 进度之前每章 stat 一次，几千章就是几千次系统调用加一大坨分配，正好砸在入场动画中间
            if (b.type == BookType.NET) {
                val readIndex = position.chapterIndex
                viewModelScope.launch(Dispatchers.IO) {
                    delay(PREFETCH_LEAD_IN_MS)
                    pruneStaleChapterCache(readIndex, chapters)
                }
            }
            // 排版环境已就绪（换源重载场景）→ 立即重新分页。分页缓存在这里清（navigate 同步清一次、
            // 拿到锁再清一次）—— 在换源处清会与在飞的预加载抢
            if (facade != null && spec != null) {
                navigate(invalidate = true) { position }
            }
        } catch (e: CancellationException) {
            // 退出阅读/换源取消了这次进书，不是"打开失败"。吞掉会误触发自动换源、改掉用户的源。
            throw e
        } catch (e: Exception) {
            _state.value = _state.value.copy(loading = false, error = e.message ?: "打开书籍失败")
            // 书源被删了、目录拉不到 —— 这两种进书就废的情况，Legado 原生也自动换源
            maybeAutoChangeSource(e.message ?: "打开书籍失败", target = position)
        }
    }

    /**
     * 按设置清掉「进度之前」且超龄的正文缓存，并 sync 目录 isCached。
     * 偏好为 0（关闭）时直接返回。不挡首章加载。
     * 同一本书隔一阵才清一次（[ChapterContentCache.claimPrune]）：一天里反复进出同一本书，不必每次都把全书过一遍。
     */
    private suspend fun pruneStaleChapterCache(
        readChapterIndex: Int,
        chapters: List<ChapterEntity>,
    ) {
        val days = appPrefs.cacheAutoPruneDays.first()
        if (days <= 0) return
        if (!contentCache.claimPrune(bookId)) return
        val maxAgeMs = days * 86_400_000L
        val urls = com.radium.inkwell.data.repo.chapterUrlsByIndex(chapters).ifEmpty { return }
        val pruned = contentCache.pruneBehindProgress(
            bookId = bookId,
            readChapterIndex = readChapterIndex,
            chapterUrls = urls,
            maxAgeMs = maxAgeMs,
        )
        if (pruned.isNotEmpty()) chapterDao.markUncached(bookId, pruned)
    }

    /** 视口/设置变化时由 UI 调用；重建排版环境并按 charOffset 恢复位置 */
    fun onLayoutReady(facade: TextMeasureFacade, spec: LayoutSpec) {
        if (this.spec == spec && this.facade != null) return
        this.facade = facade
        this.spec = spec
        // 「正文显示图片」也在排版指纹里：开关一拨走到这里，按同一个字符偏移重排。
        // 网上的书顺带改口：开着时，升级前只缓存了字的章读到时重抓一份带图的
        (source as? NetReaderBookSource)?.imagesWanted = spec.showImages
        // 加载途中旋转/改字号：用户点的那一章还没出来，重排要接着去那一章，别退回旧章
        navigate(invalidate = true) { pendingTarget ?: position }
    }

    /**
     * 「用户在等的那一次定位」（翻章、跳目录、重试、重排后回到原处、换源后重进）统一从这里发起，
     * **新的一次取代旧的**。
     *
     * 从前每次都是新起一个协程去排 engineMutex：慢源上点了下一章（N+1 要等满 15 秒超时）再点上一章，
     * N-1 只能排在 N+1 后面干等；N+1 一超时，先闪一下报错，紧接着自动换源按 N+1 去换 —— 把刚点了上一章的人
     * 又拽回 N+1。现在旧的那次直接取消：它抛的是 CancellationException，ensurePaginated / loadPurified 原样
     * 往上抛，既不会被当成「读不出来」去报错，也不会触发自动换源。
     *
     * [target] 拿到锁之后才求值：排在前面的那次可能刚把 position / pendingTarget 挪过。
     * [invalidate] 为 true 时**同步**清掉分页缓存（视口/净化规则/书源变了）：这一轮若还没拿到锁就被下一次
     * 跳转取代，锁里那句 clear 根本轮不到执行，新的那次就会捡到按旧排版/旧规则分好的页。
     */
    private fun navigate(invalidate: Boolean = false, target: () -> ReadPosition) {
        navJob?.cancel()
        navTarget = null
        if (invalidate) paginated.clear()
        navJob = viewModelScope.launch {
            engineMutex.withLock {
                // 等锁期间完成的邻章预载可能还是按旧环境排的，拿到锁再清一遍
                if (invalidate) paginated.clear()
                showPosition(target())
            }
        }
    }

    /**
     * 用户就地翻了页 / 拖了进度条：还在路上的跳章作废。
     * 不掐的话，它落地时会把人从眼前这一页拽到刚才点过、后来又放弃了的那一章。
     */
    private fun cancelNavigation() {
        navJob?.cancel()
        navJob = null
        navTarget = null
    }

    fun flip(direction: FlipDirection) {
        progressHeld = false
        val s = _state.value
        val result = paginated[s.chapterIndex] ?: return
        val chapter = result.chapter
        when (direction) {
            FlipDirection.FORWARD -> {
                if (s.pageInChapter + 1 < chapter.pages.size) {
                    cancelNavigation()
                    showPage(s.chapterIndex, s.pageInChapter + 1)
                } else if (!result.complete) {
                    // 本章还在往后排，下一页马上就来；跳章会把人甩到下一章开头
                    return
                } else if (s.chapterIndex + 1 < s.chapterCount) {
                    gotoChapter(s.chapterIndex + 1, charOffset = 0)
                } else {
                    _state.value = s.copy(atBookEnd = true)
                }
            }
            FlipDirection.BACKWARD -> {
                if (s.pageInChapter > 0) {
                    cancelNavigation()
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
        progressHeld = false
        val target = ReadPosition(index, charOffset)
        // 同一个目标还在路上（慢源上连点「下一章」）：接着等它，别取消重来 —— 否则每点一下超时就从头算
        if (navJob?.isActive == true && navTarget == target) return
        _state.value = _state.value.copy(loading = paginated[index] == null)
        navigate { target }
        navTarget = target
    }

    fun setReaderSheetOpen(open: Boolean) {
        if (_state.value.readerSheetOpen == open) return
        _state.value = _state.value.copy(readerSheetOpen = open)
    }

    fun seekToPercent(percent: Float) {
        progressHeld = false
        val s = _state.value
        val chapter = paginated[s.chapterIndex]?.chapter ?: return
        val offset = (percent.coerceIn(0f, 1f) * chapter.totalChars).toInt()
        // 进度条拖的是眼前这一章：还在路上的跳章（慢源上刚点的下一章）作废，否则它一落地就把人拽走
        cancelNavigation()
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

    /** 滑一下关菜单并翻页时用这个，别 [toggleMenu]：菜单已关会反过来打开。 */
    fun dismissMenu() {
        if (!_state.value.menuVisible) return
        _state.value = _state.value.copy(menuVisible = false)
    }

    /** UI 层按 App 主题模式 + 系统日夜算出后调这个，阅读纸张随之切日/夜槽 */
    fun setDarkActive(dark: Boolean) = readerPrefs.setDarkActive(dark)

    fun updateSettings(settings: ReaderSettings) {
        viewModelScope.launch { readerPrefs.update(settings) }
    }

    /**
     * 拖滑块（亮度、自定义纸色）时的实时预览：只改内存里的 settings，不落盘。
     * 松手由 [commitSettings] 写一次。
     *
     * 从前 onValueChange 直接走 [updateSettings]：每一帧整份 DataStore 重写一遍（一秒几十次），
     * 滑块的值还要绕「写盘 → prefs 流 → state」一圈才回来，拇指跟不上、来回抖。
     * prefs 流在两次写盘之间不发射，所以这份预览不会被它盖回去。
     */
    fun previewSettings(settings: ReaderSettings) {
        _state.update { it.copy(settings = settings) }
    }

    /** 把 [previewSettings] 预览着的那份落库。读此刻的 state 而不是滑块回调里捕获的旧值 */
    fun commitSettings() = updateSettings(_state.value.settings)

    // ---------- 字体与排版方案 ----------

    val userFonts: StateFlow<List<UserFont>> = fontStore.fonts
    val layoutSchemes: StateFlow<List<LayoutScheme>> = readerPrefs.layoutSchemes

    /** 导入成功就直接用上：人是为了换字体才去导入的，导完还得再点一下才生效像没导进来 */
    fun importFont(uri: Uri) {
        viewModelScope.launch {
            fontStore.import(uri)
                .onSuccess { font ->
                    readerPrefs.update(_state.value.settings.copy(fontId = font.id))
                    messageBus.emit("已导入字体「${font.label}」")
                }
                .onFailure { messageBus.emit("导入失败：${it.message ?: "未知错误"}") }
        }
    }

    /** 删的是正在用的字体 → 先切回默认再删文件，别让分页撞上一个刚消失的字体文件 */
    fun deleteFont(font: UserFont) {
        viewModelScope.launch {
            val current = _state.value.settings
            if (current.fontId == font.id) {
                readerPrefs.update(current.copy(fontId = ReaderSettings.FONT_SYSTEM))
            }
            fontStore.delete(font)
            messageBus.emit("已删除字体「${font.label}」")
        }
    }

    fun applyLayoutScheme(scheme: LayoutScheme) = updateSettings(scheme.applyTo(_state.value.settings))

    fun saveLayoutScheme(name: String) {
        val trimmed = name.trim().take(LayoutScheme.MAX_NAME_LENGTH)
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            readerPrefs.saveLayoutScheme(LayoutScheme.capture(trimmed, _state.value.settings))
            messageBus.emit("已保存排版方案「$trimmed」")
        }
    }

    fun deleteLayoutScheme(name: String) {
        viewModelScope.launch {
            readerPrefs.deleteLayoutScheme(name)
            messageBus.emit("已删除排版方案「$name」")
        }
    }

    // ---------- 自动翻页 ----------

    /**
     * 自动翻页的程序化翻页请求。ReaderScreen 接到 FlipController，
     * 与点击区 / 音量键走同一套翻页动画；勿在此直接调用 [flip]（那会瞬间切页）。
     */
    private val _animatedFlipRequests = MutableSharedFlow<FlipDirection>(extraBufferCapacity = 2)
    val animatedFlipRequests: SharedFlow<FlipDirection> = _animatedFlipRequests.asSharedFlow()

    private var autoFlipJob: Job? = null

    /** 阅读页上有没有长按选中的文字（选区是 UI 状态，由 ReaderScreen 告知） */
    private val selectionActive = MutableStateFlow(false)

    fun setSelectionActive(active: Boolean) {
        selectionActive.value = active
    }

    fun toggleAutoFlip() {
        if (_state.value.autoFlipping) {
            stopAutoFlip()
            return
        }
        _state.value = _state.value.copy(autoFlipping = true, menuVisible = false)
        autoFlipJob = viewModelScope.launch {
            while (true) {
                delay(_state.value.settings.autoFlipSeconds.coerceAtLeast(3) * 1000L)
                // 选字期间停表：手指还按在选区上、工具条还开着，底下那页翻走了，复制/净化拿到的是别页的字。
                // 选区收起后**重新计满一个间隔**再翻 —— 不然刚点完「复制」页面就立刻跳走，像是点出了翻页
                if (selectionActive.value) {
                    selectionActive.first { !it }
                    continue
                }
                val s = _state.value
                // 到书末就停下，而不是每隔几秒弹一次"已经是最后一页了"
                if (!s.hasNext) {
                    stopAutoFlip()
                    break
                }
                _animatedFlipRequests.tryEmit(FlipDirection.FORWARD)
            }
        }
    }

    /**
     * 也由阅读页在 ON_STOP 时调用：锁屏、切走之后循环还在 viewModelScope 里按点发翻页请求，
     * 回来已经翻过几百页、进度跟着存了，后台还白抓了一路章节。
     */
    fun stopAutoFlip() {
        autoFlipJob?.cancel()
        autoFlipJob = null
        _state.value = _state.value.copy(autoFlipping = false)
    }

    /** 正文加载失败后重试：清掉分页缓存重来一遍（站点抽风、临时封 IP 都可能只是一次性的） */
    fun retry() {
        // 手动重试 = 用户想再给一次机会（可能网络恢复了）；自动换源的额度跟着复位
        autoChangeUsed = false
        _state.value = _state.value.copy(error = null, loading = true)
        // 进书那一步就失败了（书源被删、目录拉不到、本地文件丢失）：只重排当前章会在
        // ensurePaginated 的守卫上静默返回，兜底报错又要 source 非空 —— 永远转圈、没有出口。
        if (!sessionReady) {
            viewModelScope.launch { loadSession() }
            return
        }
        // 重试的是**刚才失败的那一章**，不是上一章
        navigate(invalidate = true) { pendingTarget ?: position }
    }

    fun clearBookEnd() {
        _state.value = _state.value.copy(atBookEnd = false)
    }

    /** 本书的规则、通用规则变了 → 已分好的页全部作废，重排当前章（本地书的通用规则也是渲染时跑的，见 loadPurified） */
    private fun observeBookRules() {
        viewModelScope.launch {
            var first = true
            replaceRules.observeForBook(bookId).collect {
                if (first) { first = false; return@collect } // 首帧是初始值，别白重排一次
                navigate(invalidate = true) { pendingTarget ?: position }
            }
        }
    }

    // ---------- 长按选字 ----------

    fun setTextSelectionEnabled(on: Boolean) {
        viewModelScope.launch { appPrefs.setTextSelectionEnabled(on) }
    }

    /** 用选中的文字建一条只对本书生效的净化规则；[replacement] 为空就是删掉 */
    fun purifySelection(selected: String, replacement: String = "") {
        val text = selected.trim()
        if (text.isEmpty()) return
        viewModelScope.launch {
            replaceRules.createFromSelection(bookId, text, replacement)
            _state.value = _state.value.copy(error = null)
            messageBus.emit(
                if (replacement.isEmpty()) "已删除「${text.take(12)}」" else "已替换「${text.take(12)}」",
            )
        }
    }

    /**
     * 当前章各段的正文，下标同排版元素（= `TextSelection.elementIndex`）；章标题、图片、空段是空串。
     * 选字「替换」弹窗拿它数「本章 N 处」、截预览（[replacePreview]）。
     *
     * 读的是分页缓存里的测量结果 —— 就是屏幕上那一份字（已按现有规则净化过），不另读章节文件。
     * 章标题（第 0 个元素）不算：净化只跑正文里的段落和小标题，标题里命中了也换不到。还没排好时是空表。
     */
    fun currentChapterParagraphs(): List<String> {
        val result = paginated[_state.value.chapterIndex] ?: return emptyList()
        val measured = result.measured
        val last = measured.keys.maxOrNull() ?: return emptyList()
        // 没取到的图那一行提示（「图片没加载出来 · 点按重试」）也量在表里，它不是正文，别让净化拿它去匹配
        val imageRows = result.chapter.pages.flatMapTo(HashSet()) { page ->
            page.items.mapNotNull { (it as? PageItem.ImageBox)?.elementIndex }
        }
        return List(last + 1) { i -> if (i == 0 || i in imageRows) "" else measured[i]?.text.orEmpty() }
    }

    // ---------- 正文插图 ----------

    /**
     * 点了「图片没加载出来 · 点按重试」：再取一次，取到了按同一个字符偏移重排（图从提示条变回图，后面的字跟着挪），
     * 还是没取到就说一声 —— 什么反应都没有的话，人会以为没点上。
     */
    fun retryImage(resourceId: String) {
        val src = source ?: return
        if (!retryingImages.add(resourceId)) return
        messageBus.emit("正在重新加载图片…")
        viewModelScope.launch {
            try {
                if (readerImages.retry(src, resourceId)) {
                    if (src === source) navigate(invalidate = true) { pendingTarget ?: position }
                } else {
                    messageBus.emit("图片还是没加载出来")
                }
            } finally {
                retryingImages.remove(resourceId)
            }
        }
    }

    /** 长按看大图：解一张不超过 [maxWidth]×[maxHeight] 的（调用方给屏幕的两倍，放大了也不糊） */
    suspend fun loadViewerImage(resourceId: String, maxWidth: Int, maxHeight: Int): ImageBitmap? {
        val src = source ?: return null
        return readerImages.decodeForViewer(src, resourceId, maxWidth, maxHeight)
    }

    /**
     * 这一页上的图里已经解好的那些；没解好的排进队列，解好一张就刷新一次眼前这几页（[refreshImages]）。
     * 框的大小分页时就定了，图晚到不挪字，先画一块淡底占着（见 PageCanvas）。
     */
    private fun imagesFor(page: PageSpec): Map<Int, ImageBitmap> {
        var out: HashMap<Int, ImageBitmap>? = null
        for (item in page.items) {
            if (item !is PageItem.ImageBox || item.failed) continue
            val w = item.width.toInt().coerceAtLeast(1)
            val h = item.height.toInt().coerceAtLeast(1)
            val bmp = readerImages.bitmap(item.resourceId, w, h)
            if (bmp != null) {
                (out ?: HashMap<Int, ImageBitmap>().also { out = it })[item.elementIndex] = bmp
            } else if (!readerImages.isUndecodable(item.resourceId, w, h)) {
                pendingDecodes.putIfAbsent("${item.resourceId}\u0000$w\u0000$h", item)
            }
        }
        if (pendingDecodes.isNotEmpty() && decodeJob?.isActive != true) startDecoding()
        return out ?: emptyMap()
    }

    private fun startDecoding() {
        decodeJob = viewModelScope.launch {
            while (true) {
                val src = source ?: break
                val (key, box) = pendingDecodes.entries.firstOrNull()?.toPair() ?: break
                pendingDecodes.remove(key)
                readerImages.decode(src, box.resourceId, box.width.toInt().coerceAtLeast(1), box.height.toInt().coerceAtLeast(1))
                refreshImages()
            }
        }
    }

    /**
     * 图解好了：眼前这一页和前后两页按同一份分页结果重建一遍，把新到的图挂上去。不动游标、不存进度。
     * 没变就不写（[samePage] 连图一起比），免得整棵阅读树白重组。
     */
    private fun refreshImages() {
        val s = _state.value
        if (s.page == null) return
        val result = paginated[s.chapterIndex] ?: return
        val page = renderable(result, s.pageInChapter) ?: return
        val (prev, next) = neighborPages(s.chapterIndex, s.pageInChapter)
        if (samePage(page, s.page) && samePage(prev, s.prevPage) && samePage(next, s.nextPage)) return
        _state.value = s.copy(page = page, prevPage = prev, nextPage = next)
    }

    // ---------- 换源 ----------

    private var sourceSearchJob: Job? = null
    /** 候选列表防抖发布：每个源回来就重筛 + 写 State 会在源多时打爆重组 */
    private var candidatePublishJob: Job? = null

    /** 书名已命中的全部结果（未按作者过滤）；作者开关一拨就地重筛，不必重搜 */
    private val rawCandidates = mutableListOf<SearchResult>()

    /**
     * 本会话内是否已经发起过换源搜索（含中途关掉的半截）。
     *
     * 只要发起过，再开面板就直接摊 [rawCandidates]，**不自动重搜** —— 半截结果也比空转圈有用，
     * 要最新的由用户点面板上的「重新搜索」（[force]=true）。
     */
    private var sourceSearchStarted = false

    /** 上一次搜索是否完整跑完。UI 用来区分「真没有」和「上次没搜完」 */
    private var sourceSearchComplete = false

    /**
     * 用书名在其他启用书源中并发搜索，结果**边搜边出**。
     *
     * 从前是 awaitAll 等所有书源跑完才一次性出结果：几百个源里只要有一个站点吊着不回，
     * 整个换源面板就一直转圈；而且没有超时，那个源可能永远不回。
     *
     * @param force 为 true 时忽略会话缓存，强制重搜（面板上的「重新搜索」）。
     */
    fun searchOtherSources(force: Boolean = false) {
        val b = book ?: return
        // 同一次阅读会话里搜过（含半截）→ 直接摊开；要打新请求只能点「重新搜索」
        if (!force && sourceSearchStarted && sourceSearchJob?.isActive != true) {
            _state.value = _state.value.copy(
                searchingSources = false,
                currentLatestChapter = currentLatestChapter(b),
            ).withCandidates()
            return
        }
        sourceSearchJob?.cancel()
        candidatePublishJob?.cancel()
        sourceSearchComplete = false
        sourceSearchStarted = true
        sourceSearchJob = viewModelScope.launch {
            try {
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
                synchronized(rawCandidates) { rawCandidates.clear() }
                val done = AtomicInteger(0)
                _state.value = _state.value.copy(
                    sourceCandidates = emptyList(),
                    authorMismatched = 0,
                    searchingSources = true,
                    sourcesDone = 0,
                    sourcesTotal = all.size,
                    checkAuthor = checkAuthor,
                    currentLatestChapter = currentLatestChapter(b),
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
                                    synchronized(rawCandidates) { rawCandidates += outcome.result }
                                    recordHit(rule.id, true, outcome.result.bookUrl)
                                }
                                SearchOutcome.Miss -> recordHit(rule.id, false, null)
                                SearchOutcome.Failed -> Unit // 什么都没证明，不写库
                            }
                            done.incrementAndGet()
                            scheduleCandidatePublish(done)
                        }
                    }
                }.awaitAll()
                candidatePublishJob?.cancel()
                sourceSearchComplete = true
                _state.value = _state.value.copy(
                    sourcesDone = done.get(),
                    searchingSources = false,
                ).withCandidates()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                sourceSearchComplete = true
                _state.value = _state.value.copy(searchingSources = false).withCandidates()
            }
        }
    }

    /** 进度 + 候选列表合并刷新，避免每个源完成都重筛一次 */
    private fun scheduleCandidatePublish(done: AtomicInteger) {
        if (candidatePublishJob?.isActive == true) return
        candidatePublishJob = viewModelScope.launch {
            delay(CANDIDATE_PUBLISH_DEBOUNCE_MS)
            ensureActive()
            val n = done.get()
            _state.update { it.copy(sourcesDone = n).withCandidates() }
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
        viewModelScope.launch {
            withContext(NonCancellable) {
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
    }

    /** 拨动「匹配作者」：就地重筛已搜到的结果，不重新发请求 */
    fun setCheckAuthor(on: Boolean) {
        viewModelScope.launch { appPrefs.setChangeSourceCheckAuthor(on) }
        _state.value = _state.value.copy(checkAuthor = on).withCandidates()
    }

    /**
     * 把 [rawCandidates] 筛好写进状态：面板列的候选 + 「只看同作者」筛掉了几个。
     * 筛掉的数目一起给，面板才说得出「另有 N 个书源找到同名书，作者对不上」—— 从前它们悄悄消失，
     * 用户只看到「没找到」，想不到是开关卡掉的。
     */
    private fun ReaderUiState.withCandidates(): ReaderUiState {
        val author = book?.author.orEmpty()
        // 换源成功后 rawCandidates 里还躺着刚选上的那个源；列表里再出现「当前源」没意义，点了也是空跑
        val currentId = book?.sourceId
        val others = synchronized(rawCandidates) { rawCandidates.toList() }.filter { it.sourceId != currentId }
        if (!checkAuthor) return copy(sourceCandidates = others, authorMismatched = 0)
        val (kept, mismatched) = others.partition { authorMatches(it.author, author) }
        return copy(sourceCandidates = kept, authorMismatched = mismatched.size)
    }

    /** 换源面板「当前」卡上的最新章：本机目录的最后一章；目录还没抓到（书打不开）退回书行上记的 */
    private fun currentLatestChapter(b: BookEntity): String? =
        _state.value.toc.lastOrNull()?.title ?: b.latestChapterTitle

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
        candidatePublishJob?.cancel()
        viewModelScope.launch {
            _state.value = _state.value.copy(
                changingSource = true,
                changingTo = candidate.sourceId,
                autoChanging = false,
                searchingSources = false,
            )
            // 用户亲自选了源：掐掉可能还在跑的自动换源，否则它探测成功后会把用户的选择覆盖掉
            autoChangeJob?.cancel()
            autoChangeUsed = true
            // 旧源的预取还在飞：必须等它停，否则清完缓存后它们回来会把旧正文写进新源的缓存目录。
            // beginSourceChange 放进 try：等预取停下时被取消，finally 也得把计数还回去
            try {
                beginSourceChange()
                val rule = sourceRepo.getRule(candidate.sourceId)
                if (rule == null) {
                    // 提示而不是 error：error 会把整个阅读区换成报错页，可旧源明明还读得好好的 ——
                    // 换源没成，眼前这一页不该跟着没了
                    _state.value = _state.value.copy(changingSource = false, changingTo = null)
                    messageBus.emit("来源不存在")
                    return@launch
                }
                // 换源前记下旧源：会话缓存要把它塞回候选，否则再开换源面板只能在「新源之外」挑，
                // 退回刚才那个源还得重新搜一遍 —— 正是这次优化要消掉的浪费。
                val prevSourceId = b.sourceId
                val prevBookUrl = b.bookUrl
                val prevSourceName = _state.value.currentSourceName
                netBookRepo.changeSource(b, candidate, rule)
                    .onSuccess {
                        // 选定源时搜索可能还在飞（上面已 cancel）。手里已有候选就当本轮可用，
                        // 否则 complete 仍是 false，下次再开又会全量重搜 —— 白浪费刚搜到的那批。
                        synchronized(rawCandidates) {
                            if (rawCandidates.isNotEmpty()) sourceSearchComplete = true
                            if (prevSourceId != null && prevBookUrl != null &&
                                rawCandidates.none { it.sourceId == prevSourceId }
                            ) {
                                rawCandidates += SearchResult(
                                    title = b.title,
                                    bookUrl = prevBookUrl,
                                    author = b.author.ifBlank { null },
                                    intro = b.intro,
                                    latestChapter = b.latestChapterTitle,
                                    sourceId = prevSourceId,
                                    sourceName = prevSourceName,
                                )
                            }
                        }
                        _state.value = _state.value.copy(
                            changingSource = false,
                            changingTo = null,
                            sourceCandidates = null,
                            menuVisible = false,
                            loading = true,
                            // 换源多半就是为了绕开上一个源的报错；不清掉的话新源加载成功了
                            // 还挂着旧错误，用户以为换源没生效
                            error = null,
                        )
                        // 分页缓存由 loadSession 清掉 —— 在这里清会与在飞的预加载抢
                        loadSession()
                    }
                    .onFailure {
                        // 同上：旧源还能读，失败只提示一句，面板留着让用户换一个试。
                        // 本来就停在报错页（读不出来才来换源）的，报错页原样留着，重试/换源的出口都还在
                        _state.value = _state.value.copy(changingSource = false, changingTo = null)
                        messageBus.emit("换源失败: ${it.message}")
                    }
            } finally {
                endSourceChange()
            }
        }
    }

    /**
     * 换源开始：停掉旧源的预取/邻章预载并等它们真停下，此后不再起新的；旧源抓到的正文也不再落盘。
     *
     * 从前只在开头 stopPrefetch 一次。换源要走一趟网络（详情 + 目录），这几秒里用户关掉面板接着翻，
     * showPage 又把预载、预取拉起来了 —— 它们走的还是旧源，在 changeSource 清完缓存之后才写回来：
     * 旧源的正文成了新目录里的「已缓存」。
     */
    private suspend fun beginSourceChange() {
        sourceChangesInFlight++
        (source as? NetReaderBookSource)?.cacheWritesHeld = true
        stopPrefetch()
    }

    /** 换源结束（成功、失败、被取消都走这里）。没换成的话旧源还在用，恢复它的缓存写入 */
    private fun endSourceChange() {
        sourceChangesInFlight--
        if (!sourceChanging) (source as? NetReaderBookSource)?.cacheWritesHeld = false
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
     * 必须**异步启动**：调用点（ensurePaginated）是在 engineMutex 里跑的，
     * 而换完源要走 loadSession，它也要拿这把锁 —— 同步调用就是自锁。
     */
    private fun maybeAutoChangeSource(cause: String, target: ReadPosition) {
        val b = book ?: return
        if (b.type != BookType.NET) return // 本地书没有"源"可换
        if (autoChangeUsed || autoChangeJob?.isActive == true) return

        autoChangeJob = viewModelScope.launch {
            if (!appPrefs.autoChangeSource.first()) return@launch
            autoChangeUsed = true

            // 探测**失败的那一章**：拿上一章（position）去探，任何能读的源都会"通过"
            val chapterTitle = chapterDao.get(bookId, target.chapterIndex)?.title
            _state.value = _state.value.copy(
                autoChanging = true,
                autoChangeDone = 0,
                autoChangeTotal = 0,
            )

            val probe = autoSwitcher.findWorkingSource(
                title = b.title,
                author = b.author.orEmpty(),
                exclude = b.sourceId,
                target = AutoSourceSwitcher.Target(target.chapterIndex, chapterTitle),
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
                    error = "$cause\n自动换源失败：其他来源也读不出这一章",
                )
                return@launch
            }

            // 旧源的预取还在天上飞。必须等它停，否则 changeSource 清完缓存之后它们才回来，
            // 会把**旧源的正文**写进新源的缓存目录，并给新目录的同序号章节打上"已缓存"。
            // 换源期间也不许再起新的（见 beginSourceChange）。
            try {
                beginSourceChange()

                // 撤销退回的是「原来读到的那个字」—— 最后真正显示出来的 position，而不是没读出来的目标章
                val snapshot = b.sourceId?.let { sid ->
                    b.bookUrl?.let { url ->
                        UndoSnapshot(sid, url, NetBookRepository.ReadAnchor(position.chapterIndex, position.charOffset))
                    }
                }

                netBookRepo.changeSource(
                    b, probe.result, probe.rule,
                    // 库里存的进度还是上一章；按它对齐，换完源就落回刚读完那章 —— 要按目标章对齐
                    alignFrom = NetBookRepository.ReadAnchor(target.chapterIndex, 0),
                )
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
            } finally {
                endSourceChange()
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
        // 与 applyChangeSource 同一道闸：换源要走一整趟网络（详情+目录+清缓存+改库），
        // 连点两下就会有两趟 changeSource 交错写同一本书。这里的 undoSnapshot 还得等
        // onSuccess 才置空，光靠它拦不住第二次点击。
        if (_state.value.changingSource) return
        viewModelScope.launch {
            val rule = sourceRepo.getRule(snap.sourceId)
            if (rule == null) {
                undoSnapshot = null
                _state.value = _state.value.copy(autoChangedTo = null)
                messageBus.emit("原来源已不存在，无法退回")
                return@launch
            }
            _state.value = _state.value.copy(changingSource = true)
            try {
                beginSourceChange()

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
                        _state.value = _state.value.copy(changingSource = false)
                        messageBus.emit("退回失败: ${it.message}")
                    }
            } finally {
                endSourceChange()
            }
        }
    }

    /** 提示胶囊到点收起、用户没撤销：撤销窗口就此关上 */
    fun dismissAutoChanged() {
        undoSnapshot = null
        _state.value = _state.value.copy(autoChangedTo = null)
    }

    fun dismissSourcePanel() {
        sourceSearchJob?.cancel()
        candidatePublishJob?.cancel()
        // 故意不清 rawCandidates / sourceSearchStarted：关掉面板不是放弃结果。
        // 再开直接摊缓存（含半截）；要打新请求点「重新搜索」。
        _state.value = _state.value.copy(sourceCandidates = null, searchingSources = false)
    }

    // ---------- 内部 ----------

    /** 定位到指定位置：必要时加载+分页该章，并预取相邻章 */
    private suspend fun showPosition(target: ReadPosition) {
        // 这条永远是"用户在等的那一章"（进书、翻章、跳目录、重试都走这里），预取不走这里
        pendingTarget = target
        val src = source
        val startSpec = spec
        val startGen = tocGeneration
        val result = ensurePaginated(
            target.chapterIndex,
            userFacing = true,
            notifyAfterChar = target.charOffset,
        ) ?: run {
            // ensurePaginated 的前置守卫是静默返回 null 的。其中"排版环境还没就绪"
            // （source/facade/spec 为 null）属于正常启动时序 —— loadSession/onLayoutReady
            // 随后会再来一次，这里报错只会闪一下假错误。
            // 但环境已就绪却仍拿不到结果（如目录为空导致章号越界），就是真读不出来：
            // 必须让 loading 落地，否则又是一个"永远转圈且没有出口"。
            // 加载期间换了源也不报：ensurePaginated 是有意丢掉旧源结果，新会话随后会自己再定位一次。
            // 加载期间换了视口（旋转、改字号）同理：结果按旧 spec 排的被丢掉了，onLayoutReady
            // 排着队，拿到锁就会按 [pendingTarget] 重来 —— 这里报错只会闪一下假的「读不出来」。
            // 加载期间目录换过（后台追更）也一样：旧序号的结果被丢掉，syncSessionToc 已按新序号重新发起。
            if (src != null && source === src && facade != null && spec != null &&
                spec == startSpec && tocGeneration == startGen && _state.value.error == null
            ) {
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
     * 取正文并应用书内净化（本地书连同通用规则）。
     *
     * 书内净化在这里做、而不是抓取时：用户在阅读页长按选中一句话建规则，指望的是眼前
     * 这一页立刻干净 —— 而这一页早就缓存好了，抓取时的净化再也不会跑到它头上。
     * 本地书没有「抓取时」，通用规则也只能在这里跑。
     */
    private suspend fun loadPurified(src: ReaderBookSource, chapterIndex: Int): ChapterContent {
        // 慢到这个份上的源基本没救（正常源 1-3 秒）。用 withTimeoutOrNull 而不是 withTimeout：
        // 后者抛的是 CancellationException，会被下游的 catch(Exception) 吞掉，还会跟"协程被正常
        // 取消"混为一谈 —— 翻页时取消上一次加载也会被当成超时，进而触发自动换源。
        val raw = withTimeoutOrNull(CONTENT_TIMEOUT_MS) { src.loadChapter(chapterIndex) }
            ?: throw ContentTimeoutException()
        // 本地书不经过引擎，通用规则从前一条都不跑（从阅读导进来一批去广告的规则，本地书毫无反应），在这里和书内规则一起跑。
        // 网上的书不加：通用规则在抓取时已经跑过、结果进了缓存，这里再跑一遍，「a → ab」这种规则会替换两次
        val b = book
        val rules = if (b != null && b.type != BookType.NET) {
            replaceRules.purifyForLocalBook(b.title) + replaceRules.purifyForBook(bookId)
        } else {
            replaceRules.purifyForBook(bookId)
        }
        if (rules.isEmpty()) {
            purifySkipped.remove(chapterIndex)
            return raw
        }
        // 编译规则、逐段替换都切到 Default。调用方在主线程（Main.immediate）上：正则规则交给
        // RegexWatchdog 看着跑，它在 future.get 里一等就是 3 秒 —— 从前这 3 秒卡的就是主线程，
        // 翻页、动画全冻住，严重时直接 ANR。
        //
        // 净化超时：正文照常显示（未净化），但**必须**告诉用户 —— 否则"我的替换规则怎么不生效了"
        // 无从查起。这条路径以前没有任何时间上限：净化是在 engineMutex 里跑的，一旦某条规则
        // 陷进灾难性回溯，锁死的不只是当前章，而是此后所有章节的加载。
        // 提示不在这里弹：这一章可能只是邻章预载，用户眼前那章好好的。记下来，等它真显示出来再说（见 showPage）。
        return withContext(Dispatchers.Default) {
            try {
                val purifier = Purifier.lenient(rules)
                raw.copy(elements = purifier.apply(raw.elements)).also { purifySkipped.remove(chapterIndex) }
            } catch (_: PurifyTimeoutException) {
                purifySkipped.add(chapterIndex)
                raw
            }
        }
    }

    /** 这一章是按未净化显示的（净化超时）：显示出来时提示一次 */
    private fun notifyIfUnpurified(chapterIndex: Int) {
        if (purifySkipped.remove(chapterIndex)) {
            messageBus.emit("净化规则太复杂已跳过，本章未净化")
        }
    }

    private fun renderable(result: Paginator.Result, pageIndex: Int): RenderablePage? {
        val page = result.chapter.pages.getOrNull(pageIndex) ?: return null
        return RenderablePage(
            spec = page,
            measured = result.measured,
            images = imagesFor(page),
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
        pendingTarget = null
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
                !result.complete ||
                chapterIndex + 1 < _state.value.chapterCount,
            loading = false,
            error = null,
            atBookEnd = false,
        )
        saveProgress()
        // 翻到一页 = 人在读。滚动、自动翻页越过页界也走这里
        readingClock.touch()
        if (readingClock.pendingMillis >= ReadingClock.FLUSH_EVERY_MS) flushReadingTime()
        notifyIfUnpurified(chapterIndex)
        // 滚动一次惯性就能甩过好几页，临近章末再预取会来不及，倒数第二页就开始排邻章。
        if (pageIndex <= 1 || pageIndex >= result.chapter.pages.lastIndex - 1) {
            preloadNeighbors(chapterIndex)
        }
    }

    /**
     * 邻章分页完成后刷新相邻页图层，不动游标。
     *
     * 没变就别写：[RenderablePage] 不是 data class、每次现建，照写一份就是新实例 —— 邻章早就排好的话，
     * 这一下什么都没变，却让整棵阅读树重组一遍。
     */
    private fun refreshNeighbors() {
        val s = _state.value
        if (paginated[s.chapterIndex] == null) return
        val (prev, next) = neighborPages(s.chapterIndex, s.pageInChapter)
        if (samePage(prev, s.prevPage) && samePage(next, s.nextPage)) return
        _state.value = s.copy(prevPage = prev, nextPage = next)
    }

    /**
     * 同一份分页结果里的同一页：[renderable] 每次现建外壳，里头的 PageSpec / 测量表是同一个实例。
     * 挂着的图也要一样：图晚到的那一页是同一个 PageSpec，不比图的话新到的图永远挂不上去
     */
    private fun samePage(a: RenderablePage?, b: RenderablePage?): Boolean =
        a === b || (a != null && b != null && a.spec === b.spec && a.measured === b.measured && a.images == b.images)

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
        notifyAfterChar: Int = 0,
    ): Paginator.Result? {
        paginated[chapterIndex]?.let { if (it.complete) return it }
        if (chapterIndex !in 0 until (_state.value.chapterCount)) return null
        val src = source ?: return null
        val facade = facade ?: return null
        val spec = spec ?: return null
        val gen = tocGeneration
        // 发起这次加载的协程（跳章时就是 navJob）。部分结果的 showPage 是另投递到主线程的，
        // 不随它取消 —— 这一跳已经被下一次取代的话，别再把人拽回被放弃的那一章
        val caller = currentCoroutineContext()[Job]
        return try {
            val title = src.chapterTitle(chapterIndex) ?: ""
            // 正文开头和章节名重复的那一两行不排：排版自己会在章首排一个大标题（见 RepeatedTitle）。
            // 放在净化之后：开头的广告行先被删掉，下面那行标题才会露到第一行来。
            // 进度按章内字符偏移记，去掉一行标题旧进度往后挪十来个字，仍落在同一页，不另做迁移
            val purified = loadPurified(src, chapterIndex)
            // 关着图时，网上的书只有图没有字的章照旧当读不出来（见 ImageOnlyChapterException）。
            // 看的是去掉重复标题**之前**：那一行标题也是字，从前它在时这一章不算空
            if (!spec.showImages && book?.type == BookType.NET &&
                purified.elements.isNotEmpty() && purified.elements.all { it is ContentElement.Image }
            ) {
                throw ImageOnlyChapterException()
            }
            val content = purified.copy(
                elements = RepeatedTitle.strip(purified.elements, title, book?.title.orEmpty()),
            )
            // 开着图：排版前先把这一章的图量好（要知道多高才知道这一页装不装得下），见 ReaderImages.measure
            if (spec.showImages) readerImages.measure(src, content)
            // 这次排版作废了没有：视口 / 字号换了、换了源、目录换过。和下面写回前的三道检查是同一组条件
            fun stale() = spec != this@ReaderViewModel.spec || src !== source || gen != tocGeneration
            val result = measureMutex.withLock {
                paginated[chapterIndex]?.let { if (it.complete) return it }
                // 排队等锁期间条件已经变了：别再按旧参数整章排一遍、占着锁让新字号干等。
                // 连点几下 A+ 时，每一档排在锁上的邻章预载都会走到这里
                if (stale()) return null
                withContext(Dispatchers.Default) {
                    val job = coroutineContext[Job]
                    Paginator(facade).paginate(
                        chapterIndex, title, content, spec,
                        notifyAfterChar = notifyAfterChar,
                        images = { readerImages.info(it.resourceId) },
                        // 半截结果不会进缓存：取消会在 withContext 收尾时抛出，stale 由下面的检查丢掉
                        isAbandoned = { job?.isActive == false || stale() },
                        onPartial = { partial ->
                            // 排到一半视口/字号已经换了：这份是按旧 spec 排的，别塞进缓存；源换了、目录换过同理
                            if (stale()) return@paginate
                            paginated[chapterIndex] = partial
                            if (userFacing) {
                                val pageIdx = if (notifyAfterChar == Int.MAX_VALUE) {
                                    partial.chapter.pages.lastIndex
                                } else {
                                    partial.chapter.pageIndexFor(notifyAfterChar)
                                }
                                // 这个回调跑在 Default 上：showPage 读改写 _state / position / 预取 Job，
                                // 直接在这里调就是和主线程上的写入赛跑。投递回主线程 —— 它排在本次
                                // 排版完成后的那次 showPage 之前，先出的仍是这一屏
                                viewModelScope.launch(Dispatchers.Main) {
                                    if (caller?.isActive == false || gen != tocGeneration) return@launch
                                    showPage(chapterIndex, pageIdx, keepOffset = notifyAfterChar)
                                }
                            }
                        },
                    )
                }
            }
            // 排版中途被 isAbandoned 叫停的半截结果：不能当缓存。下面三道检查拦不住它 ——
            // 连点 A+、A- 回到原字号时，作废的条件到这里又不成立了，半截结果会被当成整章写进去
            // （页脚「1/3」而不是「1/25」，按偏移定位也会落到错的页）
            if (!result.complete) return null
            // 排版期间 onLayoutReady 换了 spec（改字号、旋转）：这份结果是按**旧**视口排的，
            // 写回去就会被开头那句 `if (it.complete) return it` 当成有效缓存复用 —— 页面按旧宽度
            // 裁字或留大片空白，直到再换一次设置才好。丢掉，让调用方按新 spec 重排。
            // 邻章预取几乎必踩：网络书拉下一章要一两秒，用户这时点 A+ 是很自然的事。
            if (spec != this.spec) return null
            // 同理：拉正文期间换了源（loadSession 已清过分页缓存），这份是**旧源**的正文。
            // 写回去，下一章翻过去看到的还是旧源的内容 —— 而换源多半就是嫌它的正文不对。
            if (src !== source) return null
            // 同理：拉正文期间目录换过、序号整体错了位，chapterIndex 已经指向另一章
            if (gen != tocGeneration) return null
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
                maybeAutoChangeSource(
                    "章节加载失败: ${e.message}",
                    target = ReadPosition(chapterIndex, notifyAfterChar),
                )
            }
            null
        }
    }

    /**
     * 邻章预载的父 Job（挂在 viewModelScope 下）。每次出页都会发一轮，不能「新一轮取消旧一轮」——
     * 网络书拉一章要一两秒，连翻时就永远排不完。只在换源时由 [stopPrefetch] 一并掐掉并等停。
     */
    private val neighborJobs = SupervisorJob(viewModelScope.coroutineContext[Job])
    private val neighborScope = CoroutineScope(viewModelScope.coroutineContext + neighborJobs)

    /**
     * 一轮预载 / 预取是照着什么发起的。一章里每到首尾两页都会再要一次（[showPage]），
     * 跳章还会由 [showPosition] 再补一次 —— 一章四五轮，照着同一份条件重来纯属白干。
     * 源、排版参数、目录、预取章数任一变了，就是另一轮。
     */
    private data class PreloadKey(
        val source: ReaderBookSource,
        val center: Int,
        val spec: LayoutSpec?,
        val tocGeneration: Int,
        val ahead: Int = 0,
    )

    private var neighborKey: PreloadKey? = null
    private var neighborJob: Job? = null

    private fun preloadNeighbors(center: Int) {
        // 换源进行中不起新的预载/预取：它们走的还是旧源，等 changeSource 清完缓存才写回来，
        // 旧源的正文就成了新目录里的「已缓存」（见 beginSourceChange）。换完 loadSession 会重新发起
        if (sourceChanging) return
        val src = source ?: return
        val count = _state.value.chapterCount
        fun needs(i: Int) = i in 0 until count && paginated[i]?.complete != true
        val key = PreloadKey(src, center, spec, tocGeneration)
        // 两侧都排好了就不必再起：showPage 出页时已经按它们算过 prev/next。
        // 同一份条件的那轮还在跑，也别再叠一轮 —— 叠上去的那轮会在锁外把正文再读一遍、再净化一遍。
        if ((needs(center + 1) || needs(center - 1)) &&
            !(key == neighborKey && neighborJob?.isActive == true)
        ) {
            neighborKey = key
            neighborJob = neighborScope.launch {
                // 邻章排版不占 engineMutex：用户翻章的 IO 不该排队等上一章量字。
                // 测量仍走 measureMutex（facade 不能并发）。
                ensurePaginated(center + 1)
                ensurePaginated(center - 1)
                refreshNeighbors()
            }
        }
        prefetchAhead(center)
    }

    private var prefetchJob: Job? = null
    private var prefetchKey: PreloadKey? = null

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
        if (sourceChanging) return // 理由同 preloadNeighbors
        val ahead = _state.value.settings.preloadChapters
        if (ahead <= 0) return
        val src = source ?: return
        val total = _state.value.chapterCount
        // 同一份条件的那轮还在跑、或已经跑完：不重开。跑完的那轮把该抓的都抓了（失败的等换章再试）；
        // 从前每到章首章尾都 cancel 重来，几章的缓存命中检查一遍遍地过。被取消的那轮（换源）照常重开
        val key = PreloadKey(src, center, spec, tocGeneration, ahead)
        if (key == prefetchKey && prefetchJob?.isCancelled == false) return
        prefetchKey = key
        prefetchJob?.cancel()
        prefetchJob = viewModelScope.launch(Dispatchers.IO) {
            delay(PREFETCH_LEAD_IN_MS)
            for (i in (center + 2)..(center + 1 + ahead)) {
                if (i >= total) break
                if (!isActive) return@launch
                // 已缓存的话 prefetchChapter 只看一眼文件头就返回；没缓存才抓。
                // 网络书这条路径禁止开 WebView（见 NetReaderBookSource.prefetchChapter）。
                try {
                    src.prefetchChapter(i)
                    // 开着图：网上的书把这一章的图也先取进缓存，翻到时不用再等（本地书的图就在包里，不用取）
                    if (src is NetReaderBookSource && src.imagesWanted) src.prefetchImages(i)
                } catch (e: CancellationException) {
                    // 换源/退出取消了预取。runCatching 会吞掉，循环继续往下章写，和清缓存打架。
                    throw e
                } catch (_: Exception) {
                    // 预取失败不影响当前阅读，静默跳过
                }
            }
        }
    }

    /**
     * 换源前必须等预取协程真正停掉。
     * [Job.cancel] 只发信号：预取可能还卡在写缓存，随后 [ChapterContentCache.clear] 会被它写脏。
     */
    private suspend fun stopPrefetch() {
        prefetchJob?.cancelAndJoin()
        prefetchJob = null
        prefetchKey = null
        neighborKey = null
        // 邻章预载同理：它也走 loadChapter，换源后回来会给新目录的同序号章节打上「已缓存」
        neighborJobs.children.toList().forEach { it.cancelAndJoin() }
    }

    /** 只保留 center±1 的分页结果（测量结果持有 TextLayoutResult，较重） */
    private fun trimWindow(center: Int, alsoKeep: Int = center) {
        val keep = ((center - 1)..(center + 1)).toSet() + alsoKeep
        paginated.keys.retainAll { it in keep }
    }

    private var saveProgressJob: Job? = null
    /** 试读选择不加入后不要再把进度写回 —— 那一行马上要被清掉 */
    private var skipProgressFlush = false

    /**
     * 库里的进度超出本机目录、补抓目录又失败（离线、书源抽风）时，[loadSession] 把位置夹到了末章开头。
     * 那只是个**占位**，不是用户读到的地方：从前照样被 showPage / 退后台写回库里，还带着新的 readAt ——
     * 同步按 readAt 取进度，这个倒退就赢过另一台设备的真实进度、扩散到所有设备。
     * 用户真正翻页 / 跳章之后才恢复写入。
     */
    private var progressHeld = false

    /** 试读结束：正式上架。调用方等它完成再退页，避免和丢弃竞态。 */
    suspend fun commitToShelf() = withContext(NonCancellable) {
        val p = position
        bookRepo.saveProgress(bookId, p.chapterIndex, p.charOffset)
        bookRepo.commitToShelf(bookId)
        book = book?.copy(inShelf = true, deleted = false)
        _state.value = _state.value.copy(inShelf = true)
    }

    /** 试读结束：不上架。墓碑试读只清目录；新试读真删，不留同步墓碑。 */
    suspend fun discardUnshelved() = withContext(NonCancellable) {
        skipProgressFlush = true
        bookRepo.discardUnshelved(bookId)
    }

    private fun saveProgress() {
        if (skipProgressFlush || progressHeld) return
        saveProgressJob?.cancel()
        saveProgressJob = viewModelScope.launch {
            delay(PROGRESS_DEBOUNCE_MS)
            persistProgress()
        }
    }

    private suspend fun persistProgress() {
        if (skipProgressFlush || progressHeld) return
        val p = position
        withContext(Dispatchers.IO + NonCancellable) {
            bookRepo.saveProgress(bookId, p.chapterIndex, p.charOffset)
        }
    }

    /** 离开阅读页 / 进后台时立刻刷盘，避免防抖窗口里进程被杀丢进度 */
    fun flushProgress() {
        if (skipProgressFlush || progressHeld) return
        saveProgressJob?.cancel()
        val p = position
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO + NonCancellable).launch {
            bookRepo.saveProgress(bookId, p.chapterIndex, p.charOffset)
        }
    }

    // ---------- 阅读时长 ----------

    private val readingClock = ReadingClock()

    /** 阅读页回到前台（含刚打开）。之前在后台的时间不算 */
    fun onReaderStarted() = readingClock.start()

    /** 进后台 / 离开阅读页：收尾这一段并落库 */
    fun onReaderStopped() {
        readingClock.stop()
        flushReadingTime()
    }

    /** 与 [flushProgress] 同一个理由用进程级作用域：页面销毁时 viewModelScope 已经取消，最后一段会写丢 */
    private fun flushReadingTime() {
        val millis = readingClock.drain()
        if (millis <= 0) return
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO + NonCancellable).launch {
            readingStats.record(bookId, millis)
        }
    }

    override fun onCleared() {
        onReaderStopped()
        flushProgress()
        (source as? AutoCloseable)?.close()
    }

    private companion object {
        const val TAG = "ReaderViewModel"


        /**
         * 预取前先等多久，理由见 [prefetchAhead]。
         *
         * 比进书入场动画（Motion.READER_ENTER_MS = 200）多留一倍：动画结束那一帧往往还在收尾，
         * 紧贴着开工等于没让。翻页时这条等待同样有益（让开翻页动画），而且不会饿着预取——
         * 用户读完一页远不止 400ms。
         */
        const val PREFETCH_LEAD_IN_MS = 400L

        /**
         * 目录多久至多重读一次（[observeToc]）：预取连写几章，不该每章读一遍目录、重组一次菜单。
         * 面板上的缓存标记晚一秒亮无妨；进书那一刻也正好让开入场动画 —— loadSession 自己已经读过一遍目录
         */
        const val TOC_REFRESH_MS = 1_000L

        /** 进度写入防抖；离开阅读页走 [flushProgress] 兜住 */
        const val PROGRESS_DEBOUNCE_MS = 400L

        /** 单个书源的换源搜索超时；卡住的站点不能拖住整个面板 */
        const val SOURCE_SEARCH_TIMEOUT_MS = 30_000L

        /** 换源候选列表防抖；与搜索页同量级，避免每个源完成都刷一次 UI */
        const val CANDIDATE_PUBLISH_DEBOUNCE_MS = 250L

        /**
         * 单章正文加载上限。超过就判这个源"网络很差"，触发自动换源。
         * 正常源 1-3 秒；15 秒还没回来的基本没救，继续等只是让用户对着转圈发呆。
         */
        const val CONTENT_TIMEOUT_MS = 15_000L
    }
}
