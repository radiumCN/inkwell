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
import com.radium.inkwell.data.db.entity.BookType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
     * 按 [BookEntity.readAt] 降序：最近读过的先刷，有更新时更快看见红点。
     * PullToRefresh 转圈只挂一小会儿 —— 整轮 await 绑死指示器时，架上书一多就像卡死。
     * 后台继续跑，顶栏用 [refreshProgress] 显示「更新中 x/y」。
     */
    fun refreshAll() {
        refreshJob?.cancel()
        _refreshing.value = true
        _refreshProgress.value = BookshelfRefreshProgress()
        refreshJob = viewModelScope.launch {
            val myJob = coroutineContext.job
            try {
                val books = allBooks.value
                    .filter { it.type == BookType.NET && !it.sourceId.isNullOrBlank() }
                    .sortedByDescending { it.readAt }
                if (books.isEmpty()) {
                    messages.emit("书架上没有网络书")
                    return@launch
                }
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
                val limiter = Semaphore(REFRESH_CONCURRENCY)
                val done = AtomicInteger(0)
                val added = books.map { book ->
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
                            val d = done.incrementAndGet()
                            if (refreshJob === myJob) {
                                _refreshProgress.update {
                                    it.copy(done = d, total = total, running = true)
                                }
                            }
                        }
                    }
                }.awaitAll()

                val updatedBooks = added.count { it > 0 }
                val totalChapters = added.sum()
                messages.emit(
                    if (updatedBooks == 0) "没有新章节"
                    else "$updatedBooks 本书更新了，共 $totalChapters 章"
                )
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

    /** 书架顶栏是否显示「发现」入口 */
    val exploreEnabled: StateFlow<Boolean> = appPrefs.exploreEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** 书架展示：网格或列表 */
    val layout: StateFlow<BookshelfLayout> = appPrefs.bookshelfLayout
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BookshelfLayout.GRID)

    val allBooks: StateFlow<List<BookEntity>> = bookRepo.books
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val messages = MessageBus()

    // ---- 分组 ----

    /** null = 全部；"" 也是一个真实分组（未分组的书） */
    private val _group = MutableStateFlow<String?>(null)
    val group: StateFlow<String?> = _group.asStateFlow()
    fun setGroup(g: String?) { _group.value = g }

    val groups: StateFlow<List<String>> = allBooks
        .map { list ->
            list.map { it.groupName.trim() }.filter { it.isNotEmpty() }.distinct().sorted()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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

    /** 查看隐藏书籍要不要先验证身份 */
    val hiddenRequireAuth: StateFlow<Boolean> = appPrefs.hiddenRequireAuth
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** 验证过了才允许进来 */
    fun openHiddenPanel() {
        _showHidden.value = true
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

    val books: StateFlow<List<BookEntity>> =
        combine(allBooks, _group, _showHidden) { list, g, showHidden ->
            list
                .filter { showHidden || !it.hidden }
                .filter { book ->
                    when (g) {
                        null -> true
                        UNGROUPED -> book.groupName.isBlank()
                        else -> book.groupName.trim() == g
                    }
                }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ---- 多选 ----

    private val _selected = MutableStateFlow<Set<String>>(emptySet())
    val selected: StateFlow<Set<String>> = _selected.asStateFlow()

    fun toggleSelect(id: String) {
        _selected.value = _selected.value.let { if (id in it) it - id else it + id }
    }

    /** 从长按面板点「多选」：带着这本书进多选态 */
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
            ids.forEach { bookRepo.deleteBook(it) }
            clearSelection()
            messages.emit("已删除 ${ids.size} 本")
        }
    }

    fun assignGroupSelected(group: String) {
        val ids = _selected.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val name = group.trim()
            ids.forEach { bookRepo.setGroup(it, name) }
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
            ids.forEach { bookRepo.setHidden(it, hidden) }
            clearSelection()
            messages.emit(
                if (hidden) "已隐藏 ${ids.size} 本。长按顶栏「书架」标题可以找回"
                else "已取消隐藏 ${ids.size} 本"
            )
        }
    }

    // ---- 单本操作（长按面板） ----

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
