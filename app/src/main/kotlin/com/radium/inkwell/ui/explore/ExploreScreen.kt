package com.radium.inkwell.ui.explore

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radium.inkwell.core.source.SearchResult
import com.radium.inkwell.ui.components.AppLoadingIndicator
import com.radium.inkwell.ui.components.AppScaffold
import com.radium.inkwell.ui.components.AppSnackbarHost
import com.radium.inkwell.ui.components.AppTextButton
import com.radium.inkwell.ui.components.AppTopBar
import com.radium.inkwell.ui.components.BookListRow
import com.radium.inkwell.ui.components.ChipRow
import com.radium.inkwell.ui.components.CollectMessages
import com.radium.inkwell.ui.components.ContentListDefaults
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.ui.components.EmptyState
import com.radium.inkwell.ui.components.ErrorState
import com.radium.inkwell.ui.components.FloatingGlassBar
import com.radium.inkwell.ui.components.LoadingState
import com.radium.inkwell.ui.components.groupedCardItem
import com.radium.inkwell.ui.components.horizontalOnly
import com.radium.inkwell.ui.components.pageScroll
import com.radium.inkwell.ui.components.rememberAppTopBarScroll
import com.radium.inkwell.ui.components.settingsPageColor
import com.radium.inkwell.ui.components.topBarScroll
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.VerticalDivider
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowUpDown
import top.yukonga.miuix.kmp.icon.extended.Replace
import top.yukonga.miuix.kmp.icon.extended.SearchDevice
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
    val topBarScroll = rememberAppTopBarScroll()
    var sourceSheetOpen by remember { mutableStateOf(false) }
    // 书源胶囊占掉的高度：Miuix Scaffold 不把它算进给内容的 padding，列表末尾自己垫（同书架）
    var sourceBarHeight by remember { mutableStateOf(0.dp) }
    val capsuleVisible = state.sourcesLoaded && state.sources.isNotEmpty()

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
        // 顶栏大标题折叠挂在 Scaffold 的 modifier 上（见 topBarScroll）
        modifier = Modifier.topBarScroll(topBarScroll),
        containerColor = pageColor,
        topBar = {
            // 切书源的入口在底部胶囊（SourceCapsule），顶栏只留页面标题 —— 标题位不再放控件，
            // 换回标准的大标题栏（AppTopBar 的例外清单里「发现页的书源切换按钮」一条随这次改动取消）
            AppTopBar(
                title = "发现",
                scrollBehavior = topBarScroll,
                onBack = onBack,
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
            )
        },
        snackbarHost = { AppSnackbarHost(snackbar) },
        floatingToolbar = {
            // 底部书源胶囊：与书架隐藏区胶囊同一种玻璃。发现页没有多选态，这个槽里只有它
            SourceCapsule(
                visible = capsuleVisible,
                sourceName = state.currentSource?.name.orEmpty(),
                onClick = { sourceSheetOpen = true },
                onHeightChange = { sourceBarHeight = it },
            )
        },
    ) { padding ->
        // 库还没读回来：先空着。不能落进下面的空态 —— 那会每次进页都闪一下「没有可用的发现来源」
        if (!state.sourcesLoaded) return@AppScaffold
        if (state.sources.isEmpty()) {
            EmptyState(
                icon = MiuixIcons.SearchDevice,
                title = "没有可用的发现来源",
                hint = "导入支持发现页的来源后，可在此浏览分类列表",
                actionLabel = "去导入来源",
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
                icon = MiuixIcons.SearchDevice,
                title = "这个分类没有内容",
                actionLabel = "重试",
                onAction = { viewModel.retry() },
                modifier = Modifier.padding(padding),
            )
            // 只让开两侧：顶栏（连同分类 chips）的高度垫在列表开头、导航栏垫在末尾，
            // 列表滚到玻璃顶栏与手势条下面（见 AppScaffold）。书行拼成一张卡，行间不留缝
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().padding(padding.horizontalOnly()).pageScroll(),
                contentPadding = PaddingValues(
                    top = padding.calculateTopPadding() + Dimens.gapS,
                    // 末尾除了导航栏，还要让开底部的书源胶囊 —— 最后一行才不被它压住（同书架的 hiddenBarHeight）
                    bottom = padding.calculateBottomPadding() + if (capsuleVisible) sourceBarHeight + Dimens.gapS else 0.dp,
                ),
                overscrollEffect = null,
            ) {
                val bookCount = state.books.size
                itemsIndexed(state.books, key = { _, it -> it.exploreKey }) { index, book ->
                    val inShelf = book in state.shelf
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

    // 选书源：底部面板（搜索 / 最近 / 全部），点行即换（见 ExploreSourceSheet）
    if (sourceSheetOpen) {
        ExploreSourceSheet(
            sources = state.sources,
            recentIds = state.recentSourceIds,
            currentId = state.currentSource?.id,
            onPick = { viewModel.selectSource(it) },
            onOpenManage = onOpenSourceManage,
            onDismiss = { sourceSheetOpen = false },
        )
    }
}

/**
 * 底部「书源胶囊」，悬浮在屏幕底部（[FloatingGlassBar]，和书架隐藏区胶囊、多选操作条同一种玻璃）：
 * 换源图标 + 当前书源名 ｜ 切换书源 ⇅。整条可点，点开是 [ExploreSourceSheet]。
 *
 * 书源切换从前挂在顶栏标题上（下拉菜单）：书源一多列表就没法搜、只能一路滑，而且入口在屏幕左上角，
 * 换一次源要抬手到够不着的地方。沉到底部后开面板与点选项都在拇指区 —— 与书架隐藏区把开关和 ✕
 * 收进底部胶囊是同一个理由（见 HiddenCapsule 的注释）。
 *
 * 没有可发现的书源时不显示（空态里「去导入规则」才是出路）；胶囊占掉的高度经 [onHeightChange]
 * 报出去，调用方垫到列表末尾。
 */
@Composable
private fun SourceCapsule(
    visible: Boolean,
    sourceName: String,
    onClick: () -> Unit,
    onHeightChange: (Dp) -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    FloatingGlassBar(visible = visible, onHeightChange = onHeightChange) { floor ->
        Row(
            Modifier
                // 整条一个动作（开选择器）：一个可点区，读屏一个焦点
                .clickable(role = Role.Button, onClick = onClick)
                .heightIn(min = Dimens.touchTarget)
                .padding(start = Dimens.gapL, end = Dimens.gapM),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                MiuixIcons.Replace,
                contentDescription = null,
                tint = scheme.onSurface,
                modifier = Modifier.size(Dimens.iconSm),
            )
            Text(
                sourceName.ifBlank { "选择来源" },
                style = MiuixTheme.textStyles.body2,
                fontWeight = FontWeight.Medium,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .padding(start = Dimens.gapXS),
            )
            VerticalDivider(
                Modifier
                    .padding(horizontal = Dimens.gapM)
                    .height(Dimens.iconSm),
            )
            Text(
                "切换来源",
                style = MiuixTheme.textStyles.body2,
                color = scheme.onSurface,
                maxLines = 1,
                modifier = Modifier.padding(end = Dimens.gapXS),
            )
            Icon(
                MiuixIcons.Basic.ArrowUpDown,
                contentDescription = null,
                tint = scheme.onSurface,
                modifier = Modifier.size(Dimens.iconSm),
            )
        }
    }
}
