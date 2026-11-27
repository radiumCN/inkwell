package com.radium.inkwell.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radium.inkwell.data.prefs.AppPrefs
import com.radium.inkwell.data.prefs.BookTocRule
import com.radium.inkwell.ui.components.AppBottomSheet
import com.radium.inkwell.ui.components.AppIconButton
import com.radium.inkwell.ui.components.CardViewport
import com.radium.inkwell.ui.components.ChapterListItem
import com.radium.inkwell.ui.components.ContentListDefaults
import com.radium.inkwell.ui.components.ContentListItem
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.ui.components.FastScroller
import com.radium.inkwell.ui.components.PrimaryButton
import com.radium.inkwell.ui.components.SearchField
import com.radium.inkwell.ui.components.SectionHeader
import com.radium.inkwell.ui.components.SettingFootnote
import com.radium.inkwell.ui.components.SettingGroup
import com.radium.inkwell.ui.components.SheetPageHeader
import com.radium.inkwell.ui.components.SheetPages
import com.radium.inkwell.ui.components.TocOrderButton
import com.radium.inkwell.ui.components.animationsEnabled
import com.radium.inkwell.ui.components.bottomBarEnter
import com.radium.inkwell.ui.components.bottomBarExit
import com.radium.inkwell.ui.components.cardMargin
import com.radium.inkwell.ui.components.groupedCardItem
import com.radium.inkwell.ui.components.highlightMatches
import com.radium.inkwell.ui.components.pageScroll
import com.radium.inkwell.ui.components.settingsCardColor
import com.radium.inkwell.ui.components.sheetBottomInset
import java.io.IOException
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.icon.extended.ExpandLess
import top.yukonga.miuix.kmp.icon.extended.ExpandMore
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ReaderTocSheet(
    rule: BookTocRule,
    toc: List<TocItem>,
    current: Int,
    position: TocReadingPosition?,
    showCacheState: Boolean,
    onDismiss: () -> Unit,
    onSelect: (TocItem) -> Unit,
    onApplyRule: suspend (BookTocRule) -> Unit,
) {
    val appPrefs = koinInject<AppPrefs>()
    val reversed = appPrefs.tocReversedState.collectAsStateWithLifecycle().value ?: false
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(rule) }
    var saving by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    val back: () -> Unit = { if (!saving) editing = false }

    val hasHierarchy = remember(toc) { toc.any { it.depth > 0 } }
    val parseVolumes = !hasHierarchy && (editing || rule != BookTocRule.ORIGINAL)
    val titles = remember(toc, parseVolumes) { if (parseVolumes) toc.map(TocItem::title) else emptyList() }
    val volumes = remember(titles) { tocVolumes(titles) }
    val hasVolumes = remember(volumes) { volumes.any { it != null } }
    val currentKey = remember(toc, current, position) { currentTocKey(toc, current, position) }

    // SheetPages 会卸下被盖住的一级页，搜索和滚动状态留在面板上。
    var query by remember { mutableStateOf("") }
    val q = query.trim()
    val shown = remember(toc, volumes, rule, reversed, q) { displayToc(toc, volumes, rule, reversed, q) }
    val currentPos = remember(shown, currentKey) { shown.indexOfFirst { it.item.key == currentKey } }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = (currentPos - TOC_ROWS_ABOVE_CURRENT).coerceAtLeast(0),
    )
    var lastOrder by remember { mutableStateOf(reversed to q) }
    LaunchedEffect(reversed, q) {
        val order = reversed to q
        if (order == lastOrder) return@LaunchedEffect
        val reordered = order.first != lastOrder.first
        lastOrder = order
        listState.requestScrollToItem(
            if (!reordered && q.isEmpty()) (currentPos - TOC_ROWS_ABOVE_CURRENT).coerceAtLeast(0) else 0,
        )
    }

    AppBottomSheet(onDismissRequest = onDismiss, padNavigationBar = false, allowDismiss = !saving) {
        val focus = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
        val panelHeight = (windowHeight * TOC_MAX_HEIGHT_FRACTION).coerceAtMost(
            Dimens.sheetListMaxHeight + Dimens.touchTarget + Dimens.searchFieldHeight + Dimens.gapXL + sheetBottomInset(),
        )
        SheetPages(
            page = editing,
            root = false,
            onBack = back,
            background = readerOverlayColor(),
            // 两页同高，进出时窗口边缘固定，只滑里面的页面。
            modifier = Modifier.fillMaxWidth().height(panelHeight),
            linearSlide = true,
        ) { rulesPage ->
            if (rulesPage) {
                val preview = remember(toc, volumes, draft, currentKey) { previewToc(toc, volumes, draft, currentKey) }
                TocRuleEditor(
                    draft = draft,
                    canOrganize = !hasHierarchy,
                    preview = preview,
                    previewNote = when {
                        hasHierarchy -> "此目录已有层级，沿用原目录结构"
                        draft != BookTocRule.ORIGINAL && !hasVolumes -> "当前目录没有可整理的卷名"
                        else -> null
                    },
                    saving = saving,
                    saveFailed = saveFailed,
                    onBack = back,
                    onChoose = {
                        draft = it
                        saveFailed = false
                    },
                    onApply = {
                        if (!saving) {
                            if (draft == rule) {
                                back()
                            } else {
                                val toSave = draft
                                saving = true
                                saveFailed = false
                                scope.launch {
                                    try {
                                        onApplyRule(toSave)
                                        // 显示规则可能增减卷标题的高度。回到原来可见的那一章，丢弃旧行内偏移。
                                        listState.requestScrollToItem(listState.firstVisibleItemIndex)
                                        editing = false
                                    } catch (_: IOException) {
                                        saveFailed = true
                                    } finally {
                                        saving = false
                                    }
                                }
                            }
                        }
                    },
                )
            } else {
                Column {
                    Box(Modifier.fillMaxWidth().height(Dimens.touchTarget)) {
                        Text(
                            "目录",
                            modifier = Modifier.align(Alignment.Center).semantics { heading() },
                            style = MiuixTheme.textStyles.title4,
                            fontWeight = FontWeight.Medium,
                        )
                        val colors = MiuixTheme.colorScheme
                        AppIconButton(
                            onClick = {
                                focus.clearFocus()
                                keyboard?.hide()
                                draft = rule
                                saveFailed = false
                                editing = true
                            },
                            modifier = Modifier.align(Alignment.CenterStart).semantics { stateDescription = rule.label },
                            enabled = toc.isNotEmpty(),
                            contentColor = if (rule == BookTocRule.ORIGINAL) colors.onSurface else colors.primary,
                        ) {
                            Icon(MiuixIcons.Tune, contentDescription = "目录规则")
                        }
                        TocOrderButton(
                            reversed = reversed,
                            onToggle = { scope.launch { appPrefs.setTocReversed(!reversed) } },
                            modifier = Modifier.align(Alignment.CenterEnd),
                        )
                    }
                    TocList(
                        toc = toc,
                        shown = shown,
                        listState = listState,
                        query = query,
                        onQueryChange = { query = it },
                        current = current,
                        currentKey = currentKey,
                        currentPos = currentPos,
                        showCacheState = showCacheState,
                        onSelect = onSelect,
                    )
                }
            }
        }
    }
}

