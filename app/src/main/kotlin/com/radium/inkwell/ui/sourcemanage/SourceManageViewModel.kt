package com.radium.inkwell.ui.sourcemanage

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radium.inkwell.data.db.entity.BookSourceListItem
import com.radium.inkwell.data.db.entity.CheckStatus
import kotlinx.coroutines.flow.mapLatest
import com.radium.inkwell.data.repo.BookSourceRepository
import com.radium.inkwell.ui.components.MessageBus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** 一个书源的校验结果。[respondMs] 响应耗时 —— 书源好坏很大程度上就是"快不快" */
data class SourceCheck(val ok: Boolean, val message: String, val respondMs: Long = 0)

/** 校验进度；null = 没在校验 */
data class CheckProgress(
    val done: Int,
    val total: Int,
    /** 这一轮已经判成失效的个数：概况行跟着涨，不用等跑完才知道坏了多少 */
    val failed: Int = 0,
    /** 正在跑的书源（同时最多几个）：那几行就地转圈 */
    val running: Set<String> = emptySet(),
    /** 这一轮还没出结果的（含 [running]）：行上还是上一次的结果，显示时变淡，分得清新旧 */
    val pending: Set<String> = emptySet(),
)

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
 * 0 也算没测到（同搜索调度、自动换源的口径）：没有搜索规则的源校验时一步都不跑，从前记的耗时是 0，
 * 「按响应速度」就把这些根本搜不了的源全排在了最前面。现在校验记 -1，库里已经存着的 0 靠这里兜住。
 */
fun sourceComparator(sort: SourceSort): Comparator<BookSourceListItem> = when (sort) {
    SourceSort.MANUAL -> compareBy({ it.sortOrder }, { it.name })
    SourceSort.NAME -> compareBy { it.name }
    SourceSort.UPDATED -> compareByDescending { it.updatedAt }
    SourceSort.RESPOND_TIME -> compareBy(
        { if (it.respondTime <= 0) Long.MAX_VALUE else it.respondTime },
        { it.name },
    )
}

