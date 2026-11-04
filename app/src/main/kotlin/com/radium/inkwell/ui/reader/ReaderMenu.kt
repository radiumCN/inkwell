package com.radium.inkwell.ui.reader

import com.radium.inkwell.ui.components.sheetBottomInset
import com.radium.inkwell.ui.components.TonalButton
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Delete
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.PaddingValues
import com.radium.inkwell.ui.components.AppFilterChip
import com.radium.inkwell.ui.components.AppIconButton
import com.radium.inkwell.ui.components.AppSlider
import androidx.compose.animation.AnimatedVisibility
import com.radium.inkwell.ui.components.AppTabContent
import com.radium.inkwell.ui.components.AppTabRow
import com.radium.inkwell.ui.components.bottomBarEnter
import com.radium.inkwell.ui.components.bottomBarExit
import com.radium.inkwell.ui.components.scrimEnter
import com.radium.inkwell.ui.components.scrimExit
import com.radium.inkwell.ui.components.topBarEnter
import com.radium.inkwell.ui.components.topBarExit
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clip
import kotlinx.coroutines.flow.StateFlow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radium.inkwell.ui.components.SwitchRow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.radium.inkwell.ui.components.ChapterListItem
import com.radium.inkwell.ui.components.ChipRow
import com.radium.inkwell.ui.components.ContentListDefaults
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.ui.components.SectionHeader
import com.radium.inkwell.ui.components.SettingGroupPosition
import com.radium.inkwell.reader.api.FlipAnimation
import com.radium.inkwell.reader.api.FlipDirection
import com.radium.inkwell.reader.api.ReaderSettings
import com.radium.inkwell.reader.api.ReaderTheme
import com.radium.inkwell.reader.api.TapAction
import com.radium.inkwell.reader.api.TapZones
import com.radium.inkwell.reader.flip.FLIP_SLOP_DP
import com.radium.inkwell.ui.theme.argbToHsv
import com.radium.inkwell.ui.theme.hsvToArgb
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.material.icons.filled.Tune
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.radium.inkwell.ui.components.AppBottomSheet
import com.radium.inkwell.ui.components.SegmentedChoice
import com.radium.inkwell.ui.components.AppTextButton
import com.radium.inkwell.ui.components.SearchField
import com.radium.inkwell.ui.theme.AppShapes
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun ReaderMenu(
    visible: Boolean,
    session: ReaderSessionUi,
    overlay: ReaderOverlayUi,
    pageUi: ReaderPageUi,
    toc: StateFlow<List<TocItem>>,
    onExit: () -> Unit,
    onGotoChapter: (Int) -> Unit,
    onSeekPercent: (Float) -> Unit,
    onUpdateSettings: (ReaderSettings) -> Unit,
    onPreviewSettings: (ReaderSettings) -> Unit,
    onCommitSettings: () -> Unit,
    onSetTextSelection: (Boolean) -> Unit,
    typography: TypographyActions,
    onSearchSources: () -> Unit,
    onToggleAutoFlip: () -> Unit,
    onDismiss: () -> Unit,
    onSwipePage: (FlipDirection) -> Unit,
    onReaderSheetOpen: (Boolean) -> Unit,
) {
    var showToc by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    // 菜单现在常驻组合（为了能播退场动画），所以关菜单时得把面板一起收掉 ——
    // 否则下次呼出菜单，上回没关的目录会自己冒出来
    LaunchedEffect(visible) {
        if (!visible) {
            showToc = false
            showSettings = false
        }
    }
    LaunchedEffect(showToc, showSettings) {
        onReaderSheetOpen(showToc || showSettings)
    }

    // 菜单这一层靠**色阶 + 形状**跟纸面分开，不靠阴影和蒙层：
    // - 从前顶栏、底栏与纸同色，只隔一根发丝线，看着像页面上下多了两行字，而不是盖上来的一层。
    // - 也别回到投影 / 压暗：同色纸上 4~8dp 的投影就是一道脏灰线；压暗正文会把米色纸压成灰绿，
    //   在顶栏底边裂出一道生硬的分界（两条都实测过）。纸书里书签和纸页之间也没有阴影。
    // 顶栏是比纸深（深色纸上是浅）一档的实色条，朝正文那一侧是大圆角；底部是两块同色的悬浮圆角条
    // （进度、操作），四周露出纸色。圆角的边、露出来的那圈纸，就是「盖在纸上」的全部表达。
    // 浮层底色与点「设置」弹出的面板同一个（见 readerOverlayColor）。
    val scheme = MiuixTheme.colorScheme
    val barColor = readerOverlayColor()
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl

    // 菜单从前是硬出硬消（直接 if (visible) 组合/移除）。现在顶栏从上滑入、底栏从下滑入、
    // 整层淡入 —— 让人看得出这是「盖上来的一层」，而不是页面突然换了个样子。
    // 退场比入场快（帮手里退场取 fast* 档）：用户已经决定关掉了，界面还慢悠悠淡出会像没反应。
    AnimatedVisibility(
        visible = visible,
        enter = scrimEnter(),
        exit = scrimExit(),
    ) {
        val density = LocalDensity.current
        val slopPx = with(density) { FLIP_SLOP_DP.dp.toPx() }
        Box(Modifier.fillMaxSize()) {
            // 底层：整块纸面，**透明**。点一下关菜单；按当前翻页方向滑一下 = 关并翻一页。
            // 顶栏和底部面板都是 Surface，落在它们身上的触摸被它们吃掉，不会漏到这一层。
            // 无涟漪：这是块半屏的空白。目录/设置/换源开着时不拦截，否则跟面板里的列表抢手势。
            // visible 已是 false 时也不挂 —— 退场动画还在播若仍吃手势，关完会短暂翻不了页。
            Box(
                Modifier
                    .fillMaxSize()
                    .menuBodyGesture(
                        enabled = visible &&
                            !showToc &&
                            !showSettings &&
                            overlay.sourceCandidates == null,
                        vertical = session.settings.flipAnimation == FlipAnimation.SCROLL,
                        slopPx = slopPx,
                        onTap = onDismiss,
                        onFlip = onSwipePage,
                    ),
            )
            Column(Modifier.fillMaxSize()) {
                // 顶栏从上滑入，底部从下滑入 —— 用 animateEnterExit 而不是再套一层
                // AnimatedVisibility：套进来的那层 visible 恒为 true，根本不会播。
                Surface(
                    color = barColor,
                    contentColor = scheme.onSurface,
                    shape = AppShapes.extraLarge.copy(topStart = ZeroCornerSize, topEnd = ZeroCornerSize),
                    modifier = Modifier.animateEnterExit(topBarEnter(), topBarExit()),
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = Dimens.gapXS),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 与全应用的 BackButton 同一个箭头（Miuix 细线 Back），只是读屏名字不同；它不自动镜像，RTL 下自己翻
                        AppIconButton(onClick = onExit) {
                            Icon(
                                MiuixIcons.Back,
                                contentDescription = "退出阅读",
                                modifier = if (rtl) Modifier.graphicsLayer { scaleX = -1f } else Modifier,
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                session.bookTitle,
                                style = MiuixTheme.textStyles.headline2,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                pageUi.chapterTitle,
                                style = MiuixTheme.textStyles.footnote1,
                                // 次要文字走纸张主题的摘要色（够 4.5:1），不再手写 0.65 透明度
                                color = scheme.onSurfaceVariantSummary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                Spacer(Modifier.weight(1f))

                Column(
                    Modifier
                        .animateEnterExit(bottomBarEnter(), bottomBarExit())
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = Dimens.gapM, vertical = Dimens.gapS),
                    verticalArrangement = Arrangement.spacedBy(Dimens.gapS),
                ) {
                    Surface(
                        shape = AppShapes.extraLarge,
                        color = barColor,
                        contentColor = scheme.onSurface,
                    ) {
                        ChapterProgress(
                            pageUi = pageUi,
                            chapterCount = session.chapterCount,
                            onGotoChapter = onGotoChapter,
                            onSeekPercent = onSeekPercent,
                            modifier = Modifier.padding(horizontal = Dimens.gapS, vertical = Dimens.gapXS),
                        )
                    }
                    Surface(
                        shape = AppShapes.extraLarge,
                        color = barColor,
                        contentColor = scheme.onSurface,
                    ) {
                        MenuActions(
                            session = session,
                            overlay = overlay,
                            onShowToc = { showToc = true },
                            onSearchSources = onSearchSources,
                            onToggleAutoFlip = onToggleAutoFlip,
                            onShowSettings = { showSettings = true },
                            modifier = Modifier.padding(Dimens.gapXS),
                        )
                    }
                }
            }
        }
    }

    if (showToc) {
        val tocItems by toc.collectAsStateWithLifecycle()
        // 面板底是浮层色（AppBottomSheet 默认的 background），章节行是卡片色，两者差一档。
        // 目录会滚到手势条下面：导航栏高度垫在列表末尾（见 TocList），不让面板在外面垫
        AppBottomSheet(
            onDismissRequest = { showToc = false },
            padNavigationBar = false,
        ) {
            TocList(
                toc = tocItems,
                current = pageUi.chapterIndex,
                // 本地书正文一直在，缓存指示对它没有意义（isCached 恒为 false，
                // 显示出来只会让每一章都像"没预加载"）
                showCacheState = session.isNetBook,
                // 目录跳章后收起整个菜单；上一章/下一章则留着菜单，方便连着翻。
                // 跳章不等面板的退场动画（dismiss { }）：那会让翻到目标章晚上三百毫秒，像点了没反应
                onSelect = { showToc = false; onGotoChapter(it); onDismiss() },
            )
        }
    }

    if (showSettings) {
        // 不压暗正文：面板只占半屏，上面露出来的正文就是排版的实时预览；隔着一层黑看，行距字距勉强看得出，
        // 纸色和亮度根本没法判断。底与菜单的浮层同色（readerOverlayColor，与纸面差一档 —— 同色的话面板上沿等于不存在）。
        // 导航栏高度由面板内容自己垫在滚动末尾（见 TypographyPanel），这里不垫
        AppBottomSheet(
            onDismissRequest = { showSettings = false },
            backgroundColor = readerOverlayColor(),
            padNavigationBar = false,
            enableWindowDim = false,
        ) {
            TypographyPanel(
                settings = session.settings,
                onUpdate = onUpdateSettings,
                onPreview = onPreviewSettings,
                onCommit = onCommitSettings,
                textSelectionEnabled = session.textSelectionEnabled,
                onTextSelectionChange = onSetTextSelection,
                typography = typography,
            )
        }
    }

}

/**
 * 底栏「上一章 / 下一章」。不用按钮：按钮自带底色与最小高度，夹在滑块两边像两张小卡片，
 * 整行高度被它们钉死。文字链 + [Dimens.touchTarget] 保触控，视觉跟轨对齐。
 */
@Composable
private fun ChapterTextAction(
    label: String,
    enabled: Boolean,
    color: Color,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        style = MiuixTheme.textStyles.body2,
        fontWeight = FontWeight.Medium,
        color = if (enabled) color else color.copy(alpha = 0.38f),
        modifier = Modifier
            .heightIn(min = Dimens.touchTarget)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = Dimens.gapXS)
            .wrapContentHeight(align = Alignment.CenterVertically),
    )
}

