package com.radium.inkwell.ui.sourcewizard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radium.inkwell.core.source.BookSourceEngine
import com.radium.inkwell.core.source.BookSourceRule
import com.radium.inkwell.core.source.SearchResult
import com.radium.inkwell.core.source.detect.BookPageAnalysis
import com.radium.inkwell.core.source.detect.BookPeek
import com.radium.inkwell.core.source.detect.ChapterPageAnalysis
import com.radium.inkwell.core.source.detect.ContentCandidate
import com.radium.inkwell.core.source.detect.DetectedBookInfo
import com.radium.inkwell.core.source.detect.DryRun
import com.radium.inkwell.core.source.detect.RuleField
import com.radium.inkwell.core.source.detect.STAGE_DETAIL
import com.radium.inkwell.core.source.detect.SearchAnalysis
import com.radium.inkwell.core.source.detect.SearchTemplates
import com.radium.inkwell.core.source.detect.SiteAnalyzer
import com.radium.inkwell.core.source.detect.SourceDraftBuilder
import com.radium.inkwell.core.source.detect.TocCandidate
import com.radium.inkwell.data.repo.BookSourceRepository
import com.radium.inkwell.data.repo.NetBookRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class WizardStep {
    /** 输入网址；识别进行中也停在这一步，按钮转圈 */
    Input,

    /** 核对识别结果、起名、保存 */
    Review,

    /** 网页里搜一次，记下搜索请求（P2） */
    SearchCapture,

    /** 网页上点一下目录里的一章，纠正认错的目录（P3） */
    PickToc,

    /** 网页上点一下正文，纠正认错的正文块（P3） */
    PickContent,

    /** 逐项看、改规则，每项显示试抓结果（P3） */
    Advanced,
}

data class SourceWizardUiState(
    val url: String = "",
    val step: WizardStep = WizardStep.Input,
    /** 识别进行到哪一步（「正在识别目录…」）；null = 空闲 */
    val progress: String? = null,
    /** 输入页的失败原因：打不开、没认出目录。留在页面上，不走一闪而过的 Snackbar —— 用户得照着它改 */
    val inputError: String? = null,
    val book: BookPageAnalysis? = null,
    val tocIndex: Int = 0,
    /** 当前目录候选的样本章识别结果；换目录候选时重认 */
    val chapter: ChapterPageAnalysis? = null,
    val chapterLoading: Boolean = false,
    val chapterError: String? = null,
    val contentIndex: Int = 0,
    /** 详情页上没认出书名时让用户手填；认出来了就不用 */
    val manualTitle: String = "",
    val sourceName: String = "",
    val saving: Boolean = false,
    val saveError: String? = null,
    /** 这个站已经有书源了：弹窗问是用它打开，还是重新识别 */
    val existing: BookSourceRule? = null,
    val openingExisting: Boolean = false,
    /**
     * 存好（或选了已有书源）后要打开的那本书，页面看到它就跳预览页。放在状态里而不是一次性事件：
     * 保存可能要一分多钟，恰好在存完那一刻页面重建（转屏、切深色）的话，一次性事件发出去没人收就丢了 ——
     * 用户停在核对页、保存按钮又能点了，再点一次就是第二份书源。
     */
    val opened: SearchResult? = null,

    // ---- 搜索（P2） ----
    val search: SearchAnalysis? = null,
    val searchLoading: Boolean = false,
    val searchError: String? = null,
    /** 网页里当前停在哪一页：搜一次时，没有表单可抓就从这个地址里找关键词 */
    val webUrl: String = "",

    // ---- 点选 / 高级（P3） ----
    val pickLoading: Boolean = false,
    val pickError: String? = null,
    /** 在「高级」里手动改过、确认用的整份规则；非空时保存用它，不再按候选现拼 */
    val customRule: BookSourceRule? = null,
    /** 「高级」页每一栏正在编辑的规则文字 */
    val editing: Map<RuleField, String> = emptyMap(),
    val dryRun: DryRun? = null,
    val dryRunning: Boolean = false,
) {
    val toc: TocCandidate? get() = book?.tocCandidates?.getOrNull(tocIndex)

    val canSave: Boolean
        get() = opened == null && !saving && !chapterLoading && !searchLoading &&
            (customRule != null || chapter?.contentCandidates?.getOrNull(contentIndex) != null) &&
            (book?.info?.name != null || manualTitle.isNotBlank())
}

