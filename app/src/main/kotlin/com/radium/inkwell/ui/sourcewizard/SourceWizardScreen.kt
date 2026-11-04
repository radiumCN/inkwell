package com.radium.inkwell.ui.sourcewizard

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radium.inkwell.core.source.SearchResult
import com.radium.inkwell.core.source.detect.BookPageAnalysis
import com.radium.inkwell.core.source.detect.DetectedChapter
import com.radium.inkwell.core.source.detect.TocCandidate
import com.radium.inkwell.data.repo.BookSourceRepository
import com.radium.inkwell.ui.components.AppAlertDialog
import com.radium.inkwell.ui.components.AppLoadingIndicator
import com.radium.inkwell.ui.components.AppTopBar
import com.radium.inkwell.ui.components.CompactTextField
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.ui.components.PrimaryButton
import com.radium.inkwell.ui.components.SectionHeader
import com.radium.inkwell.ui.components.SegmentedChoice
import com.radium.inkwell.ui.components.SettingBlock
import com.radium.inkwell.ui.components.SettingFootnote
import com.radium.inkwell.ui.components.SettingGroup
import com.radium.inkwell.ui.components.SettingGroupPosition
import com.radium.inkwell.ui.components.SettingRow
import com.radium.inkwell.ui.components.exceptBottom
import com.radium.inkwell.ui.components.rememberAppTopBarScroll
import com.radium.inkwell.ui.components.settingsPageColor
import com.radium.inkwell.ui.components.topBarScroll

/**
 * 新建书源：输入一本书的网址 → 核对认出来的目录与正文 → 存成书源并打开这本书。
 *
 * 两步在同一页里切换，不拆成两个路由：核对页的数据（识别结果）只活在这次向导里，
 * 拆成路由就得把它塞进路由参数或进程内暂存，而它对别的页面毫无用处。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceWizardScreen(
    onBack: () -> Unit,
    onOpenBook: (SearchResult) -> Unit,
    viewModel: SourceWizardViewModel,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        viewModel.openBook.collect(onOpenBook)
    }
    // 核对页按返回回到输入页：认了半天的结果别一下子扔掉，用户多半只是想改个网址
    BackHandler(state.step == WizardStep.Review) { viewModel.backToInput() }

    val pageColor = settingsPageColor()
    val topBarScroll = rememberAppTopBarScroll()
    Scaffold(
        modifier = Modifier.topBarScroll(topBarScroll),
        containerColor = pageColor,
        topBar = {
            AppTopBar(
                "新建书源",
                topBarScroll,
                onBack = { if (state.step == WizardStep.Review) viewModel.backToInput() else onBack() },
                containerColor = pageColor,
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                // 内容滚到手势条下面，导航栏高度垫在末尾（见 exceptBottom）
                .padding(padding.exceptBottom())
                // padding 里已含导航栏高度：先标成已消费，下面的 imePadding 才不会把导航栏那截再算一遍
                .consumeWindowInsets(padding)
                // 网址、书名、书源名三处输入框：键盘弹起会盖住下面的按钮
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(top = Dimens.gapS, bottom = Dimens.gapXL + padding.calculateBottomPadding()),
        ) {
            when (state.step) {
                WizardStep.Input -> InputStep(state, viewModel)
                WizardStep.Review -> ReviewStep(state, viewModel)
            }
        }
    }

    state.existing?.let { existing ->
        AppAlertDialog(
            onDismissRequest = viewModel::dismissExisting,
            title = "这个网站已有书源",
            text = "书源「${existing.name}」就是这个网站的。直接用它打开这本书，还是重新识别、另建一个书源？",
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
    state.inputError?.let { SettingFootnote(it, color = MaterialTheme.colorScheme.error) }
    SettingFootnote(
        "在浏览器里打开这本书的目录页（列出全部章节的那一页）或详情页，复制地址粘贴到这里。" +
            "应用会认出目录和正文在页面上的位置，生成一个书源。",
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
            if (candidates.size > 1) {
                SegmentedChoice(
                    options = choiceLabels(candidates.map { "${it.chapters.size} 章" }),
                    selectedIndex = state.tocIndex,
                    onSelect = viewModel::selectToc,
                )
            }
            Text("共 ${toc.chapters.size} 章", style = MaterialTheme.typography.titleSmall)
            ChapterPeek(toc.chapters)
        }
    }
    tocNotes(book, toc).takeIf { it.isNotEmpty() }?.let { SettingFootnote(it) }

    SectionHeader("正文")
    SettingGroup {
        SettingBlock {
            val chapter = state.chapter
            val error = state.chapterError
            when {
                state.chapterLoading -> BusyLine("正在识别正文…")
                error != null -> Text(error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                chapter != null -> {
                    val candidates = chapter.contentCandidates
                    if (candidates.size > 1) {
                        SegmentedChoice(
                            options = choiceLabels(candidates.map { "${it.textLength} 字" }),
                            selectedIndex = state.contentIndex,
                            onSelect = viewModel::selectContent,
                        )
                    }
                    candidates.getOrNull(state.contentIndex)?.let { content ->
                        Text("约 ${content.textLength} 字", style = MaterialTheme.typography.titleSmall)
                        Text(
                            content.preview + "…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = PREVIEW_LINES,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
    SettingFootnote(contentNote(toc, state))

    SectionHeader("保存")
    SettingGroup {
        SettingBlock {
            CompactTextField(
                value = state.sourceName,
                onValueChange = viewModel::setSourceName,
                placeholder = "书源名称",
            )
            PrimaryButton(
                text = "保存书源并打开这本书",
                onClick = viewModel::save,
                enabled = state.canSave,
                loading = state.saving,
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.saving) BusyLine("正在按识别结果试抓目录和正文…")
        }
    }
    state.saveError?.let { SettingFootnote(it, color = MaterialTheme.colorScheme.error) }
    SettingFootnote(
        "书源保存在分组「${BookSourceRepository.CREATED_GROUP}」里。它不能用来搜索；" +
            "以后要读这个网站的其他书，在这里输入那本书的网址，会直接用它打开。",
    )
}

/** 一行「转圈 + 说明」：按钮自己也在转，但光转圈看不出卡在哪一步 */
@Composable
private fun BusyLine(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.gapS)) {
        AppLoadingIndicator(size = Dimens.buttonSpinner)
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 分段按钮的标签撞了（两组都是 30 章）就改用序号，否则用户分不出点的是哪个 */
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
    add("正文里夹的广告，可以之后用净化规则去掉")
}.joinToString("。")

private const val PEEK_HEAD = 3
private const val PREVIEW_LINES = 4