@Composable
private fun TocRuleEditor(
    draft: BookTocRule,
    canOrganize: Boolean,
    preview: List<TocDisplayRow>,
    previewNote: String?,
    saving: Boolean,
    saveFailed: Boolean,
    onBack: () -> Unit,
    onChoose: (BookTocRule) -> Unit,
    onApply: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        SheetPageHeader("目录规则", onBack = onBack)
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .pageScroll()
                .verticalScroll(rememberScrollState(), overscrollEffect = null),
        ) {
            SettingFootnote("仅对当前书籍生效")
            SettingGroup(Modifier.selectableGroup()) {
                BookTocRule.entries.forEach { option ->
                    val selected = option == draft
                    ContentListItem(
                        selected = selected,
                        onClick = { onChoose(option) },
                        enabled = !saving && (canOrganize || option == BookTocRule.ORIGINAL),
                        contentPadding = ContentListDefaults.MediaPadding,
                        supportingContent = { Text(option.summary, style = ContentListDefaults.supportingStyle) },
                        trailingContent = if (selected) {
                            { Icon(MiuixIcons.Basic.Check, contentDescription = null, modifier = Modifier.size(Dimens.iconSm)) }
                        } else null,
                    ) {
                        Text(option.label, style = ContentListDefaults.titleStyle)
                    }
                }
            }
            SectionHeader("预览")
            previewNote?.let { SettingFootnote(it) }
            if (preview.isNotEmpty()) {
                SettingGroup {
                    preview.forEach { row ->
                        row.volumeHeading?.let { TocVolumeHeading(it) }
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = Dimens.touchTarget)
                                .padding(horizontal = Dimens.listHorizontal, vertical = Dimens.gapS),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Text(row.title, style = MiuixTheme.textStyles.body2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        if (saveFailed) SettingFootnote("保存失败，请重试", color = MiuixTheme.colorScheme.error)
        PrimaryButton(
            text = "应用",
            onClick = onApply,
            loading = saving,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = cardMargin())
                .padding(top = Dimens.gapS, bottom = sheetBottomInset() + Dimens.gapM),
        )
    }
}

private val BookTocRule.label: String
    get() = when (this) {
        BookTocRule.ORIGINAL -> "原样显示"
        BookTocRule.GROUP_VOLUMES -> "按卷分组"
        BookTocRule.HIDE_VOLUME -> "隐藏卷名"
    }

private val BookTocRule.summary: String
    get() = when (this) {
        BookTocRule.ORIGINAL -> "保留原始标题和层级"
        BookTocRule.GROUP_VOLUMES -> "同一卷集中显示，章名去掉卷前缀"
        BookTocRule.HIDE_VOLUME -> "去掉卷前缀，章节继续平铺"
    }

@Composable
private fun TocVolumeHeading(title: String, query: String = "") {
    val colors = MiuixTheme.colorScheme
    val text = remember(title, query, colors.primary) { highlightMatches(title, query, colors.primary) }
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.gapXXL)
            .padding(horizontal = Dimens.listHorizontal, vertical = Dimens.gapXS),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text,
            modifier = Modifier.semantics { heading() },
            style = MiuixTheme.textStyles.footnote1,
            fontWeight = FontWeight.Medium,
            color = colors.onSurfaceVariantSummary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private const val TOC_MAX_HEIGHT_FRACTION = 0.78f

@Composable
private fun TocList(
    toc: List<TocItem>,
    shown: List<TocDisplayRow>,
    listState: LazyListState,
    query: String,
    onQueryChange: (String) -> Unit,
    current: Int,
    currentKey: Int?,
    currentPos: Int,
    showCacheState: Boolean,
    onSelect: (TocItem) -> Unit,
) {
    val q = query.trim()
    val cachedCount = remember(toc) { toc.count { it.cached } }
    val animate = animationsEnabled()
    val scope = rememberCoroutineScope()

    Column {
        // SearchField 默认的底是 surfaceContainerHigh（「页面上的搜索栏」那一档）；浅色纸上浮层恰好也是这一档，
        // 框会整个隐身。这里换成面板里的卡片色 —— 搜索框与下面的目录卡同一层，浅纸深纸都浮得起来。
        // 左右与目录卡对齐（面板里的卡离两边 cardMargin）
        SearchField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = "搜索章节",
            containerColor = settingsCardColor(),
            modifier = Modifier.padding(horizontal = cardMargin(), vertical = Dimens.gapXS),
        )
        TocSummary(
            text = if (q.isEmpty()) "共 ${toc.size} 项" else "找到 ${shown.size} 项",
            cachedCount = if (showCacheState && q.isEmpty()) cachedCount else null,
        )
        if (shown.isEmpty()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = Dimens.gapXXL, bottom = Dimens.gapXXL + sheetBottomInset()),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (q.isEmpty()) "还没有章节" else "没有匹配的章节",
                    style = MiuixTheme.textStyles.body1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        } else {
            // 导航栏高度垫在列表末尾、也加进高度上限：列表一直铺到屏幕底、滚到手势条下面，看得见的条数不变。
            // 章节拼成一张卡（同详情页的目录），不再一章一张小卡、行间留缝
            val bottomInset = sheetBottomInset()
            val listPadding = PaddingValues(bottom = bottomInset)
            val cachedDot = MiuixTheme.colorScheme.primary
            val readDot = MiuixTheme.colorScheme.onSurfaceVariantSummary
            Box(
                Modifier
                    .heightIn(max = Dimens.sheetListMaxHeight + bottomInset)
                    // 和概况行之间的缝垫在视口外面：从前在列表的 contentPadding.top 里，一滚就跟着内容滚走，
                    // 章名直接顶到「共 N 章」底下。先限高再垫，整块的高度上限不变，面板不因此变高
                    .padding(top = Dimens.gapS),
            ) {
                // 视口裁成卡的圆上角：一滚，顶上切过的是中间的行，不裁的话卡顶是一刀直线（见 CardViewport）。
                // 快速滚动条和「定位当前章」留在视口外面，别被裁掉
                CardViewport {
                    LazyColumn(
                        state = listState,
                        // HyperOS 的回弹 + 到头轻震；主题自带的那份关掉，免得叠成双倍（见 pageScroll）
                        modifier = Modifier.pageScroll(),
                        contentPadding = listPadding,
                        overscrollEffect = null,
                    ) {
                        itemsIndexed(shown, key = { _, row -> row.item.key }) { i, row ->
                            val item = row.item
                            val read = item.index < current
                            // 卷标题附在首章的同一个列表项里，定位和快滚仍使用真实章节的位置。
                            Column(Modifier.groupedCardItem(i, shown.size)) {
                                row.volumeHeading?.let { TocVolumeHeading(it, q) }
                                ChapterListItem(
                                    title = row.title,
                                    selected = item.key == currentKey,
                                    read = read,
                                    highlight = q,
                                    onClick = { onSelect(item) },
                                    modifier = Modifier.padding(start = Dimens.gapM * item.depth.coerceIn(0, 4)),
                                    trailingContent = if (showCacheState && item.cached) {
                                        {
                                            Box(
                                                Modifier
                                                    .size(Dimens.gapS)
                                                    .semantics { contentDescription = "已缓存" }
                                                    .background(if (read) readDot else cachedDot, CircleShape),
                                            )
                                        }
                                    } else {
                                        null
                                    },
                                )
                            }
                        }
                    }
                }
                FastScroller(
                    state = listState,
                    itemCount = shown.size,
                    label = { shown.getOrNull(it)?.title.orEmpty() },
                    detail = { row ->
                        val index = shown.getOrNull(row)?.item?.index ?: 0
                        "全书 ${(index + 1) * 100 / toc.size.coerceAtLeast(1)}%"
                    },
                    contentPadding = listPadding,
                    modifier = Modifier.matchParentSize(),
                )
                if (q.isEmpty() && currentPos >= 0) {
                    CurrentChapterPill(
                        listState = listState,
                        currentPos = currentPos,
                        onClick = {
                            scope.launch {
                                val target = (currentPos - TOC_ROWS_ABOVE_CURRENT).coerceAtLeast(0)
                                if (animate) listState.animateScrollToItem(target) else listState.scrollToItem(target)
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = bottomInset + Dimens.gapM),
                    )
                }
            }
        }
    }
}

