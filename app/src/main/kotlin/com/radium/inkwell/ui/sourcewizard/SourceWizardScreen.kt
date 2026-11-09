package com.radium.inkwell.ui.sourcewizard

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radium.inkwell.core.source.SearchResult
import com.radium.inkwell.core.source.detect.BookPageAnalysis
import com.radium.inkwell.core.source.detect.DetectedChapter
import com.radium.inkwell.core.source.detect.DryRun
import com.radium.inkwell.core.source.detect.RuleField
import com.radium.inkwell.core.source.detect.STAGE_CONTENT
import com.radium.inkwell.core.source.detect.STAGE_DETAIL
import com.radium.inkwell.core.source.detect.STAGE_SEARCH
import com.radium.inkwell.core.source.detect.STAGE_TOC
import com.radium.inkwell.core.source.detect.SiteAnalyzer
import com.radium.inkwell.core.source.detect.TocCandidate
import com.radium.inkwell.data.repo.BookSourceRepository
import com.radium.inkwell.ui.components.AppAlertDialog
import com.radium.inkwell.ui.components.AppScaffold
import com.radium.inkwell.ui.components.AppLoadingIndicator
import com.radium.inkwell.ui.components.AppToolbar
import com.radium.inkwell.ui.components.AppTopBar
import com.radium.inkwell.ui.components.BackButton
import com.radium.inkwell.ui.components.ChipRow
import com.radium.inkwell.ui.components.CompactTextField
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.ui.components.PrimaryButton
import com.radium.inkwell.ui.components.SectionHeader
import com.radium.inkwell.ui.components.SettingBlock
import com.radium.inkwell.ui.components.SettingFootnote
import com.radium.inkwell.ui.components.SettingGroup
import com.radium.inkwell.ui.components.SettingGroupPosition
import com.radium.inkwell.ui.components.SettingRow
import com.radium.inkwell.ui.components.ToolbarTitle
import com.radium.inkwell.ui.components.horizontalOnly
import com.radium.inkwell.ui.components.pageScroll
import com.radium.inkwell.ui.components.rememberAppTopBarScroll
import com.radium.inkwell.ui.components.settingsPageColor
import com.radium.inkwell.ui.components.topBarScroll
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 新建书源：输入一本书的网址 → 核对认出来的目录与正文（可选：在网页里搜一次、点一下纠错、逐项改规则）
 * → 存成书源并打开这本书。
 *
 * 各步在同一页里切换，不拆成路由：识别结果只活在这次向导里，拆成路由就得把它塞进路由参数或进程内暂存，
 * 而它对别的页面毫无用处；网页步骤还要把用户的搜索、点选交回核对页，同一个 ViewModel 里最直接。
 */
@Composable
fun SourceWizardScreen(
    onBack: () -> Unit,
    onOpenBook: (SearchResult) -> Unit,
    viewModel: SourceWizardViewModel,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // 跳转看状态而不是收一次性事件（见 SourceWizardUiState.opened）：页面重建后照样跳
    state.opened?.let { book -> LaunchedEffect(book) { onOpenBook(book) } }
    // 子步骤（网页、高级、核对）按返回先退一步，退到输入页才离开整页
    BackHandler(state.step != WizardStep.Input) { viewModel.back() }
    val goBack = { if (!viewModel.back()) onBack() }

    when (state.step) {
        WizardStep.SearchCapture, WizardStep.PickToc, WizardStep.PickContent -> WebStep(state, viewModel, goBack)
        else -> FormStep(state, viewModel, goBack)
    }

    state.existing?.let { existing ->
        AppAlertDialog(
            onDismissRequest = viewModel::dismissExisting,
            title = "这个网站已有来源",
            text = "来源「${existing.name}」就是这个网站的。直接用它打开这本书，还是重新识别、另建一个来源？",
            confirmText = "直接打开",
            onConfirm = viewModel::openWithExisting,
            confirmLoading = state.openingExisting,
            dismissText = "重新识别",
            onDismiss = viewModel::analyzeAnyway,
            dismissEnabled = !state.openingExisting,
        )
    }
}

