package com.radium.inkwell.ui.detail

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.unit.Dp
import com.radium.inkwell.ui.components.AppDropdownMenu
import com.radium.inkwell.ui.components.AppMenuItem
import com.radium.inkwell.ui.components.AppScaffold
import com.radium.inkwell.ui.components.horizontalOnly
import com.radium.inkwell.ui.components.pageScroll
import com.radium.inkwell.ui.components.AppTextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radium.inkwell.data.db.entity.BookEntity
import com.radium.inkwell.data.db.entity.BookType
import com.radium.inkwell.ui.components.AppIconButton
import com.radium.inkwell.ui.components.AppLoadingIndicator
import com.radium.inkwell.ui.components.AppTopBar
import com.radium.inkwell.ui.components.rememberAppTopBarScroll
import com.radium.inkwell.ui.components.topBarScroll
import com.radium.inkwell.ui.components.AppSnackbarHost
import com.radium.inkwell.ui.components.BookCover
import com.radium.inkwell.ui.components.BookIntro
import com.radium.inkwell.core.util.htmlToPlainText
import com.radium.inkwell.ui.components.ChapterListItem
import com.radium.inkwell.ui.components.CollectMessages
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.ui.components.EmptyState
import com.radium.inkwell.ui.components.LoadingState
import com.radium.inkwell.ui.components.PrimaryButton
import com.radium.inkwell.ui.components.SecondaryButton
import com.radium.inkwell.ui.components.DeterminateProgressBar
import com.radium.inkwell.data.repo.OfflineDownload
import com.radium.inkwell.data.repo.OfflineRange
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.radium.inkwell.ui.components.animationsEnabled
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun BookDetailScreen(
    onRead: () -> Unit,
    onBack: () -> Unit,
    /** 宽屏 list-detail 并排时隐藏返回；窄屏仍显示 */
    showBack: Boolean = true,
    viewModel: BookDetailViewModel,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val download by viewModel.download.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    CollectMessages(viewModel.messages, snackbar)
    // 点目录：进度写完才进阅读器（见 BookDetailViewModel.openChapter）
    LaunchedEffect(Unit) {
        viewModel.openReader.collect { onRead() }
    }
    val book = state.book
    val chapters = state.chapters
    val refreshing = state.refreshing
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val motionOn = animationsEnabled()

    val topBarScroll = rememberAppTopBarScroll()
    // 整页换上封面的强调色（只换 primary 一族，底色不动），见 CoverTintedTheme
    CoverTintedTheme(rememberCoverSeed(book?.coverPath)) {
        AppScaffold(
            modifier = Modifier.topBarScroll(topBarScroll),
            topBar = {
                // 双栏布局里详情页是右侧面板，没有返回键 —— onBack 传 null 就不画。
                // 顶栏与头部渐变的起点同色（primaryContainer），两者之间不留一道色缝
                AppTopBar(
                    "书籍详情",
                    topBarScroll,
                    onBack = onBack.takeIf { showBack },
                    containerColor = MiuixTheme.colorScheme.primaryContainer,
                ) {
                    if (book?.type == BookType.NET) {
                        AppIconButton(
                            onClick = { viewModel.refresh() },
                            enabled = !refreshing,
                            // 刷新中图标换成转圈，名字跟着它走：转圈本身没有名字
                            modifier = if (refreshing) Modifier.semantics { contentDescription = "正在刷新目录" } else Modifier,
                        ) {
                            if (refreshing) {
                                AppLoadingIndicator(size = Dimens.buttonSpinner)
                            } else {
                                Icon(MiuixIcons.Refresh, contentDescription = "刷新目录")
                            }
                        }
                    }
                }
            },
            snackbarHost = { AppSnackbarHost(snackbar) },
        ) { padding ->
            val b = book
            if (b == null) {
                if (state.loaded) {
                    EmptyState(
                        icon = Icons.Outlined.Warning,
                        title = "书籍不存在",
                        hint = "它可能已被删除",
                        actionLabel = "返回",
                        onAction = onBack,
                        modifier = Modifier.padding(padding),
                    )
                } else {
                    LoadingState(Modifier.padding(padding))
                }
                return@AppScaffold
            }

            // 当前章在目录里的位置；没读过、或目录里找不到就是 -1（不给「定位当前章」）
            val currentPos = if (b.readAt > 0) chapters.indexOfFirst { it.index == b.readChapterIndex } else -1
            // 只让开两侧：内容滚到玻璃顶栏与手势条下面（见 AppScaffold）。顶栏高度不进 contentPadding，
            // 而是垫在头部**渐变里面**（Header 的 topInset）—— 顶栏底下取样到的就是渐变起点 primaryContainer，
            // 与顶栏自己的底色一致，停在顶部时两者之间不留色缝
            LazyColumn(
                Modifier.fillMaxSize().padding(padding.horizontalOnly()).pageScroll(),
                state = listState,
                contentPadding = PaddingValues(bottom = padding.calculateBottomPadding()),
                overscrollEffect = null,
            ) {
                item {
                    Header(
                        book = b,
                        topInset = padding.calculateTopPadding(),
                        chapterCount = chapters.size.takeIf { it > 0 } ?: b.totalChapters,
                        cachedCount = if (state.chaptersLoaded) chapters.count { it.isCached } else 0,
                        download = download,
                        onRead = onRead,
                        onStartDownload = viewModel::startDownload,
                        onCancelDownload = viewModel::cancelDownload,
                    )
                }
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(start = Dimens.rowHorizontal, end = Dimens.gapS),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            // 章节表还没读到时先用书行上记的章数，别闪一下「共 0 章」
                            "目录 · 共 ${if (state.chaptersLoaded) chapters.size else b.totalChapters} 章",
                            Modifier.weight(1f).padding(vertical = Dimens.gapS),
                            style = MiuixTheme.textStyles.headline2,
                            fontWeight = FontWeight.Medium,
                        )
                        // 读到第 1500 章时当前章虽然高亮，从前得手动滑很远才看得到。不在进页时自动滚过去：
                        // 那会把封面、简介和「继续阅读」推出屏幕。上面留两行，看得出前后是哪几章
                        if (currentPos >= 0) {
                            AppTextButton("定位当前章", onClick = {
                                val target = TOC_FIRST_ITEM + (currentPos - 2).coerceAtLeast(0)
                                scope.launch {
                                    if (motionOn) listState.animateScrollToItem(target) else listState.scrollToItem(target)
                                }
                            })
                        }
                    }
                    HorizontalDivider()
                }
                when {
                    // 还在读章节表：留白，别先画「没有章节」再换成目录
                    !state.chaptersLoaded -> Unit
                    refreshing && chapters.isEmpty() -> {
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
                                    "正在获取目录…",
                                    style = MiuixTheme.textStyles.body2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                        }
                    }
                    chapters.isEmpty() -> {
                        item {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(Dimens.gapXXL),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    if (b.type == BookType.NET) "目录还没加载" else "这本书没有章节",
                                    style = MiuixTheme.textStyles.body2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                                if (b.type == BookType.NET) {
                                    AppTextButton(
                                        text = "重新获取",
                                        onClick = { viewModel.refresh() },
                                        enabled = !refreshing,
                                    )
                                }
                            }
                        }
                    }
                    else -> {
                        items(chapters, key = { it.index }) { chapter ->
                            val current = chapter.index == b.readChapterIndex && b.readAt > 0
                            ChapterListItem(
                                title = chapter.title,
                                selected = current,
                                onClick = { viewModel.openChapter(chapter.index) },
                            )
                        }
                    }
                }
                item { Spacer(Modifier.height(Dimens.gapXL)) }
            }
        }
    }
}