/**
 * 章节进度：上一章 / 细轨滑块 / 下一章，下面一行「本章第几页 · 全书百分比」。
 *
 * 从前只有一根轨：看不出读到这一章的哪儿、全书的哪儿；拖的时候也没有数字，松手前不知道会落在哪一页。
 * 拖动是实时跳页的（见 ReaderViewModel.seekToPercent），这行字跟着页码一起变。
 *
 * 不写「第几章」：那是目录里的序号，书源目录里混着公告、卷名时，跟章节标题自己的编号对不上 ——
 * 标题写着第 2383 章，这里却说第 2395 章。全书百分比与页脚同一个算法（[bookProgressPercent]）。
 */
@Composable
private fun ChapterProgress(
    pageUi: ReaderPageUi,
    chapterCount: Int,
    onGotoChapter: (Int) -> Unit,
    onSeekPercent: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MiuixTheme.colorScheme
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChapterTextAction(
                label = "上一章",
                enabled = pageUi.chapterIndex > 0,
                color = scheme.onSurface,
                onClick = { onGotoChapter(pageUi.chapterIndex - 1) },
            )
            AppSlider(
                value = if (pageUi.pageCount <= 1) 0f
                else pageUi.pageInChapter.toFloat() / (pageUi.pageCount - 1),
                onValueChange = onSeekPercent,
                label = "章内进度",
                enabled = pageUi.pageCount > 1,
                activeColor = scheme.primary,
                // 夹在两行字之间，用矮一档的轨：默认 28dp 的胶囊在这条细长的悬浮条里太抢
                height = Dimens.sliderCompact,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = Dimens.gapS),
            )
            ChapterTextAction(
                label = "下一章",
                enabled = pageUi.chapterIndex + 1 < chapterCount,
                color = scheme.onSurface,
                onClick = { onGotoChapter(pageUi.chapterIndex + 1) },
            )
        }
        Text(
            progressLabel(pageUi, chapterCount),
            style = MiuixTheme.textStyles.footnote1,
            color = scheme.onSurfaceVariantSummary,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Dimens.gapXS),
        )
    }
}

