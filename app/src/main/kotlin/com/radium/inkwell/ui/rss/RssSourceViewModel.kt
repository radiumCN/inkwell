package com.radium.inkwell.ui.rss

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radium.inkwell.core.source.rss.RssSourceRule
import com.radium.inkwell.data.db.entity.RssSourceEntity
import com.radium.inkwell.data.repo.RssImportReport
import com.radium.inkwell.data.repo.RssRepository
import com.radium.inkwell.ui.components.MessageBus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RssSourceViewModel(
    private val context: Context,
    private val repo: RssRepository,
) : ViewModel() {

    /**
     * 订阅源列表；null = 库的第一帧还没到。
     * 不用 emptyList 当初值：那和「真没有订阅源」分不开，每次进页都先闪一下空态和「添加订阅源」。
     */
    val sources: StateFlow<List<RssSourceEntity>?> = repo.sources
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * 单网页源的 id（行上挂「网页」、点开进网页页而不是文章列表）。
     * 要解析每个源的规则 JSON：放在列表之外、Default 上算一次，别让每一行在组合时各解析一遍
     */
    val webSourceIds: StateFlow<Set<String>> = repo.sources
        .map { list ->
            list.filterTo(HashSet()) { e -> runCatching { RssSourceRule.fromJson(e.json).singleUrl }.getOrDefault(false) }
                .mapTo(HashSet()) { it.id }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

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
        // 粘的是 feed 地址就要联网抓，可能好几秒：再点一次得告诉人在忙，而不是一声不吭地吞掉
        if (_importing.value) {
            messages.emit("上一次导入还没完成")
            return
        }
        viewModelScope.launch {
            _importing.value = true
            try {
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
                    .onFailure {
                        // 离开页面取消的导入不是「导入失败」：吞掉取消还会让协程带着假结果往下走
                        if (it is CancellationException) throw it
                        messages.emit("导入失败: ${it.message?.take(80)}")
                    }
            } finally {
                _importing.value = false
            }
        }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch { repo.setEnabled(id, enabled) }
    }

    fun delete(id: String) {
        viewModelScope.launch { repo.delete(id) }
    }
}