private fun offlineRangeHint(range: OfflineRange): String = when (range) {
    OfflineRange.NEXT_50 -> "从读到的这一章起，往后 50 章"
    OfflineRange.TO_END -> "从读到的这一章起，一直到最后一章"
    OfflineRange.WHOLE_BOOK -> "从第一章到最后一章。章多的书要好一会儿"
}

/**
 * 头部：封面、书名、阅读与缓存按钮、简介。底下铺一道从 primaryContainer 到页面底色（surface）的竖向渐变 ——
 * 封面色（或 App 强调色）从顶栏流下来，在目录开始的地方化进纸面，详情页就不再是一张白纸上贴一张封面。
 *
 * [topInset]（顶栏高度）垫在渐变**里面**：渐变一直铺到玻璃顶栏底下，顶栏取样到的是同一个起点色。
 */
@Composable
private fun Header(
    book: BookEntity,
    topInset: Dp,
    chapterCount: Int,
    cachedCount: Int,
    download: OfflineDownload?,
    onRead: () -> Unit,
    onStartDownload: (OfflineRange) -> Unit,
    onCancelDownload: () -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    Column(
        Modifier
            // 终点是页面底 surface（Miuix 的分层），与 Scaffold 画布同色，渐变化进去不留边
            .background(Brush.verticalGradient(listOf(scheme.primaryContainer, scheme.surface)))
            .padding(top = topInset)
            .padding(Dimens.screenPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.gapL),
    ) {
        Row {
            BookCover(
                title = book.title,
                coverModel = book.coverPath,
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
                    book.title,
                    style = MiuixTheme.textStyles.title3,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (book.author.isNotBlank()) {
                    Text(
                        book.author,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                Text(
                    "共 $chapterCount 章",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        val readLabel = if (book.readAt > 0) "继续阅读" else "开始阅读"
        if (book.type == BookType.NET) {
            val running = download?.running == true
            var offlineMenuOpen by remember { mutableStateOf(false) }
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.gapM)) {
                PrimaryButton(text = readLabel, onClick = onRead, modifier = Modifier.weight(1f))
                // 缓存范围从按钮上弹出来选（每项下面一行说明从哪缓存到哪）
                Box(Modifier.weight(1f)) {
                    SecondaryButton(
                        text = if (running) "缓存中…" else "离线缓存",
                        onClick = { offlineMenuOpen = true },
                        enabled = !running,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    AppDropdownMenu(
                        expanded = offlineMenuOpen,
                        onDismissRequest = { offlineMenuOpen = false },
                        items = OfflineRange.entries.map { range ->
                            AppMenuItem(
                                text = range.label,
                                summary = offlineRangeHint(range),
                                onClick = { onStartDownload(range) },
                            )
                        },
                    )
                }
            }
            OfflineStatus(download, cachedCount, chapterCount, onCancelDownload)
        } else {
            PrimaryButton(text = readLabel, onClick = onRead, modifier = Modifier.fillMaxWidth())
        }
        val intro = remember(book.intro) { book.intro?.htmlToPlainText().orEmpty() }
        if (intro.isNotBlank()) BookIntro(intro)
    }
}

/**
 * 缓存状态一行：在缓存时是进度条 + 停止；停下来以后说一句结果；平时只报「已缓存多少章」，
 * 一章都没缓存就什么都不说（大多数书从没缓存过，没必要每本都挂一行「已缓存 0 章」）。
 */
@Composable
private fun OfflineStatus(
    download: OfflineDownload?,
    cachedCount: Int,
    chapterCount: Int,
    onCancel: () -> Unit,
) {
    val muted = MiuixTheme.colorScheme.onSurfaceVariantSummary
    when {
        download?.running == true -> Column(verticalArrangement = Arrangement.spacedBy(Dimens.gapXS)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "正在缓存 ${download.done} / ${download.total} 章" +
                        if (download.failed > 0) "，${download.failed} 章没抓到" else "",
                    style = MiuixTheme.textStyles.footnote1,
                    color = muted,
                    modifier = Modifier.weight(1f),
                )
                AppTextButton("停止", onClick = onCancel)
            }
            DeterminateProgressBar(
                progress = if (download.total == 0) 0f else download.done.toFloat() / download.total,
            )
            // 从前写在选范围的面板顶上；范围改成按钮上弹出的列表后放不下长说明，挪到正在缓存时 ——
            // 这时候用户才会想「能不能先走开」「重复的会不会再下一遍」
            Text(
                "离开这一页也会接着缓存；已经缓存过的章直接跳过。需要网页渲染才抓得到的章，留到阅读时再加载",
                style = MiuixTheme.textStyles.footnote1,
                color = muted,
            )
        }
        download != null -> Text(
            when {
                download.stoppedBecause != null -> download.stoppedBecause
                download.done < download.total -> "已停止，缓存了 ${download.done} / ${download.total} 章"
                download.failed > 0 -> "缓存完成，${download.failed} 章没抓到（阅读时会再试）"
                else -> "缓存完成，已缓存 $cachedCount / $chapterCount 章"
            },
            style = MiuixTheme.textStyles.footnote1,
            color = muted,
        )
        cachedCount > 0 -> Text(
            "已缓存 $cachedCount / $chapterCount 章",
            style = MiuixTheme.textStyles.footnote1,
            color = muted,
        )
    }
}

/** 目录列表里第一章所在的位置：前面是头部与「目录」标题两项 */
private const val TOC_FIRST_ITEM = 2