private fun progressLabel(page: ReaderPageUi, chapterCount: Int): String = buildString {
    if (page.pageCount > 0) append("本章 ${page.pageInChapter + 1} / ${page.pageCount} 页 · ")
    append("全书 ${"%.1f".format(bookProgressPercent(page, chapterCount))}%")
}

/**
 * 底部操作条。图标配上文字：从前是四个光秃秃的图标，新用户得挨个点了才知道是什么。
 *
 * 四个动作同一种样式（图标在上、字在下）、平分宽度。曾把「设置」做成实心主按钮突出它，可那是
 * 图标在左、字在右的另一种排法，一排四个里只有它横着，看着像拼错了 —— 宁可四个一样。
 * 本地书 / 滚动模式下不适用的动作保留占位并禁用，位置不跳。
 */
@Composable
private fun MenuActions(
    session: ReaderSessionUi,
    overlay: ReaderOverlayUi,
    onShowToc: () -> Unit,
    onSearchSources: () -> Unit,
    onToggleAutoFlip: () -> Unit,
    onShowSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        BarAction(
            icon = Icons.AutoMirrored.Filled.List,
            label = "目录",
            onClick = onShowToc,
            modifier = Modifier.weight(1f),
        )
        BarAction(
            icon = Icons.Default.SwapHoriz,
            label = "换源",
            onClick = onSearchSources,
            enabled = session.isNetBook,
            modifier = Modifier.weight(1f),
        )
        BarAction(
            icon = if (overlay.autoFlipping) Icons.Default.Pause else Icons.Default.PlayArrow,
            label = if (overlay.autoFlipping) "停止翻页" else "自动翻页",
            onClick = onToggleAutoFlip,
            enabled = session.settings.flipAnimation != FlipAnimation.SCROLL,
            modifier = Modifier.weight(1f),
        )
        BarAction(
            icon = Icons.Default.Tune,
            label = "设置",
            onClick = onShowSettings,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 操作条上的一个动作：图标在上、字在下。走 [AppTextButton]（按下有 Miuix 的压暗反馈，禁用时自动压到 38%）。
 *
 * 图标仍是 Material 的：四个里「换源」在 Miuix 图标集里没有对应的，混用两套线宽在同一排里一眼就看得出，
 * 宁可整排一致。
 */
@Composable
private fun BarAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    AppTextButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        contentPadding = PaddingValues(vertical = Dimens.gapXS),
        contentColor = MiuixTheme.colorScheme.onSurface,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // 名字由下面的字给，图标不再单独进读屏
            Icon(icon, contentDescription = null, modifier = Modifier.size(Dimens.iconMd))
            Text(label, style = MiuixTheme.textStyles.footnote1, maxLines = 1)
        }
    }
}

