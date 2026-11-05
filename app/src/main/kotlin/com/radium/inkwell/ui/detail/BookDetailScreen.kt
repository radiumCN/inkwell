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
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radium.inkwell.data.db.entity.BookEntity
import com.radium.inkwell.data.db.entity.BookType
import com.radium.inkwell.ui.components.AppIconButton
import com.radium.inkwell.ui.components.AppLoadingIndicator
import com.radium.inkwell.ui.components.AppToolbar
import com.radium.inkwell.ui.components.BackButton
import com.radium.inkwell.ui.components.SectionHeader
import com.radium.inkwell.ui.components.ToolbarTitle
import com.radium.inkwell.ui.components.groupedCardItem
import com.radium.inkwell.ui.components.rememberToolbarTitleReveal
import com.radium.inkwell.ui.components.AppSnackbarHost
import com.radium.inkwell.ui.components.BookCover
import com.radium.inkwell.ui.components.BookIntro
import com.radium.inkwell.core.util.htmlToPlainText
import com.radium.inkwell.ui.components.ChapterListItem
import com.radium.inkwell.ui.components.CoverHeaderBackground
import com.radium.inkwell.ui.components.CollectMessages
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.ui.components.EmptyState
import com.radium.inkwell.ui.components.LoadingState
import com.radium.inkwell.ui.components.PrimaryButton
import com.radium.inkwell.ui.components.SecondaryButton
import com.radium.inkwell.ui.components.DeterminateProgressBar
import com.radium.inkwell.data.repo.OfflineDownload
import com.radium.inkwell.data.repo.OfflineRange
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.radium.inkwell.ui.components.animationsEnabled
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.icon.extended.Report

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

    // 窄栏，不用大标题栏：这一页的标题就是书名。从前停在顶部时是 32sp 的「书籍详情」压在 20sp 的书名上面 ——
    // 两行标题，大的那行没有信息量。栏里的书名等头部的书名滚到栏底下才淡出来（见 ToolbarTitleReveal）
    val reveal = rememberToolbarTitleReveal(listState)
    // 整页换上封面的强调色（只换 primary 一族，底色不动），见 CoverTintedTheme
    CoverTintedTheme(rememberCoverSeed(book?.coverPath)) {
        AppScaffold(
            topBar = {
                // 双栏布局里详情页是右侧面板，没有返回键。
                // 顶栏是页面底色的玻璃：停在顶部时它糊的就是头部最上面那截封面，从上往下渐渐变清、化进头部（见 barBlur）
                AppToolbar(
                    modifier = reveal.bar,
                    navigationIcon = if (showBack) {
                        { BackButton(onClick = onBack) }
                    } else {
                        null
                    },
                    actions = {
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
                    },
                ) {
                    ToolbarTitle(book?.title.orEmpty(), Modifier.graphicsLayer { alpha = reveal.titleAlpha })
                }
            },
            snackbarHost = { AppSnackbarHost(snackbar) },
        ) { padding ->
            val b = book
            if (b == null) {
                if (state.loaded) {
                    EmptyState(
                        icon = MiuixIcons.Report,
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
            // 而是垫在头部的**封面底里面**（Header 的 topInset）—— 封面一直铺到顶栏底下，顶栏糊的就是它，
            // 停在顶部时栏和头部之间不是一道页面底色的空白
            LazyColumn(
                Modifier.fillMaxSize().padding(padding.horizontalOnly()).pageScroll(),
                state = listState,
                contentPadding = PaddingValues(bottom = padding.calculateBottomPadding()),
                overscrollEffect = null,
            ) {
                item {
                    Header(
                        book = b,
                        titleModifier = reveal.title,
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
                    // 分组小标题，和设置页、书源页的一样（从前是 16sp 的一行字 + 分隔线）
                    SectionHeader(
                        // 章节表还没读到时先用书行上记的章数，别闪一下「共 0 章」
                        "目录 · 共 ${if (state.chaptersLoaded) chapters.size else b.totalChapters} 章",
                        // 读到第 1500 章时当前章虽然高亮，从前得手动滑很远才看得到。不在进页时自动滚过去：
                        // 那会把封面、简介和「继续阅读」推出屏幕。上面留两行，看得出前后是哪几章
                        trailing = if (currentPos >= 0) {
                            {
                                AppTextButton("定位当前章", onClick = {
                                    val target = TOC_FIRST_ITEM + (currentPos - 2).coerceAtLeast(0)
                                    scope.launch {
                                        if (motionOn) listState.animateScrollToItem(target) else listState.scrollToItem(target)
                                    }
                                })
                            }
                        } else {
                            null
                        },
                    )
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
                        // 整个目录装进一张卡（灰底白卡，同书架列表、搜索结果），从前是一章一张小卡、卡间留缝
                        itemsIndexed(chapters, key = { _, it -> it.index }) { i, chapter ->
                            val current = chapter.index == b.readChapterIndex && b.readAt > 0
                            ChapterListItem(
                                title = chapter.title,
                                selected = current,
                                // 读过的变灰：一眼看出读到哪、后面还剩多少（同阅读页的目录面板）
                                read = b.readAt > 0 && chapter.index < b.readChapterIndex,
                                onClick = { viewModel.openChapter(chapter.index) },
                                modifier = Modifier.groupedCardItem(i, chapters.size),
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
 * 头部：封面、书名、阅读与缓存按钮、简介。底下铺这本书的封面，渐进模糊、再化进页面底色（[CoverHeaderBackground]）——
 * 封面的颜色从顶栏底下流下来，在目录开始的地方化进页面，详情页就不再是一张白纸上贴一张封面。
 *
 * [topInset]（顶栏高度）垫在封面底**里面**：封面一直铺到玻璃顶栏底下，顶栏糊的就是它。
 */
@Composable
private fun Header(
    book: BookEntity,
    /** 挂在书名上：它滚到栏底下以后，栏里才淡出书名（ToolbarTitleReveal） */
    titleModifier: Modifier,
    topInset: Dp,
    chapterCount: Int,
    cachedCount: Int,
    download: OfflineDownload?,
    onRead: () -> Unit,
    onStartDownload: (OfflineRange) -> Unit,
    onCancelDownload: () -> Unit,
) {
    Box {
        // 底的终点是页面底 surface（Miuix 的分层），与 Scaffold 画布同色，化进去不留边
        CoverHeaderBackground(book.title, book.coverPath, Modifier.matchParentSize())
        // 左右与下面目录卡的边对齐（listHorizontal）：封面、按钮、简介和目录卡是一条竖线。
        // 从前是 screenPadding（20dp），比目录卡（16dp）往里缩一截，两个按钮比下面的卡窄
        Column(
            Modifier
                .padding(top = topInset)
                .padding(horizontal = Dimens.listHorizontal, vertical = Dimens.screenPadding),
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
                        modifier = titleModifier,
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