class SourceManageViewModel(
    private val context: Context,
    private val sourceRepo: BookSourceRepository,
    private val checker: SourceChecker,
    /** 「从网址导入书源」用；走注入的那个客户端，它配了超时（裸 OkHttpClient() 没有总闸） */
    private val http: OkHttpClient,
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
    //
    // 校验本身在应用级的 [SourceChecker] 上跑：离开这页不会被掐掉（见那里）。这里只转发与展示。

    val checkProgress: StateFlow<CheckProgress?> = checker.progress

    val options: StateFlow<CheckOptions> = checker.options

    fun setOptions(o: CheckOptions) = checker.setOptions(o)

    val checking: StateFlow<Boolean> = checker.progress
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun cancelCheck() = checker.cancel()

    /** @param ids 为空则校验全部 */
    fun validate(ids: Collection<String> = emptySet()) {
        val targets = (ids.ifEmpty { sources.value.map { it.id } }).toList()
        if (!checker.start(targets)) messages.emit("正在校验中")
    }

    init {
        // 这一页开着时跑完的那一轮，照旧在页面上报一句总结
        viewModelScope.launch { checker.finished.collect { messages.emit(it) } }
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
                // 落在 cacheDir/export/ 下：FileProvider 只把这个子目录交出去（file_paths.xml），
                // 别让分享面板拿到的 URI 能够到 cacheDir 里的其他东西（下载的安装包、图片缓存）
                val file = java.io.File(java.io.File(context.cacheDir, "export"), "inkwell-sources.json")
                withContext(Dispatchers.IO) {
                    file.parentFile?.mkdirs()
                    file.writeText(text)
                }
                file
            }.onSuccess { file ->
                _exportFile.emit(file)
                clearSelection()
            }.onFailure {
                // 退出页面会取消导出：runCatching 连取消一起接住，不放出去就会弹一条假的「导出失败」
                if (it is CancellationException) throw it
                messages.emit("导出失败: ${it.message?.take(60)}")
            }
        }
    }

    private val _exportFile = kotlinx.coroutines.flow.MutableSharedFlow<java.io.File>(extraBufferCapacity = 1)
    val exportFile = _exportFile

    /**
     * Room 送来的书源列表；null = 第一帧还没到。[sources] 与 [sourcesLoaded] 都从它派生、同一拍更新。
     * 页面靠 sourcesLoaded 才能下「还没有书源」这个结论 —— 拿 emptyList 当初值的话，
     * 每次进页都先闪一下空态和「从文件导入」。
     */
    private val loadedSources: StateFlow<List<BookSourceListItem>?> = sourceRepo.sources
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val sources: StateFlow<List<BookSourceListItem>> = loadedSources
        .map { it.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val sourcesLoaded: StateFlow<Boolean> = loadedSources
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

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

    /** 筛完是空的时候的出口：搜索词、筛选、分组一并放开（排序不算筛选，留着） */
    fun clearFilters() {
        _query.value = ""
        _filter.value = SourceFilter.ALL
        _group.value = null
    }

    /** 所有出现过的分组名。一个书源可能挂多个组（逗号分隔） */
    val groups: StateFlow<List<String>> = sources
        .map { list ->
            list.flatMap { it.groupName.split(',', '，') }
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .sorted()
        }
        // 几千个源逐个拆分组：校验时每存一条结果列表流就重发一次，别在主线程上做
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        // 正筛着的分组没了（这组的书源全删了、或全移去别组 / 移出分组）→ 回到不按分组筛（同书架）。
        // 否则 _group 指着一个已不存在的名字：列表空着，而分组 chip 只在「有分组」时才画 ——
        // 最后一个组没了，chip 跟着消失，用户连取消这个筛选的入口都没有，只看到一片空白。
        viewModelScope.launch {
            groups.collect { gs ->
                val g = _group.value
                if (g != null && g !in gs) _group.value = null
            }
        }
    }

    /**
     * 无搜索规则的书源 id（仅发现页可用的、只能按网址加书的）。
     * 规则按 id **逐条**取 —— 不能再对列表里的 json 字段做 map（列表投影已不含 json）。
     *
     * 只在书源集合或规则本身变了（id / updatedAt）才重算：校验时每个源 saveCheck 一次都会让
     * 列表流重发，从前每发一次就把几百条规则重新解码一遍。
     * 重算也只重取 updatedAt 变了的那几条（[BookSourceRepository.searchlessIds]）：启停、置顶、分组
     * 都会动 updatedAt，从前拨一下开关就把几千份规则整表重解，IO 上跑好几秒，期间「仅发现页」筛选是空的。
     * 必须声明在 [visibleSources] 之前 —— 它是后者 combine 的一路输入，构造期就要取到。
     */
    val searchless: StateFlow<BookSourceRepository.SearchlessIds> = sourceRepo.sources
        .map { list -> list.map { it.id to it.updatedAt } }
        .distinctUntilChanged()
        .mapLatest { list -> sourceRepo.searchlessIds(list) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BookSourceRepository.SearchlessIds())

    /**
     * 列表实际显示的书源：搜索 + 筛选 + 分组 + 排序。
     * [searchless] 也是一路输入：从前只在筛选时读它的当前值，「仅发现页」在它算完之前点下去
     * （或导入之后）列表就一直是空的 / 旧的，直到别的条件变一下。
     *
     * 整条在 Default 上算：几千个源每敲一个字就是上万次 contains 再排一次序，校验时每存一条结果又重来一遍，
     * 从前全压在主线程上。搜索词只对**过滤**防抖（输入框读的仍是 [query]，打字不受影响）；清空立刻生效。
     */
    @OptIn(FlowPreview::class)
    val visibleSources: StateFlow<List<BookSourceListItem>> =
        kotlinx.coroutines.flow.combine(
            sources,
            _query.debounce { if (it.isEmpty()) 0L else QUERY_DEBOUNCE_MS },
            _filter, _sort, _group, searchless,
        ) { args ->
            @Suppress("UNCHECKED_CAST")
            val list = args[0] as List<BookSourceListItem>
            val q = args[1] as String
            val f = args[2] as SourceFilter
            val o = args[3] as SourceSort
            val g = args[4] as String?
            val exploreOnly = (args[5] as BookSourceRepository.SearchlessIds).exploreOnly
            list.asSequence()
                .filter { matchesQuery(it, q) }
                .filter { matchesFilter(it, f, exploreOnly) }
                .filter { g == null || it.groupName.split(',', '，').any { s -> s.trim() == g } }
                .sortedWith(sourceComparator(o))
                .toList()
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        // 多选只对**看得见的**书源生效：选了几个再改搜索词/筛选/分组，看不见的还留在选中集合里，
        // 「删除 N 个」就会连它们一起删掉。可见列表一变就把选中收窄。
        viewModelScope.launch {
            visibleSources.collect { visible ->
                val ids = visible.mapTo(HashSet()) { it.id }
                _selected.update { sel -> if (ids.containsAll(sel)) sel else sel.filterTo(LinkedHashSet()) { it in ids } }
            }
        }
    }

    private fun matchesQuery(s: BookSourceListItem, q: String): Boolean {
        if (q.isBlank()) return true
        val k = q.trim()
        return s.name.contains(k, true) || s.id.contains(k, true) || s.groupName.contains(k, true)
    }

    private fun matchesFilter(s: BookSourceListItem, f: SourceFilter, exploreOnly: Set<String>): Boolean = when (f) {
        SourceFilter.ALL -> true
        SourceFilter.ENABLED -> s.enabled
        SourceFilter.DISABLED -> !s.enabled
        SourceFilter.FAILED -> s.checkStatus == CheckStatus.FAILED
        SourceFilter.UNCHECKED -> s.checkStatus == CheckStatus.UNCHECKED
        SourceFilter.EXPLORE_ONLY -> s.id in exploreOnly
    }

    val failedCount: StateFlow<Int> = sources
        .map { list -> list.count { it.checkStatus == CheckStatus.FAILED } }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** 一次性提示：用事件流而非 StateFlow，避免相同内容的连续提示被去重吞掉 */
    val messages = MessageBus()

    private val _importing = MutableStateFlow(false)
    /** 正在导入（读文件 / 下载 / 解析入库）。页面据此给一行进行中提示，别让人以为没点上、又点一遍 */
    val importing: StateFlow<Boolean> = _importing.asStateFlow()
    private var importJob: Job? = null

    /**
     * 导入一次只跑一个。从前三个入口各起各的协程、全程没有任何反馈：几 MB 的合集要解析好几秒，
     * 用户以为没点上又点一次，两次导入并行，各自读一遍「已有」再各写一遍。
     */
    private fun startImport(block: suspend () -> Unit) {
        if (importJob?.isActive == true) {
            messages.emit("上一次导入还没完成")
            return
        }
        importJob = viewModelScope.launch {
            _importing.value = true
            try {
                block()
            } finally {
                _importing.value = false
            }
        }
    }

    fun importFromFile(uri: Uri) {
        startImport {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use {
                        it.readBytes().toString(Charsets.UTF_8)
                    } ?: error("无法读取文件")
                }
            }
            text.onSuccess { importText(it) }
                .onFailure {
                    // 离开页面会取消这次读取：取消不是「读取失败」，放出去，别弹假提示
                    if (it is CancellationException) throw it
                    messages.emit("读取失败: ${it.message}")
                }
        }
    }

    fun importFromUrl(url: String) {
        if (url.isBlank()) return
        startImport {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    http.newCall(Request.Builder().url(url.trim()).build())
                        .execute().use { resp ->
                            check(resp.isSuccessful) { "HTTP ${resp.code}" }
                            // 封个顶：地址是用户粘来的，指到一个几百 MB 的文件上，body.string() 会把它整个读进内存。
                            // 先预读（request 只往缓冲里填、不消费）确认没超，再交给 body.string() ——
                            // BOM 与 Content-Type 里的字符集它都会处理，自己按 UTF-8 解的话带 BOM 的合集就解析不了
                            val body = resp.body
                            check(!body.source().request(MAX_IMPORT_BYTES + 1L)) {
                                "文件太大（超过 ${MAX_IMPORT_BYTES shr 20}MB）"
                            }
                            body.string()
                        }
                }
            }
            text.onSuccess { importText(it) }
                .onFailure {
                    // 同上：取消不是「下载失败」
                    if (it is CancellationException) throw it
                    messages.emit("下载失败: ${it.message}")
                }
        }
    }

    private suspend fun importText(text: String) {
        sourceRepo.importJson(text)
            .onSuccess { messages.emit(it.summary) }
            .onFailure { messages.emit("导入失败: ${it.message?.take(120)}") }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch { sourceRepo.setEnabled(id, enabled) }
    }

    fun delete(id: String) {
        viewModelScope.launch { sourceRepo.delete(id) }
    }

    private companion object {
        /** 从网址导入书源的大小上限 */
        const val MAX_IMPORT_BYTES = 32 shl 20
        /** 搜索框停手多久才重新过滤：几千个源每个字都全量过滤一遍，打字会跟不上 */
        const val QUERY_DEBOUNCE_MS = 150L
    }
}
