package com.radium.inkwell.ui.explore

import com.radium.inkwell.ui.components.exceptBottom
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import com.radium.inkwell.ui.components.AppLoadingIndicator
import com.radium.inkwell.ui.components.AppTextButton
import com.radium.inkwell.ui.components.BackButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.radium.inkwell.ui.components.Dimens
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radium.inkwell.core.source.SearchResult
import com.radium.inkwell.data.repo.bookKey
import com.radium.inkwell.ui.components.BookListRow
import com.radium.inkwell.ui.components.ChipRow
import com.radium.inkwell.ui.components.CollectMessages
import com.radium.inkwell.ui.components.ContentListDefaults
import com.radium.inkwell.ui.components.EmptyState
import com.radium.inkwell.ui.components.ErrorState
import com.radium.inkwell.ui.components.LoadingState
import com.radium.inkwell.ui.components.OptionPickerSheet
import com.radium.inkwell.ui.components.PickerOption
import com.radium.inkwell.ui.components.settingsPageColor
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreScreen(
    onBack: () -> Unit,
    onOpenSourceManage: () -> Unit,
    onOpenPreview: (List<SearchResult>) -> Unit,
    viewModel: ExploreViewModel,
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
    // 同搜索页：只认 nearEnd 的跳变，一页只多两三行、或首页一屏放得下时就再也翻不动了
    LaunchedEffect(nearEnd, state.hasMore, state.loading, state.loadingMore, state.books.size) {
        if (nearEnd) viewModel.loadMore()
    }
    // 换源/换分类回到顶部。listState 是跨分类共用的：上一个分类滑到第 40 条，新分类就被夹到
    // 它的最后一屏打开，往往还顺手触发了第 2 页。切换时列表多半还在转圈、没挂上去，
    // 用 requestScrollToItem —— 它在下一次测量时生效，不要求列表此刻已经布局。
    // 但只在书源/分类「真的变了」时才回顶：点开书再返回，本页会重新进入组合，
    // LaunchedEffect 跟着重跑一遍，会把 saveable 刚恢复的滚动位置又拽回顶部。
    // 上次生效的键也得 rememberSaveable，普通 remember 在离开组合时就丢了
    var scrolledFor by rememberSaveable { mutableStateOf<List<Int>?>(null) }
    LaunchedEffect(state.sourceIndex, state.categoryIndex) {
        val key = listOf(state.sourceIndex, state.categoryIndex)
        if (scrolledFor != null && scrolledFor != key) listState.requestScrollToItem(0)
        scrolledFor = key
    }

    // 与搜索/书架同一套浅色灰底白卡，避免默认 surface 白底从书行圆角缝里透出来
    val pageColor = settingsPageColor()
    Scaffold(
        containerColor = pageColor,
        topBar = {
            // 留经典窄栏：标题位是个可点的书源切换按钮，两段式会把这个控件摊到大标题的位置上
            TopAppBar(
                title = {
                    // 选书源统一走底部面板：从前是裸 DropdownMenu，弹个小浮层、选中态全靠猜
                    AppTextButton(onClick = { sourceMenuOpen = true }) {
                        Text(
                            state.currentSource?.name ?: "发现",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Icon(Icons.Default.ArrowDropDown, contentDescription = "切换书源")
                    }
                },
                navigationIcon = {
                    BackButton(onClick = onBack)
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = pageColor,
                    scrolledContainerColor = pageColor,
                ),
            )
        },
        snackbarHost = { AppSnackbarHost(snackbar) },
    ) { padding ->
        // 只让开顶栏与两侧，列表滚到手势条下面（见 exceptBottom）
        Column(Modifier.fillMaxSize().padding(padding.exceptBottom())) {
            // 库还没读回来：先空着。不能落进下面的空态 —— 那会每次进页都闪一下「没有可用的发现来源」
            if (!state.sourcesLoaded) return@Column
            if (state.sources.isEmpty()) {
                EmptyState(
                    icon = Icons.Default.Explore,
                    title = "没有可用的发现来源",
                    hint = "导入带发现页规则后，可在此浏览分类列表",
                    actionLabel = "去导入规则",
                    onAction = onOpenSourceManage,
                )
                return@Column
            }

            // 分类 chips —— 走共享 ChipRow（自带横向滚动），不再手搓 LazyRow + FilterChip
            if (state.categories.size > 1) {
                ChipRow(
                    options = state.categories,
                    selectedIndex = state.categoryIndex,
                    onSelect = { viewModel.selectCategory(it) },
                    contentPadding = PaddingValues(
                        horizontal = Dimens.listHorizontal,
                        vertical = Dimens.gapXS,
                    ),
                )
            }

            when {
                state.loading -> LoadingState()
                state.error != null -> ErrorState(
                    message = state.error.orEmpty(),
                    onRetry = { viewModel.retry() },
                )
                state.books.isEmpty() -> EmptyState(
                    icon = Icons.Default.Explore,
                    title = "这个分类没有内容",
                    actionLabel = "重试",
                    onAction = { viewModel.retry() },
                )
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = ContentListDefaults.listContentPadding(bottom = padding.calculateBottomPadding()),
                    verticalArrangement = Arrangement.spacedBy(ContentListDefaults.ListSpacing),
                ) {
                    items(state.books, key = { it.exploreKey }) { book ->
                        val inShelf = bookKey(book.title, book.author) in state.shelfKeys
                        BookListRow(
                            title = book.title,
                            subtitle = listOfNotNull(book.author, book.latestChapter)
                                .joinToString(" · "),
                            caption = book.intro,
                            coverModel = book.coverUrl,
                            trailingLabel = if (inShelf) "已加入" else "加入",
                            trailingLoading = book.bookUrl in state.addingUrls,
                            trailingEnabled = !inShelf,
                            onTrailing = { viewModel.addToShelf(book) },
                            onClick = { onOpenPreview(listOf(book)) },
                        )
                    }
                    if (state.loadingMore) {
                        item {
                            Box(
                                Modifier.fillMaxWidth().padding(Dimens.gapL),
                                contentAlignment = Alignment.Center,
                            ) { AppLoadingIndicator(size = Dimens.iconMd) }
                        }
                    }
                    // 加载更多失败后不再「滑到底自动重来」（会无限重试），出口放在这里
                    if (state.loadMoreFailed) {
                        item {
                            Box(
                                Modifier.fillMaxWidth().padding(Dimens.gapS),
                                contentAlignment = Alignment.Center,
                            ) {
                                AppTextButton(onClick = { viewModel.retryMore() }) {
                                    Text("加载失败，点此重试")
                                }
                            }
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
