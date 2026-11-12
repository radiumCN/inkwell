package com.radium.inkwell.ui.rss

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radium.inkwell.core.source.rss.RssSourceRule
import com.radium.inkwell.data.repo.RssFeedRepository
import com.radium.inkwell.data.repo.RssImportReport
import com.radium.inkwell.data.repo.RssRepository
import com.radium.inkwell.ui.components.MessageBus
import com.radium.inkwell.ui.components.writeExportFile
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RssSourceViewModel(
    private val context: Context,
    private val repo: RssRepository,
    private val feeds: RssFeedRepository,
) : ViewModel() {

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

    // ---- 搜索 / 筛选 / 分组 ----

    private val _query = MutableStateFlow("")
    /** 输入框直接读它（不经过下面的列表计算），打字不卡 */
    val query: StateFlow<String> = _query.asStateFlow()
    fun setQuery(q: String) { _query.value = q }

    private val _filter = MutableStateFlow(RssSourceFilter.ALL)
    val filter: StateFlow<RssSourceFilter> = _filter.asStateFlow()
    fun setFilter(f: RssSourceFilter) { _filter.value = f }

    /** null = 不按分组筛 */
    private val _group = MutableStateFlow<String?>(null)
    val group: StateFlow<String?> = _group.asStateFlow()
    fun setGroupFilter(g: String?) { _group.value = g }

    /** 筛完是空的时候的出口：搜索词、筛选、分组一并放开 */
    fun clearFilters() {
        _query.value = ""
        _filter.value = RssSourceFilter.ALL
        _group.value = null
    }

    /**
     * 页面的列表：全部 + 搜索筛选之后的 + 分组，一次算好一起给（[buildRssSourceList]）。
     * 三样放在一个值里，而不是各自一个流：分开的话「全部」先到、「显示的」后到，进页先闪一下空列表。
     * null = 库的第一帧还没到。不用空列表当初值：那和「真没有订阅源」分不开，每次进页都先闪一下空态和「添加订阅源」。
     */
    val list: StateFlow<RssSourceList?> =
        combine(repo.sources, _query, _filter, _group) { all, q, f, g -> buildRssSourceList(all, q, f, g) }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // ---- 多选 ----

    private val _selected = MutableStateFlow<Set<String>>(emptySet())
    /** 多选勾上的订阅源 id；空 = 不在多选态 */
    val selected: StateFlow<Set<String>> = _selected.asStateFlow()

    init {
        viewModelScope.launch {
            list.collect { l ->
                if (l == null) return@collect
                // 正筛着的分组没了（这组的源全删了、全移去别组）→ 回到不按分组筛（同来源管理）：
                // 不然分组 chip 跟着消失，连取消这个筛选的入口都没有
                val g = _group.value
                if (g != null && g !in l.groups) _group.value = null
                // 多选只对看得见的生效：选了几个再改搜索词 / 筛选，看不见的还留在选中里，
                // 「删除 N 个」会连它们一起删掉（同来源管理）
                val ids = l.visible.mapTo(HashSet()) { it.id }
                _selected.update { sel -> if (ids.containsAll(sel)) sel else sel.filterTo(LinkedHashSet()) { it in ids } }
            }
        }
    }

    fun toggleSelect(id: String) {
        _selected.update { if (id in it) it - id else it + id }
    }

    /** 只全选当前看得见的（搜索、筛选、分组之后）：「已禁用」下全选再删除，不会连开着的一起删 */
    fun selectAll() {
        _selected.value = list.value?.visible.orEmpty().mapTo(LinkedHashSet()) { it.id }
    }

    fun clearSelection() {
        _selected.value = emptySet()
    }

    // ---- 批量操作（底部操作条） ----

    /** 启停之后留在多选里（同来源管理）：可能还要接着删、导出、设分组 */
    fun setEnabledSelected(enabled: Boolean) = withSelection(clear = false) { ids ->
        repo.setEnabled(ids, enabled)
        "${if (enabled) "已启用" else "已禁用"} ${ids.size} 个订阅源"
    }

    fun deleteSelected() = withSelection(clear = true) { ids ->
        repo.delete(ids)
        // 它们在订阅页缓存的文章一起清；漏了也只是占地方（冷启动的清理会扫掉孤儿），但删了又加回来时那批旧的会冒出来
        ids.forEach { feeds.deleteBySource(it) }
        "已删除 ${ids.size} 个订阅源"
    }

    fun setGroupSelected(group: String) = withSelection(clear = true) { ids ->
        repo.setGroup(ids, group)
        if (group.isBlank()) "已移出分组" else "已归入「${group.trim()}」"
    }

    private fun withSelection(clear: Boolean, block: suspend (Set<String>) -> String) {
        val ids = _selected.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val message = block(ids)
            if (clear) clearSelection()
            messages.emit(message)
        }
    }

    private val _exportFile = MutableSharedFlow<File>(extraBufferCapacity = 1)

    /** 导出好的文件，页面收到就交给系统分享（[com.radium.inkwell.ui.components.CollectExportShares]） */
    val exportFile: SharedFlow<File> = _exportFile.asSharedFlow()

    /** 选中的订阅源导出成阅读的订阅源 json（见 [RssRepository.exportJson]） */
    fun exportSelected() {
        val ids = _selected.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            try {
                val file = writeExportFile(context, "inkwell-rss-sources.json", repo.exportJson(ids))
                clearSelection()
                _exportFile.emit(file)
            } catch (e: CancellationException) {
                // 退出页面会取消导出：接住不放就会弹一条假的「导出失败」
                throw e
            } catch (e: Exception) {
                messages.emit("导出失败: ${e.message?.take(60)}")
            }
        }
    }
}
