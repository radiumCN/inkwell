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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.radium.inkwell.data.db.dao.ChapterDao
import com.radium.inkwell.data.db.entity.BookEntity
import com.radium.inkwell.data.db.entity.BookType
import com.radium.inkwell.data.db.entity.ChapterEntity
import com.radium.inkwell.data.repo.BookRepository
import com.radium.inkwell.data.repo.BookSourceRepository
import com.radium.inkwell.data.repo.NetBookRepository
import com.radium.inkwell.ui.components.AppLoadingIndicator
import com.radium.inkwell.ui.components.AppSnackbarHost
import com.radium.inkwell.ui.components.BookCover
import com.radium.inkwell.ui.components.ChapterListItem
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.ui.components.EmptyState
import com.radium.inkwell.ui.components.LoadingState
import com.radium.inkwell.ui.components.PrimaryButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookDetailScreen(
    bookId: String,
    onRead: () -> Unit,
    onBack: () -> Unit,
    /** 宽屏 list-detail 并排时隐藏返回；窄屏仍显示 */
    showBack: Boolean = true,
) {
    val bookRepo = koinInject<BookRepository>()
    val netBookRepo = koinInject<NetBookRepository>()
    val sourceRepo = koinInject<BookSourceRepository>()
    val chapterDao = koinInject<ChapterDao>()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var refreshing by remember { mutableStateOf(false) }
    // 区分"还在查库"和"查完了没这本书"：前者转圈，后者给空态
    var loaded by remember(bookId) { mutableStateOf(false) }
    val book by produceState<BookEntity?>(initialValue = null, bookId) {
        // 追更会改 totalChapters / coverPath / intro —— 订阅书行，刷新后 UI 自动跟上，
        // 不必再靠 reloadKey 整页重拉。
        bookRepo.observeBook(bookId).collect {
            value = it
            loaded = true
        }
    }
    val chapters by produceState(emptyList<ChapterEntity>(), bookId) {
        chapterDao.observeByBook(bookId).collect { value = it }
    }

    /**
     * 追更。返回新增章数。
     *
     * 顺带：封面为空时 refreshToc 会补抓详情（见 NetBookRepository）——
     * WebDAV 同步下来的书缺封面、缺目录，进详情自动跑一次就能两边都补齐。
     */
    suspend fun refreshToc(quiet: Boolean = false): Result<Int> {
        val b = book ?: return Result.failure(IllegalStateException("书不存在"))
        val rule = b.sourceId?.let { sourceRepo.getRule(it) }
        if (rule == null) {
            val err = IllegalStateException("书源不存在，先换源")
            if (!quiet) snackbar.showSnackbar(err.message!!)
            return Result.failure(err)
        }
        return netBookRepo.refreshToc(b, rule)
            .onSuccess { added ->
                if (!quiet) {
                    snackbar.showSnackbar(
                        if (added > 0) "更新了 $added 章" else "已经是最新的了",
                    )
                }
            }
            .onFailure {
                if (!quiet) snackbar.showSnackbar("刷新失败: ${it.message}")
            }
    }

    fun triggerRefresh(quiet: Boolean = false) {
        if (refreshing) return
        refreshing = true
        scope.launch {
            try {
                refreshToc(quiet = quiet)
            } finally {
                refreshing = false
            }
        }
    }

    // WebDAV 只同步书行、不同步章节表。同步下来的网络书进详情时目录是空的 ——
    // 以前要等进阅读器才补抓，详情页自己却装聋作哑（菜单还写着「目录」）。
    // 进页发现章节表为空就自动追更一次；失败不弹窗打扰，留给下面的空态重试出口。
    var autoFetchAttempted by remember(bookId) { mutableStateOf(false) }
    LaunchedEffect(bookId, book?.id) {
        val b = book ?: return@LaunchedEffect
        if (autoFetchAttempted) return@LaunchedEffect
        if (b.type != BookType.NET) return@LaunchedEffect
        autoFetchAttempted = true
        // 直接查库，避开 Flow 首帧还是 emptyList 时误触发
        if (chapterDao.getByBook(bookId).isNotEmpty()) return@LaunchedEffect
        refreshing = true
        try {
            refreshToc(quiet = true)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // quiet：空态里有重试
        } finally {
            refreshing = false
        }
    }

    fun openReader(chapterIndex: Int? = null) {
        scope.launch {
            if (chapterIndex != null) {
                bookRepo.saveProgress(bookId, chapterIndex, 0)
            }
            onRead()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("书籍详情") },
                navigationIcon = {
                    if (showBack) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                },
                actions = {
                    if (book?.type == BookType.NET) {
                        IconButton(
                            onClick = { triggerRefresh(quiet = false) },
                            enabled = !refreshing,
                        ) {
                            if (refreshing) {
                                AppLoadingIndicator(size = Dimens.buttonSpinner)
                            } else {
                                Icon(Icons.Default.Refresh, contentDescription = "刷新目录")
                            }
                        }
                    }
                },
            )
        },
        snackbarHost = { AppSnackbarHost(snackbar) },
    ) { padding ->
        val b = book
        if (b == null) {
            if (loaded) {
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

        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Header(
                    book = b,
                    chapterCount = chapters.size.takeIf { it > 0 } ?: b.totalChapters,
                    onRead = { openReader() },
                )
            }
            item {
                Text(
                    "目录 · 共 ${chapters.size} 章",
                    Modifier.padding(horizontal = Dimens.rowHorizontal, vertical = Dimens.gapS),
                    style = MaterialTheme.typography.titleMedium,
                )
                HorizontalDivider()
            }
            when {
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
                                TextButton(
                                    onClick = { triggerRefresh(quiet = false) },
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
                            onClick = { openReader(chapter.index) },
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
    var introExpanded by remember { mutableStateOf(false) }

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
            )
            Column(Modifier.padding(start = Dimens.gapL).align(Alignment.CenterVertically)) {
                Text(book.title, style = MaterialTheme.typography.titleLarge, maxLines = 2)
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
        if (!book.intro.isNullOrBlank()) {
            Column {
                Text("简介", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(Dimens.gapXS))
                Text(
                    book.intro,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (introExpanded) Int.MAX_VALUE else 4,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(onClick = { introExpanded = !introExpanded }) {
                    Text(if (introExpanded) "收起" else "展开")
                }
            }
        }
    }
}
