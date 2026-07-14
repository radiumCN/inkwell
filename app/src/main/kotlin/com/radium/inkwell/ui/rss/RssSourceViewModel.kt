package com.radium.inkwell.ui.rss

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radium.inkwell.data.db.entity.RssSourceEntity
import com.radium.inkwell.data.repo.RssImportReport
import com.radium.inkwell.data.repo.RssRepository
import com.radium.inkwell.ui.components.MessageBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RssSourceViewModel(
    private val context: Context,
    private val repo: RssRepository,
) : ViewModel() {

    val sources: StateFlow<List<RssSourceEntity>> = repo.sources
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val messages = MessageBus()

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    fun importFromText(text: String) = runImport { repo.import(text) }

    fun importFromClipboard() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
        if (text.isNullOrBlank()) {
            messages.emit("剪贴板是空的")
            return
        }
        importFromText(text)
    }

    fun importFromFile(uri: Uri) = runImport {
        val text = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        } ?: error("文件读不出内容")
        repo.import(text)
    }

    private fun runImport(block: suspend () -> RssImportReport) {
        if (_importing.value) return
        viewModelScope.launch {
            _importing.value = true
            runCatching { block() }
                .onSuccess { report ->
                    messages.emit(
                        buildString {
                            if (report.total > 0) {
                                append("导入订阅源：新增 ${report.added}")
                                if (report.updated > 0) append("，更新 ${report.updated}")
                            } else {
                                append("没有导入任何订阅源")
                            }
                            // 跳过的必须说清为什么，否则用户只会看到"少了几个"却不知道少在哪
                            if (report.skipped.isNotEmpty()) {
                                append("；跳过 ${report.skipped.size}（")
                                append(report.skipped.take(2).joinToString("；"))
                                append("）")
                            }
                        }
                    )
                }
                .onFailure { messages.emit("导入失败: ${it.message?.take(80)}") }
            _importing.value = false
        }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch { repo.setEnabled(id, enabled) }
    }

    fun delete(id: String) {
        viewModelScope.launch { repo.delete(id) }
    }
}
