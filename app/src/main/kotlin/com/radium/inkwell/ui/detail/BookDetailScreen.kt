package com.radium.inkwell.ui.detail

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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.radium.inkwell.ui.components.animationsEnabled

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookDetailScreen(
    onRead: () -> Unit,
    onBack: () -> Unit,
    /** 宽屏 list-detail 并排时隐藏返回；窄屏仍显示 */
    showBack: Boolean = true,
    viewModel: BookDetailViewModel,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
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
    Scaffold(
        modifier = Modifier.topBarScroll(topBarScroll),
        topBar = {
            // 双栏布局里详情页是右侧面板，没有返回键 —— onBack 传 null 就不画
            AppTopBar("书籍详情", topBarScroll, onBack = onBack.takeIf { showBack }) {
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
                            Icon(Icons.Default.Refresh, contentDescription = "刷新目录")
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
                    icon = Icons.Default.Warning,
                    title = "书籍不存在",
                    hint = "它可能已被删除",
                    actionLabel = "返回",
                    onAction = onBack,
                    modifier = Modifier.padding(padding),
                )
            } else {
                LoadingState(Modifier.padding(padding))
            }
            return@Scaffold
        }

        // 当前章在目录里的位置；没读过、或目录里找不到就是 -1（不给「定位当前章」）
        val currentPos = if (b.readAt > 0) chapters.indexOfFirst { it.index == b.readChapterIndex } else -1
        LazyColumn(Modifier.fillMaxSize().padding(padding), state = listState) {
            item {
                Header(
                    book = b,
                    chapterCount = chapters.size.takeIf { it > 0 } ?: b.totalChapters,
                    onRead = onRead,
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
                        style = MaterialTheme.typography.titleMedium,
                    )
                    // 读到第 1500 章时当前章虽然高亮，从前得手动滑很远才看得到。不在进页时自动滚过去：
                    // 那会把封面、简介和「继续阅读」推出屏幕。上面留两行，看得出前后是哪几章
                    if (currentPos >= 0) {
                        AppTextButton(onClick = {
                            val target = TOC_FIRST_ITEM + (currentPos - 2).coerceAtLeast(0)
                            scope.launch {
                                if (motionOn) listState.animateScrollToItem(target) else listState.scrollToItem(target)
                            }
                        }) {
                            Text("定位当前章")
                        }
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
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (b.type == BookType.NET) {
                                AppTextButton(
                                    onClick = { viewModel.refresh() },
                                    enabled = !refreshing,
                                ) {
                                    Text("重新获取")
                                }
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

@Composable
private fun Header(
    book: BookEntity,
    chapterCount: Int,
    onRead: () -> Unit,
) {

    Column(
        Modifier.padding(Dimens.screenPadding),
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
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (book.author.isNotBlank()) {
                    Text(
                        book.author,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "共 $chapterCount 章",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        PrimaryButton(
            text = if (book.readAt > 0) "继续阅读" else "开始阅读",
            onClick = onRead,
            modifier = Modifier.fillMaxWidth(),
        )
        val intro = remember(book.intro) { book.intro?.htmlToPlainText().orEmpty() }
        if (intro.isNotBlank()) BookIntro(intro)
    }
}

/** 目录列表里第一章所在的位置：前面是头部与「目录」标题两项 */
private const val TOC_FIRST_ITEM = 2
