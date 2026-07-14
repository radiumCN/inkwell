package com.radium.inkwell.ui.bookshelf

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radium.inkwell.data.db.entity.BookEntity
import com.radium.inkwell.data.repo.BookRepository
import com.radium.inkwell.ui.components.MessageBus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class BookshelfViewModel(
    private val bookRepo: BookRepository,
    private val appPrefs: com.radium.inkwell.data.prefs.AppPrefs,
) : ViewModel() {

    /** 书架顶栏是否显示「发现」入口 */
    val exploreEnabled: StateFlow<Boolean> = appPrefs.exploreEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

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
     * 是否临时显示隐藏的书。
     *
     * **进程内状态，不落库** —— 退出 App 就复位。落库的话「隐藏」就形同虚设：
     * 用户看完一次，开关一直开着，那些书就再也没被隐藏过。
     */
    private val _showHidden = MutableStateFlow(false)
    val showHidden: StateFlow<Boolean> = _showHidden.asStateFlow()

    /**
     * 隐藏区的面板开着没有。
     *
     * 和 [showHidden] **分开**：从前它俩是同一个状态，于是「收起」一个按钮干了两件事 ——
     * 关掉面板，顺手把书又藏回去。用户只是不想看那块面板，书却跟着消失了。
     *
     * 现在「收起」只管面板，书的显隐由面板里的开关说了算。
     */
    private val _hiddenPanelOpen = MutableStateFlow(false)
    val hiddenPanelOpen: StateFlow<Boolean> = _hiddenPanelOpen.asStateFlow()

    /** 查看隐藏书籍要不要先验证身份 */
    val hiddenRequireAuth: StateFlow<Boolean> = appPrefs.hiddenRequireAuth
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** 验证过了才允许进来。进来就默认把书显出来 —— 你就是为这个来的 */
    fun openHiddenPanel() {
        _hiddenPanelOpen.value = true
        _showHidden.value = true
    }

    /** 只关面板。书显不显，看 [setShowHidden] —— 关灯不用钥匙，但也不该顺手把书收走 */
    fun closeHiddenPanel() { _hiddenPanelOpen.value = false }

    fun setShowHidden(on: Boolean) { _showHidden.value = on }

    /** 两个开关都只在隐藏区内部露面，所以设置这件事归书架管，而不是设置页 */
    fun setHiddenRequireAuth(on: Boolean) {
        viewModelScope.launch { appPrefs.setHiddenRequireAuth(on) }
    }

    val hideBooksEnabled: StateFlow<Boolean> = appPrefs.hideBooksEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setHideBooksEnabled(on: Boolean) {
        viewModelScope.launch { appPrefs.setHideBooksEnabled(on) }
    }
    /** 一键收摊：面板关掉，书也藏回去 */
    fun collapseHiddenAll() {
        _hiddenPanelOpen.value = false
        _showHidden.value = false
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

    fun assignGroup(bookId: String, group: String) {
        viewModelScope.launch {
            bookRepo.setGroup(bookId, group.trim())
            messages.emit(if (group.isBlank()) "已移出分组" else "已归入「${group.trim()}」")
        }
    }

    companion object {
        /** 「未分组」这一档的哨兵值 —— 它和「全部」(null) 不是一回事 */
        const val UNGROUPED = "\u0000ungrouped"
    }

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    fun importBooks(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _importing.value = true
            var ok = 0
            var failed = 0
            var lastError: String? = null
            uris.forEach { uri ->
                bookRepo.importLocalBook(uri)
                    .onSuccess { ok++ }
                    .onFailure { failed++; lastError = it.message }
            }
            _importing.value = false
            messages.emit(
                when {
                    failed == 0 -> "已导入 $ok 本"
                    ok == 0 -> "导入失败: $lastError"
                    else -> "导入 $ok 本，失败 $failed 本"
                }
            )
        }
    }

    fun deleteBook(id: String) {
        viewModelScope.launch { bookRepo.deleteBook(id) }
    }

}