@Composable
private fun FormStep(state: SourceWizardUiState, viewModel: SourceWizardViewModel, goBack: () -> Unit) {
    val pageColor = settingsPageColor()
    val topBarScroll = rememberAppTopBarScroll()
    // 每一步各记各的滚动位置：从核对页底下点进「高级」，不该落在高级页的半中间
    val scroll = key(state.step) { rememberScrollState() }
    AppScaffold(
        modifier = Modifier.topBarScroll(topBarScroll),
        containerColor = pageColor,
        topBar = {
            AppTopBar(
                if (state.step == WizardStep.Advanced) "逐项修改规则" else "新建来源",
                topBarScroll,
                onBack = goBack,
                containerColor = pageColor,
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                // 只让开两侧：顶栏高度垫在滚动内容开头（内容滚到毛玻璃顶栏下面），导航栏高度垫在末尾（见 AppScaffold）
                .padding(padding.horizontalOnly())
                // padding 里已含导航栏高度：先标成已消费，下面的 imePadding 才不会把导航栏那截再算一遍
                .consumeWindowInsets(padding)
                // 网址、书名、书源名、规则各栏都是输入框：键盘弹起会盖住下面的按钮
                .imePadding()
                .pageScroll()
                .verticalScroll(scroll, overscrollEffect = null)
                .padding(
                    top = padding.calculateTopPadding() + Dimens.gapS,
                    bottom = Dimens.gapXL + padding.calculateBottomPadding(),
                ),
        ) {
            when (state.step) {
                WizardStep.Input -> InputStep(state, viewModel)
                WizardStep.Advanced -> AdvancedStep(state, viewModel)
                else -> ReviewStep(state, viewModel)
            }
        }
    }
}

/**
 * 网页步骤。顶栏用窄栏（[AppToolbar]）而不是 [AppTopBar]：WebView 不接嵌套滚动，大标题顶栏收不起来，
 * 就是白送一条永久变高的顶栏，还要从本来就不大的网页里再切走一截。
 */
@Composable
private fun WebStep(state: SourceWizardUiState, viewModel: SourceWizardViewModel, goBack: () -> Unit) {
    val start = viewModel.webStartUrl(state) ?: return
    val (title, mode, hint) = when (state.step) {
        WizardStep.SearchCapture -> Triple(
            "在网页里搜一次",
            WebMode.Capture,
            "用网站自己的搜索框搜「${SiteAnalyzer.SUGGESTED_KEYWORD}」（别的常见字也行），出现搜索结果后点「就是这一页」。",
        )
        WizardStep.PickToc -> Triple("点一下目录里的一章", WebMode.PickLink, "点目录里任意一章的章节名。点了不会跳转，只是告诉向导目录在哪。")
        else -> Triple("点一下正文", WebMode.PickText, "点正文里的任意一段文字。点了不会跳转，只是告诉向导正文在哪。")
    }
    // 整页是 WebView：它不在 Compose 里画，录不进毛玻璃取样的图层，顶栏保持实色
    AppScaffold(
        blurBars = false,
        topBar = {
            AppToolbar(navigationIcon = { BackButton(onClick = goBack) }) {
                ToolbarTitle(title)
            }
        },
    ) { padding ->
        // 不滚动的整页：整块让开系统栏；搜索框在网页里，键盘弹起时把网页和底下的按钮一起顶上去
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding(),
        ) {
            // 换步骤（搜索 → 点目录）就换一个全新的 WebView：注入的脚本跟着模式走，旧页面的状态别带过来
            key(state.step, start) {
                WizardWebView(
                    url = start,
                    mode = mode,
                    highlight = MiuixTheme.colorScheme.primary,
                    onPageUrl = viewModel::onWebPage,
                    onFormSubmit = viewModel::onFormSubmitted,
                    onPick = viewModel::onPicked,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.listHorizontal, vertical = Dimens.gapM),
                verticalArrangement = Arrangement.spacedBy(Dimens.gapS),
            ) {
                Text(hint, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                val error = if (state.step == WizardStep.SearchCapture) state.searchError else state.pickError
                error?.let { Text(it, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.error) }
                if (state.pickLoading) BusyLine("正在按你点的地方重新认…")
                if (state.step == WizardStep.SearchCapture) {
                    PrimaryButton(
                        text = "就是这一页",
                        onClick = viewModel::confirmSearchCapture,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun InputStep(state: SourceWizardUiState, viewModel: SourceWizardViewModel) {
    val busy = state.progress != null
    SectionHeader("书的网址")
    SettingGroup {
        SettingBlock {
            CompactTextField(
                value = state.url,
                onValueChange = viewModel::setUrl,
                placeholder = "https://",
            )
            PrimaryButton(
                text = "开始识别",
                onClick = viewModel::submit,
                enabled = state.url.isNotBlank(),
                loading = busy,
                modifier = Modifier.fillMaxWidth(),
            )
            state.progress?.let { BusyLine(it) }
        }
    }
    state.inputError?.let { SettingFootnote(it, color = MiuixTheme.colorScheme.error) }
    SettingFootnote(
        "在浏览器里打开这本书的目录页（列出全部章节的那一页）或详情页，复制地址粘贴到这里；" +
            "正在读的某一章的地址也行。应用会认出目录和正文在页面上的位置，生成一个来源。",
    )
    SettingFootnote("识别只看这一本书的页面，不会搜索整个网站。需要登录、有人机验证的网站认不出来。")
}

@Composable
private fun ReviewStep(state: SourceWizardUiState, viewModel: SourceWizardViewModel) {
    val book = state.book ?: return
    val toc = state.toc ?: return

    SectionHeader("书籍")
    SettingGroup {
        val name = book.info.name?.value
        if (name != null) {
            SettingRow("书名", value = name, position = SettingGroupPosition.First)
        } else {
            SettingBlock {
                CompactTextField(
                    value = state.manualTitle,
                    onValueChange = viewModel::setManualTitle,
                    placeholder = "没认出书名，请手动填写",
                )
            }
        }
        SettingRow(
            "作者",
            value = book.info.author?.value ?: "未识别",
            // 上面不管是书名行还是手填书名的输入块，作者都是卡里最后一项
            position = SettingGroupPosition.Last,
        )
    }

    SectionHeader("目录")
    SettingGroup {
        SettingBlock {
            val candidates = book.tocCandidates
            // 候选几组不定（点选后还会多一组），按规范用能横滚的 ChipRow，不用定档的分段按钮
            if (candidates.size > 1) {
                ChipRow(
                    options = choiceLabels(candidates.map { "${it.chapters.size} 章" }),
                    selectedIndex = state.tocIndex,
                    onSelect = viewModel::selectToc,
                )
            }
            Text("共 ${toc.chapters.size} 章", style = MiuixTheme.textStyles.subtitle)
            ChapterPeek(toc.chapters)
        }
        SettingRow("认得不对？去网页上点一下", onClick = viewModel::openPickToc, position = SettingGroupPosition.Last)
    }
    tocNotes(book, toc).takeIf { it.isNotEmpty() }?.let { SettingFootnote(it) }

    SectionHeader("正文")
    SettingGroup {
        SettingBlock { ContentSummary(state, viewModel) }
        if (state.chapter != null) {
            SettingRow("认得不对？去网页上点一下", onClick = viewModel::openPickContent, position = SettingGroupPosition.Last)
        }
    }
    SettingFootnote(contentNote(toc, state))

    SearchSection(state, viewModel)

    SectionHeader("高级")
    SettingGroup {
        SettingRow(
            "逐项查看、修改规则",
            onClick = viewModel::openAdvanced,
            position = if (state.customRule != null) SettingGroupPosition.First else SettingGroupPosition.Alone,
        )
        if (state.customRule != null) {
            SettingRow("撤销手动修改", onClick = viewModel::discardCustomRule, position = SettingGroupPosition.Last)
        }
    }
    if (state.customRule != null) {
        SettingFootnote("规则已手动改过，保存时用改过的那份。再换目录、正文或搜索，会丢掉手动修改。")
    }

    SectionHeader("保存")
    SettingGroup {
        SettingBlock {
            CompactTextField(
                value = state.sourceName,
                onValueChange = viewModel::setSourceName,
                placeholder = "来源名称",
            )
            PrimaryButton(
                text = "保存来源并打开这本书",
                onClick = viewModel::save,
                enabled = state.canSave,
                loading = state.saving,
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.saving) BusyLine("正在按规则试抓目录和正文…")
        }
    }
    state.saveError?.let { SettingFootnote(it, color = MiuixTheme.colorScheme.error) }
    val group = BookSourceRepository.CREATED_GROUP
    SettingFootnote(
        if (state.search != null) {
            "来源保存在分组「$group」里，可以在应用里搜这个网站的书。"
        } else {
            "来源保存在分组「$group」里。没设置搜索的话它不能用来搜索；" +
                "以后要读这个网站的其他书，在这里输入那本书的网址，会直接用它打开。"
        },
    )
}

@Composable
private fun ColumnScope.ContentSummary(state: SourceWizardUiState, viewModel: SourceWizardViewModel) {
    val chapter = state.chapter
    val error = state.chapterError
    when {
        state.chapterLoading -> BusyLine("正在识别正文…")
        error != null -> Text(error, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.error)
        chapter != null -> {
            val candidates = chapter.contentCandidates
            if (candidates.size > 1) {
                ChipRow(
                    options = choiceLabels(candidates.map { "${it.textLength} 字" }),
                    selectedIndex = state.contentIndex,
                    onSelect = viewModel::selectContent,
                )
            }
            candidates.getOrNull(state.contentIndex)?.let { content ->
                Text("约 ${content.textLength} 字", style = MiuixTheme.textStyles.subtitle)
                Text(
                    content.preview + "…",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = PREVIEW_LINES,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SearchSection(state: SourceWizardUiState, viewModel: SourceWizardViewModel) {
    val search = state.search
    SectionHeader("搜索")
    SettingGroup {
        when {
            state.searchLoading -> SettingBlock { BusyLine("正在按你搜的那一次试搜…") }
            search != null -> {
                SettingBlock {
                    Text("搜「${search.keyword}」搜到 ${search.results.size} 本", style = MiuixTheme.textStyles.subtitle)
                    search.results.take(PEEK_HEAD).forEach { r ->
                        Text(
                            listOfNotNull(r.title, r.author).joinToString(" · "),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                SettingRow("重新搜一次", onClick = viewModel::openSearchCapture, position = SettingGroupPosition.Middle)
                SettingRow("不要搜索", onClick = viewModel::removeSearch, position = SettingGroupPosition.Last)
            }
            else -> SettingRow("在网页里搜一次", onClick = viewModel::openSearchCapture, position = SettingGroupPosition.Alone)
        }
    }
    state.searchError?.let { SettingFootnote(it, color = MiuixTheme.colorScheme.error) }
    if (search == null && !state.searchLoading) {
        SettingFootnote("可选。在网站上搜一次，以后就能在应用里搜这个网站，换源时也能找到它。")
    }
}

@Composable
private fun AdvancedStep(state: SourceWizardUiState, viewModel: SourceWizardViewModel) {
    val run = state.dryRun
    SettingFootnote(
        "每一栏是一条 Legado 规则，下面是按它试抓到的东西。改完点「试抓一遍」看看，满意了点最下面的「用这套规则」。",
    )
    SettingGroup {
        SettingBlock {
            PrimaryButton(
                text = "试抓一遍",
                onClick = viewModel::runDryRun,
                loading = state.dryRunning,
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.dryRunning) BusyLine("正在按这套规则抓详情、目录、正文…")
            run?.let {
                Text(dryRunSummary(it), style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
    }
    for ((stage, title, fields) in ADVANCED_SECTIONS) {
        SectionHeader(title)
        run?.failures?.get(stage)?.let { SettingFootnote("这一段没走通：$it", color = MiuixTheme.colorScheme.error) }
        SettingGroup {
            fields.forEach { field ->
                SettingBlock {
                    Text(FIELD_LABELS.getValue(field), style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.Medium)
                    CompactTextField(
                        value = state.editing[field].orEmpty(),
                        onValueChange = { viewModel.setField(field, it) },
                        placeholder = "不填",
                    )
                    sampleLine(run, field, state.editing[field].orEmpty())?.let { (text, isError) ->
                        Text(
                            text,
                            style = MiuixTheme.textStyles.footnote1,
                            color = if (isError) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = SAMPLE_LINES,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        if (stage == STAGE_SEARCH && state.editing[RuleField.SearchUrl].isNullOrBlank()) {
            SettingFootnote("没设置搜索时这一组留空即可；要设置，回核对页「在网页里搜一次」最省事。")
        }
    }
    SettingGroup {
        SettingBlock {
            PrimaryButton(text = "用这套规则", onClick = viewModel::applyAdvanced, modifier = Modifier.fillMaxWidth())
        }
    }
    SettingFootnote("改过的规则只在这次新建里生效；回核对页再换目录、正文或搜索，会丢掉这里的修改。")
}

/** 一行「转圈 + 说明」：按钮自己也在转，但光转圈看不出卡在哪一步 */
@Composable
private fun BusyLine(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.gapS)) {
        AppLoadingIndicator(size = Dimens.buttonSpinner)
        Text(text, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

/** 开头三章、省略、最后一章：一眼看出头尾对不对、有没有混进导航 */
@Composable
private fun ChapterPeek(chapters: List<DetectedChapter>) {
    val lines = if (chapters.size <= PEEK_HEAD + 1) {
        chapters.map { it.title }
    } else {
        chapters.take(PEEK_HEAD).map { it.title } + "⋮" + chapters.last().title
    }
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.gapXS)) {
        lines.forEach { line ->
            Text(
                line,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 候选标签撞了（两组都是 30 章）就改用序号，否则用户分不出点的是哪个 */
private fun choiceLabels(labels: List<String>): List<String> =
    if (labels.toSet().size == labels.size) labels else labels.indices.map { "第 ${it + 1} 组" }

private fun tocNotes(book: BookPageAnalysis, toc: TocCandidate): String = buildList {
    if (book.fromChapterUrl != null) add("你给的是其中一章，已经跳到这本书的目录页来认")
    if (book.tocCandidates.size > 1) add("认出了几组链接，默认选的是最像目录的那组；头尾不对就换一组")
    if (book.tocLink != null) add("目录在另一页，已经点进去认过了")
    if (book.tocNextPage != null) add("目录分了好几页，这里只数了第一页，加书时会自动翻完")
    if (toc.reversed) add("网站上是倒序排的，已按章节顺序翻过来")
    if (book.rendered) add("这个网站要等网页脚本跑完才显示目录，打开书会慢一些")
}.joinToString("。")

private fun contentNote(toc: TocCandidate, state: SourceWizardUiState): String = buildList {
    add("拿「${toc.sampleChapter.title}」试的")
    if (state.chapter?.nextPage != null) add("一章分了好几页，阅读时会自动拼起来")
    add("正文里夹的广告，可以在「高级」里加替换规则，或之后用净化规则去掉")
}.joinToString("。")

private fun dryRunSummary(run: DryRun): String = buildList {
    if (run.chapterCount > 0) add("目录 ${run.chapterCount} 章")
    run.sampleChapterTitle?.let { add("正文试的是「$it」") }
    run.searchCount?.let { add("搜索 $it 本") }
    if (run.failures.isNotEmpty()) add("有 ${run.failures.size} 段没走通，见下面红字")
}.joinToString("，").ifEmpty { "什么都没抓到" }

/** 一栏下面那行：取到了什么 / 出了什么错；还没试抓、或这一栏空着不填时为 null */
private fun sampleLine(run: DryRun?, field: RuleField, text: String): Pair<String, Boolean>? {
    run ?: return null
    val sample = run.samples[field]
    sample?.error?.let { return "出错：$it" to true }
    sample?.output?.let { return (it.ifBlank { "（取到的是空的）" }) to false }
    return when {
        field == RuleField.ReplaceRegex -> if (text.isBlank()) null else "效果看上面「正文」一栏" to false
        field == RuleField.SearchUrl -> run.searchCount?.let { "搜到 $it 本" to false }
        text.isBlank() -> null
        else -> "没跑到这一项（前面那段没走通，或这一项用不上）" to false
    }
}

private val ADVANCED_SECTIONS = listOf(
    Triple(
        STAGE_DETAIL, "书籍信息",
        listOf(RuleField.BookName, RuleField.BookAuthor, RuleField.BookCover, RuleField.BookIntro, RuleField.TocUrl),
    ),
    Triple(STAGE_TOC, "目录", listOf(RuleField.ChapterList, RuleField.ChapterName, RuleField.ChapterUrl, RuleField.NextTocUrl)),
    Triple(STAGE_CONTENT, "正文", listOf(RuleField.Content, RuleField.NextContentUrl, RuleField.ReplaceRegex)),
    Triple(
        STAGE_SEARCH, "搜索",
        listOf(
            RuleField.SearchUrl, RuleField.SearchList, RuleField.SearchName, RuleField.SearchAuthor,
            RuleField.SearchBookUrl, RuleField.SearchCover, RuleField.SearchLatest,
        ),
    ),
)

private val FIELD_LABELS = mapOf(
    RuleField.BookName to "书名",
    RuleField.BookAuthor to "作者",
    RuleField.BookCover to "封面",
    RuleField.BookIntro to "简介",
    RuleField.TocUrl to "目录页地址（目录不在详情页时）",
    RuleField.ChapterList to "章节列表",
    RuleField.ChapterName to "章节名",
    RuleField.ChapterUrl to "章节地址",
    RuleField.NextTocUrl to "目录下一页",
    RuleField.Content to "正文",
    RuleField.NextContentUrl to "正文下一页",
    RuleField.ReplaceRegex to "替换规则（##正则##替换为，去广告用）",
    RuleField.SearchUrl to "搜索地址（{{key}} 是搜索词）",
    RuleField.SearchList to "结果列表",
    RuleField.SearchName to "书名",
    RuleField.SearchAuthor to "作者",
    RuleField.SearchBookUrl to "书的地址",
    RuleField.SearchCover to "封面",
    RuleField.SearchLatest to "最新章节",
)

private const val PEEK_HEAD = 3
private const val PREVIEW_LINES = 4
private const val SAMPLE_LINES = 3
