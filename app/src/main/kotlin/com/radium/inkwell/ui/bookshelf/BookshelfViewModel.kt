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
) : ViewModel() {

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

    val books: StateFlow<List<BookEntity>> =
        combine(allBooks, _group) { list, g ->
            when (g) {
                null -> list
                UNGROUPED -> list.filter { it.groupName.isBlank() }
                else -> list.filter { it.groupName.trim() == g }
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
