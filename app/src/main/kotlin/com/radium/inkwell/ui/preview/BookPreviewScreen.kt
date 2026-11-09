package com.radium.inkwell.ui.preview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import com.radium.inkwell.ui.components.AppSnackbarHost
import com.radium.inkwell.ui.components.AppDropdownMenu
import com.radium.inkwell.ui.components.AppMenuItem
import com.radium.inkwell.ui.components.AppScaffold
import com.radium.inkwell.ui.components.horizontalOnly
import com.radium.inkwell.ui.components.pageScroll
import com.radium.inkwell.ui.components.AppToolbar
import com.radium.inkwell.ui.components.BackButton
import com.radium.inkwell.ui.components.SectionHeader
import com.radium.inkwell.ui.components.ToolbarTitle
import com.radium.inkwell.ui.components.groupedCardItem
import com.radium.inkwell.ui.components.rememberToolbarTitleReveal
import com.radium.inkwell.ui.components.AppTextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import com.radium.inkwell.ui.components.ChapterListItem
import com.radium.inkwell.ui.components.CollectMessages
import com.radium.inkwell.ui.components.PrimaryButton
import com.radium.inkwell.core.util.htmlToPlainText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radium.inkwell.core.source.SearchResult
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.ui.components.BookCover
import com.radium.inkwell.ui.components.BookIntro
import com.radium.inkwell.ui.components.ErrorState
import com.radium.inkwell.ui.components.LoadingState
import com.radium.inkwell.ui.components.AppLoadingIndicator
import com.radium.inkwell.ui.components.SecondaryButton
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun BookPreviewScreen(
    results: List<SearchResult>,
    onRead: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: BookPreviewViewModel,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    // 走共享的 CollectMessages：新提示直接替换正在显示的那条。从前这里自己 collect + showSnackbar，
    // showSnackbar 挂到上一条消失才返回 —— 连点「加入」「开始阅读」时提示一条条排队，几秒后才轮到
    CollectMessages(viewModel.messages, snackbar)
    LaunchedEffect(Unit) {
        viewModel.openReader.collect(onRead)
    }

    // 窄栏，栏里的书名等头部的书名滚到栏底下才淡出 —— 同书籍详情（理由见那边与 ToolbarTitleReveal）
    val listState = rememberLazyListState()
    val reveal = rememberToolbarTitleReveal(listState)
    AppScaffold(
        topBar = {
            AppToolbar(modifier = reveal.bar, navigationIcon = { BackButton(onClick = onBack) }) {
                ToolbarTitle(state.title, Modifier.graphicsLayer { alpha = reveal.titleAlpha })
            }
        },
        snackbarHost = { AppSnackbarHost(snackbar) },
    ) { padding ->
        when {
            // 连书名都还没有（不是从搜索结果点进来的）才整页转圈；有就边加载边把已知的先画出来
            state.loading && state.title.isBlank() -> Box(Modifier.fillMaxSize().padding(padding)) {
                LoadingState(label = state.loadingLabel)
                // 卡在某个源上时别只能干等：有别的书源就给出换源，不必等超时
                if (state.sources.size > 1) {
                    SwitchSourceButton(
                        state = state,
                        onSwitch = viewModel::switchSource,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(Dimens.gapXL),
                    )
                }
            }

            // 从前这里手搓了一份错误屏（还漏掉了图标）；收敛到共享 ErrorState。
            // 一个源挂了不该让人卡死 —— 有别的书源就给出「换个书源」的出口。
            // 换源入口与加载态同一个位置（页底居中）：书源列表要从按钮上弹出来，
            // ErrorState 里的次按钮拿不到锚点
            state.error != null -> Box(Modifier.fillMaxSize().padding(padding)) {
                ErrorState(
                    message = state.error.orEmpty(),
                    onRetry = viewModel::load,
                )
                if (state.sources.size > 1) {
                    SwitchSourceButton(
                        state = state,
                        onSwitch = viewModel::switchSource,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(Dimens.gapXL),
                    )
                }
            }

            // 只让开两侧：顶栏高度垫在列表开头、导航栏垫在末尾，内容滚到玻璃顶栏与手势条下面（见 AppScaffold）
            else -> LazyColumn(
                Modifier.fillMaxSize().padding(padding.horizontalOnly()).pageScroll(),
                state = listState,
                contentPadding = PaddingValues(
                    top = padding.calculateTopPadding(),
                    bottom = padding.calculateBottomPadding(),
                ),
                overscrollEffect = null,
            ) {
                // 加载中也画头部：搜索结果里的书名、封面、简介早就在 state 里了（见 BookPreviewViewModel），
                // 从前只给一个转圈 —— 卡在慢源上最多要等三个源各 15 秒，人连点的是哪本都看不见。
                // 换源入口在头部里（多于一个源时），不必干等超时。
                item {
                    Header(
                        state, viewModel,
                        titleModifier = reveal.title,
                        loading = state.loading,
                    )
                }
                if (state.loading) {
                    item {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(Dimens.gapXXL),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            AppLoadingIndicator(size = Dimens.iconMd)
                            Spacer(Modifier.height(Dimens.gapM))
                            Text(
                                state.loadingLabel,
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                    return@LazyColumn
                }
                // 目录：分组小标题 + 整个装进一张卡，同书籍详情
                item { SectionHeader("目录 · 共 ${state.chapters.size} 章") }
                itemsIndexed(state.chapters, key = { _, it -> it.index }) { i, chapter ->
                    ChapterListItem(
                        title = chapter.title,
                        selected = false,
                        onClick = { viewModel.read(chapter.index) },
                        modifier = Modifier.groupedCardItem(i, state.chapters.size),
                        enabled = !state.busy,
                    )
                }
                item { Spacer(Modifier.height(Dimens.gapXL)) }
            }
        }
    }

}

/**
 * 换源的书源列表，从触发它的按钮上弹出来（当前书源打勾）。书源名称当主标题，网址退到第二行 ——
 * 从前列表里只有一串域名。详情页从前一处是裸 DropdownMenu（选中态靠手拼 "✓ "），
 * 另一处是加载失败时一排纵向 TextButton，后来收敛成底部面板，现在是锚在按钮上的弹出列表。
 */
@Composable
private fun SourceMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    state: BookPreviewUiState,
    onSwitch: (Int) -> Unit,
) {
    AppDropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        items = state.sources.mapIndexed { index, source ->
            AppMenuItem(
                text = source.name,
                summary = source.id,
                selected = index == state.currentSource,
                onClick = { onSwitch(index) },
            )
        },
    )
}

