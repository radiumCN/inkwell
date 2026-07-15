package com.radium.inkwell.ui.sourcemanage

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radium.inkwell.data.db.entity.BookSourceEntity
import com.radium.inkwell.data.db.entity.CheckStatus
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

/** 一个书源的校验结果。[respondMs] 响应耗时 —— 书源好坏很大程度上就是"快不快" */
data class SourceCheck(val ok: Boolean, val message: String, val respondMs: Long = 0)

/** 校验进度；null = 没在校验 */
data class CheckProgress(val done: Int, val total: Int)

/**
 * 校验选项。
 *
 * 校验项可以逐个关掉：正文那步最慢也最容易被站点限流，只想快速筛一遍死源时
 * 关掉它能快好几倍。关键词也得可改 —— 生僻词会把好书源误判成「搜索无结果」。
 */
data class CheckOptions(
    val keyword: String = "我的",
    val timeoutMs: Long = 60_000,
    val checkDetail: Boolean = true,
    val checkToc: Boolean = true,
    val checkContent: Boolean = true,
)

enum class SourceFilter(val label: String) {
    ALL("全部"),
    ENABLED("已启用"),
    DISABLED("已禁用"),
    FAILED("失效"),
    UNCHECKED("未校验"),
    EXPLORE_ONLY("仅发现页"),
}

enum class SourceSort(val label: String) {
    MANUAL("手动排序"),
    NAME("按名称"),
    RESPOND_TIME("按响应速度"),
    UPDATED("按更新时间"),
}

/**
 * 排序比较器。抽成顶层函数是为了能直接测 —— 尤其「按响应速度」这条：
 * 未校验和失效的书源 respondTime 是 -1，直接拿它排会让 -1 冒充"最快"，
 * 一堆死源排到最前面，正好排反了。
 */
