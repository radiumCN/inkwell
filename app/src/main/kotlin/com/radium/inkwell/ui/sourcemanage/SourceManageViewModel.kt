package com.radium.inkwell.ui.sourcemanage

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radium.inkwell.data.db.entity.BookSourceEntity
import com.radium.inkwell.data.repo.BookSourceRepository
import com.radium.inkwell.ui.components.MessageBus
import com.radium.inkwell.core.model.ContentElement
import com.radium.inkwell.core.source.BookSourceEngine
import com.radium.inkwell.data.repo.NetBookRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request

/** 一个书源的校验结果 */
data class SourceCheck(val ok: Boolean, val message: String)

/** 校验进度；null = 没在校验 */
data class CheckProgress(val done: Int, val total: Int)

class SourceManageViewModel(
    private val context: Context,
    private val sourceRepo: BookSourceRepository,
    private val netBookRepo: NetBookRepository,
    private val engine: BookSourceEngine,
) : ViewModel() {

    // ---- 多选 ----

    private val _selected = MutableStateFlow<Set<String>>(emptySet())
    val selected: StateFlow<Set<String>> = _selected.asStateFlow()

    fun toggleSelect(id: String) {
        _selected.value = _selected.value.let { if (id in it) it - id else it + id }
    }

    fun selectAll() {
        _selected.value = sources.value.map { it.id }.toSet()
    }

    fun clearSelection() {
        _selected.value = emptySet()
    }

    // ---- 批量操作 ----

    fun deleteSelected() {
        val ids = _selected.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            sourceRepo.deleteAll(ids)
            _checks.value = _checks.value - ids
            clearSelection()
            messages.emit("已删除 ${ids.size} 个书源")
        }
    }

    fun setEnabledSelected(enabled: Boolean) {
        val ids = _selected.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            sourceRepo.setEnabledAll(ids, enabled)
            messages.emit("${if (enabled) "已启用" else "已禁用"} ${ids.size} 个书源")
        }
    }

    /** 删掉校验失败的那些（校验完最想干的事） */
    fun deleteInvalid() {
        val ids = _checks.value.filterValues { !it.ok }.keys
        if (ids.isEmpty()) {
            messages.emit("没有失效的书源")
            return
        }
        viewModelScope.launch {
            sourceRepo.deleteAll(ids)
            _checks.value = _checks.value - ids
            clearSelection()
            messages.emit("已删除 ${ids.size} 个失效书源")
        }
    }

    // ---- 校验 ----

    private val _checks = MutableStateFlow<Map<String, SourceCheck>>(emptyMap())
    val checks: StateFlow<Map<String, SourceCheck>> = _checks.asStateFlow()

    private val _checkProgress = MutableStateFlow<CheckProgress?>(null)
    val checkProgress: StateFlow<CheckProgress?> = _checkProgress.asStateFlow()

    private var checkJob: Job? = null

    fun cancelCheck() {
        checkJob?.cancel()
        _checkProgress.value = null
    }

    /**
     * 校验书源：跑真实的 搜索 → 详情 → 目录 → 正文 冒烟。
     *
     * 只校验搜索是不够的 —— 「搜得到但读不了」的书源一样是废的，那正是这阵子最常见的坏法。
     * 并发限流到 6，避免几百个源同时打网站被限流；单源 45 秒超时。
     *
     * @param ids 为空则校验全部
     */
    fun validate(ids: Collection<String> = emptySet(), keyword: String = "剑") {
        if (checkJob?.isActive == true) return
        val targets = (ids.ifEmpty { sources.value.map { it.id } }).toList()
        if (targets.isEmpty()) return
        checkJob = viewModelScope.launch {
            _checkProgress.value = CheckProgress(0, targets.size)
            var done = 0
            val limiter = Semaphore(6)
            coroutineScope {
                targets.map { id ->
                    async {
                        limiter.withPermit {
                            val check = checkOne(id, keyword)
                            _checks.value = _checks.value + (id to check)
                            done++
                            _checkProgress.value = CheckProgress(done, targets.size)
                        }
                    }
                }.awaitAll()
            }
            _checkProgress.value = null
            val bad = _checks.value.filterKeys { it in targets }.count { !it.value.ok }
            messages.emit("校验完成：可用 ${targets.size - bad}，失效 $bad")
        }
    }

    private suspend fun checkOne(id: String, keyword: String): SourceCheck {
        val rule = sourceRepo.getRule(id) ?: return SourceCheck(false, "书源规则损坏")
        if (rule.search == null) return SourceCheck(true, "仅发现页（不校验搜索）")
        return runCatching {
            withTimeout(45_000) {
                val hit = engine.search(rule, keyword).items.firstOrNull()
                    ?: error("搜索无结果")
                val (_, toc) = netBookRepo.fetchDetailAndToc(rule, hit.bookUrl)
                val first = toc.firstOrNull() ?: error("目录为空")
                val content = engine.getContent(
                    rule, first.url,
                    toc.mapTo(HashSet()) { it.url },
                    chapterVariable = first.variable,
                )
                val chars = content.elements
                    .filterIsInstance<ContentElement.Paragraph>()
                    .sumOf { it.text.length }
                check(chars >= 100) { "正文过短（$chars 字）" }
                "可用 · ${toc.size} 章"
            }
        }.fold(
            onSuccess = { SourceCheck(true, it) },
            onFailure = { SourceCheck(false, it.message?.take(50) ?: "失败") },
        )
    }

    val sources: StateFlow<List<BookSourceEntity>> = sourceRepo.sources
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 无搜索规则（仅发现页可用）的书源 id，列表上打标签避免用户误以为能搜索 */
    val exploreOnlyIds: StateFlow<Set<String>> = sourceRepo.sources
        .map { list ->
            list.mapNotNull { entity ->
                val rule = sourceRepo.parseRule(entity.json).getOrNull() ?: return@mapNotNull null
                entity.id.takeIf { rule.search == null }
            }.toSet()
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

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