@Composable
private fun TocList(
    toc: List<TocItem>,
    current: Int,
    showCacheState: Boolean,
    onSelect: (Int) -> Unit,
) {
    val listState = rememberLazyListState()
    var query by remember { mutableStateOf("") }
    val filtered = remember(toc, query) {
        if (query.isBlank()) toc
        else toc.filter { it.title.contains(query.trim(), ignoreCase = true) }
    }
    LaunchedEffect(Unit) {
        if (current > 3) listState.scrollToItem(current - 3)
    }

    Column {
        // SearchField 默认的底是 surfaceContainerHigh（「页面上的搜索栏」那一档）；浅色纸上浮层恰好也是这一档，
        // 框会整个隐身。这里换成卡片色 —— 搜索框与下面的章节卡同一层，浅纸深纸都浮得起来
        SearchField(
            value = query,
            onValueChange = { query = it },
            placeholder = "搜索章节",
            containerColor = MiuixTheme.colorScheme.surfaceContainer,
            modifier = Modifier.padding(horizontal = Dimens.screenPadding, vertical = Dimens.gapXS),
        )
        if (filtered.isEmpty()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = Dimens.gapXXL, bottom = Dimens.gapXXL + sheetBottomInset()),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "没有匹配的章节",
                    style = MiuixTheme.textStyles.body1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        } else {
            // 导航栏高度垫在列表末尾、也加进高度上限：列表一直铺到屏幕底、滚到手势条下面，看得见的条数不变
            val bottomInset = sheetBottomInset()
            LazyColumn(
                state = listState,
                modifier = Modifier.heightIn(max = Dimens.sheetListMaxHeight + bottomInset),
                contentPadding = ContentListDefaults.listContentPadding(bottom = bottomInset),
                verticalArrangement = Arrangement.spacedBy(ContentListDefaults.ListSpacing),
            ) {
                items(filtered, key = { it.index }) { item ->
                    ChapterListItem(
                        title = item.title,
                        selected = item.index == current,
                        onClick = { onSelect(item.index) },
                        inset = false,
                        trailingContent = if (showCacheState && item.cached) {
                            {
                                // 有点=正文已缓存，翻到它不用等网络。没点的章节才是"翻过去要现抓"的，
                                // 也就是可能卡住的那些 —— 这正是预加载到底跑到哪了的唯一可见线索。
                                Box(
                                    Modifier
                                        .size(Dimens.gapS)
                                        .semantics { contentDescription = "已缓存" }
                                        .background(MiuixTheme.colorScheme.primary, CircleShape),
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
}

// ---------- 阅读设置面板 ----------

private val LINE_SPACING_OPTIONS = listOf(
    "紧凑" to 1.4f,
    "标准" to 1.6f,
    "宽松" to 1.9f,
)

/** 段与段之间的空（em，按正文字号算）。0 = 段落之间只靠首行缩进区分 */
private val PARA_SPACING_OPTIONS = listOf(
    "无" to 0f,
    "紧凑" to 0.3f,
    "标准" to 0.6f,
    "宽松" to 1.0f,
)

/** 字距（em）。档位很小：中文字距加到 0.1em 已经明显松散，再大就是一个个散字 */
private val LETTER_SPACING_OPTIONS = listOf(
    "标准" to 0f,
    "稍宽" to 0.05f,
    "宽" to 0.1f,
)

/** 首行缩进（字）。有的书正文自带全角空格缩进，再叠两字就成了四字 —— 给个「无」让人关掉 */
private val INDENT_OPTIONS = listOf(
    "无" to 0f,
    "1 字" to 1f,
    "2 字" to 2f,
)

/** 章节标题相对正文的倍数。1.0 = 与正文同大（有人不喜欢标题占地方） */
private val TITLE_SCALE_OPTIONS = listOf(
    "小" to 1.0f,
    "标准" to 1.4f,
    "大" to 1.8f,
)

/** 章节标题上方留白（dp）。0 = 标题直接贴着正文顶 */
private val TITLE_TOP_OPTIONS = listOf(
    "无" to 0f,
    "小" to 12f,
    "标准" to 24f,
    "大" to 48f,
)

/** 头部留白（dp）= 正文上边距：页眉小标题落在这，正文从它下面开始。缩小 → 正文多出空间 */
private val HEADER_TOP_OPTIONS = listOf(
    "紧凑" to 8f,
    "标准" to 24f,
    "宽松" to 48f,
)

/** 正文下边距（dp）—— 每页正文离屏幕下边的距离。上边距由「头部留白」管 */
private val MARGIN_V_OPTIONS = listOf(
    "紧凑" to 12f,
    "标准" to 28f,
    "宽松" to 48f,
)

/** 正文左右边距（dp） */
private val MARGIN_H_OPTIONS = listOf(
    "紧凑" to 16f,
    "标准" to 24f,
    "宽松" to 36f,
)

/**
 * 阅读设置面板。
 *
 * 分四页，按「改的频率」而不是按「技术类别」分：
 * - 排版：字号、行距、亮度、字体、纸张、方案 —— 最常调的，打开面板第一眼就该是它们
 * - 版式：段距、字距、缩进、对齐、标题与边距 —— 调一次就很少再动的细节
 * - 翻页：翻页方式、自动翻页间隔、音量键翻页、点击区域
 * - 更多：设一次基本不再动的（预加载、屏幕常亮）
 *
 * 层次靠三档色阶拉开（见 ReaderThemeScope）：面板本身与纸面差一档（[readerOverlayColor]），
 * 设置项收进分组卡（[ReaderGroup]，[readerCardColor]），卡里的分段按钮未选中再差一档、选中的实心填强调色。从前是十几组「小标题 + 一排 chip」平铺在纸上，
 * 每组长得一模一样，选中的 chip 只是一层淡底，一屏看下来没有任何东西跳出来。
 *
 * **只占半屏、不压暗正文**：调排版要看得见效果。从前面板固定 560dp 高、底下还压着一层黑，
 * 改行距字距时正文被盖住大半，纸色和亮度隔着蒙层更是没法判断。现在上面露出来的正文就是实时预览。
 *
 * Tab 与切换过渡都走 [AppTabRow] / [AppTabContent]（`ui/components`）—— 全应用同一套形态
 * 与节奏，别在这里手写 TabRow。
 */
@Composable
private fun TypographyPanel(
    settings: ReaderSettings,
    onUpdate: (ReaderSettings) -> Unit,
    onPreview: (ReaderSettings) -> Unit,
    onCommit: () -> Unit,
    textSelectionEnabled: Boolean,
    onTextSelectionChange: (Boolean) -> Unit,
    typography: TypographyActions,
) {
    var tab by remember { mutableIntStateOf(0) }
    val scroll = rememberScrollState()
    // 换 tab 把滚动复位：否则从「排版」滚到一半切到「翻页」，人会落在空白里发愣
    LaunchedEffect(tab) { scroll.scrollTo(0) }
    val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    // 内容区定高（别用 heightIn(max)：那只是上限，内容矮时面板跟着缩，切到长的那页又被顶起来）。
    // 按窗口比例取，大屏封顶：加上 Tab 与拖拽把手，整个面板约半屏
    val contentHeight = (windowHeight * PANEL_HEIGHT_FRACTION).coerceAtMost(Dimens.sheetListMaxHeight)

    Column(Modifier.fillMaxWidth()) {
        // TabRow 钉在顶不滚，只让下面的内容滚
        AppTabRow(
            titles = SETTINGS_TABS,
            selectedIndex = tab,
            onSelect = { tab = it },
            // 透出面板自己的底色；默认的 surface 是纸色，会在面板顶上横出一条纸色的带子。
            // 选中那一格是卡片色（surfaceContainer），与面板差一档，透明底上照样分得清
            containerColor = Color.Transparent,
        )
        AppTabContent(
            targetState = tab,
            label = "readerSettingsTab",
            modifier = Modifier
                .fillMaxWidth()
                // 导航栏高度加进定高、也垫在滚动内容末尾：面板铺到屏幕底、设置项滚到手势条下面，看得见的高度不变
                .height(contentHeight + sheetBottomInset())
                .verticalScroll(scroll)
                .padding(horizontal = Dimens.screenPadding)
                .padding(top = Dimens.gapM, bottom = Dimens.gapXXL + sheetBottomInset()),
        ) { current ->
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.gapL)) {
                when (current) {
                    0 -> LayoutTab(settings, onUpdate, onPreview, onCommit, typography)
                    1 -> StyleTab(settings, onUpdate)
                    2 -> FlipTab(settings, onUpdate)
                    else -> MoreTab(settings, onUpdate, textSelectionEnabled, onTextSelectionChange)
                }
            }
        }
    }
}

/**
 * 设置面板里的一张分组卡：标题在卡外，用次要文字色（不跟选中态抢强调色）；内容在卡里（底色见 [readerCardColor]）。
 * 卡内的行自己带左右内缩（[ReaderRow] / [ReaderBlock] / 分组 [SwitchRow] 都是 listHorizontal），
 * 卡本身只给上下一点留白。
 */
@Composable
private fun ReaderGroup(
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column {
        if (title != null) {
            Text(
                title,
                style = MiuixTheme.textStyles.body2,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier
                    .padding(start = Dimens.listHorizontal, bottom = Dimens.gapS)
                    .semantics { heading() },
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .clip(AppShapes.large)
                .background(readerCardColor())
                .padding(vertical = Dimens.gapXS),
            content = content,
        )
    }
}

/**
 * 卡里一行：标签在左、控件在右；放不下就退成标签在上、控件在下。
 *
 * 从前每一项都是「一行小标题 + 一排 chip」上下摞着：高度翻倍，眼睛还得在标题和选项之间来回跳。
 * 放不放得下按 [slots] 估 —— 控件要几个触控目标宽，再按系统字号放大；大字号、窄屏时宁可摞起来，
 * 也不把分段按钮挤到文字截断。标签列定宽，同一张卡里各行的控件左边对齐。
 */
@Composable
private fun ReaderRow(
    label: String,
    slots: Int,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.listHorizontal, vertical = Dimens.gapS),
    ) {
        val fontScale = LocalDensity.current.fontScale
        val labelWidth = Dimens.settingLabelWidth * fontScale
        val labelText: @Composable (Modifier) -> Unit = { m ->
            Text(label, style = MiuixTheme.textStyles.body2, modifier = m)
        }
        if (maxWidth >= labelWidth + Dimens.touchTarget * slots * fontScale) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                labelText(Modifier.width(labelWidth))
                Box(Modifier.weight(1f)) { content() }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.gapS)) {
                labelText(Modifier)
                content()
            }
        }
    }
}

/** 卡里没有标签的一整块（chip 条、九宫格、色板），左右与 [ReaderRow] 同一个内缩 */
@Composable
private fun ReaderBlock(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.listHorizontal, vertical = Dimens.gapS),
    ) { content() }
}

