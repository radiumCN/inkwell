package com.radium.inkwell.ui.sourcewizard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radium.inkwell.core.source.BookSourceEngine
import com.radium.inkwell.core.source.BookSourceRule
import com.radium.inkwell.core.source.SearchResult
import com.radium.inkwell.core.source.detect.BookPageAnalysis
import com.radium.inkwell.core.source.detect.BookPeek
import com.radium.inkwell.core.source.detect.ChapterPageAnalysis
import com.radium.inkwell.core.source.detect.DetectedBookInfo
import com.radium.inkwell.core.source.detect.SiteAnalyzer
import com.radium.inkwell.core.source.detect.SourceDraftBuilder
import com.radium.inkwell.core.source.detect.TocCandidate
import com.radium.inkwell.data.repo.BookSourceRepository
import com.radium.inkwell.data.repo.NetBookRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
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
) {
    val toc: TocCandidate? get() = book?.tocCandidates?.getOrNull(tocIndex)

    val canSave: Boolean
        get() = !saving && !chapterLoading &&
            chapter?.contentCandidates?.getOrNull(contentIndex) != null &&
            (book?.info?.name != null || manualTitle.isNotBlank())
}

/**
 * 「新建书源」向导：给一本书的网址 → 认出目录与正文 → 用户核对 → 存成一个普通书源并打开这本书。
 *
 * 识别本身在 core（[SiteAnalyzer]），这里管流程：
 * - 先看这个站是不是已经有书源了（自建书源不能搜索，同站的第二本书只能靠网址加）—— 有就直接用它打开，
 *   不再生成一份重复的；
 * - 换目录候选时，样本章跟着换，正文重认；
 * - 存之前用**引擎**按生成的规则把详情、目录、正文完整走一遍。识别器说认出来了不算数，
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

    /** 存好（或选了已有书源）后要打开的那本书，页面据此跳到预览页 */
    private val _openBook = MutableSharedFlow<SearchResult>(extraBufferCapacity = 1)
    val openBook: SharedFlow<SearchResult> = _openBook

    /** 识别 / 保存 / 用已有书源打开：同一时刻只跑一个，新的开始前掐掉旧的，免得旧结果串进来 */
    private var job: Job? = null

    /** 换目录候选后重认正文；连点几个候选时只留最后一个 */
    private var chapterJob: Job? = null

    fun setUrl(text: String) = _state.update { it.copy(url = text, inputError = null) }

    fun setManualTitle(text: String) = _state.update { it.copy(manualTitle = text) }

    fun setSourceName(text: String) = _state.update { it.copy(sourceName = text) }

    fun selectContent(index: Int) = _state.update { it.copy(contentIndex = index, saveError = null) }

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
    fun dismissExisting() = _state.update { it.copy(existing = null) }

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
            _state.update { it.copy(openingExisting = false, existing = null) }
            _openBook.emit(
                SearchResult(
                    title = info.name?.value.orEmpty(),
                    bookUrl = peek.bookUrl,
                    author = info.author?.value,
                    coverUrl = info.coverUrl?.value,
                    intro = info.intro?.value,
                    sourceId = rule.id,
                    sourceName = rule.name,
                ),
            )
        }
    }

    /** 核对页按返回：回到输入页改网址，而不是直接退出、把认了半天的结果扔掉 */
    fun backToInput() {
        job?.cancel()
        chapterJob?.cancel()
        _state.update { it.copy(step = WizardStep.Input, progress = null, saving = false, chapterLoading = false) }
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
            )
        }
    }

    /** 换一组目录：样本章跟着变，正文重认 */
    fun selectToc(index: Int) {
        val s = _state.value
        val book = s.book ?: return
        val toc = book.tocCandidates.getOrNull(index) ?: return
        if (index == s.tocIndex) return
        _state.update {
            it.copy(
                tocIndex = index,
                chapter = null,
                chapterLoading = true,
                chapterError = null,
                contentIndex = 0,
                saveError = null,
            )
        }
        chapterJob?.cancel()
        chapterJob = viewModelScope.launch {
            val chapter = attempt(CHAPTER_TIMEOUT_MS) { analyzer.analyzeChapter(toc.sampleChapter.url) }
            _state.update {
                it.copy(chapter = chapter.getOrNull(), chapterLoading = false, chapterError = chapterProblem(chapter))
            }
        }
    }

    fun save() {
        val s = _state.value
        if (!s.canSave) return
        val book = s.book ?: return
        val toc = s.toc ?: return
        val chapter = s.chapter ?: return
        val content = chapter.contentCandidates.getOrNull(s.contentIndex) ?: return
        val name = s.sourceName.trim().ifEmpty { SourceDraftBuilder.defaultName(book.bookUrl) }
        job?.cancel()
        job = viewModelScope.launch {
            _state.update { it.copy(saving = true, saveError = null) }
            val draft = SourceDraftBuilder.build(
                name, book, toc, chapter, content,
                group = BookSourceRepository.CREATED_GROUP,
            )
            val detail = attempt(VERIFY_TIMEOUT_MS) {
                val (detail, chapters) = netBookRepo.fetchDetailAndToc(draft, book.bookUrl)
                if (chapters.isEmpty()) error("按识别结果没抓到目录")
                val sample = chapters.firstOrNull { it.url == toc.sampleChapter.url } ?: chapters.first()
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
                detail
            }.getOrElse { e ->
                _state.update { it.copy(saving = false, saveError = "检查没通过：${failureText(e)}") }
                return@launch
            }
            val saved = sourceRepo.addCreated(draft)
            _state.update { it.copy(saving = false) }
            _openBook.emit(
                SearchResult(
                    title = detail.title.ifBlank { book.info.name?.value ?: s.manualTitle.trim() },
                    bookUrl = book.bookUrl,
                    author = detail.author ?: book.info.author?.value,
                    coverUrl = detail.coverUrl ?: book.info.coverUrl?.value,
                    intro = detail.intro ?: book.info.intro?.value,
                    sourceId = saved.id,
                    sourceName = saved.name,
                ),
            )
        }
    }

    /** 样本章认不出正文时给的话；认出来了返回 null */
    private fun chapterProblem(result: Result<ChapterPageAnalysis>): String? {
        result.exceptionOrNull()?.let { return failureText(it) }
        if (result.getOrNull()?.contentCandidates.isNullOrEmpty()) {
            return "样本章节上没认出正文。可以换一组目录试试；需要登录或有人机验证的网站认不出来"
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
        const val BOOK_TIMEOUT_MS = 60_000L
        const val CHAPTER_TIMEOUT_MS = 45_000L

        /** 存前检查：详情 + 目录（可能分好几页）+ 一章正文 */
        const val VERIFY_TIMEOUT_MS = 60_000L
        const val PEEK_TIMEOUT_MS = 15_000L
        const val MESSAGE_CHARS = 120
    }
}
