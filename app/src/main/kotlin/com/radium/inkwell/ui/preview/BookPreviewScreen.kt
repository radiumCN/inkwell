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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import com.radium.inkwell.ui.components.AppSnackbarHost
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
import com.radium.inkwell.ui.components.PrimaryButton
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radium.inkwell.core.source.SearchResult
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.ui.components.BookCover
import com.radium.inkwell.ui.components.ErrorState
import com.radium.inkwell.ui.components.LoadingState
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
        snackbarHost = { AppSnackbarHost(snackbar) },
    ) { padding ->
        when {
            state.loading -> LoadingState(
                Modifier.padding(padding),
                label = "正在获取详情与目录…",
            )

            // 从前这里手搓了一份错误屏（还漏掉了图标）；收敛到共享 ErrorState。
            // 一个源挂了不该让人卡死 —— 有别的书源就给出「换个书源」的出口。
            state.error != null -> ErrorState(
                message = state.error.orEmpty(),
                modifier = Modifier.padding(padding),
                onRetry = viewModel::load,
                secondaryLabel = "换个书源试试（共 ${state.sources.size} 个）"
                    .takeIf { state.sources.size > 1 },
                onSecondary = if (state.sources.size > 1) ({ sourcePickerOpen = true }) else null,
            )

            else -> LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                item { Header(state, viewModel, onOpenSourcePicker = { sourcePickerOpen = true }) }
                item {
                    Text(
                        "目录 · 共 ${state.chapters.size} 章",
                        Modifier.padding(horizontal = Dimens.rowHorizontal, vertical = Dimens.gapS),
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
                            // 纯文字可点行保留 rowVertical(14)：list*(8) 会让行高跌破 48dp 触控下限
                            .padding(horizontal = Dimens.rowHorizontal, vertical = Dimens.rowVertical),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    HorizontalDivider()
                }
                item { Spacer(Modifier.height(Dimens.gapXL)) }
            }
        }
    }

    // 换源：详情页从前一处是裸 DropdownMenu（选中态靠手拼 "✓ "），
    // 另一处是加载失败时一排纵向 TextButton。两处收敛到同一个面板。
    if (sourcePickerOpen) {
        OptionPickerSheet(
            title = "换源",
            // 书源名称当主标题，网址退到副标题 —— 从前列表里只有一串域名
            options = state.sources.map { PickerOption(id = it.id, label = it.name, subtitle = it.id) },
            selectedId = state.sources.getOrNull(state.currentSource)?.id,
            onSelect = { opt ->
                sourcePickerOpen = false
                state.sources.indexOfFirst { it.id == opt.id }
                    .takeIf { it >= 0 }
                    ?.let { viewModel.switchSource(it) }
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
        Modifier.padding(Dimens.screenPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.gapL),
    ) {
        Row {
            BookCover(
                title = state.title,
                coverModel = state.coverUrl,
                modifier = Modifier.size(width = 96.dp, height = 128.dp),
                placeholderChars = 4,
            )
            Column(Modifier.padding(start = Dimens.gapL).align(Alignment.CenterVertically)) {
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

        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.gapM)) {
            SecondaryButton(
                text = if (state.inShelf) "已在书架" else "加入书架",
                onClick = viewModel::addToShelf,
                enabled = !state.busy && !state.inShelf,
                loading = state.busy && !state.inShelf,
                modifier = Modifier.weight(1f),
            )
            PrimaryButton(
                text = "开始阅读",
                onClick = { viewModel.read() },
                enabled = !state.busy,
                modifier = Modifier.weight(1f),
            )
        }

        if (!state.intro.isNullOrBlank()) {
            Column {
                Text("简介", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(Dimens.gapXS))
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