/** 几档里选一档：标签 + 分段按钮。档位值是浮点，按 [tolerance] 认当前在哪一档 */
@Composable
private fun SegmentRow(
    label: String,
    options: List<Pair<String, Float>>,
    value: Float,
    tolerance: Float,
    onSelect: (Float) -> Unit,
) {
    ReaderRow(label, slots = options.size) {
        SegmentedChoice(
            options = options.map { it.first },
            selectedIndex = options.indexOfFirst { kotlin.math.abs(it.second - value) < tolerance },
            onSelect = { onSelect(options[it].second) },
        )
    }
}

/**
 * [onPreview] / [onCommit]：连续拖动的滑块（纸色、亮度）拖着时只预览、松手才落库，
 * 见 [ReaderViewModel.previewSettings]。点一下就定的项照旧走 [onUpdate]。
 */
@Composable
private fun LayoutTab(
    settings: ReaderSettings,
    onUpdate: (ReaderSettings) -> Unit,
    onPreview: (ReaderSettings) -> Unit,
    onCommit: () -> Unit,
    typography: TypographyActions,
) {
    // 最常调的三样放第一张卡，打开面板不用滚就在眼前
    ReaderGroup {
        ReaderRow("字号", slots = 3) { FontSizeStepper(settings, onUpdate) }
        SegmentRow("行距", LINE_SPACING_OPTIONS, settings.lineSpacingMult, 0.05f) {
            onUpdate(settings.copy(lineSpacingMult = it))
        }
        // 亮度要一整条滑轨才好拖：slots 给足，窄屏上标签自动摞到上面
        ReaderRow("亮度", slots = 6) { BrightnessControl(settings, onUpdate, onPreview, onCommit) }
    }

    ReaderGroup("字体") {
        Box(Modifier.padding(vertical = Dimens.gapS)) {
            FontSection(
                settings = settings,
                actions = typography,
                onSelect = { onUpdate(settings.copy(fontId = it)) },
                horizontalPadding = Dimens.listHorizontal,
            )
        }
    }

    ReaderGroup("纸张") {
        Column(Modifier.padding(vertical = Dimens.gapS)) {
            ThemeSwatches(settings, onUpdate, horizontalPadding = Dimens.listHorizontal)
            // 选中自定义才展开调色 —— 平时它是一整块滑块，占掉半个面板
            if (settings.theme.id == ReaderTheme.CUSTOM_ID) {
                Column(Modifier.padding(horizontal = Dimens.listHorizontal)) {
                    CustomPaperEditor(
                        theme = settings.theme,
                        onChange = { onPreview(settings.copy(theme = it)) },
                        onChangeFinished = onCommit,
                    )
                }
            }
        }
    }

    ReaderGroup("排版方案") {
        Box(Modifier.padding(vertical = Dimens.gapS)) {
            LayoutSchemeSection(settings, typography, horizontalPadding = Dimens.listHorizontal)
        }
    }
}

/**
 * 字号：加减按钮 + 当前值，一行搞定。
 * 用 [TonalButton]（浅底实心，与分段按钮的槽同一档）：同一张卡里下面就是一排分段按钮，按钮若另起一种底色，
 * 看着像两套控件拼在一起。按钮的上下内边距把高度撑到 48dp 以上，触控区够。
 * 「A−」读屏只会念成「A 减」，补一个说清用途的名字
 */
@Composable
private fun FontSizeStepper(settings: ReaderSettings, onUpdate: (ReaderSettings) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TonalButton(
            text = "A−",
            onClick = { if (settings.fontSizeSp > 12f) onUpdate(settings.copy(fontSizeSp = settings.fontSizeSp - 1)) },
            modifier = Modifier.weight(1f).semantics { contentDescription = "字号减小" },
        )
        Text(
            "${settings.fontSizeSp.toInt()}",
            // 数字两侧留出间距，宽度至少一个触控目标：字号从 9 变到 10 时两侧按钮别跟着挪
            Modifier.padding(horizontal = Dimens.gapM).widthIn(min = Dimens.touchTarget),
            textAlign = TextAlign.Center,
            style = MiuixTheme.textStyles.title3,
        )
        TonalButton(
            text = "A＋",
            onClick = { if (settings.fontSizeSp < 32f) onUpdate(settings.copy(fontSizeSp = settings.fontSizeSp + 1)) },
            modifier = Modifier.weight(1f).semantics { contentDescription = "字号增大" },
        )
    }
}

@Composable
private fun BrightnessControl(
    settings: ReaderSettings,
    onUpdate: (ReaderSettings) -> Unit,
    onPreview: (ReaderSettings) -> Unit,
    onCommit: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Default.DarkMode, contentDescription = null,
            Modifier.size(Dimens.iconSm), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        AppSlider(
            value = settings.brightnessOverride ?: 0.5f,
            onValueChange = { v ->
                onPreview(settings.copy(brightnessOverride = v.coerceIn(0.01f, 1f)))
            },
            onValueChangeFinished = onCommit,
            label = "亮度",
            enabled = settings.brightnessOverride != null,
            modifier = Modifier.weight(1f).padding(horizontal = Dimens.gapS),
        )
        Icon(
            Icons.Default.LightMode, contentDescription = null,
            Modifier.size(Dimens.iconSm), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        AppFilterChip(
            selected = settings.brightnessOverride == null,
            onClick = {
                onUpdate(
                    settings.copy(
                        brightnessOverride = if (settings.brightnessOverride == null) 0.5f else null,
                    )
                )
            },
            label = "系统",
            modifier = Modifier.padding(start = Dimens.gapM),
        )
    }
}

