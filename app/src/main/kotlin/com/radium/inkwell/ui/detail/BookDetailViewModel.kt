package com.radium.inkwell.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radium.inkwell.data.db.dao.ChapterDao
import com.radium.inkwell.data.db.entity.BookEntity
import com.radium.inkwell.data.db.entity.BookType
import com.radium.inkwell.data.db.entity.ChapterEntity
import com.radium.inkwell.data.repo.BookRepository
import com.radium.inkwell.data.repo.BookSourceRepository
import com.radium.inkwell.data.repo.NetBookRepository
import com.radium.inkwell.ui.components.MessageBus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch

data class BookDetailUiState(
    val book: BookEntity? = null,
    /** 区分"还在查库"和"查完了没这本书"：前者转圈，后者给空态 */
    val loaded: Boolean = false,
    val chapters: List<ChapterEntity> = emptyList(),
    /**
     * 章节表读到了没有。书行是单行查询、通常先回来；几千章的目录要几十到几百毫秒 ——
     * 这段时间拿 emptyList 当真，页面就先闪「共 0 章 / 目录还没加载」，网络书还多一个「重新获取」。
     */
    val chaptersLoaded: Boolean = false,
    /** 正在追更（顶栏转圈、空目录时的「正在获取目录…」） */
    val refreshing: Boolean = false,
)

/**
 * 书籍详情。
 *
 * 从前这页没有 ViewModel：四个仓库/DAO 直接在 Composable 里 koinInject，追更和写进度跑在
 * rememberCoroutineScope 上 —— 页面一离开组合（返回、宽屏换一本书、旋屏重建），正在跑的追更连同
 * 它的提示一起被掐断，点目录写进度也可能写到一半就没了。挪到这里，活多久跟着返回栈上的这一格走。
 */
class BookDetailViewModel(
    private val bookId: String,
    private val bookRepo: BookRepository,
    private val netBookRepo: NetBookRepository,
    private val sourceRepo: BookSourceRepository,
    private val chapterDao: ChapterDao,
) : ViewModel() {

    private val refreshing = MutableStateFlow(false)

    /**
     * 书行 + 章节表 + 追更中，**只在页面看得见时**订阅（WhileSubscribed）。
     *
     * 从前两个 collect 挂在 viewModelScope 上常驻：详情页压在返回栈里时照收不误。从详情进书以后，
     * 阅读页每预取一章（改 isCached）章节表就失效一次，这边就跟着把整本几千章的目录重查一遍。
     * 章节流先发一个 null（[BookDetailUiState.chaptersLoaded] = false）：章节表通常比书行晚到，
     * 别先闪「共 0 章」。重新订阅时 StateFlow 留着上一份，返回详情页不会闪空。
     */
    val state: StateFlow<BookDetailUiState> = combine(
        // 追更会改 totalChapters / coverPath / intro —— 订阅书行，刷新后 UI 自动跟上
        bookRepo.observeBook(bookId),
        chapterDao.observeByBook(bookId).map<List<ChapterEntity>, List<ChapterEntity>?> { it }.onStart { emit(null) },
        refreshing,
    ) { book, chapters, refreshing ->
        BookDetailUiState(
            book = book,
            loaded = true,
            chapters = chapters.orEmpty(),
            chaptersLoaded = chapters != null,
            refreshing = refreshing,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BookDetailUiState())

    val messages = MessageBus()

    /** 进度写完了，页面该进阅读器了 */
    private val _openReader = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val openReader: SharedFlow<Unit> = _openReader

    init {
        // WebDAV 只同步书行、不同步章节表。同步下来的网络书进详情时目录是空的 ——
        // 以前要等进阅读器才补抓，详情页自己却装聋作哑（菜单还写着「目录」）。
        // 进页发现章节表为空就自动追更一次；失败不弹提示打扰，留给空态里的「重新获取」。
        viewModelScope.launch {
            val book = bookRepo.observeBook(bookId).filterNotNull().first()
            if (book.type != BookType.NET) return@launch
            // 直接查库，避开 Flow 首帧还是 emptyList 时误触发
            if (chapterDao.getByBook(bookId).isNotEmpty()) return@launch
            refresh(quiet = true)
        }
    }

    /**
     * 追更（刷新目录）。
     *
     * 顺带：封面为空时 refreshToc 会补抓详情（见 NetBookRepository）——
     * WebDAV 同步下来的书缺封面、缺目录，进详情自动跑一次就能两边都补齐。
     *
     * 转圈**先收起、再发提示**：提示走 [MessageBus]，发出去就返回。从前是在这里 await
     * `showSnackbar`，它要等那条提示自己消失（约 4 秒）才返回，转圈和「正在获取目录…」就陪着多挂 4 秒。
     */
    fun refresh(quiet: Boolean = false) {
        if (refreshing.value) return
        refreshing.value = true
        viewModelScope.launch {
            val message = try {
                refreshMessage()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "刷新失败: ${e.message}"
            } finally {
                refreshing.value = false
            }
            if (!quiet) messages.emit(message)
        }
    }

    /** 跑一遍追更，返回要给用户看的那句话 */
    private suspend fun refreshMessage(): String {
        // 取最新行：从进页到点刷新之间，换源可能已经改了 sourceId
        val book = bookRepo.getBook(bookId) ?: return "书不存在"
        val rule = book.sourceId?.let { sourceRepo.getRule(it) } ?: return "书源不存在，先换源"
        return netBookRepo.refreshToc(book, rule).fold(
            onSuccess = { added -> if (added > 0) "更新了 $added 章" else "已经是最新的了" },
            onFailure = { e ->
                if (e is CancellationException) throw e
                "刷新失败: ${e.message}"
            },
        )
    }

    /**
     * 从目录第 [chapterIndex] 章开始读：先把进度写进库，再让页面进阅读器 —— 反过来的话
     * 阅读器开书时读到的还是旧进度。
     *
     * 写进度用 [NonCancellable]：点完立刻返回、或宽屏上马上换了一本书，这一格的 ViewModel 跟着清掉，
     * 这一笔也不能写丢（下次进书就不是你点的那章了）。
     */
    fun openChapter(chapterIndex: Int) {
        viewModelScope.launch(NonCancellable) {
            bookRepo.saveProgress(bookId, chapterIndex, 0)
            _openReader.emit(Unit)
        }
    }
}