/** 加载卡住 / 加载失败时页底那个「换个书源试试」，书源列表从它上面弹出来 */
@Composable
private fun SwitchSourceButton(
    state: BookPreviewUiState,
    onSwitch: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        AppTextButton("换个来源试试（共 ${state.sources.size} 个）", onClick = { open = true })
        SourceMenu(expanded = open, onDismiss = { open = false }, state = state, onSwitch = onSwitch)
    }
}

@Composable
private fun Header(
    state: BookPreviewUiState,
    viewModel: BookPreviewViewModel,
    /** 挂在书名上：它滚到栏底下以后，栏里才淡出书名（ToolbarTitleReveal） */
    titleModifier: Modifier,
    /** 详情与目录还在取：书名等先按搜索结果画，按钮先置灰（没目录可读、可加） */
    loading: Boolean = false,
) {

    // 左右与下面目录卡的边对齐（listHorizontal），同书籍详情
    Column(
        Modifier.padding(horizontal = Dimens.listHorizontal, vertical = Dimens.screenPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.gapL),
    ) {
        Row {
            BookCover(
                title = state.title,
                coverModel = state.coverUrl,
                modifier = Modifier.size(
                    width = Dimens.coverDetailWidth,
                    height = Dimens.coverDetailHeight,
                ),
                placeholderChars = 4,
                // 书名就在右边，封面不再进读屏
                contentDescription = null,
            )
            Column(Modifier.padding(start = Dimens.gapL).align(Alignment.CenterVertically)) {
                Text(
                    state.title,
                    modifier = titleModifier,
                    style = MiuixTheme.textStyles.title3,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (state.author.isNotBlank()) {
                    Text(
                        state.author,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                Text(
                    if (loading) "来源 ${state.sourceName}" else "共 ${state.chapters.size} 章 · 来源 ${state.sourceName}",
                    style = MiuixTheme.textStyles.footnote1,
                    // 正文性小字走摘要色；outline / dividerLine 是给线用的，读不清
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                if (state.sources.size > 1) {
                    var sourceMenuOpen by remember { mutableStateOf(false) }
                    Box {
                        AppTextButton(onClick = { sourceMenuOpen = true }, contentPadding = PaddingValues(0.dp)) {
                            Text(
                                "换源（${state.sources.size} 个来源）",
                                style = MiuixTheme.textStyles.footnote1,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                        SourceMenu(
                            expanded = sourceMenuOpen,
                            onDismiss = { sourceMenuOpen = false },
                            state = state,
                            onSwitch = viewModel::switchSource,
                        )
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.gapM)) {
            SecondaryButton(
                text = if (state.inShelf) "已在书架" else "加入书架",
                onClick = viewModel::addToShelf,
                enabled = !loading && !state.busy && !state.inShelf,
                loading = state.busy && !state.busyForReading && !state.inShelf,
                modifier = Modifier.weight(1f),
            )
            PrimaryButton(
                text = "开始阅读",
                onClick = { viewModel.read() },
                enabled = !loading && !state.busy,
                loading = state.busy && state.busyForReading,
                modifier = Modifier.weight(1f),
            )
        }

        val intro = remember(state.intro) { state.intro?.htmlToPlainText().orEmpty() }
        if (intro.isNotBlank()) BookIntro(intro)
    }
}