/**
 * 版式：段距、字距、缩进、对齐，以及标题与边距。都是「调一次就很少再动」的细节，
 * 从排版页挪出来，让排版页一屏就能看到字号、字体和纸色。
 */
@Composable
private fun StyleTab(settings: ReaderSettings, onUpdate: (ReaderSettings) -> Unit) {
    ReaderGroup("间距") {
        SegmentRow("段距", PARA_SPACING_OPTIONS, settings.paragraphSpacingEm, 0.05f) {
            onUpdate(settings.copy(paragraphSpacingEm = it))
        }
        SegmentRow("字距", LETTER_SPACING_OPTIONS, settings.letterSpacingEm, 0.005f) {
            onUpdate(settings.copy(letterSpacingEm = it))
        }
    }

    ReaderGroup("段落") {
        SegmentRow("首行缩进", INDENT_OPTIONS, settings.firstLineIndentEm, 0.05f) {
            onUpdate(settings.copy(firstLineIndentEm = it))
        }
        SwitchRow(
            title = "两端对齐",
            subtitle = "每行左右都贴齐边距。关掉后行尾参差，但字距不会被拉开",
            checked = settings.justify,
            onCheckedChange = { onUpdate(settings.copy(justify = it)) },
            position = SettingGroupPosition.Last,
        )
    }

    ReaderGroup("标题") {
        SegmentRow("字号", TITLE_SCALE_OPTIONS, settings.titleScale, 0.05f) {
            onUpdate(settings.copy(titleScale = it))
        }
        SegmentRow("上间距", TITLE_TOP_OPTIONS, settings.titleTopSpacingDp, 1f) {
            onUpdate(settings.copy(titleTopSpacingDp = it))
        }
    }

    ReaderGroup("页面") {
        SegmentRow("头部留白", HEADER_TOP_OPTIONS, settings.headerTopSpacingDp, 2f) {
            onUpdate(settings.copy(headerTopSpacingDp = it))
        }
        SegmentRow("下边距", MARGIN_V_OPTIONS, settings.marginVerticalDp, 2f) {
            onUpdate(settings.copy(marginVerticalDp = it))
        }
        SegmentRow("左右边距", MARGIN_H_OPTIONS, settings.marginHorizontalDp, 2f) {
            onUpdate(settings.copy(marginHorizontalDp = it))
        }
    }
}

@Composable
private fun FlipTab(settings: ReaderSettings, onUpdate: (ReaderSettings) -> Unit) {
    ReaderGroup("翻页方式") {
        ReaderBlock {
            SegmentedChoice(
                options = FlipAnimation.entries.map { it.label },
                selectedIndex = FlipAnimation.entries.indexOf(settings.flipAnimation),
                onSelect = { onUpdate(settings.copy(flipAnimation = FlipAnimation.entries[it])) },
            )
        }
    }

    // 滚动模式没有"页"，自动翻页 / 音量键 / 震动无从谈起 —— 与其留在那儿让人调了没反应，不如藏起来。
    // 点击左右跳整页则反过来：仿真里是主操作，滚动里默认关，单独给开关。
    if (settings.flipAnimation != FlipAnimation.SCROLL) {
        // 七档间隔放不进一排分段按钮，仍用能横滚的 chip 条
        ReaderGroup("自动翻页间隔") {
            Box(Modifier.padding(vertical = Dimens.gapS)) {
                ChipRow(
                    options = AUTO_FLIP_OPTIONS.map { "${it}s" },
                    selectedIndex = AUTO_FLIP_OPTIONS.indexOf(settings.autoFlipSeconds),
                    onSelect = { onUpdate(settings.copy(autoFlipSeconds = AUTO_FLIP_OPTIONS[it])) },
                    contentPadding = PaddingValues(horizontal = Dimens.listHorizontal),
                )
            }
        }

        ReaderGroup {
            SwitchRow(
                title = "音量键翻页",
                checked = settings.volumeKeyFlip,
                onCheckedChange = { onUpdate(settings.copy(volumeKeyFlip = it)) },
                position = SettingGroupPosition.First,
            )
            SwitchRow(
                title = "翻页震动",
                subtitle = "翻过一页时轻震一下。连续翻页会一直震，默认关",
                checked = settings.flipHaptic,
                onCheckedChange = { onUpdate(settings.copy(flipHaptic = it)) },
                position = SettingGroupPosition.Last,
            )
        }

        ReaderGroup("点击区域") {
            ReaderBlock { TapZoneEditor(settings.tapZones) { onUpdate(settings.copy(tapZones = it)) } }
        }
    } else {
        ReaderGroup {
            SwitchRow(
                title = "点击翻页",
                subtitle = "打开后按下面的点击区域翻页；默认关，点击只唤出菜单",
                checked = settings.scrollClickToFlip,
                onCheckedChange = { onUpdate(settings.copy(scrollClickToFlip = it)) },
                position = SettingGroupPosition.Alone,
            )
        }
        // 关着时分区不生效，摆出来只会让人以为改了没反应
        if (settings.scrollClickToFlip) {
            ReaderGroup("点击区域") {
                ReaderBlock { TapZoneEditor(settings.tapZones) { onUpdate(settings.copy(tapZones = it)) } }
            }
        }
    }
}

/**
 * 点击区域：上面一排预设，下面是代表整块阅读区的九宫格，点一格就在
 * 上一页 → 下一页 → 菜单 → 无动作 之间轮换。正中那格固定是菜单，画出来但不可点（见 [TapZones]）。
 *
 * 轮换而不是每格弹选择面板：编辑器本身就在底部面板里，再叠一层面板，关两次才回得到正文。
 * 四个状态靠格子里的字和底色区分，轮一圈就回来，点错了接着点即可。
 */