fun sourceComparator(sort: SourceSort): Comparator<BookSourceEntity> = when (sort) {
    SourceSort.MANUAL -> compareBy({ it.sortOrder }, { it.name })
    SourceSort.NAME -> compareBy { it.name }
    SourceSort.UPDATED -> compareByDescending { it.updatedAt }
    SourceSort.RESPOND_TIME -> compareBy(
        { if (it.respondTime < 0) Long.MAX_VALUE else it.respondTime },
        { it.name },
    )
}

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
        // 只全选**当前可见**的（搜索/筛选/分组之后）—— 否则「失效」筛选下点全选再删除，
        // 会连看不见的正常源一起删掉
        _selected.value = visibleSources.value.map { it.id }.toSet()
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


    // ---- 校验 ----

    private val _checkProgress = MutableStateFlow<CheckProgress?>(null)
    val checkProgress: StateFlow<CheckProgress?> = _checkProgress.asStateFlow()

    private val _options = MutableStateFlow(CheckOptions())
    val options: StateFlow<CheckOptions> = _options.asStateFlow()

    fun setOptions(o: CheckOptions) {
        _options.value = o
    }

    private var checkJob: Job? = null

    val checking: StateFlow<Boolean> = _checkProgress
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun cancelCheck() {
        checkJob?.cancel()
        _checkProgress.value = null
    }

    /**
     * 校验书源：跑真实的 搜索 → 详情 → 目录 → 正文 冒烟。
     *
     * 只校验搜索是不够的 —— 「搜得到但读不了」的书源一样是废的，那正是最常见的坏法。
     * 结果**落库**：几百个源校验一轮好几分钟，只存内存的话退出页面就没了，
     * 用户根本没机会拿它做后续处理（筛选失效、按响应速度排序、批量禁用）。
     *
     * @param ids 为空则校验全部
     */
    fun validate(ids: Collection<String> = emptySet()) {
        if (checkJob?.isActive == true) return
        val targets = (ids.ifEmpty { sources.value.map { it.id } }).toList()
        if (targets.isEmpty()) return
        val opts = _options.value
        checkJob = viewModelScope.launch {
            _checkProgress.value = CheckProgress(0, targets.size)
            var done = 0
            val limiter = Semaphore(CHECK_CONCURRENCY)
            coroutineScope {
                targets.map { id ->
                    async {
                        limiter.withPermit {
                            val check = checkOne(id, opts)
                            sourceRepo.saveCheck(id, check.ok, check.message, check.respondMs)
                            done++
                            _checkProgress.value = CheckProgress(done, targets.size)
                        }
                    }
                }.awaitAll()
            }
            _checkProgress.value = null
            val bad = sources.value.count { it.id in targets && it.checkStatus == CheckStatus.FAILED }
            messages.emit("校验完成：可用 ${targets.size - bad}，失效 $bad")
        }
    }

    /**
     * 校验一个书源。两处刻意放宽，从前它们会把好书源判死：
     * 1. 只探第一章。可第一章常常是「作品相关」「防盗公告」这类没有正文的东西 ——
     *    正文抓不到不是书源坏了。所以再探一章中间的，两章都空才判失败。
     * 2. 要求正文 ≥100 字，否则失败。短章节（序章、过渡章）本来就不到 100 字。
     *    现在只要**非空**就算通过，偏短则照常可用、附带提示。
     */
    private suspend fun checkOne(id: String, opts: CheckOptions): SourceCheck {
        val rule = sourceRepo.getRule(id) ?: return SourceCheck(false, "书源规则损坏")
        if (rule.search == null) return SourceCheck(true, "仅发现页（不校验搜索）")
        val startedAt = System.currentTimeMillis()
        return runCatching {
            withTimeout(opts.timeoutMs) {
                val hit = engine.search(rule, opts.keyword).items.firstOrNull()
                    ?: error("搜索无结果")
                if (!opts.checkDetail && !opts.checkToc && !opts.checkContent) {
                    return@withTimeout ok(toc = 0, chars = -1, startedAt)
                }

                val (_, toc) = netBookRepo.fetchDetailAndToc(rule, hit.bookUrl)
                if (!opts.checkToc && !opts.checkContent) {
                    return@withTimeout ok(toc.size, chars = -1, startedAt)
                }
                check(toc.isNotEmpty()) { "目录为空" }
                if (!opts.checkContent) return@withTimeout ok(toc.size, chars = -1, startedAt)

                val urls = toc.mapTo(HashSet()) { it.url }
                val probes = listOfNotNull(
                    toc.first(),
                    toc.getOrNull(toc.size / 2).takeIf { toc.size > 1 },
                )
                var best = 0
                var lastError: String? = null
                for (chapter in probes) {
                    val chars = runCatching {
                        engine.getContent(rule, chapter.url, urls, chapter.variable)
                            .elements
                            .filterIsInstance<ContentElement.Paragraph>()
                            .sumOf { it.text.length }
                    }.getOrElse { lastError = it.message; 0 }
                    if (chars > best) best = chars
                    if (best >= SHORT_CONTENT_CHARS) break
                }
                check(best > 0) { lastError ?: "正文为空" }
                ok(toc.size, best, startedAt)
            }
        }.getOrElse {
            SourceCheck(false, it.message?.take(60) ?: "失败", System.currentTimeMillis() - startedAt)
        }
    }

    private fun ok(toc: Int, chars: Int, startedAt: Long): SourceCheck {
        val ms = System.currentTimeMillis() - startedAt
        val parts = buildList {
            add("可用")
            if (toc > 0) add("$toc 章")
            add("${ms}ms")
            if (chars in 1 until SHORT_CONTENT_CHARS) add("正文偏短（$chars 字）")
        }
        return SourceCheck(true, parts.joinToString(" · "), ms)
    }

    // ---- 校验之后：拿结果做事 ----

    /** 失效的书源。删除是不可逆的，禁用则留着以后再校验一次 */
    private val failedIds: List<String>
        get() = sources.value.filter { it.checkStatus == CheckStatus.FAILED }.map { it.id }

    fun disableInvalid() {
        val ids = failedIds
        if (ids.isEmpty()) return
        viewModelScope.launch {
            sourceRepo.setEnabledAll(ids, false)
            clearSelection()
            messages.emit("已禁用 ${ids.size} 个失效书源")
        }
    }

    fun deleteInvalid() {
        val ids = failedIds
        if (ids.isEmpty()) return
        viewModelScope.launch {
            sourceRepo.deleteAll(ids)
            clearSelection()
            messages.emit("已删除 ${ids.size} 个失效书源")
        }
    }

    // ---- 排序 / 分组 / 导出 ----

    fun moveToTop() = withSelection { sourceRepo.moveToTop(it); "已置顶 ${it.size} 个" }
    fun moveToBottom() = withSelection { sourceRepo.moveToBottom(it); "已置底 ${it.size} 个" }

    fun setGroup(group: String) = withSelection {
        sourceRepo.setGroup(it, group)
        if (group.isBlank()) "已移出分组" else "已归入「$group」"
    }

    private fun withSelection(block: suspend (Set<String>) -> String) {
        val ids = _selected.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val msg = block(ids)
            clearSelection()
            messages.emit(msg)
        }
    }

    /** 导出选中的书源为 legado 格式 JSON，交给系统分享 */
    fun exportSelected() {
        val ids = _selected.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            runCatching {
                val text = sourceRepo.exportJson(ids)
                val file = java.io.File(context.cacheDir, "inkwell-sources.json")
                withContext(Dispatchers.IO) { file.writeText(text) }
                file
            }.onSuccess { file ->
                _exportFile.emit(file)
                clearSelection()
            }.onFailure { messages.emit("导出失败: ${it.message?.take(60)}") }
        }
    }

    private val _exportFile = kotlinx.coroutines.flow.MutableSharedFlow<java.io.File>(extraBufferCapacity = 1)
    val exportFile = _exportFile

    val sources: StateFlow<List<BookSourceEntity>> = sourceRepo.sources
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ---- 搜索 / 筛选 / 排序 / 分组 ----

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()
    fun setQuery(q: String) { _query.value = q }

    private val _filter = MutableStateFlow(SourceFilter.ALL)
    val filter: StateFlow<SourceFilter> = _filter.asStateFlow()
    fun setFilter(f: SourceFilter) { _filter.value = f }

    private val _sort = MutableStateFlow(SourceSort.MANUAL)
    val sort: StateFlow<SourceSort> = _sort.asStateFlow()
    fun setSort(o: SourceSort) { _sort.value = o }

    /** null = 不按分组筛选 */
    private val _group = MutableStateFlow<String?>(null)
    val group: StateFlow<String?> = _group.asStateFlow()
    fun setGroupFilter(g: String?) { _group.value = g }

    /** 所有出现过的分组名。一个书源可能挂多个组（逗号分隔） */
    val groups: StateFlow<List<String>> = sources
        .map { list ->
            list.flatMap { it.groupName.split(',', '，') }
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .sorted()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 列表实际显示的书源：搜索 + 筛选 + 分组 + 排序 */
    val visibleSources: StateFlow<List<BookSourceEntity>> =
        kotlinx.coroutines.flow.combine(
            sources, _query, _filter, _sort, _group,
        ) { list, q, f, o, g ->
            list.asSequence()
                .filter { matchesQuery(it, q) }
                .filter { matchesFilter(it, f) }
                .filter { g == null || it.groupName.split(',', '，').any { s -> s.trim() == g } }
                .sortedWith(sourceComparator(o))
                .toList()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private fun matchesQuery(s: BookSourceEntity, q: String): Boolean {
        if (q.isBlank()) return true
        val k = q.trim()
        return s.name.contains(k, true) || s.id.contains(k, true) || s.groupName.contains(k, true)
    }

    private fun matchesFilter(s: BookSourceEntity, f: SourceFilter): Boolean = when (f) {
        SourceFilter.ALL -> true
        SourceFilter.ENABLED -> s.enabled
        SourceFilter.DISABLED -> !s.enabled
        SourceFilter.FAILED -> s.checkStatus == CheckStatus.FAILED
        SourceFilter.UNCHECKED -> s.checkStatus == CheckStatus.UNCHECKED
        SourceFilter.EXPLORE_ONLY -> s.id in exploreOnlyIds.value
    }

    val failedCount: StateFlow<Int> = sources
        .map { list -> list.count { it.checkStatus == CheckStatus.FAILED } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

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

    private companion object {
        /** 并发上限。几百个源同时打网站只会集体被限流，反而校验出一堆假的"失效" */
        const val CHECK_CONCURRENCY = 6
        /** 低于此字数只提示"正文偏短"，不判失败 —— 序章、过渡章本来就短 */
        const val SHORT_CONTENT_CHARS = 100
    }
}