/** 目录上方的一行概况。左边与卡里的章名对齐 */
@Composable
private fun TocSummary(text: String, cachedCount: Int?) {
    val summary = MiuixTheme.colorScheme.onSurfaceVariantSummary
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = cardMargin() + Dimens.listHorizontal)
            .padding(top = Dimens.gapS)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MiuixTheme.textStyles.footnote1, color = summary)
        if (cachedCount != null) {
            Text(" · ", style = MiuixTheme.textStyles.footnote1, color = summary)
            // 和章节行尾那颗点同一个样子：概况里就把它是什么讲了
            Box(
                Modifier
                    .padding(end = Dimens.gapXS)
                    .size(Dimens.gapS)
                    .background(MiuixTheme.colorScheme.primary, CircleShape),
            )
            Text("已缓存 $cachedCount 章", style = MiuixTheme.textStyles.footnote1, color = summary)
        }
    }
}

/**
 * 「定位当前章」：在读那章滚出视野时浮出来。实心强调色胶囊（和阅读设置面板里实心的选中段同一种「强调」），
 * 压在章节卡上也分得出来；箭头指在读那章在上面还是下面。高度保底触控下限。
 */
@Composable
private fun CurrentChapterPill(
    listState: LazyListState,
    currentPos: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val offscreen by remember(listState, currentPos) {
        derivedStateOf { listState.layoutInfo.visibleItemsInfo.none { it.index == currentPos } }
    }
    val above by remember(listState, currentPos) {
        derivedStateOf { currentPos < listState.firstVisibleItemIndex }
    }
    AnimatedVisibility(
        visible = offscreen,
        modifier = modifier,
        enter = bottomBarEnter(),
        exit = bottomBarExit(),
    ) {
        val scheme = MiuixTheme.colorScheme
        Row(
            Modifier
                .heightIn(min = Dimens.touchTarget)
                .squircleSurface(scheme.primary, Dimens.touchTarget / 2)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = Dimens.gapL),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.gapXS),
        ) {
            Icon(
                if (above) MiuixIcons.ExpandLess else MiuixIcons.ExpandMore,
                contentDescription = null,
                tint = scheme.onPrimary,
                modifier = Modifier.size(Dimens.iconSm),
            )
            Text(
                "定位当前章",
                style = MiuixTheme.textStyles.body2,
                fontWeight = FontWeight.Medium,
                color = scheme.onPrimary,
            )
        }
    }
}

/** 定位到在读那章时，它上面留几章：看得出前后是哪几章 */
private const val TOC_ROWS_ABOVE_CURRENT = 3
