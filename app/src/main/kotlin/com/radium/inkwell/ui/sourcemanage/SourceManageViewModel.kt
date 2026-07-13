package com.radium.inkwell.ui.sourcemanage

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radium.inkwell.data.db.entity.BookSourceEntity
import com.radium.inkwell.data.repo.BookSourceRepository
import com.radium.inkwell.ui.components.MessageBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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

    /** 一次性提示：用事件流而非 StateFlow，避免相同内容的连续提示被去重吞掉 */
    val messages = MessageBus()

    fun importFromClipboard() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
        if (text.isNullOrBlank()) {
            messages.emit("剪贴板为空")
            return
        }
        importText(text)
    }

    fun importFromFile(uri: Uri) {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use {
                        it.readBytes().toString(Charsets.UTF_8)
                    } ?: error("无法读取文件")
                }
            }
            text.onSuccess { importText(it) }
                .onFailure { messages.emit("读取失败: ${it.message}") }
        }
    }

    fun importFromUrl(url: String) {
        if (url.isBlank()) return
        viewModelScope.launch {
            messages.emit("正在下载书源…")
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    OkHttpClient().newCall(Request.Builder().url(url.trim()).build())
                        .execute().use { resp ->
                            check(resp.isSuccessful) { "HTTP ${resp.code}" }
                            resp.body?.string().orEmpty()
                        }
                }
            }
            text.onSuccess { importText(it) }
                .onFailure { messages.emit("下载失败: ${it.message}") }
        }
    }

    private fun importText(text: String) {
        viewModelScope.launch {
            sourceRepo.importJson(text)
                .onSuccess { messages.emit(it.summary) }
                .onFailure { messages.emit("导入失败: ${it.message?.take(120)}") }
        }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch { sourceRepo.setEnabled(id, enabled) }
    }

    fun delete(id: String) {
        viewModelScope.launch { sourceRepo.delete(id) }
    }
}
