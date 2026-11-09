package com.radium.inkwell.ui.search

import androidx.compose.ui.unit.Dp
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import com.radium.inkwell.ui.components.AppIconButton
import com.radium.inkwell.ui.components.AppTextButton
import com.radium.inkwell.ui.components.AppLoadingIndicator
import com.radium.inkwell.ui.components.BackButton
import com.radium.inkwell.ui.components.DeterminateProgressBar
import com.radium.inkwell.ui.components.AppSnackbarHost
import com.radium.inkwell.ui.components.AppToolbar
import com.radium.inkwell.ui.components.AppScaffold
import com.radium.inkwell.ui.components.AppDropdownMenu
import com.radium.inkwell.ui.components.AppMenuItem
import com.radium.inkwell.ui.components.groupedCardItem
import com.radium.inkwell.ui.components.horizontalOnly
import com.radium.inkwell.ui.components.pageScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radium.inkwell.core.source.SearchResult
import com.radium.inkwell.ui.components.AppAlertDialog
import com.radium.inkwell.ui.components.BookListRow
import com.radium.inkwell.ui.components.ContentListItem
import com.radium.inkwell.ui.components.SectionHeader
import com.radium.inkwell.ui.components.CollectMessages
import com.radium.inkwell.ui.components.ContentListDefaults
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.ui.components.EmptyState
import com.radium.inkwell.ui.components.ErrorState
import com.radium.inkwell.ui.components.SearchField
import com.radium.inkwell.ui.components.animationsEnabled
import com.radium.inkwell.ui.components.expandEnter
import com.radium.inkwell.ui.components.expandExit
import com.radium.inkwell.ui.components.settingsPageColor
import com.radium.inkwell.ui.theme.AppTheme
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Recent
import top.yukonga.miuix.kmp.icon.extended.Sort
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onOpenPreview: (List<SearchResult>) -> Unit,
    viewModel: SearchViewModel,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    CollectMessages(viewModel.messages, snackbar)
    var confirmClearHistory by rememberSaveable { mutableStateOf(false) }

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

    // 只认 nearEnd 的 false→true 跳变不够：一页只多出两三行、或第一页一屏就放得下时 nearEnd 一直是 true，
    // 下一页永远不会来，得往回滑再滑下来才能触发。每次加载落地（条数、hasMore、加载状态变化）都再判一次。
    LaunchedEffect(nearEnd, state.hasMore, state.searching, state.loadingMore, state.results.size) {
        if (nearEnd) viewModel.loadMore()
    }

    // 与书架/设置同一套画布：灰底白卡（Miuix 的页面底 surface 上放卡片色的结果行）
    val pageColor = settingsPageColor()
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    // 进搜索页就是来打字的：还没搜过、框里也是空的就直接把光标放进去，不必再点一下输入框。
    // 从预览页返回（已经搜过）不抢焦点，别平白把键盘弹出来挡住结果
    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (state.searchId == 0 && viewModel.query.isEmpty()) searchFocus.requestFocus()
    }
    // 在 items 外面读一次：animationsEnabled() 内部会挂一个 ContentObserver，写进 items 里就是每行挂一个
    val motionOn = animationsEnabled()
    val motion = AppTheme.motionScheme

    // 历史在两种时候出来：进页还没搜过；搜过之后把输入框清空了 —— 清空就是「换个词」的意思，
    // 这时候还摆着上一个词的结果（或「没找到」）没有用，从前得退出页面再进来才看得到历史。
    // 正在搜 / 暂停着的时候不让位：结果还在长，或者等着用户点「继续搜索」。
    val idle = !state.searching && !state.searchPaused
    // 「清空了输入框」要单独成立，不能只靠有没有历史：清空后把历史删光，
    // 否则会落回上一个词的旧结果，顶着一个空搜索框
    val cleared = idle && state.searchId > 0 && viewModel.query.isBlank()
    val showHistory = idle && state.history.isNotEmpty() && (state.searchId == 0 || cleared)
    val showEmpty = !showHistory && ((state.results.isEmpty() && idle) || cleared)
    val showResults = !showHistory && !showEmpty

    AppScaffold(
        containerColor = pageColor,
        topBar = {
            // 留窄栏：标题位放的是输入框。换成 AppTopBar 的大标题，输入框没处放 ——
            // 大标题那一行只收文字，Miuix 的顶栏标题也只收字符串
            AppToolbar(
                navigationIcon = {
                    BackButton(onClick = onBack)
                },
                containerColor = pageColor,
                actions = {
                    AppIconButton(onClick = { viewModel.search() }) {
                        Icon(MiuixIcons.Search, contentDescription = "搜索")
                    }
                },
                // 进度条、暂停条、数量/排序条都钉在顶栏里：结果列表从它们下面滚过去、一起被毛玻璃盖住，
                // 而不是在列表顶上永远挡着一截
                bottomContent = {
                    AnimatedVisibility(
                        visible = state.searching || state.searchPaused,
                        enter = expandEnter(),
                        exit = expandExit(),
                    ) {
                        Column {
                            DeterminateProgressBar(
                                progress = if (state.sourceCount == 0) 0f else state.doneCount.toFloat() / state.sourceCount,
                            )
                            if (state.searchPaused) {
                                SearchPauseBar(
                                    // 提前结束要说清楚是「找到了」而不是「卡住了」，还剩多少源没搜
                                    label = if (state.earlyStopped) "已找到这本书，还有 ${state.pausedRemaining} 个来源没搜"
                                    else "搜索已暂停",
                                    onResume = viewModel::resumeSearch,
                                )
                            }
                        }
                    }
                    if (showResults) {
                        // 列表上方一条：左侧数量、右侧当前排序。不塞进标题行 ——
                        // 那一行已经是搜索框 + 搜索按钮，再加排序图标会挤成一团。
                        SearchSortBar(
                            count = state.results.size,
                            sort = state.sort,
                            searching = state.searching,
                            onSelectSort = viewModel::setSort,
                        )
                    }
                },
            ) {
                SearchField(
                    // 读 VM 里同步更新的那份，不读 StateFlow（理由见 SearchViewModel.query）
                    value = viewModel.query,
                    onValueChange = viewModel::setQuery,
                    placeholder = "书名 / 作者",
                    onSearch = {
                        // 回车搜了就收键盘：IME 的 Search 动作默认不收，键盘一直挡着大半屏结果
                        keyboard?.hide()
                        focusManager.clearFocus()
                        viewModel.search()
                    },
                    modifier = Modifier
                        .padding(end = Dimens.gapS)
                        .focusRequester(searchFocus),
                )
            }
        },
        snackbarHost = { AppSnackbarHost(snackbar) },
    ) { padding ->
        // 里面的列表各自 imePadding：padding 里已含导航栏高度，标成已消费，免得键盘上方多一道空隙
        Box(Modifier.fillMaxSize().consumeWindowInsets(padding)) {
            // 列表只让开两侧：顶栏（连同钉在里面的进度/排序条）的高度垫在列表开头，内容滚到玻璃顶栏下面；
            // 导航栏高度垫在末尾，滚到手势条下面（见 AppScaffold）
            val listModifier = Modifier.padding(padding.horizontalOnly()).imePadding().pageScroll()
            if (showHistory) {
                SearchHistoryList(
                    modifier = listModifier,
                    topInset = padding.calculateTopPadding(),
                    bottomInset = padding.calculateBottomPadding(),
                    history = state.history,
                    onSearch = viewModel::searchFromHistory,
                    onRemove = viewModel::removeHistory,
                    onClear = { confirmClearHistory = true },
                )
            } else if (showEmpty) {
                if (state.searchId > 0 && !cleared && state.allFailed) {
                    // 每个源都超时或报错：多半是网络问题，不是书源里没这本。说「没找到」会让人换词白搜
                    ErrorState(
                        title = "来源都没有响应",
                        message = "搜索的来源全部超时或出错，请检查网络后重试",
                        onRetry = viewModel::retrySearch,
                        modifier = Modifier.padding(padding),
                    )
                } else if (state.searchId > 0 && !cleared) {
                    // 搜完了一本没有。从前这里落回进页时那句「按已启用的规则搜索」，
                    // 看起来像什么都没发生 —— 用户分不清是没搜、还是搜了没有。
                    EmptyState(
                        icon = MiuixIcons.Search,
                        title = "没有找到相关的书",
                        hint = "换个关键词试试，或到来源管理里检查来源是否已启用、是否还能用",
                        modifier = Modifier.padding(padding),
                    )
                } else if (state.historyLoaded) {
                    // 历史还没读到时什么都不画：读到后要么是历史列表、要么才是这块空态，别先闪一下它
                    EmptyState(
                        icon = MiuixIcons.Search,
                        title = "按已启用的来源搜索",
                        hint = "输入书名或作者；还没导入来源时搜不到结果",
                        modifier = Modifier.padding(padding),
                    )
                }
            } else {
                // edge-to-edge 下让结果列表底部让开键盘，不然最后几条被盖住也滚不出来。
                // 结果拼成一张卡（HyperOS 的灰底白卡），行间不留缝 —— 留了卡就断开
                val resultCount = state.results.size
                LazyColumn(
                    state = listState,
                    modifier = listModifier,
                    contentPadding = PaddingValues(
                        top = padding.calculateTopPadding() + Dimens.gapS,
                        bottom = padding.calculateBottomPadding(),
                    ),
                    overscrollEffect = null,
                ) {
                    // key 必须与合并键是同一个（SearchHit.key）：拿代表条目的 sourceId|bookUrl 当 key 的话，
                    // 一个书源把同一地址列成两本书就撞 key 崩溃，见 searchKey
                    itemsIndexed(state.results, key = { _, hit -> hit.key }) { index, hit ->
                        // 边搜边重排（源数变了、相关度变了）时让行滑到新位置，而不是一帧之内互换 ——
                        // 手指底下的那行瞬间换成别的书，点开的就不是想点的那本
                        val result = hit.result
                        // 合并进这一行的任何一个源已经在架，这一行就是「已加入」（见 ShelfIndex）
                        val inShelf = hit.results.any { it in state.shelf }
                        BookListRow(
                            title = result.title,
                            subtitle = listOfNotNull(
                                result.author,
                                result.wordCount,
                                result.latestChapter,
                            ).joinToString(" · "),
                            // 同名同作者的书跨书源合并成一行；书源越多越可能就是要找的那本
                            caption = buildString {
                                if (hit.origins.size > 1) append("${hit.origins.size} 个来源 · ")
                                else append("来源: ")
                                append(hit.preferred.sourceId)
                                result.kind?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
                            },
                            coverModel = hit.preferred.coverUrl ?: result.coverUrl,
                            // 已在书架就显示"已加入"且不可点，不再让人重复加
                            trailingLabel = if (inShelf) "已加入" else "加入",
                            trailingLoading = result.bookUrl in state.addingUrls,
                            trailingEnabled = !inShelf,
                            onTrailing = { viewModel.addToShelf(hit) },
                            onClick = {
                                // 进详情就停搜：后台还在打上百个源的话，详情页会一直转圈。
                                // 返回后不自动接着搜，由用户点「继续搜索」。
                                viewModel.pauseSearch()
                                onOpenPreview(viewModel.rankedResults(hit.results))
                            },
                            // 关了系统动画就传 null（这个 API 的「不动画」写法），同书架
                            modifier = Modifier
                                .animateItem(
                                    fadeInSpec = if (motionOn) motion.defaultEffectsSpec() else null,
                                    placementSpec = if (motionOn) motion.defaultSpatialSpec() else null,
                                    fadeOutSpec = if (motionOn) motion.fastEffectsSpec() else null,
                                )
                                .groupedCardItem(index, resultCount),
                            colors = ContentListDefaults.plainColors(),
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

    if (confirmClearHistory) {
        AppAlertDialog(
            onDismissRequest = { confirmClearHistory = false },
            title = "清空搜索历史？",
            confirmText = "清空",
            onConfirm = {
                confirmClearHistory = false
                viewModel.clearHistory()
            },
        )
    }
}

/**
 * 进页还没搜时的搜索历史：点一条直接搜，行尾 ✕ 删单条，标题行右侧清空全部。
 *
 * 用列表行而不是一堆 chip：书名常常很长（「我在末世有套房」后面再带个作者），chip 换行后
 * 参差不齐，单条删除的 ✕ 也没处放；一行一条，删哪条一目了然。
 * 各条拼成一张卡（与结果列表同一种灰底白卡）。
 */
@Composable
private fun SearchHistoryList(
    modifier: Modifier,
    /** 垫在列表开头的顶栏高度：列表从玻璃顶栏下面滚过去，见 AppScaffold */
    topInset: Dp,
    /** 垫在列表末尾的导航栏高度，见 AppScaffold */
    bottomInset: Dp,
    history: List<String>,
    onSearch: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClear: () -> Unit,
) {
    val motion = AppTheme.motionScheme
    // 键盘弹着时历史多半在它底下，同结果列表一样让开（imePadding 在调用方的 modifier 里）
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(top = topInset, bottom = Dimens.gapL + bottomInset),
        overscrollEffect = null,
    ) {
        item(key = "header") {
            SectionHeader("搜索历史", trailing = { AppTextButton("清空", onClick = onClear) })
        }
        // key 加前缀：裸用关键词的话，搜过「header」这个词就跟标题行的 key 撞上，LazyColumn 当场崩溃，
        // 而且历史还在、每次进页都崩
        itemsIndexed(history, key = { _, it -> "h:$it" }) { index, keyword ->
            ContentListItem(
                onClick = { onSearch(keyword) },
                // 删一条时它淡出、下面的挪上来补位；否则那一行是硬消失，看不出删的是哪条
                modifier = Modifier
                    .animateItem(
                        fadeInSpec = motion.defaultEffectsSpec(),
                        placementSpec = motion.defaultSpatialSpec(),
                        fadeOutSpec = motion.fastEffectsSpec(),
                    )
                    .groupedCardItem(index, history.size),
                // 行尾 48dp 的 × 比一行字高：它撑开行高，上下只留 8dp（照默认的 16dp 会胖成 80dp）
                contentPadding = ContentListDefaults.MediaPadding,
                leadingContent = {
                    Icon(MiuixIcons.Recent, contentDescription = null, Modifier.size(Dimens.iconSm))
                },
                trailingContent = {
                    AppIconButton(onClick = { onRemove(keyword) }) {
                        Icon(
                            MiuixIcons.Close,
                            contentDescription = "删除「$keyword」",
                            Modifier.size(Dimens.iconSm),
                        )
                    }
                },
            ) {
                Text(keyword, style = ContentListDefaults.titleStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * 搜索结果工具条：左数量、右排序入口。整行高度贴 [Dimens.touchTarget]，
 * 点排序一侧就在它下面弹出排序列表（当前方式打勾，每项下面一行说明怎么排）。
 *
 * 可点的是排序那一小块，它自己就得够 48dp 高：从前靠上下各 [Dimens.gapS] 撑，
 * 18dp 图标 / 一行 14sp 的字撑出来只有 36dp 左右，手指按偏一点就落空。
 * 外层不再另加上下留白 —— 加了就是 56dp，比两侧内容都高出一截。
 */
@Composable
private fun SearchSortBar(
    count: Int,
    sort: SearchSort,
    searching: Boolean,
    onSelectSort: (SearchSort) -> Unit,
) {
    var sortMenuOpen by rememberSaveable { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.listHorizontal),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (count == 0 && searching) "搜索中…" else "共 $count 本",
            style = MiuixTheme.textStyles.body2,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.weight(1f))
        Box {
            Row(
                Modifier
                    .clickable(role = Role.Button, onClick = { sortMenuOpen = true })
                    .heightIn(min = Dimens.touchTarget)
                    .padding(horizontal = Dimens.gapXS),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    MiuixIcons.Sort,
                    contentDescription = null,
                    Modifier.size(Dimens.iconSm),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Text(
                    sort.label,
                    Modifier.padding(start = Dimens.gapXS),
                    style = MiuixTheme.textStyles.body2,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            AppDropdownMenu(
                expanded = sortMenuOpen,
                onDismissRequest = { sortMenuOpen = false },
                items = SearchSort.entries.map {
                    AppMenuItem(
                        text = it.label,
                        summary = when (it) {
                            SearchSort.RELEVANCE -> "与关键词最相关的排前面"
                            SearchSort.WORD_COUNT -> "字数多的排前面；无字数的沉底"
                            SearchSort.TITLE_PINYIN -> "按书名拼音 A→Z"
                            SearchSort.AUTHOR_PINYIN -> "按作者拼音 A→Z"
                            SearchSort.UPDATE_TIME -> "能解析出日期的按新到旧；多数源写在分类里"
                        },
                        selected = it == sort,
                        onClick = { onSelectSort(it) },
                    )
                },
            )
        }
    }
}

/**
 * 进详情后搜索被掐掉：进度条冻在当时的位置，给一个手动「继续」而不是返回就自动接着打。
 * 「继续搜索」走 [AppTextButton]：文字按钮没有底色，进度条底下不会冒出一块实心按钮；
 * 行高由它自带的 48dp 触控下限撑住，与 [SearchSortBar] 一致。
 */
@Composable
private fun SearchPauseBar(label: String, onResume: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.listHorizontal),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MiuixTheme.textStyles.body2,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        AppTextButton("继续搜索", onClick = onResume)
    }
}