/**
 * 「新建书源」向导：给一本书的网址 → 认出目录与正文 → 用户核对 → 存成一个普通书源并打开这本书。
 *
 * 识别本身在 core（[SiteAnalyzer]），这里管流程：
 * - 先看这个站是不是已经有书源了 —— 有就直接用它打开，不再生成一份重复的；
 * - 换目录候选时，样本章跟着换，正文重认；
 * - 搜索可选：用户在网页里搜一次，从那次请求生成搜索地址、认结果列表、用引擎真搜一遍；
 * - 认错了有两层兜底：网页上点一下指给它看（围绕点中的元素重认），或者在「高级」里逐项改规则；
 * - 存之前用**引擎**按最终规则把详情、目录、正文完整走一遍。识别器说认出来了不算数，
 *   引擎真抓得出来才存 —— 否则用户建完源一打开就是空书。
 */
class SourceWizardViewModel(
    private val analyzer: SiteAnalyzer,
    private val sourceRepo: BookSourceRepository,
    private val netBookRepo: NetBookRepository,
    private val engine: BookSourceEngine,
) : ViewModel() {

    private val _state = MutableStateFlow(SourceWizardUiState())
    val state: StateFlow<SourceWizardUiState> = _state.asStateFlow()

    /** 识别 / 保存 / 用已有书源打开：同一时刻只跑一个，新的开始前掐掉旧的，免得旧结果串进来 */
    private var job: Job? = null

    /** 换目录候选后重认正文；连点几个候选时只留最后一个 */
    private var chapterJob: Job? = null

    /** 试搜、点选、试抓：各自可被同类的新操作打断 */
    private var searchJob: Job? = null
    private var pickJob: Job? = null
    private var dryRunJob: Job? = null

    /** 网页里最近一次提交的表单。只在 [WizardStep.SearchCapture] 里收，离开就清 */
    private var capturedForm: CapturedForm? = null

    fun setUrl(text: String) = _state.update { it.copy(url = text, inputError = null) }

    fun setManualTitle(text: String) = _state.update { it.copy(manualTitle = text) }

    fun setSourceName(text: String) = _state.update { it.copy(sourceName = text) }

    fun selectContent(index: Int) = _state.update { it.copy(contentIndex = index, saveError = null, customRule = null) }

    fun submit() {
        val url = normalizeBookUrl(_state.value.url) ?: run {
            _state.update { it.copy(inputError = "这不像一个网址。请粘贴以 http:// 或 https:// 开头的地址") }
            return
        }
        _state.update { it.copy(url = url, inputError = null) }
        job?.cancel()
        job = viewModelScope.launch {
            val existing = sourceRepo.findByHost(url).firstOrNull()
            if (existing != null) {
                _state.update { it.copy(existing = existing) }
            } else {
                analyze(url)
            }
        }
    }

    /** 弹窗外点一下：两样都不选，回到输入页 */
    fun dismissExisting() {
        // 「直接打开」已经在认了：关掉弹窗就是不要了，别等它认完又自己跳走
        if (_state.value.openingExisting) job?.cancel()
        _state.update { it.copy(existing = null, openingExisting = false) }
    }

    /** 弹窗里选了「重新识别」：不管已有的书源，照常识别、另建一个 */
    fun analyzeAnyway() {
        val url = _state.value.url
        _state.update { it.copy(existing = null) }
        job?.cancel()
        job = viewModelScope.launch { analyze(url) }
    }

    /**
     * 用已有书源打开这本书：交给预览页，加书架、阅读都走它。先在页面上认一下：
     * 给的若是一章，书的地址换成目录那页（书源的详情规则是照书页写的，拿章节页去套什么都认不出）；
     * 预览页还要个书名占位 —— 已有书源的详情规则未必认得出书名（不少源的书名只在搜索结果里给）。
     */
    fun openWithExisting() {
        val rule = _state.value.existing ?: return
        val url = _state.value.url
        job?.cancel()
        job = viewModelScope.launch {
            _state.update { it.copy(openingExisting = true) }
            val peek = attempt(PEEK_TIMEOUT_MS) { analyzer.peekBook(url) }.getOrDefault(BookPeek(url, DetectedBookInfo()))
            val info = peek.info
            val book = SearchResult(
                title = info.name?.value.orEmpty(),
                bookUrl = peek.bookUrl,
                author = info.author?.value,
                coverUrl = info.coverUrl?.value,
                intro = info.intro?.value,
                sourceId = rule.id,
                sourceName = rule.name,
            )
            _state.update { it.copy(openingExisting = false, existing = null, opened = book) }
        }
    }

    /**
     * 返回键 / 顶栏返回：网页、高级这些子步骤先回核对页；核对页回输入页（认了半天的结果别一下子扔掉，
     * 用户多半只是想改个网址）。返回 false 表示已在输入页，该退出整页了。
     */
    fun back(): Boolean {
        when (_state.value.step) {
            WizardStep.Input -> return false
            WizardStep.Review -> backToInput()
            WizardStep.SearchCapture, WizardStep.PickToc, WizardStep.PickContent -> leaveWeb()
            WizardStep.Advanced -> {
                dryRunJob?.cancel()
                _state.update { it.copy(step = WizardStep.Review, dryRunning = false) }
            }
        }
        return true
    }

    private fun backToInput() {
        job?.cancel()
        chapterJob?.cancel()
        searchJob?.cancel()
        _state.update {
            it.copy(
                step = WizardStep.Input, progress = null, saving = false, chapterLoading = false,
                searchLoading = false, search = null, searchError = null, customRule = null, dryRun = null,
            )
        }
    }

    private suspend fun analyze(url: String) {
        _state.update { it.copy(progress = "正在识别目录…", inputError = null) }
        val book = attempt(BOOK_TIMEOUT_MS) { analyzer.analyzeBook(url) }.getOrElse { e ->
            _state.update { it.copy(progress = null, inputError = failureText(e)) }
            return
        }
        val toc = book.tocCandidates.firstOrNull() ?: run {
            _state.update {
                it.copy(
                    progress = null,
                    inputError = "这一页上没认出章节列表。请打开这本书的目录页（列出全部章节的那一页），复制它的地址再试",
                )
            }
            return
        }
        _state.update { it.copy(progress = "正在识别正文…") }
        val chapter = attempt(CHAPTER_TIMEOUT_MS) { analyzer.analyzeChapter(toc.sampleChapter.url) }
        _state.update {
            it.copy(
                progress = null,
                step = WizardStep.Review,
                book = book,
                tocIndex = 0,
                chapter = chapter.getOrNull(),
                chapterLoading = false,
                chapterError = chapterProblem(chapter),
                contentIndex = 0,
                manualTitle = "",
                sourceName = SourceDraftBuilder.defaultName(book.bookUrl),
                saveError = null,
                search = null,
                searchError = null,
                customRule = null,
                dryRun = null,
            )
        }
    }

    /** 换一组目录：样本章跟着变，正文重认 */
    fun selectToc(index: Int) {
        val s = _state.value
        val book = s.book ?: return
        if (index == s.tocIndex || index !in book.tocCandidates.indices) return
        _state.update { it.copy(tocIndex = index) }
        reanalyzeChapter()
    }

    /** 当前目录候选的样本章重认正文；手动改过的规则作废（目录都换了，改的那些不再对得上） */
    private fun reanalyzeChapter() {
        val toc = _state.value.toc ?: return
        _state.update {
            it.copy(chapter = null, chapterLoading = true, chapterError = null, contentIndex = 0, saveError = null, customRule = null)
        }
        chapterJob?.cancel()
        chapterJob = viewModelScope.launch {
            val chapter = attempt(CHAPTER_TIMEOUT_MS) { analyzer.analyzeChapter(toc.sampleChapter.url) }
            _state.update {
                it.copy(chapter = chapter.getOrNull(), chapterLoading = false, chapterError = chapterProblem(chapter))
            }
        }
    }

    // ---- 网页（搜索 / 点选） ----

    fun openSearchCapture() = enterWeb(WizardStep.SearchCapture)

    fun openPickToc() = enterWeb(WizardStep.PickToc)

    fun openPickContent() {
        if (_state.value.chapter == null) return
        enterWeb(WizardStep.PickContent)
    }

    /** 网页里打开哪一页：搜索从站点首页搜（搜索框几乎都在首页），点目录在目录页，点正文在样本章 */
    fun webStartUrl(s: SourceWizardUiState): String? = when (s.step) {
        WizardStep.SearchCapture -> s.book?.bookUrl?.let(SourceDraftBuilder::siteOrigin)?.let { "$it/" }
        WizardStep.PickToc -> s.book?.tocUrl
        WizardStep.PickContent -> s.chapter?.url
        else -> null
    }

    private fun enterWeb(step: WizardStep) {
        capturedForm = null
        _state.update { it.copy(step = step, webUrl = "", pickError = null, pickLoading = false, searchError = null) }
    }

    /** 用户自己退出网页：掐掉还在认的点选 */
    private fun leaveWeb() {
        pickJob?.cancel()
        backToReview()
    }

    /**
     * 回核对页，不碰协程。点选认完之后在它自己的协程里调这个 —— 调 [leaveWeb] 就是自己取消自己，
     * 后面的状态更新会在下一个挂起点被取消异常打断。
     */
    private fun backToReview() {
        capturedForm = null
        _state.update { it.copy(step = WizardStep.Review, pickLoading = false) }
    }

    fun onWebPage(url: String) = _state.update { it.copy(webUrl = url) }

    /** 网页里提交了表单。不是这个站的（广告 iframe）不认；最后一次为准 */
    fun onFormSubmitted(form: CapturedForm) {
        val s = _state.value
        val book = s.book ?: return
        if (s.step != WizardStep.SearchCapture) return
        if (!SearchTemplates.belongsTo(form.action, book.bookUrl)) return
        if (SearchTemplates.formKeyword(form.fields) == null) return
        capturedForm = form
    }

    /**
     * 「就是这一页」：从刚才那次搜索生成搜索地址，回核对页试搜。
     * 表单优先（字段、方法、编码都在）；没有表单（脚本拼地址跳过去的站）就在当前地址里找建议的那个词。
     */
    fun confirmSearchCapture() {
        val s = _state.value
        val book = s.book ?: return
        val attempts = searchAttempts(capturedForm, s.webUrl)
        if (attempts.isEmpty()) {
            _state.update {
                it.copy(searchError = "没看出这次搜索的请求。请用网站自己的搜索框搜「${SiteAnalyzer.SUGGESTED_KEYWORD}」，出现结果后再点")
            }
            return
        }
        leaveWeb()
        _state.update { it.copy(searchLoading = true, searchError = null, search = null, customRule = null) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            // 第一个认得出结果列表的为准；都不行就报第一个的原因（表单那条最有可能是对的，它的原因最有用）
            var firstError: Throwable? = null
            for ((template, keyword) in attempts) {
                val result = attempt(SEARCH_TIMEOUT_MS) { analyzer.analyzeSearch(template, keyword, book.bookUrl) }
                result.onSuccess { found ->
                    _state.update { it.copy(searchLoading = false, search = found, searchError = null) }
                    return@launch
                }
                if (firstError == null) firstError = result.exceptionOrNull()
            }
            _state.update { it.copy(searchLoading = false, searchError = firstError?.let(::failureText)) }
        }
    }

    fun removeSearch() {
        searchJob?.cancel()
        _state.update { it.copy(search = null, searchError = null, searchLoading = false, customRule = null) }
    }

    /**
     * 网页上点中了东西：围绕它在静态页上重认，认出来就当作首选候选、回核对页。
     * 点选不信网页报上来的结构，只拿它当线索 —— 规则最终是在引擎抓到的那份页面上生成的。
     */
    fun onPicked(href: String, text: String) {
        val s = _state.value
        val book = s.book ?: return
        when (s.step) {
            WizardStep.PickToc -> {
                if (href.isBlank()) return
                runPick {
                    val picked = analyzer.pickToc(book.tocUrl, href)
                        ?: return@runPick "没在你点的地方认出目录。请点目录里的某一章（章节名本身）"
                    val others = book.tocCandidates.filter { it.listRule != picked.listRule }
                    _state.update { it.copy(book = book.copy(tocCandidates = listOf(picked) + others), tocIndex = 0) }
                    backToReview()
                    reanalyzeChapter()
                    null
                }
            }
            WizardStep.PickContent -> {
                val chapter = s.chapter ?: return
                runPick {
                    val picked = analyzer.pickContent(chapter.url, text)
                        ?: return@runPick "没在你点的地方认出正文。请点正文里的某一段文字"
                    val others = chapter.contentCandidates.filter { it.rule != picked.rule }
                    _state.update {
                        it.copy(
                            chapter = chapter.copy(contentCandidates = listOf(picked) + others),
                            contentIndex = 0,
                            chapterError = null,
                            customRule = null,
                        )
                    }
                    backToReview()
                    null
                }
            }
            else -> Unit
        }
    }

    /** 跑一次点选识别；[block] 返回失败时给用户的话，成功返回 null */
    private fun runPick(block: suspend () -> String?) {
        pickJob?.cancel()
        pickJob = viewModelScope.launch {
            _state.update { it.copy(pickLoading = true, pickError = null) }
            val problem = attempt(PICK_TIMEOUT_MS) { block() }.getOrElse { failureText(it) }
            _state.update { it.copy(pickLoading = false, pickError = problem) }
        }
    }

    // ---- 高级 ----

    /** 当前要存的那份规则：手动改过就是手动的那份，否则按选中的候选现拼 */
    private fun currentDraft(s: SourceWizardUiState): BookSourceRule? {
        val book = s.book ?: return null
        val name = s.sourceName.trim().ifEmpty { SourceDraftBuilder.defaultName(book.bookUrl) }
        s.customRule?.let { return it.copy(bookSourceName = name, bookSourceGroup = BookSourceRepository.CREATED_GROUP) }
        val toc = s.toc ?: return null
        // 正文没认出来也要拼得出一份：「高级」页正是这种时候的最后一招 —— 用户在那儿手写正文规则。
        // 空的正文规则存不进去：[SourceWizardUiState.canSave] 要求有正文候选或手动改过的规则
        val chapter = s.chapter ?: ChapterPageAnalysis(toc.sampleChapter.url, emptyList(), nextPage = null)
        val content = chapter.contentCandidates.getOrNull(s.contentIndex)
            ?: ContentCandidate(rule = "", textLength = 0, preview = "", score = 0.0)
        return SourceDraftBuilder.build(
            name, book, toc, chapter, content,
            group = BookSourceRepository.CREATED_GROUP,
            search = s.search?.setup,
        )
    }

    fun openAdvanced() {
        val s = _state.value
        val draft = currentDraft(s) ?: return
        _state.update {
            it.copy(
                step = WizardStep.Advanced,
                editing = RuleField.entries.associateWith { f -> f.get(draft).orEmpty() },
                dryRun = null,
            )
        }
        runDryRun()
    }

    fun setField(field: RuleField, text: String) = _state.update { it.copy(editing = it.editing + (field to text)) }

    /** 按正在编辑的规则试抓一遍，每一栏显示取到了什么 */
    fun runDryRun() {
        val s = _state.value
        val book = s.book ?: return
        val rule = editedRule(s) ?: return
        dryRunJob?.cancel()
        dryRunJob = viewModelScope.launch {
            _state.update { it.copy(dryRunning = true) }
            val keyword = s.search?.keyword?.takeIf { rule.search != null }
            val run = attempt(VERIFY_TIMEOUT_MS) { analyzer.dryRun(rule, book.bookUrl, keyword) }
            _state.update {
                it.copy(
                    dryRunning = false,
                    dryRun = run.getOrElse { e -> DryRun(emptyMap(), mapOf(STAGE_DETAIL to failureText(e)), 0, null, null) },
                )
            }
        }
    }

    /** 「用这套规则」：存下手动版，回核对页。之后再换候选会把它丢掉（核对页上说清了） */
    fun applyAdvanced() {
        val rule = editedRule(_state.value) ?: return
        dryRunJob?.cancel()
        _state.update { it.copy(step = WizardStep.Review, customRule = rule, dryRunning = false, saveError = null) }
    }

    fun discardCustomRule() = _state.update { it.copy(customRule = null) }

    private fun editedRule(s: SourceWizardUiState): BookSourceRule? {
        var rule = currentDraft(s) ?: return null
        for ((field, text) in s.editing) rule = field.set(rule, text)
        return rule
    }

    // ---- 保存 ----

    fun save() {
        val s = _state.value
        if (!s.canSave) return
        val book = s.book ?: return
        val draft = currentDraft(s) ?: return
        job?.cancel()
        job = viewModelScope.launch {
            _state.update { it.copy(saving = true, saveError = null) }
            val detail = attempt(VERIFY_TIMEOUT_MS) {
                val (detail, chapters) = netBookRepo.fetchDetailAndToc(draft, book.bookUrl)
                if (chapters.isEmpty()) error("按识别结果没抓到目录")
                val sampleUrl = s.toc?.sampleChapter?.url
                val sample = chapters.firstOrNull { it.url == sampleUrl } ?: chapters.first()
                val text = engine.getContent(
                    draft,
                    sample.url,
                    chapters.mapTo(HashSet()) { it.url },
                    chapterVariable = sample.variable,
                    chapterTitle = sample.title,
                    chapterIndex = sample.index,
                    bookUrl = book.bookUrl,
                    variables = detail.variables,
                )
                if (text.elements.isEmpty()) error("按识别结果没抓到正文")
                // 带了搜索就也搜一次：存进去的源要是搜不出东西，搜索页里它只会一直报错
                s.search?.keyword?.let { kw ->
                    if (draft.search != null && engine.search(draft, kw).items.isEmpty()) error("按识别结果没搜出东西")
                }
                detail
            }.getOrElse { e ->
                _state.update { it.copy(saving = false, saveError = "检查没通过：${failureText(e)}") }
                return@launch
            }
            val saved = sourceRepo.addCreated(draft)
            val opened = SearchResult(
                title = detail.title.ifBlank { book.info.name?.value ?: s.manualTitle.trim() },
                bookUrl = book.bookUrl,
                author = detail.author ?: book.info.author?.value,
                coverUrl = detail.coverUrl ?: book.info.coverUrl?.value,
                intro = detail.intro ?: book.info.intro?.value,
                sourceId = saved.id,
                sourceName = saved.name,
            )
            _state.update { it.copy(saving = false, opened = opened) }
        }
    }

    /** 识别器是单例，为点选留着的那几页 DOM 跟着向导一起走 */
    override fun onCleared() {
        analyzer.forgetPages()
    }

    /** 样本章认不出正文时给的话；认出来了返回 null */
    private fun chapterProblem(result: Result<ChapterPageAnalysis>): String? {
        result.exceptionOrNull()?.let { return failureText(it) }
        if (result.getOrNull()?.contentCandidates.isNullOrEmpty()) {
            return "样本章节上没认出正文。可以换一组目录，或者在网页上点一下正文；需要登录或有人机验证的网站认不出来"
        }
        return null
    }

    /**
     * 跑一步网络活：失败、超时都折成 [Result.failure]，取消照常往外抛（离开页面不是「识别失败」）。
     * 识别里没有书源脚本，抓取（OkHttp）和渲染都认取消，`withTimeoutOrNull` 掐得动。
     */
    private suspend fun <T> attempt(timeoutMs: Long, block: suspend () -> T): Result<T> = try {
        withTimeoutOrNull(timeoutMs) { Result.success(block()) }
            ?: Result.failure(IllegalStateException("网站响应太慢，${timeoutMs / 1000} 秒内没走完"))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    private fun failureText(e: Throwable): String = e.message?.take(MESSAGE_CHARS) ?: "识别失败"

    private companion object {
        /** 目录页可能要跟进「查看目录」再抓一页，认不出还要等 WebView 渲染两次 */
        private const val BOOK_TIMEOUT_MS = 60_000L
        private const val CHAPTER_TIMEOUT_MS = 45_000L
        private const val SEARCH_TIMEOUT_MS = 45_000L
        private const val PICK_TIMEOUT_MS = 45_000L

        /** 存前检查：详情 + 目录（可能分好几页）+ 一章正文（+ 一次搜索） */
        private const val VERIFY_TIMEOUT_MS = 90_000L
        private const val PEEK_TIMEOUT_MS = 15_000L
        private const val MESSAGE_CHARS = 120
    }
}
