package com.radium.inkwell.ui.preview

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radium.inkwell.core.source.SearchResult
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.ui.components.BookCover
import com.radium.inkwell.ui.components.SecondaryButton
import com.radium.inkwell.ui.components.OptionPickerSheet
import com.radium.inkwell.ui.components.PickerOption
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookPreviewScreen(
    results: List<SearchResult>,
    onRead: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: BookPreviewViewModel = koinViewModel { parametersOf(results) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var sourcePickerOpen by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.messages.messages.collect { snackbar.showSnackbar(it) }
    }
    LaunchedEffect(Unit) {
        viewModel.openReader.collect(onRead)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("书籍详情", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when {
            state.loading -> Column(
                Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text("正在获取详情与目录…", style = MaterialTheme.typography.bodySmall)
            }

            state.error != null -> Column(
                Modifier.fillMaxSize().padding(padding).padding(horizontal = 32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("加载失败", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    state.error.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = viewModel::load) { Text("重试") }

                // 这本书还有别的书源 —— 一个源挂了不该让人卡死在这
                if (state.sources.size > 1) {
                    Spacer(Modifier.height(24.dp))
                    OutlinedButton(onClick = { sourcePickerOpen = true }) {
                        Text("换个书源试试（共 ${state.sources.size} 个）")
                    }
                }
            }

            else -> LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                item { Header(state, viewModel, onOpenSourcePicker = { sourcePickerOpen = true }) }
                item {
                    Text(
                        "目录 · 共 ${state.chapters.size} 章",
                        Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    HorizontalDivider()
                }
                items(state.chapters, key = { it.index }) { chapter ->
                    Text(
                        chapter.title,
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !state.busy) { viewModel.read(chapter.index) }
                            .padding(horizontal = Dimens.rowHorizontal, vertical = Dimens.rowVertical),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    HorizontalDivider()
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    // 换源：详情页从前一处是裸 DropdownMenu（选中态靠手拼 "✓ "），
    // 另一处是加载失败时一排纵向 TextButton。两处收敛到同一个面板。
    if (sourcePickerOpen) {
        OptionPickerSheet(
            title = "换源",
            options = state.sources.map { PickerOption(id = it, label = it) },
            selectedId = state.sources.getOrNull(state.currentSource),
            onSelect = { opt ->
                sourcePickerOpen = false
                state.sources.indexOf(opt.id).takeIf { it >= 0 }?.let { viewModel.switchSource(it) }
            },
            onDismiss = { sourcePickerOpen = false },
        )
    }
}

@Composable
private fun Header(
    state: BookPreviewUiState,
    viewModel: BookPreviewViewModel,
    onOpenSourcePicker: () -> Unit,
) {
    var introExpanded by remember { mutableStateOf(false) }

    Column(
        Modifier.padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row {
            BookCover(
                title = state.title,
                coverModel = state.coverUrl,
                modifier = Modifier.size(width = 96.dp, height = 128.dp),
                placeholderChars = 4,
            )
            Column(Modifier.padding(start = 16.dp).align(Alignment.CenterVertically)) {
                Text(state.title, style = MaterialTheme.typography.titleLarge, maxLines = 2)
                if (state.author.isNotBlank()) {
                    Text(
                        state.author,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "共 ${state.chapters.size} 章 · 来源 ${state.sourceName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                if (state.sources.size > 1) {
                    TextButton(onClick = onOpenSourcePicker, contentPadding = PaddingValues(0.dp)) {
                        Text("换源（${state.sources.size} 个书源）", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SecondaryButton(
                text = if (state.inShelf) "已在书架" else "加入书架",
                onClick = viewModel::addToShelf,
                enabled = !state.busy && !state.inShelf,
                loading = state.busy && !state.inShelf,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = { viewModel.read() },
                enabled = !state.busy,
                modifier = Modifier.weight(1f),
            ) {
                Text("开始阅读")
            }
        }

        if (!state.intro.isNullOrBlank()) {
            Column {
                Text("简介", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    state.intro,
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
