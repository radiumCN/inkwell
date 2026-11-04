package com.radium.inkwell.ui.explore

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.foundation.lazy.itemsIndexed
import com.radium.inkwell.ui.components.AppDropdownMenu
import com.radium.inkwell.ui.components.AppMenuItem
import com.radium.inkwell.ui.components.AppScaffold
import com.radium.inkwell.ui.components.groupedCardItem
import com.radium.inkwell.ui.components.horizontalOnly
import com.radium.inkwell.ui.components.pageScroll
import com.radium.inkwell.ui.components.AppLoadingIndicator
import com.radium.inkwell.ui.components.AppTextButton
import com.radium.inkwell.ui.components.BackButton
import com.radium.inkwell.ui.components.AppSnackbarHost
import com.radium.inkwell.ui.components.AppToolbar
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
import androidx.compose.ui.text.font.FontWeight
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
import com.radium.inkwell.ui.components.settingsPageColor
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowUpDown
import top.yukonga.miuix.kmp.theme.MiuixTheme

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

    // 与搜索/书架同一套灰底白卡（页面底 surface 上放卡片色的书行）
    val pageColor = settingsPageColor()
    val showCategories = state.sourcesLoaded && state.sources.isNotEmpty() && state.categories.size > 1
    AppScaffold(
        containerColor = pageColor,
        topBar = {
            // 留窄栏：标题位是个可点的书源切换按钮，大标题那一行只收文字、放不下控件
            AppToolbar(
                navigationIcon = {
                    BackButton(onClick = onBack)
                },
                containerColor = pageColor,
                // 分类 chips 钉在顶栏里：书列表从它下面滚过去、一起被毛玻璃盖住
                bottomContent = {
                    if (showCategories) {
                        // 走共享 ChipRow（自带横向滚动），不再手搓 LazyRow + FilterChip
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
                },
            ) {
                // 选书源从标题处弹出列表（当前书源打勾）：从前是裸 DropdownMenu，选中态全靠猜。
                // 字号字重照窄栏标题（title3 / Medium）、字色照标题的 onSurface —— 它就是这一页的标题，
                // 只是能点；行尾的 ⇅ 交代「点了能换」
                Box {
                    AppTextButton(
                        onClick = { sourceMenuOpen = true },
                        contentColor = MiuixTheme.colorScheme.onSurface,
                        contentPadding = PaddingValues(horizontal = Dimens.gapS),
                    ) {
                        Text(
                            state.currentSource?.name ?: "发现",
                            modifier = Modifier.weight(1f, fill = false),
                            fontSize = MiuixTheme.textStyles.title3.fontSize,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Icon(
                            MiuixIcons.Basic.ArrowUpDown,
                            contentDescription = "切换书源",
                            modifier = Modifier.padding(start = Dimens.gapXS).size(Dimens.iconSm),
                        )
                    }
                    AppDropdownMenu(
                        expanded = sourceMenuOpen,
                        onDismissRequest = { sourceMenuOpen = false },
                        items = state.sources.mapIndexed { index, source ->
                            AppMenuItem(
                                text = source.name,
                                selected = source.id == state.currentSource?.id,
                                onClick = { viewModel.selectSource(index) },
                            )
                        },
                    )
                }
            }
        },
        snackbarHost = { AppSnackbarHost(snackbar) },
    ) { padding ->
        // 库还没读回来：先空着。不能落进下面的空态 —— 那会每次进页都闪一下「没有可用的发现来源」
        if (!state.sourcesLoaded) return@AppScaffold
        if (state.sources.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.Explore,
                title = "没有可用的发现来源",
                hint = "导入带发现页规则后，可在此浏览分类列表",
                actionLabel = "去导入规则",
                onAction = onOpenSourceManage,
                modifier = Modifier.padding(padding),
            )
            return@AppScaffold
        }

        when {
            state.loading -> LoadingState(Modifier.padding(padding))
            state.error != null -> ErrorState(
                message = state.error.orEmpty(),
                onRetry = { viewModel.retry() },
                modifier = Modifier.padding(padding),
            )
            state.books.isEmpty() -> EmptyState(
                icon = Icons.Outlined.Explore,
                title = "这个分类没有内容",
                actionLabel = "重试",
                onAction = { viewModel.retry() },
                modifier = Modifier.padding(padding),
            )
            // 只让开两侧：顶栏（连同分类 chips）的高度垫在列表开头、导航栏垫在末尾，
            // 列表滚到玻璃顶栏与手势条下面（见 AppScaffold）。书行拼成一张卡，行间不留缝
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(padding.horizontalOnly()).pageScroll(),
                contentPadding = PaddingValues(
                    top = padding.calculateTopPadding() + Dimens.gapS,
                    bottom = padding.calculateBottomPadding(),
                ),
                overscrollEffect = null,
            ) {
                val bookCount = state.books.size
                itemsIndexed(state.books, key = { _, it -> it.exploreKey }) { index, book ->
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
                        modifier = Modifier.groupedCardItem(index, bookCount),
                        colors = ContentListDefaults.plainColors(),
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
                            AppTextButton("加载失败，点此重试", onClick = { viewModel.retryMore() })
                        }
                    }
                }
            }
        }
    }
}
