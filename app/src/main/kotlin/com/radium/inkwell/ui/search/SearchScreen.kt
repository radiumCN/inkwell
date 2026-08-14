package com.radium.inkwell.ui.search

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import com.radium.inkwell.ui.components.AppIconButton
import com.radium.inkwell.ui.components.AppLoadingIndicator
import com.radium.inkwell.ui.components.BackButton
import com.radium.inkwell.ui.components.DeterminateProgressBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import com.radium.inkwell.ui.components.AppSnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radium.inkwell.core.source.SearchResult
import com.radium.inkwell.data.repo.bookKey
import com.radium.inkwell.ui.components.BookListRow
import com.radium.inkwell.ui.components.CollectMessages
import com.radium.inkwell.ui.components.ContentListDefaults
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.ui.components.EmptyState
import com.radium.inkwell.ui.components.OptionPickerSheet
import com.radium.inkwell.ui.components.PickerOption
import com.radium.inkwell.ui.components.SearchField
import com.radium.inkwell.ui.components.expandEnter
import com.radium.inkwell.ui.components.expandExit
import com.radium.inkwell.ui.components.settingsPageColor
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onOpenPreview: (List<SearchResult>) -> Unit,
    viewModel: SearchViewModel,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    CollectMessages(viewModel.messages, snackbar)
    var showSortPicker by rememberSaveable { mutableStateOf(false) }

    // 滚动位置活在 ViewModel：进详情再返回时 Composable 会重建，但 VM 还在返回栈上。
    // 初值从 VM 读；滑动过程持续写回。只有 searchId/sortId 相对上次钉顶变了才滚回顶 ——
    // 否则 LaunchedEffect 在「返回重建」时也会再跑一遍，把位置冲成 0。
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = viewModel.listIndex,
        initialFirstVisibleItemScrollOffset = viewModel.listOffset,
    )
    val nearEnd by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - 3
        }
    }
    // 结果是边搜边出、且会按相关度重排的。LazyColumn 带 key 时会把首个可见项按 key 钉住 ——
    // 更相关的书随后插到它前面，列表就等于被顶下去了，用户得手动往回滑才看得见最相关的那本。
    // 所以：新搜索滚回顶部；搜索过程中只要用户自己没滑动过，就一直粘在顶部。
    LaunchedEffect(listState) {
        snapshotFlow {
            Triple(
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset,
                listState.isScrollInProgress,
            )
        }.collect { (index, offset, scrolling) ->
            if (scrolling) viewModel.noteUserScrolled()
            viewModel.noteScroll(index, offset)
        }
    }
    LaunchedEffect(state.searchId, state.sortId) {
        if (viewModel.consumeScrollToTopIfNeeded(state.searchId, state.sortId)) {
            listState.scrollToItem(0)
        }
    }
    LaunchedEffect(state.results.firstOrNull()) {
        if (state.searching && !viewModel.userScrolled) {
            listState.scrollToItem(0)
            viewModel.noteScroll(0, 0)
        }
    }

    LaunchedEffect(nearEnd) {
        if (nearEnd) viewModel.loadMore()
    }

    // 与书架/设置同一套画布：浅色灰底白卡。默认 Scaffold 是 surface 白底，
    // 结果行圆角缝里会透出一块白板，像每条结果后面垫了一层白背景。
    val pageColor = settingsPageColor()
    Scaffold(
        containerColor = pageColor,
        topBar = {
            // 留经典窄栏：标题位放的是输入框。换成 AppTopBar 的两段式，输入框会被摊到
            // 大标题那一行的位置上，它自己的高度与字号跟大标题的排版打架
            TopAppBar(
                title = {
                    SearchField(
                        value = state.query,
                        onValueChange = viewModel::setQuery,
                        placeholder = "书名 / 作者",
                        onSearch = { viewModel.search() },
                        modifier = Modifier.padding(end = Dimens.gapS),
                    )
                },
                navigationIcon = {
                    BackButton(onClick = onBack)
                },
                actions = {
                    AppIconButton(onClick = { viewModel.search() }) {
                        Icon(Icons.Default.Search, contentDescription = "搜索")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = pageColor,
                    scrolledContainerColor = pageColor,
                ),
            )
        },
        snackbarHost = { AppSnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            AnimatedVisibility(
                visible = state.searching || state.searchPaused,
                enter = expandEnter(),
                exit = expandExit(),
            ) {
                Column {
                    DeterminateProgressBar(
                        progress = {
                            if (state.sourceCount == 0) 0f
                            else state.doneCount.toFloat() / state.sourceCount
                        },
                    )
                    if (state.searchPaused) {
                        SearchPauseBar(onResume = viewModel::resumeSearch)
                    }
                }
            }
            if (state.results.isEmpty() && !state.searching && !state.searchPaused) {
                EmptyState(
                    icon = Icons.Default.Search,
                    title = "按已启用的规则搜索",
                    hint = "输入书名或作者；未导入规则时无结果",
                )
            } else {
                // 列表上方一条：左侧数量、右侧当前排序。不塞进顶栏 ——
                // 顶栏已经是搜索框 + 搜索按钮，再加排序图标会挤成一团。
                SearchSortBar(
                    count = state.results.size,
                    sort = state.sort,
                    searching = state.searching,
                    onOpenSort = { showSortPicker = true },
                )
                // edge-to-edge 下让结果列表底部让开键盘，不然最后几条被盖住也滚不出来
                LazyColumn(
                    state = listState,
                    modifier = Modifier.imePadding(),
                    contentPadding = ContentListDefaults.listContentPadding(),
                    verticalArrangement = Arrangement.spacedBy(ContentListDefaults.ListSpacing),
                ) {
                    items(state.results, key = { "${it.result.sourceId}|${it.result.bookUrl}" }) { hit ->
                        val result = hit.result
                        val inShelf = bookKey(result.title, result.author) in state.shelfKeys
                        BookListRow(
                            title = result.title,
                            subtitle = listOfNotNull(
                                result.author,
                                result.wordCount,
                                result.latestChapter,
                            ).joinToString(" · "),
                            // 同名同作者的书跨书源合并成一行；书源越多越可能就是要找的那本
                            caption = buildString {
                                if (hit.origins.size > 1) append("${hit.origins.size} 个书源 · ")
                                else append("来源: ")
                                append(hit.preferred.sourceId)
                                result.kind?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
                            },
                            coverModel = hit.preferred.coverUrl ?: result.coverUrl,
                            // 已在书架就显示"已加入"且不可点，不再让人重复加
                            trailingLabel = if (inShelf) "已加入" else "加入",
                            trailingLoading = state.addingUrl == result.bookUrl,
                            trailingEnabled = !inShelf,
                            onTrailing = { viewModel.addToShelf(hit) },
                            onClick = {
                                // 进详情就停搜：后台还在打上百个源的话，详情页会一直转圈。
                                // 返回后不自动接着搜，由用户点「继续搜索」。
                                viewModel.pauseSearch()
                                onOpenPreview(viewModel.rankedResults(hit.results))
                            },
                        )
                    }
                    if (state.loadingMore) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(Dimens.gapL), contentAlignment = Alignment.Center) {
                                AppLoadingIndicator(size = Dimens.iconMd)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSortPicker) {
        OptionPickerSheet(
            title = "排序方式",
            options = SearchSort.entries.map {
                PickerOption(
                    id = it.name,
                    label = it.label,
                    subtitle = when (it) {
                        SearchSort.RELEVANCE -> "与关键词最相关的排前面"
                        SearchSort.WORD_COUNT -> "字数多的排前面；无字数的沉底"
                        SearchSort.TITLE_PINYIN -> "按书名拼音 A→Z"
                        SearchSort.AUTHOR_PINYIN -> "按作者拼音 A→Z"
                        SearchSort.UPDATE_TIME -> "能解析出日期的按新到旧；多数源写在分类里"
                    },
                )
            },
            selectedId = state.sort.name,
            onSelect = { opt ->
                showSortPicker = false
                runCatching { SearchSort.valueOf(opt.id) }.getOrNull()?.let(viewModel::setSort)
            },
            onDismiss = { showSortPicker = false },
        )
    }
}

/**
 * 搜索结果工具条：左数量、右排序入口。整行高度贴 [Dimens.touchTarget]，
 * 点排序一侧打开底部面板（与书源管理同一套 OptionPicker）。
 */
@Composable
private fun SearchSortBar(
    count: Int,
    sort: SearchSort,
    searching: Boolean,
    onOpenSort: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.listHorizontal, vertical = Dimens.gapXS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (count == 0 && searching) "搜索中…" else "共 $count 本",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        Row(
            Modifier
                .clickable(role = Role.Button, onClick = onOpenSort)
                .padding(vertical = Dimens.gapS, horizontal = Dimens.gapXS),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Sort,
                contentDescription = null,
                Modifier.size(Dimens.iconSm),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                sort.label,
                Modifier.padding(start = Dimens.gapXS),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 进详情后搜索被掐掉：进度条冻在当时的位置，给一个手动「继续」而不是返回就自动接着打。
 * 密度和 [SearchSortBar] 同一套，避免进度条底下突然冒出一块 40dp 按钮。
 */
@Composable
private fun SearchPauseBar(onResume: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.listHorizontal, vertical = Dimens.gapXS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "搜索已暂停",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "继续搜索",
            Modifier
                .clickable(role = Role.Button, onClick = onResume)
                .heightIn(min = Dimens.touchTarget)
                .wrapContentHeight(Alignment.CenterVertically)
                .padding(horizontal = Dimens.gapXS),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
