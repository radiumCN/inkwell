package com.radium.inkwell.ui.sourcemanage

import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radium.inkwell.data.db.entity.BookSourceEntity
import com.radium.inkwell.data.repo.BookSourceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class SourceManageViewModel(
    private val context: Context,
    private val sourceRepo: BookSourceRepository,
) : ViewModel() {

    val sources: StateFlow<List<BookSourceEntity>> = sourceRepo.sources
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun importFromClipboard() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
        if (text.isNullOrBlank()) {
            _message.value = "剪贴板为空"
            return
        }
        viewModelScope.launch {
            sourceRepo.importJson(text)
                .onSuccess { _message.value = it.summary }
                .onFailure { _message.value = "导入失败: ${it.message}" }
        }
    }

    fun importFromUrl(url: String) {
        if (url.isBlank()) return
        viewModelScope.launch {
            _message.value = "正在下载书源…"
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    OkHttpClient().newCall(Request.Builder().url(url.trim()).build())
                        .execute().use { resp ->
                            check(resp.isSuccessful) { "HTTP ${resp.code}" }
                            resp.body?.string().orEmpty()
                        }
                }
            }
            text.onSuccess { body ->
                sourceRepo.importJson(body)
                    .onSuccess { _message.value = it.summary }
                    .onFailure { _message.value = "导入失败: ${it.message?.take(120)}" }
            }.onFailure { _message.value = "下载失败: ${it.message}" }
        }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch { sourceRepo.setEnabled(id, enabled) }
    }

    fun delete(id: String) {
        viewModelScope.launch { sourceRepo.delete(id) }
    }

    fun clearMessage() {
        _message.value = null
    }
}
