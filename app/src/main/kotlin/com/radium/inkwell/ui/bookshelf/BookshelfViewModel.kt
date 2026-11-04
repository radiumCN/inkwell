package com.radium.inkwell.ui.bookshelf

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radium.inkwell.data.db.entity.BookEntity
import com.radium.inkwell.data.repo.BookRepository
import com.radium.inkwell.data.repo.LocalImportResult
import com.radium.inkwell.ui.components.MessageBus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import com.radium.inkwell.data.db.entity.BookType
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger

/** 书架追更进度：PullToRefresh 转圈早收起后，靠这个告诉用户还在刷 */
data class BookshelfRefreshProgress(
    val done: Int = 0,
    val total: Int = 0,
    val running: Boolean = false,
)

class BookshelfViewModel(
    private val bookRepo: BookRepository,
    private val appPrefs: com.radium.inkwell.data.prefs.AppPrefs,
    private val netBookRepo: com.radium.inkwell.data.repo.NetBookRepository,
    private val sourceRepo: com.radium.inkwell.data.repo.BookSourceRepository,
    private val chapterDao: com.radium.inkwell.data.db.dao.ChapterDao,
) : ViewModel() {

    // ---------- 追更 ----------

    /** 仅驱动 PullToRefresh 指示器；很快复位，真正进度看 [refreshProgress] */
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _refreshProgress = MutableStateFlow(BookshelfRefreshProgress())
    val refreshProgress: StateFlow<BookshelfRefreshProgress> = _refreshProgress.asStateFlow()

    private var refreshJob: Job? = null

    /**
     * 下拉刷新：把书架上所有**网络书**的目录并发刷一遍。
     *
     * 在此之前，追更只能一本一本进详情页点刷新 —— 而你根本不知道哪本书更新了，
     * 于是只能挨个点开碰运气。那不叫追更。
     *
     * 限流 4 而不是全部并发：几十本书同时打同一个站点，会被限流甚至封 IP，
     * 结果是一本都刷不出来。
     *
     * 按「最近阅读」降序（与书架同一口径：[BookEntity.readAt] / [BookEntity.openedAt] 取较晚者）：
     * 最近读过的先刷，有更新时更快看见红点。
     * PullToRefresh 转圈只挂一小会儿 —— 整轮 await 绑死指示器时，架上书一多就像卡死。
     * 后台继续跑，顶栏用 [refreshProgress] 显示「更新中 x/y」。
     *
     * 隐藏区收着时，隐藏的书**照刷、但不计数**：放在看得见的书全部刷完、提示发出、进度条收起之后，
     * 悄悄再刷一轮。不刷的话藏起来的书永远追不上更新（下次进隐藏区红点全是旧的）；计进去的话，
     * 「更新中 3/5」「2 本书更新了」里多出来的那几本，就是在替旁人数「这儿还藏着几本」。
     * 隐藏区开着时它们本来就在眼前，和别的书一样计数。
     */
    fun refreshAll() {
        refreshJob?.cancel()
        _refreshing.value = true
        _refreshProgress.value = BookshelfRefreshProgress()
        // 先登记、再启动。viewModelScope 跑在 Main.immediate 上，普通 launch 会**当场**执行到第一个
        // 挂起点：「书架上没有网络书」这条路一路不挂起就走完了，finally 里的 refreshJob 还是上一轮的
        // （或 null），`refreshJob === myJob` 不成立 —— 转圈永远停不下来。
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            val myJob = coroutineContext.job
            try {
                val showingHidden = _showHidden.value
                val (books, silent) = allBooks.value
                    .filter { it.type == BookType.NET && !it.sourceId.isNullOrBlank() }
                    .sortedByDescending { maxOf(it.readAt, it.openedAt) }
                    .partition { showingHidden || !it.hidden }
                if (books.isEmpty()) {
                    messages.emit("书架上没有网络书")
                } else {
                    val total = books.size
                    _refreshProgress.value = BookshelfRefreshProgress(
                        done = 0,
                        total = total,
                        running = true,
                    )
                    // 转圈早收起：下拉只是「触发追更」，不是「等到刷完」
                    launch {
                        delay(REFRESH_INDICATOR_MS)
                        if (refreshJob === myJob) _refreshing.value = false
                    }
                    val added = refreshTocs(books) { d ->
                        if (refreshJob === myJob) {
                            _refreshProgress.update {
                                it.copy(done = d, total = total, running = true)
                            }
                        }
                    }
                    val updatedBooks = added.count { it > 0 }
                    val totalChapters = added.sum()
                    messages.emit(
                        if (updatedBooks == 0) "没有新章节"
                        else "$updatedBooks 本书更新了，共 $totalChapters 章"
                    )
                }
                if (silent.isNotEmpty()) {
                    // 看得见的已经刷完：转圈与「更新中」先收起，隐藏的那几本不再出现在任何计数里
                    if (refreshJob === myJob) {
                        _refreshing.value = false
                        _refreshProgress.value = BookshelfRefreshProgress()
                    }
                    refreshTocs(silent)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                messages.emit("刷新失败: ${e.message?.take(80)}")
            } finally {
                // 被新一轮 cancel 时别清掉新任务刚写上的进度/转圈
                if (refreshJob === myJob) {
                    _refreshing.value = false
                    _refreshProgress.value = BookshelfRefreshProgress()
                }
            }
        }
        refreshJob = job
        job.start()
    }

    /**
     * 并发刷一批书的目录，返回每本新增的章数（失败/超时记 0）。[onDone] 带上已完成的本数，给进度条用。
     * 限流与超时的理由见 [refreshAll]。
     */
    private suspend fun refreshTocs(
        books: List<BookEntity>,
        onDone: (Int) -> Unit = {},
    ): List<Int> = coroutineScope {
        val limiter = Semaphore(REFRESH_CONCURRENCY)
        val done = AtomicInteger(0)
        books.map { book ->
            async {
                try {
                    limiter.withPermit {
                        val rule = book.sourceId?.let { sourceRepo.getRule(it) }
                            ?: return@withPermit 0
                        // 单本失败/超时不能拖垮整轮 —— 几十本书里总有一两个源在抽风
                        withTimeoutOrNull(TOC_REFRESH_TIMEOUT_MS) {
                            netBookRepo.refreshToc(book, rule).getOrDefault(0)
                        } ?: 0
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    0
                } finally {
                    onDone(done.incrementAndGet())
                }
            }
        }.awaitAll()
    }

    companion object {
        /** 「未分组」这一档的哨兵值 —— 它和「全部」(null) 不是一回事 */
        const val UNGROUPED = "\u0000ungrouped"

        /** 单本追更超时；与换源单源搜索同量级，避免一个吊源拖死整轮下拉 */
        private const val TOC_REFRESH_TIMEOUT_MS = 30_000L

        /** PullToRefresh 指示器展示时长；之后靠顶栏进度条告知仍在刷 */
        private const val REFRESH_INDICATOR_MS = 500L

        private const val REFRESH_CONCURRENCY = 4
    }

    /**
     * 书架顶栏是否显示「发现」入口。从预热版取（见 AppPrefs 的「设置页读的预热版」），初值也用它此刻的值：
     * 从前初值写死 true，关了发现入口的人冷启动首帧先画出「发现」图标再消失，搜索图标跟着挪位。
     * 冷启动时首帧要等主题读到才放行（MainActivity），这两样出自同一次磁盘读取，到那时基本都已就位。
     */
    val exploreEnabled: StateFlow<Boolean> = appPrefs.exploreEnabledState
        .map { it ?: true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), appPrefs.exploreEnabledState.value ?: true)

    /** 书架展示：网格或列表。初值同上，别让列表用户先看一帧网格 */
    val layout: StateFlow<BookshelfLayout> = appPrefs.bookshelfLayoutState
        .map { it ?: BookshelfLayout.GRID }
        .stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5_000),
            appPrefs.bookshelfLayoutState.value ?: BookshelfLayout.GRID,
        )

    /**
     * Room 送来的书架；null = 第一帧还没到。
     *
     * [allBooks] 与 [shelfReady] 都从这一份派生，二者同一拍更新。从前 shelfReady 另订一份
     * `books.map { true }`、allBooks 初值是 emptyList —— 进页那一瞬间「空列表」和「真没有书」分不开，
     * 书架先闪一下空态和「导入本地书」，书才冒出来。
     */
    private val shelf: StateFlow<List<BookEntity>?> = bookRepo.books
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val allBooks: StateFlow<List<BookEntity>> = shelf
        .map { it.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Room 第一帧已到（含空架）。启动指标用它报 timeToFullDisplay，
     * 而不是首帧空白 —— 书架书是异步来的，TTID 测的不是用户看见的那一帧。
     * 页面也靠它决定能不能下「书架是空的」这个结论。
     */
    val shelfReady: StateFlow<Boolean> = shelf
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val messages = MessageBus()

    // ---- 首次启动 ----

    /** null = 还没读到；见 AppPrefs.onboardedState */
    val onboarded: StateFlow<Boolean?> = appPrefs.onboardedState

    fun finishOnboarding() {
        viewModelScope.launch { appPrefs.setOnboarded() }
    }

    init {
        // 书架上一出现书（导入了、WebDAV 恢复下来了、老用户升级上来本来就有）就算走过欢迎页，以后不再出现
        viewModelScope.launch {
            if (appPrefs.onboardedState.filterNotNull().first()) return@launch
            shelf.filterNotNull().first { it.isNotEmpty() }
            appPrefs.setOnboarded()
        }
    }

    // ---- 分组 ----

    /** null = 全部；"" 也是一个真实分组（未分组的书） */
    private val _group = MutableStateFlow<String?>(null)
    val group: StateFlow<String?> = _group.asStateFlow()
    fun setGroup(g: String?) { _group.value = g }

    // 分组名列表 [groups] 声明在隐藏区那一节：它要按「隐藏区开没开」过滤，构造期就得取到 _showHidden

    // ---- 隐藏 ----

    /**
     * 是否处于隐藏区（显示隐藏的书 + 顶上那条状态条）。
     *
     * **进程内状态，不落库** —— 退出 App 就复位。落库的话「隐藏」就形同虚设：
     * 用户看完一次，开关一直开着，那些书就再也没被隐藏过。
     *
     * 打开隐藏区 = 就是要看隐藏的书，不再另设「显不显」开关。收起就整区退出。
     */
    private val _showHidden = MutableStateFlow(false)
    val showHidden: StateFlow<Boolean> = _showHidden.asStateFlow()

    /**
     * 筛选条与「设置分组」里列出的分组名。隐藏区收着时**不算隐藏的书**：一个组里只有隐藏的书，
     * 它的名字就不该作为一个 chip 摆在书架上 —— 点进去是空的，名字本身还泄了底。
     * 隐藏区开着时那些书就在眼前，它们的组照常列出。
     */
    val groups: StateFlow<List<String>> = combine(allBooks, _showHidden) { list, showHidden ->
        list.asSequence()
            .filter { showHidden || !it.hidden }
            .map { it.groupName.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()
            .toList()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        // 正筛着的分组没了（删光了这组的书、或把它们都移去别组、或收起隐藏区后它只剩隐藏的书）
        // → 回到「全部」。否则 _group 指着一个筛选条上已经不存在的名字：书列表空着、chip 一个也不亮，
        // 用户只看到「书架突然空了」。
        viewModelScope.launch {
            groups.collect { gs ->
                val g = _group.value
                if (g != null && g != UNGROUPED && g !in gs) _group.value = null
            }
        }
    }

    /**
     * 「退出 App 就复位」得真的发生。书架是根页面，它的 ViewModel 跟着 Activity 活：
     * Android 12 起在根页按返回只是把任务挪到后台，按 Home、切最近任务更是如此 —— 进程不死，
     * 隐藏区就一直开着，下一个拿起手机的人不用验证就看得到。
     *
     * 跟的是**整个 App** 退到后台（ProcessLifecycleOwner），不是书架页自己的生命周期：
     * 从隐藏区点开一本书进阅读页，书架页也会 stop，那时收起只会逼用户读完回来再验证一次。
     */
    private val collapseHiddenOnBackground = object : DefaultLifecycleObserver {
        override fun onStop(owner: LifecycleOwner) = collapseHiddenAll()
    }

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(collapseHiddenOnBackground)
    }

    override fun onCleared() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(collapseHiddenOnBackground)
    }

    /** 查看隐藏书籍要不要先验证身份 */
    val hiddenRequireAuth: StateFlow<Boolean> = appPrefs.hiddenRequireAuth
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * 验证过了才允许进来。
     *
     * 返回这一下有没有书要并回**当前**列表（当前分组下确有被藏的书）—— 界面据此决定要不要回到顶部。
     * 没有的话列表一本不变，回顶反而把人从原处拽走：第一次想藏书，正是先进隐藏区、再去长按那本书。
     */
    fun openHiddenPanel(): Boolean {
        _showHidden.value = true
        val g = _group.value
        return allBooks.value.any { it.hidden && inGroup(it, g) }
    }

    /** 「展开需验证」只在隐藏区状态条上露面，所以设置这件事归书架管，而不是设置页 */
    fun setHiddenRequireAuth(on: Boolean) {
        viewModelScope.launch { appPrefs.setHiddenRequireAuth(on) }
    }

    /** 退出隐藏区：状态条收起，隐藏的书也藏回去 */
    fun collapseHiddenAll() {
        _showHidden.value = false
    }

    val hiddenCount: StateFlow<Int> = allBooks
        .map { list -> list.count { it.hidden } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /**
     * 开书那一刻的书架顺序；非 null 时 [books] 照它摆。
     *
     * 书架按「最近读 / 打开」排序，而开书当场就会 markOpened —— 这本书立刻跳到第一位。阅读页的
     * 退出动画却是缩回「点开它时的位置」（InkwellNavigator.openOrigin）：缩回去的地方已经换成了别的书；
     * 读满几秒再退出，还会先看见旧顺序、紧接着整架重排一次。冻住顺序，等退出动画播完再放开
     * （[releaseShelfOrder]），这本书再用 animateItem 滑到最前面 —— 顺带交代了它为什么换了位置。
     * 冻结期间新冒出来的书排在最后；书本身的增删、改动照常反映。
     */
    private val frozenOrder = MutableStateFlow<List<String>?>(null)

    fun freezeShelfOrder() {
        frozenOrder.value = books.value.map { it.id }
    }

    fun releaseShelfOrder() {
        frozenOrder.value = null
    }

    val books: StateFlow<List<BookEntity>> =
        combine(allBooks, _group, _showHidden, frozenOrder) { list, g, showHidden, frozen ->
            val visible = list
                .filter { showHidden || !it.hidden }
                .filter { inGroup(it, g) }
            if (frozen == null) {
                visible
            } else {
                val rank = HashMap<String, Int>(frozen.size * 2).apply { frozen.forEachIndexed { i, id -> put(id, i) } }
                visible.sortedBy { rank[it.id] ?: Int.MAX_VALUE }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * 书架顶上的「继续阅读」：最近在读的那一本。只在「全部」里出现 —— 筛到某个分组时人是在找书，
     * 不是要接着读。
     *
     * 取自 [books] 而不是 allBooks：开书时顺序被冻住（见 [frozenOrder]），卡片也就跟着冻住 ——
     * 否则从网格里点开另一本，退出动画还没播完，顶上的卡片就先换了封面。隐藏区收着时隐藏书本来就
     * 不在 [books] 里，卡片也就不会把藏起来的书摆到最显眼的位置。
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val continueReading: StateFlow<ContinueReading?> =
        combine(books, _group) { list, g ->
            if (g != null) null else list.firstOrNull { it.lastActiveAt() > 0L }
        }
            .distinctUntilChanged()
            .mapLatest { book ->
                book?.let { ContinueReading(it, chapterDao.get(it.id, it.readChapterIndex)?.title) }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private fun inGroup(book: BookEntity, g: String?): Boolean = when (g) {
        null -> true
        UNGROUPED -> book.groupName.isBlank()
        else -> book.groupName.trim() == g
    }

    // ---- 多选 ----

    private val _selected = MutableStateFlow<Set<String>>(emptySet())
    val selected: StateFlow<Set<String>> = _selected.asStateFlow()

    init {
        // 多选只对**看得见的**书生效。选了几本再切分组、或收起隐藏区，看不见的书还留在选中集合里，
        // 「删除 N 本」就会把它们一起删掉 —— 用户根本不知道自己选过。可见列表一变就把选中收窄。
        viewModelScope.launch {
            books.collect { visible ->
                val ids = visible.mapTo(HashSet()) { it.id }
                _selected.update { sel -> if (ids.containsAll(sel)) sel else sel.filterTo(LinkedHashSet()) { it in ids } }
            }
        }
    }

    fun toggleSelect(id: String) {
        _selected.value = _selected.value.let { if (id in it) it - id else it + id }
    }

    /** 从长按菜单点「多选」：带着这本书进多选态 */
    fun startSelection(id: String) {
        _selected.value = setOf(id)
    }

    fun selectAll() {
        // 只全选**当前可见**的（分组筛选 + 隐藏区过滤之后）——
        // 否则「未分组」下点全选再删除，会把别的分组里的书一并删掉
        _selected.value = books.value.map { it.id }.toSet()
    }

    fun clearSelection() {
        _selected.value = emptySet()
    }

    fun deleteSelected() {
        val ids = _selected.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            bookRepo.deleteBooks(ids)
            clearSelection()
            messages.emit("已删除 ${ids.size} 本")
        }
    }

    fun assignGroupSelected(group: String) {
        val ids = _selected.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val name = group.trim()
            bookRepo.setGroupAll(ids, name)
            clearSelection()
            messages.emit(
                if (name.isBlank()) "已移出分组 ${ids.size} 本"
                else "已归入「$name」${ids.size} 本"
            )
        }
    }

    fun setHiddenSelected(hidden: Boolean) {
        val ids = _selected.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            bookRepo.setHiddenAll(ids, hidden)
            clearSelection()
            messages.emit(
                if (hidden) "已隐藏 ${ids.size} 本。长按顶栏「书架」标题可以找回"
                else "已取消隐藏 ${ids.size} 本"
            )
        }
    }

    // ---- 单本操作（长按菜单） ----

    fun deleteBook(id: String) {
        viewModelScope.launch { bookRepo.deleteBook(id) }
    }

    fun assignGroup(bookId: String, group: String) {
        viewModelScope.launch {
            bookRepo.setGroup(bookId, group.trim())
            messages.emit(if (group.isBlank()) "已移出分组" else "已归入「${group.trim()}」")
        }
    }

    fun setHidden(bookId: String, hidden: Boolean) {
        viewModelScope.launch {
            bookRepo.setHidden(bookId, hidden)
            messages.emit(
                // 找回的路必须在这里说清楚 —— 入口是个不可见的手势，
                // 不当场告诉他，他就再也想不起来了
                if (hidden) "已隐藏。长按顶栏「书架」标题可以找回"
                else "已取消隐藏"
            )
        }
    }

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    fun importBooks(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _importing.value = true
            var ok = 0
            var skipped = 0
            var failed = 0
            var lastError: String? = null
            uris.forEach { uri ->
                bookRepo.importLocalBook(uri)
                    .onSuccess {
                        when (it) {
                            is LocalImportResult.Added -> ok++
                            is LocalImportResult.AlreadyOnShelf -> skipped++
                        }
                    }
                    .onFailure { failed++; lastError = it.message }
            }
            _importing.value = false
            messages.emit(
                when {
                    failed == 0 && skipped == 0 -> "已导入 $ok 本"
                    failed == 0 && ok == 0 -> if (skipped == 1) "这本书已在书架" else "这些书已在书架"
                    failed == 0 -> "已导入 $ok 本，跳过 $skipped 本（已在书架）"
                    ok == 0 && skipped == 0 -> "导入失败: $lastError"
                    else -> "导入 $ok 本，跳过 $skipped 本，失败 $failed 本"
                }
            )
        }
    }

}
