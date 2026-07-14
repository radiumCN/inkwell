package com.radium.inkwell.ui.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radium.inkwell.core.source.SearchResult
import com.radium.inkwell.ui.components.BookListRow
import com.radium.inkwell.ui.components.CollectMessages
import com.radium.inkwell.ui.components.EmptyState
import com.radium.inkwell.ui.components.SearchField
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onOpenPreview: (SearchResult) -> Unit,
    viewModel: SearchViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    CollectMessages(viewModel.messages, snackbar)

    // 滚到底部自动加载下一页（与发现页一致）
    val listState = rememberLazyListState()
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
                    SearchField(
                        value = state.query,
                        onValueChange = viewModel::setQuery,
                        placeholder = "书名 / 作者",
                        onSearch = { viewModel.search() },
                        modifier = Modifier.padding(end = 8.dp),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.search() }) {
                        Icon(Icons.Default.Search, contentDescription = "搜索")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.searching) {
                LinearProgressIndicator(
                    progress = {
                        if (state.sourceCount == 0) 0f
                        else state.doneCount.toFloat() / state.sourceCount
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (state.results.isEmpty() && !state.searching) {
                EmptyState(
                    icon = Icons.Default.Search,
                    title = "搜索全网书源",
                    hint = "输入书名或作者，从启用的书源中并发搜索",
                )
            } else {
                LazyColumn(state = listState) {
                    items(state.results, key = { "${it.result.title}|${it.result.author}" }) { hit ->
                        val result = hit.result
                        BookListRow(
                            title = result.title,
                            subtitle = listOfNotNull(result.author, result.latestChapter)
                                .joinToString(" · "),
                            // 同名同作者的书跨书源合并成一行；书源越多越可能就是要找的那本
                            caption = if (hit.origins.size > 1) {
                                "${hit.origins.size} 个书源 · ${result.sourceId}"
                            } else {
                                "来源: ${result.sourceId}"
                            },
                            coverModel = result.coverUrl,
                            trailingLabel = "加入",
                            trailingLoading = state.addingUrl == result.bookUrl,
                            onTrailing = { viewModel.addToShelf(result) },
                            onClick = { onOpenPreview(result) },
                        )
                    }
                    if (state.loadingMore) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(24.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