@Composable
private fun TapZoneEditor(zones: TapZones, onChange: (TapZones) -> Unit) {
    val presets = TapZones.Preset.entries
    val presetIndex = presets.indexOfFirst { it.zones == zones }
    Column {
        // 改过任何一格、跟哪个预设都对不上时，补一个选中的「自定义」，让人知道当前不是预设而不是什么都没选
        ChipRow(
            options = presets.map { it.label } + if (presetIndex < 0) listOf("自定义") else emptyList(),
            selectedIndex = if (presetIndex < 0) presets.size else presetIndex,
            onSelect = { i -> presets.getOrNull(i)?.let { onChange(it.zones) } },
        )
        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = Dimens.gapS),
            verticalArrangement = Arrangement.spacedBy(Dimens.gapXS),
        ) {
            for (row in 0..2) {
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.gapXS)) {
                    for (col in 0..2) {
                        val index = row * 3 + col
                        TapZoneCell(
                            action = zones.at(col, row),
                            position = TAP_ZONE_POSITIONS[index],
                            // 正中锁死为菜单
                            onClick = if (index == TapZones.CENTER) null else {
                                { onChange(zones.with(index, zones.at(col, row).next())) }
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TapZoneCell(
    action: TapAction,
    position: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val scheme = MiuixTheme.colorScheme
    val shape = AppShapes.small
    // 面板在 ReaderThemeScope 里，颜色都是从纸色派生的（见那里）：下一页是强调色的淡底、最显眼；
    // 上一页是正文色 14% 的灰底、淡一档；菜单再淡一档（正文色 6%），像纸面上一级浅浅的台阶；
    // 无动作不铺底只描边，像「这格空着」。四态靠色相 + 浓淡 + 有无底色拉开。
    // 灰底直接叠正文色而不取 Miuix 的中性槽：那几档在深色纸上有的恰好等于卡片色，格子会隐身
    val (container, content) = when (action) {
        TapAction.PREV -> scheme.onSurface.copy(alpha = 0.14f) to scheme.onSurface
        TapAction.NEXT -> scheme.primaryContainer to scheme.onPrimaryContainer
        TapAction.MENU -> scheme.onSurface.copy(alpha = 0.06f) to scheme.onSurface
        TapAction.NONE -> Color.Transparent to scheme.onSurfaceVariantSummary
    }
    Box(
        modifier
            .heightIn(min = Dimens.touchTarget)
            .clip(shape)
            .background(container)
            .then(
                if (action == TapAction.NONE) Modifier.border(Dimens.strokeThin, scheme.dividerLine, shape)
                else Modifier
            )
            .then(
                if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick)
                else Modifier
            )
            // 读屏得知道是哪一格、现在是什么、点了会怎样；只念格子里的「下一页」分不清九格。
            // 用 clearAndSet 把格子里的 Text 吞掉：只加 contentDescription 的话，可点格会念两遍动作名，
            // 不可点的正中格还会拆成「格子 + 文字」两个焦点。clickable 在它外层，按钮角色与点击不受影响
            .clearAndSetSemantics {
                contentDescription = if (onClick != null) "${position}：${action.label}，点按切换"
                else "${position}：${action.label}，固定"
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(action.label, style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.Medium, color = content)
    }
}

/** 轮换顺序：两个翻页方向挨着，最常用的改法（左右互换、全改下一页）点一两下就到 */
private fun TapAction.next(): TapAction = when (this) {
    TapAction.PREV -> TapAction.NEXT
    TapAction.NEXT -> TapAction.MENU
    TapAction.MENU -> TapAction.NONE
    TapAction.NONE -> TapAction.PREV
}

private val TAP_ZONE_POSITIONS = listOf(
    "左上", "正上", "右上",
    "左侧", "正中", "右侧",
    "左下", "正下", "右下",
)

@Composable
private fun MoreTab(
    settings: ReaderSettings,
    onUpdate: (ReaderSettings) -> Unit,
    textSelectionEnabled: Boolean,
    onTextSelectionChange: (Boolean) -> Unit,
) {
    ReaderGroup("预加载章节") {
        ReaderBlock {
            SegmentedChoice(
                options = PRELOAD_OPTIONS.map { if (it == 0) "关闭" else "$it 章" },
                selectedIndex = PRELOAD_OPTIONS.indexOf(settings.preloadChapters),
                onSelect = { onUpdate(settings.copy(preloadChapters = PRELOAD_OPTIONS[it])) },
            )
        }
    }

    ReaderGroup {
        // 这两个设置一直存在于 ReaderSettings 里，却**从来没有 UI 能改**
        SwitchRow(
            title = "阅读时保持屏幕常亮",
            checked = settings.keepScreenOn,
            onCheckedChange = { onUpdate(settings.copy(keepScreenOn = it)) },
            position = SettingGroupPosition.First,
        )
        SwitchRow(
            title = "页脚显示电量",
            subtitle = "阅读页右下角的电池图标。关掉后仍显示页码、进度和时间",
            checked = settings.showBattery,
            onCheckedChange = { onUpdate(settings.copy(showBattery = it)) },
            position = SettingGroupPosition.Middle,
        )

        // 存储仍在 AppPrefs（不在 ReaderSettings）：它本就存在那儿，搬进 ReaderSettings 要跨
        // DataStore 迁移，弄不好会把已经关掉它的用户静默改回开。这里只是把开关挪到它该在的地方 ——
        // 它是纯粹的阅读页设置，从前却待在全局设置的「书源」分组下。
        SwitchRow(
            title = "长按选字",
            subtitle = if (textSelectionEnabled) {
                "长按正文可选中、复制，或建一条只对这本书生效的净化规则"
            } else {
                "已关闭。翻页时手指停顿久了不会再误触选中"
            },
            checked = textSelectionEnabled,
            onCheckedChange = onTextSelectionChange,
            position = SettingGroupPosition.Last,
        )
    }
}

/**
 * 自定义纸张：调纸色（色相/浓度/明度）与字色深浅。
 *
 * **实时报对比度**，低于 7:1 就明说。不拦着用户配，但得让他知道代价 ——
 * 「墨绿纸配灰字」看着挺雅，读两章眼睛就疼，而用户根本不会把眼疼归因到自己调的色上。
 */
@Composable
private fun CustomPaperEditor(
    theme: ReaderTheme,
    onChange: (ReaderTheme) -> Unit,
    onChangeFinished: () -> Unit,
) {
    val bgHsv = remember(theme.background) { argbToHsv(theme.background) }
    // 字色深浅：0 = 与纸同色（看不见），1 = 纯黑/纯白（最刺眼）。取它在纸色明度上的相对位置
    val textLevel = remember(theme.textColor) { argbToHsv(theme.textColor)[2] }

    fun rebuild(h: Float = bgHsv[0], s: Float = bgHsv[1], v: Float = bgHsv[2], t: Float = textLevel) {
        val bg = hsvToArgb(h, s, v)
        // 字色沿用纸的色相，但压低浓度 —— 完全同色相的浓字会显脏，完全无彩的灰字又跟纸不搭
        val text = hsvToArgb(h, (s * 0.45f).coerceAtMost(0.35f), t)
        onChange(ReaderTheme.custom(bg, text))
    }

    val ratio = ReaderTheme.contrastRatio(theme.background, theme.textColor)

    Spacer(Modifier.height(Dimens.gapM))
    SectionLabel("纸色")
    AppSlider(
        value = bgHsv[0],
        valueRange = 0f..360f,
        onValueChange = { rebuild(h = it) },
        onValueChangeFinished = onChangeFinished,
        label = "纸色色相",
    )
    AppSlider(
        value = bgHsv[1],
        valueRange = 0f..0.25f,
        onValueChange = { rebuild(s = it) },
        onValueChangeFinished = onChangeFinished,
        label = "纸色浓度",
    )
    AppSlider(
        value = bgHsv[2],
        valueRange = 0f..1f,
        onValueChange = { rebuild(v = it) },
        onValueChangeFinished = onChangeFinished,
        label = "纸色明度",
    )

    SectionLabel("字色深浅")
    AppSlider(
        value = textLevel,
        valueRange = 0f..1f,
        onValueChange = { rebuild(t = it) },
        onValueChangeFinished = onChangeFinished,
        label = "字色深浅",
    )

    Text(
        text = "正文对比度 %.1f:1".format(ratio) + when {
            ratio >= 7.0 -> " · 长时间阅读没问题"
            ratio >= 4.5 -> " · 能看清，但读久了会累"
            else -> " · 太低，读起来费劲"
        },
        style = MiuixTheme.textStyles.footnote1,
        color = if (ratio >= 7.0) MiuixTheme.colorScheme.onSurfaceVariantSummary
        else MiuixTheme.colorScheme.error,
        modifier = Modifier.padding(top = Dimens.gapXS),
    )
}

/**
 * 纸张主题选择。
 *
 * **横向可滚动**：从前是固定 Row，4 款刚好铺满一行；现在有 10 款，再塞进定宽的 Row 里
 * 只会把每个色块压扁（自动翻页那排 chip 就是这么被压成竖排的）。
 *
 * **色块底下带名字**：六款浅色纸缩成 48dp 的圆点后，「暖纸/宣纸/牛皮/灰岩」肉眼几乎分不出，
 * 光靠颜色选主题等于抽奖。名字也顺带修好了读屏 —— 从前 contentDescription 是 id，
 * TalkBack 会念出英文的 "paper"。
 */
@Composable
private fun ThemeSwatches(
    settings: ReaderSettings,
    onUpdate: (ReaderSettings) -> Unit,
    /** 左右内缩放在滚动内容里：色板随内容滚到分组卡的边缘，而不是在内缩线上被截断 */
    horizontalPadding: Dp = 0.dp,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = horizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(Dimens.gapM),
    ) {
        ReaderTheme.ALL.forEach { theme ->
            ThemeSwatch(
                theme = theme,
                selected = theme.id == settings.theme.id,
                onClick = { onUpdate(settings.copy(theme = theme)) },
            )
        }
        // 自定义纸张：颜色不在 ReaderTheme.ALL 那张表上，得从当前 settings 里取。
        // 没调过就先给一份暖纸的底，用户点进去再改
        val custom = if (settings.theme.id == ReaderTheme.CUSTOM_ID) settings.theme
        else ReaderTheme.custom(ReaderTheme.PAPER.background, ReaderTheme.PAPER.textColor)
        ThemeSwatch(
            theme = custom,
            selected = settings.theme.id == ReaderTheme.CUSTOM_ID,
            onClick = { onUpdate(settings.copy(theme = custom)) },
        )
    }
}

@Composable
private fun ThemeSwatch(theme: ReaderTheme, selected: Boolean, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimens.gapXS),
    ) {
        Box(
            Modifier
                // 色块 52dp（Dimens.swatch，与设置页色板同尺寸），仍 ≥48dp 触控下限；
                // clip 必须在 selectable 之前 —— 否则按压反馈是方的，会溢出这个圆形色块的边界
                .size(Dimens.swatch)
                .clip(CircleShape)
                .background(Color(theme.background))
                .border(
                    width = if (selected) Dimens.strokeThick else Dimens.strokeThin,
                    color = if (selected) MiuixTheme.colorScheme.primary
                    else MiuixTheme.colorScheme.dividerLine,
                    shape = CircleShape,
                )
                // 整块作为单选目标带上纸张名与选中态：否则读屏聚焦未选中色块只会念"文"，分不清是哪款
                .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
                .semantics { contentDescription = theme.label },
            contentAlignment = Alignment.Center,
        ) {
            // 选中的打勾；没选中的放一个"文"字，直接把这款纸的正文对比度演示出来 ——
            // 光看纸色选不出好不好读
            if (selected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    Modifier.size(Dimens.iconSm),
                    tint = Color(theme.textColor),
                )
            } else {
                Text(
                    "文",
                    color = Color(theme.textColor),
                    style = MiuixTheme.textStyles.body2,
                )
            }
        }
        Text(
            theme.label,
            style = MiuixTheme.textStyles.footnote2,
            color = if (selected) MiuixTheme.colorScheme.primary
            else MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

private val SETTINGS_TABS = listOf("排版", "版式", "翻页", "更多")

/** 设置面板内容区占窗口高度的比例。加上 Tab 与拖拽把手约半屏，上半屏留给正文当实时预览 */
private const val PANEL_HEIGHT_FRACTION = 0.42f

/**
 * 分组卡里的小标题（自定义纸张的「纸色」「字色深浅」）。用次要文字色，与卡外的分组标题同一级 ——
 * 共享的 SectionHeader 自带卡外的那套缩进，放进卡里会对不齐。
 */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MiuixTheme.textStyles.body2,
        fontWeight = FontWeight.Medium,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(top = Dimens.gapM, bottom = Dimens.gapS),
    )
}

/** 自动翻页可选间隔（秒）。太快来不及读，太慢不如自己翻 */
private val AUTO_FLIP_OPTIONS = listOf(5, 10, 15, 20, 30, 45, 60)

/** 预取多少章正文。给多了只是白耗流量：读者不会一口气跳着读十章 */
private val PRELOAD_OPTIONS = listOf(0, 1, 3, 5, 10)
