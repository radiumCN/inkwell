package com.radium.inkwell.ui.explore

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radium.inkwell.core.source.SearchResult
import com.radium.inkwell.ui.components.BookListRow
import com.radium.inkwell.ui.components.EmptyState
import com.radium.inkwell.ui.components.OptionPickerSheet
import com.radium.inkwell.ui.components.PickerOption
import com.radium.inkwell.ui.components.CollectMessages
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreScreen(
    onBack: () -> Unit,
    onOpenSourceManage: () -> Unit,
    onOpenPreview: (List<SearchResult>) -> Unit,
    viewModel: ExploreViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    CollectMessages(viewModel.messages, snackbar)
    val listState = rememberLazyListState()
    var sourceMenuOpen by remember { mutableStateOf(false) }

    // 滚到底部自动加载下一页
    val nearEnd by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - 3
        }
    }
    LaunchedEffect(nearEnd) {
        if (nearEnd) viewModel.loadMore()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    // 选书源统一走底部面板：从前是裸 DropdownMenu，弹个小浮层、选中态全靠猜
                    TextButton(onClick = { sourceMenuOpen = true }) {
                        Text(
                            state.currentSource?.name ?: "发现",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Icon(Icons.Default.ArrowDropDown, contentDescription = "切换书源")
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.sources.isEmpty()) {
                EmptyState(
                    icon = Icons.Default.Explore,
                    title = "没有可用的发现书源",
                    hint = "导入带发现页规则的书源后，可在此浏览分类书单",
                    actionLabel = "去导入书源",
                    onAction = onOpenSourceManage,
                )
                return@Column
            }

            // 分类 chips
            if (state.categories.size > 1) {
                LazyRow(
                    Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(state.categories) { i, name ->
                        FilterChip(
                            selected = i == state.categoryIndex,
                            onClick = { viewModel.selectCategory(i) },
                            label = { Text(name) },
                        )
                    }
                }
            }

            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                state.books.isEmpty() -> EmptyState(
                    icon = Icons.Default.Explore,
                    title = "这个分类没有内容",
                    actionLabel = "重试",
                    onAction = { viewModel.retry() },
                )
                else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(state.books, key = { "${it.sourceId}|${it.bookUrl}" }) { book ->
                        BookListRow(
                            title = book.title,
                            subtitle = listOfNotNull(book.author, book.latestChapter)
                                .joinToString(" · "),
                            caption = book.intro,
                            coverModel = book.coverUrl,
                            trailingLabel = "加入",
                            trailingLoading = state.addingUrl == book.bookUrl,
                            onTrailing = { viewModel.addToShelf(book) },
                            onClick = { onOpenPreview(listOf(book)) },
                        )
                    }
                    if (state.loadingMore) {
                        item {
                            Box(
                                Modifier.fillMaxWidth().padding(16.dp),
                                contentAlignment = Alignment.Center,
                            ) { CircularProgressIndicator(Modifier.size(24.dp)) }
                        }
                    }
                }
            }
        }
    }

    if (sourceMenuOpen) {
        OptionPickerSheet(
            title = "选择书源",
            options = state.sources.map { PickerOption(id = it.id, label = it.name) },
            selectedId = state.currentSource?.id,
            onSelect = { opt ->
                sourceMenuOpen = false
                state.sources.indexOfFirst { it.id == opt.id }
                    .takeIf { it >= 0 }
                    ?.let { viewModel.selectSource(it) }
            },
            onDismiss = { sourceMenuOpen = false },
        )
    }
}
