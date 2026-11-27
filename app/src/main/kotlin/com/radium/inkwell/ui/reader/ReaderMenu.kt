package com.radium.inkwell.ui.reader

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.radium.inkwell.ui.components.blockTouchesBelow
import com.radium.inkwell.ui.components.sheetBottomInset
import com.radium.inkwell.util.UsageLog
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrightnessHigh
import androidx.compose.material.icons.outlined.BrightnessLow
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.text.TextStyle
import com.radium.inkwell.ui.theme.AppTheme
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.layout.BottomSheetDefaults
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.mutableFloatStateOf
import top.yukonga.miuix.kmp.squircle.squircleClip
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.indication
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.PaddingValues
import com.radium.inkwell.ui.components.AppFilterChip
import com.radium.inkwell.ui.components.AppIconButton
import com.radium.inkwell.ui.components.AppDropdownMenu
import com.radium.inkwell.ui.components.AppMenuItem
import com.radium.inkwell.ui.components.AppSlider
import com.radium.inkwell.ui.components.AppTextButton
import androidx.compose.animation.AnimatedVisibility
import com.radium.inkwell.ui.components.SheetPageHeader
import com.radium.inkwell.ui.components.SheetPages
import com.radium.inkwell.ui.components.bottomBarEnter
import com.radium.inkwell.ui.components.bottomBarExit
import com.radium.inkwell.ui.components.scrimEnter
import com.radium.inkwell.ui.components.scrimExit
import com.radium.inkwell.ui.components.topBarEnter
import com.radium.inkwell.ui.components.topBarExit
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clip
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radium.inkwell.ui.components.SwitchRow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.radium.inkwell.ui.components.CardLayer
import com.radium.inkwell.ui.components.ChipRow
import com.radium.inkwell.ui.components.LocalCardLayer
import com.radium.inkwell.ui.components.IconLabelButton
import com.radium.inkwell.ui.components.cardMargin
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.ui.components.SectionHeader
import com.radium.inkwell.ui.components.SettingGroupPosition
import com.radium.inkwell.ui.components.SettingRow
import com.radium.inkwell.ui.components.PrimaryButton
import com.radium.inkwell.ui.components.PickerOption
import com.radium.inkwell.ui.components.SpinnerSettingRow
import com.radium.inkwell.reader.api.BatteryDisplayStyle
import com.radium.inkwell.reader.api.FlipAnimation
import com.radium.inkwell.reader.api.FlipDirection
import com.radium.inkwell.reader.api.ReaderSettings
import com.radium.inkwell.reader.api.ReaderTheme
import com.radium.inkwell.reader.api.TapAction
import com.radium.inkwell.reader.api.TapZones
import com.radium.inkwell.reader.flip.FLIP_SLOP_DP
import com.radium.inkwell.ui.theme.argbToHsv
import com.radium.inkwell.ui.theme.hsvToArgb
import com.radium.inkwell.ui.theme.hsvToColor
import com.radium.inkwell.ui.components.AppColorSlider
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.radium.inkwell.ui.components.SegmentedChoice
import com.radium.inkwell.ui.theme.AppShapes
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.ListView
import top.yukonga.miuix.kmp.icon.extended.Replace
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleSurface
import com.radium.inkwell.ui.components.pageScroll
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.LocalContentColor
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import com.radium.inkwell.ui.components.sinkOnPress
import com.radium.inkwell.data.prefs.BookTocRule
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.icon.extended.VolumeUp

@Composable
internal fun ReaderMenu(
    visible: Boolean,
    chapterHandoff: State<Float>?,
    session: ReaderSessionUi,
    overlay: ReaderOverlayUi,
    pageUi: ReaderPageUi,
    toc: StateFlow<List<TocItem>>,
    tocRule: StateFlow<BookTocRule>,
    onApplyTocRule: suspend (BookTocRule) -> Unit,
    onExit: () -> Unit,
    onGotoChapter: (Int) -> Unit,
    onGotoTocEntry: (TocItem) -> Unit,
    onShowTextSearch: () -> Unit,
    onSeekPercent: (Float) -> Unit,
    onUpdateSettings: (ReaderSettings) -> Unit,
    onPreviewSettings: (ReaderSettings) -> Unit,
    onCommitSettings: () -> Unit,
    onSelectPaper: (String) -> Unit,
    onCustomPaperChange: (ReaderTheme, commit: Boolean) -> Unit,
    onSetTextSelection: (Boolean) -> Unit,
    typography: TypographyActions,
    onSearchSources: () -> Unit,
    onRefreshChapter: () -> Unit,
    onToggleAutoFlip: () -> Unit,
    onReadAloud: () -> Unit,
    speechActive: Boolean,
    onDismiss: () -> Unit,
    onSwipePage: (FlipDirection) -> Unit,
    onReaderSheetOpen: (Boolean) -> Unit,
    /** 录着翻页区的图层（见 ReaderScreen）：菜单的几条栏对它取样做毛玻璃。null 时退回实色栏 */
    pageBackdrop: LayerBackdrop? = null,
) {
    var showToc by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf<SettingsSlider?>(null) }
    var showMore by remember { mutableStateOf(false) }
    val scrollMode = session.settings.flipAnimation == FlipAnimation.SCROLL
    val canToggleAutoFlip = overlay.autoFlipping ||
        (!scrollMode && !speechActive && !pageUi.loading && pageUi.page != null)
    val settingsShown by animateFloatAsState(
        if (dragging == null) 1f else 0f,
        AppTheme.motionScheme.defaultEffectsSpec(),
        label = "readerSettingsShown",
    )

    // 菜单现在常驻组合（为了能播退场动画），所以关菜单时得把面板一起收掉 ——
    // 否则下次呼出菜单，上回没关的目录会自己冒出来
    LaunchedEffect(visible) {
        if (!visible) {
            showToc = false
            showSettings = false
            dragging = null
            showMore = false
        }
    }
    LaunchedEffect(showToc, showSettings) {
        onReaderSheetOpen(showToc || showSettings)
    }

    // 菜单这一层靠**色阶 + 毛玻璃**跟纸面分开，不靠阴影和蒙层：
    // - 从前顶栏、底栏与纸同色，只隔一根发丝线，看着像页面上下多了两行字，而不是盖上来的一层。
    // - 也别回到投影 / 压暗：同色纸上 4~8dp 的投影就是一道脏灰线；压暗正文会把米色纸压成灰绿，
    //   在顶栏底边裂出一道生硬的分界（两条都实测过）。纸书里书签和纸页之间也没有阴影。
    // 两条栏都是比纸深（深色纸上是浅）一档的 HyperOS 毛玻璃（readerGlass）：对正文取样模糊、再叠八成的浮层色。
    // 浮层底色与点「设置」弹出的面板同一个（见 readerOverlayColor）。
    //
    // **两条栏都通栏、不带圆角**（HyperOS 的玻璃栏就是这样）。从前顶栏朝正文一侧是大圆角，底部是两块悬浮的圆角条
    // （进度、操作）四周露出纸色 —— 装上后被指出「图标外面套了个容器」：返回键、四个操作像是各装在一张卡片里；
    // 再加上悬浮条、顶栏、设置面板、面板里的卡片各是一种圆角，「弹出层的圆角不统一」。
    // 现在圆角只留给真正弹出来的那一层（目录、设置面板），两条常驻的栏是贴边的平条。
    val scheme = MiuixTheme.colorScheme
    val barColor = readerOverlayColor()
    // 毛玻璃时栏自己不铺底（底由 readerGlass 画），否则实色把模糊整个盖住
    val barFill = if (pageBackdrop != null) Color.Transparent else barColor
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
            // 顶栏和底部面板挂了 blockTouchesBelow，落在它们身上的触摸到此为止，不会漏到这一层。
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
            val topInsets = readerMenuTopInsets()
            // 上移模式用外层的淡入淡出，整条栏的背景铺到屏幕顶边。
            val topBarMotion = if (chapterHandoff == null) {
                Modifier.animateEnterExit(topBarEnter(), topBarExit())
            } else Modifier
            Column(Modifier.fillMaxSize().graphicsLayer { alpha = settingsShown }) {
                Surface(
                    color = barFill,
                    contentColor = scheme.onSurface,
                    modifier = topBarMotion
                        .readerGlass(pageBackdrop, RectangleShape, barColor)
                        .blockTouchesBelow(),
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .windowInsetsPadding(topInsets)
                            // 与 App 的窄顶栏同高（AppToolbar），左右同样只留 gapXS（48dp 的图标按钮自带一圈可点区）
                            .height(Dimens.toolbarHeight)
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
                                modifier = if (chapterHandoff == null) Modifier else Modifier.graphicsLayer {
                                    alpha = (2f * chapterHandoff.value - 1f).coerceIn(0f, 1f)
                                },
                                style = MiuixTheme.textStyles.footnote1,
                                // 次要文字走纸张主题的摘要色（够 4.5:1），不再手写 0.65 透明度
                                color = scheme.onSurfaceVariantSummary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (overlay.refreshingChapter || overlay.chapterRefreshFailed) {
                                Text(
                                    if (overlay.refreshingChapter) "正在重新获取本章…" else "获取失败，原内容已保留 · 更多中重试",
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = scheme.onSurfaceVariantSummary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        AppIconButton(
                            onClick = onShowTextSearch,
                            enabled = pageUi.page != null && !overlay.changingSource && !overlay.autoChanging,
                        ) {
                            Icon(MiuixIcons.Search, contentDescription = "查找正文")
                        }
                        Box {
                            AppIconButton(onClick = { showMore = true }) {
                                Icon(MiuixIcons.More, contentDescription = "更多阅读操作")
                            }
                            AppDropdownMenu(
                                expanded = showMore,
                                onDismissRequest = { showMore = false },
                                items = buildList {
                                    add(
                                        AppMenuItem(
                                            text = if (overlay.autoFlipping) "停止自动翻页" else "自动翻页",
                                            summary = when {
                                                overlay.autoFlipping -> null
                                                scrollMode -> "滚动模式不可用"
                                                speechActive -> "结束朗读后可用"
                                                else -> null
                                            },
                                            // 只拦「开」：停是随时都得能点的。点正文不会停自动翻页，这里就是唯一的出口 ——
                                            // 从前翻进一章加载中 / 读不出来的，这一项灰掉，自动翻页停不下来
                                            enabled = canToggleAutoFlip,
                                            onClick = onToggleAutoFlip,
                                        ),
                                    )
                                    if (session.isNetBook) {
                                        add(
                                            AppMenuItem(
                                                text = when {
                                                    overlay.refreshingChapter -> "正在重新获取本章…"
                                                    overlay.chapterRefreshFailed -> "重试获取本章"
                                                    else -> "重新获取本章"
                                                },
                                                summary = "从当前来源获取最新内容",
                                                enabled = !overlay.refreshingChapter && !overlay.changingSource &&
                                                    !overlay.autoChanging && !pageUi.loading && pageUi.page != null,
                                                onClick = onRefreshChapter,
                                            ),
                                        )
                                    }
                                },
                            )
                        }
                    }
                }

                Spacer(Modifier.weight(1f))

                // 底栏：一条通栏，进度在上、四个操作在下，一直铺到屏幕底（手势条那一截也是玻璃）
                Surface(
                    color = barFill,
                    contentColor = scheme.onSurface,
                    modifier = Modifier
                        .animateEnterExit(bottomBarEnter(), bottomBarExit())
                        .fillMaxWidth()
                        .readerGlass(pageBackdrop, RectangleShape, barColor)
                        .blockTouchesBelow(),
                ) {
                    Column(
                        Modifier
                            .navigationBarsPadding()
                            .padding(horizontal = Dimens.gapS, vertical = Dimens.gapXS),
                    ) {
                        ChapterProgress(
                            pageUi = pageUi,
                            chapterCount = session.chapterCount,
                            onGotoChapter = onGotoChapter,
                            onSeekPercent = onSeekPercent,
                        )
                        MenuActions(
                            session = session,
                            readAloudEnabled = speechActive || (!pageUi.loading && pageUi.page != null),
                            onShowToc = { showToc = true },
                            onSearchSources = onSearchSources,
                            onReadAloud = onReadAloud,
                            onShowSettings = { showSettings = true },
                        )
                    }
                }
            }
        }
    }

    if (showToc) {
        val tocItems by toc.collectAsStateWithLifecycle()
        val rule by tocRule.collectAsStateWithLifecycle()
        ReaderTocSheet(
            rule = rule,
            toc = tocItems,
            current = pageUi.chapterIndex,
            position = pageUi.tocPosition,
            showCacheState = session.isNetBook,
            onDismiss = { showToc = false },
            // 目录跳章立即关菜单，不等面板退场，以免延迟翻到目标章。
            onSelect = { showToc = false; onGotoTocEntry(it); onDismiss() },
            onApplyRule = onApplyTocRule,
        )
    }

    if (showSettings) {
        // 不压暗正文：面板只占半屏，上面露出来的正文就是排版的实时预览；隔着一层黑看，行距字距勉强看得出，
        // 纸色和亮度根本没法判断。底与菜单的浮层同色（readerOverlayColor，与纸面差一档 —— 同色的话面板上沿等于不存在）。
        // 导航栏高度由面板内容自己垫在滚动末尾（见 TypographyPanel），这里不垫。
        //
        // 手上正拖着哪条滑轨提到这一层：面板底色、拖动条都跟着它变。
        // 拖着字号 / 亮度时面板让开 —— 底色和拖动条淡掉，只剩手上那一条（见 TypographyPanel）。
        // 二级页的标题行（‹ 和页名）不用面板自己的那一行：它得跟着页面一起滑进来（见 SheetPages）
        var page by remember { mutableStateOf(SettingsPage.Main) }
        val overlayColor = readerOverlayColor()
        val handleColor = BottomSheetDefaults.dragHandleColor()
        val textStyles = MiuixTheme.textStyles
        var sheetOpen by remember { mutableStateOf(true) }
        val sheetColor = overlayColor.copy(alpha = overlayColor.alpha * settingsShown)
        // 浮层在独立宿主里组合，显示期间从 State 读取最新设置。
        val currentSession by rememberUpdatedState(session)
        OverlayBottomSheet(
            show = sheetOpen,
            onDismissRequest = { sheetOpen = false },
            onDismissFinished = {
                showSettings = false
                dragging = null
            },
            cornerRadius = AppShapes.radiusPopup,
            insideMargin = DpSize.Zero,
            enableWindowDim = false,
            renderInRootScaffold = false,
            dragHandleColor = handleColor.copy(alpha = handleColor.alpha * settingsShown),
            surface = {
                if (isOverscroll) Modifier.background(sheetColor)
                else Modifier.readerSettingsSurface(pageBackdrop, sheetColor, cornerRadius)
            },
        ) {
            // Overlay 的内容在 Scaffold 的宿主里组合，显式带上阅读主题的颜色。
            MiuixTheme(colors = scheme, textStyles = textStyles) {
                CompositionLocalProvider(
                    LocalContentColor provides scheme.onSurface,
                    LocalCardLayer provides CardLayer(scheme.surfaceContainer, Dimens.gapXL),
                ) {
                    TypographyPanel(
                        page = page,
                        onOpen = { page = it },
                        dragging = dragging,
                        onDragging = { dragging = it },
                        shown = settingsShown,
                        pageBackdrop = pageBackdrop,
                        settings = currentSession.settings,
                        isNetBook = currentSession.isNetBook,
                        onUpdate = onUpdateSettings,
                        onPreview = onPreviewSettings,
                        onCommit = onCommitSettings,
                        onSelectPaper = onSelectPaper,
                        onCustomPaperChange = onCustomPaperChange,
                        textSelectionEnabled = currentSession.textSelectionEnabled,
                        onTextSelectionChange = onSetTextSelection,
                        typography = typography,
                        autoFlipping = overlay.autoFlipping,
                        canToggleAutoFlip = canToggleAutoFlip,
                        onToggleAutoFlip = onToggleAutoFlip,
                    )
                }
            }
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
 * 本地书的换源保留占位并禁用，位置不跳。
 */
@Composable
private fun MenuActions(
    session: ReaderSessionUi,
    readAloudEnabled: Boolean,
    onShowToc: () -> Unit,
    onSearchSources: () -> Unit,
    onReadAloud: () -> Unit,
    onShowSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        BarAction(
            icon = MiuixIcons.ListView,
            label = "目录",
            onClick = { UsageLog.event("ui.click", "action" to "reader.toc"); onShowToc() },
            modifier = Modifier.weight(1f),
        )
        BarAction(
            icon = MiuixIcons.Replace,
            label = "换源",
            onClick = { UsageLog.event("ui.click", "action" to "reader.source"); onSearchSources() },
            enabled = session.isNetBook,
            modifier = Modifier.weight(1f),
        )
        BarAction(
            icon = MiuixIcons.VolumeUp,
            label = "朗读",
            onClick = { UsageLog.event("ui.click", "action" to "reader.read_aloud"); onReadAloud() },
            enabled = readAloudEnabled,
            modifier = Modifier.weight(1f),
        )
        BarAction(
            icon = MiuixIcons.Tune,
            label = "设置",
            onClick = { UsageLog.event("ui.click", "action" to "reader.settings"); onShowSettings() },
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 操作条上的一个动作：图标在上、字在下（[IconLabelButton]，多选态的底部操作条也是它）。四格均分整条宽，左右不再加内边距。
 *
 * 四个图标都取 Miuix 图标集（目录 ListView、换源 Replace、朗读 VolumeUp、设置 Tune）：
 * 同一排里混两套线宽一眼就看得出，所以要换就整排一起换。
 */
@Composable
private fun BarAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    IconLabelButton(
        icon = icon,
        label = label,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        contentPadding = PaddingValues(vertical = Dimens.gapXS),
    )
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

/**
 * 阅读设置面板：一级页 + 三个二级页（排版 / 翻页 / 更多），**全在这一个面板里**。
 *
 * - **一级页**放最常调、要边看边调的：亮度、字号、行距、纸张、字体，一屏放下；底下三个入口进二级页（见 [MainPage]）。
 *   从前是四个页签：页签只是把设置分成四堆，最常调的和一年调一次的都得先挑对页签；「排版」「版式」两个近义词更是猜不出哪页放什么。
 * - **二级页**整页从右推进来（[SheetPages]，和整页转场同一套几何，侧滑返回跟手），第一行是它自己的标题行：
 *   左边 ‹ 返回、中间页名（[SheetPageHeader]），系统返回键也是先回一级。
 *   二级页还在这个面板里、正文照样露在上面 —— 段距、边距这些细节也是边调边看，不用进单独的设置页。
 * - **面板定高**：各页铺满同一个定高的容器，二级页 = 标题行 + 内容，进出二级页面板总高不变、不跳。
 * - **拖滑块时面板让开**（[dragging]）：手按在字号或亮度滑轨上拖起来，面板底色、拖动条和别的设置项一起淡掉，
 *   只剩手上那一条浮在原位，整屏正文都是预览；松手回来。HyperOS 控制中心拖亮度就是这样。
 *
 * 层次靠三档色阶拉开（见 ReaderThemeScope）：面板本身与纸面差一档（[readerOverlayColor]），
 * 设置项收进分组卡（[ReaderGroup]，[readerCardColor]），卡里的分段按钮未选中再差一档、选中的实心填强调色。
 *
 * **只占半屏、不压暗正文**：调排版要看得见效果。从前面板固定 560dp 高、底下还压着一层黑，
 * 改行距字距时正文被盖住大半，纸色和亮度隔着蒙层更是没法判断。现在上面露出来的正文就是实时预览。
 */
@Composable
private fun TypographyPanel(
    page: SettingsPage,
    onOpen: (SettingsPage) -> Unit,
    dragging: SettingsSlider?,
    onDragging: (SettingsSlider?) -> Unit,
    shown: Float,
    pageBackdrop: LayerBackdrop?,
    settings: ReaderSettings,
    isNetBook: Boolean,
    onUpdate: (ReaderSettings) -> Unit,
    onPreview: (ReaderSettings) -> Unit,
    onCommit: () -> Unit,
    onSelectPaper: (String) -> Unit,
    onCustomPaperChange: (ReaderTheme, commit: Boolean) -> Unit,
    textSelectionEnabled: Boolean,
    onTextSelectionChange: (Boolean) -> Unit,
    typography: TypographyActions,
    autoFlipping: Boolean,
    canToggleAutoFlip: Boolean,
    onToggleAutoFlip: () -> Unit,
) {
    val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    // 各页共用定高面板，切换时正文预览和底部页签留在原位。
    val pageHeight = (windowHeight * PANEL_HEIGHT_FRACTION).coerceAtMost(Dimens.sheetListMaxHeight) + Dimens.touchTarget
    val back = { onOpen(SettingsPage.Main) }

    val bottomInset = sheetBottomInset()
    Column(
        Modifier
            .fillMaxWidth()
            .height(pageHeight + bottomInset),
    ) {
        SheetPages(
            page = page,
            root = SettingsPage.Main,
            onBack = back,
            background = readerOverlayColor(),
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) { current ->
            Column(Modifier.fillMaxSize()) {
                current.title?.let { title ->
                    Box(Modifier.fillMaxWidth()) {
                        SheetPageHeader(title, onBack = back)
                        if (current == SettingsPage.Typeset) {
                            AppTextButton(
                                "恢复默认",
                                onClick = { typography.onAdjust { resetTypography() } },
                                modifier = Modifier.align(Alignment.CenterEnd),
                            )
                        }
                    }
                }
                val scroll = rememberScrollState()
                Column(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            val fadeHeight = Dimens.gapL.toPx().coerceAtMost(size.height / 2f)
                            if (fadeHeight > 0f && scroll.canScrollBackward) {
                                drawRect(
                                    brush = Brush.verticalGradient(
                                        listOf(Color.Transparent, Color.Black), endY = fadeHeight,
                                    ),
                                    size = Size(size.width, fadeHeight),
                                    blendMode = BlendMode.DstIn,
                                )
                            }
                            if (fadeHeight > 0f && scroll.canScrollForward) {
                                val startY = size.height - fadeHeight
                                drawRect(
                                    brush = Brush.verticalGradient(
                                        listOf(Color.Black, Color.Transparent), startY = startY, endY = size.height,
                                    ),
                                    topLeft = Offset(0f, startY),
                                    size = Size(size.width, fadeHeight),
                                    blendMode = BlendMode.DstIn,
                                )
                            }
                        }
                        .pageScroll()
                        .verticalScroll(scroll, overscrollEffect = null)
                        .padding(horizontal = cardMargin())
                        .padding(top = if (current.title != null) Dimens.gapM else 0.dp, bottom = Dimens.gapM),
                    verticalArrangement = Arrangement.spacedBy(if (current == SettingsPage.Main) Dimens.gapM else Dimens.gapL),
                ) {
                    when (current) {
                        SettingsPage.Main -> MainPage(
                            settings = settings,
                            onUpdate = onUpdate,
                            onPreview = onPreview,
                            onCommit = onCommit,
                            onSelectPaper = onSelectPaper,
                            onCustomPaperChange = onCustomPaperChange,
                            typography = typography,
                            dragging = dragging,
                            onDragging = onDragging,
                            shown = shown,
                            pageBackdrop = pageBackdrop,
                        )
                        SettingsPage.Typeset -> TypesetTab(settings, typography)
                        SettingsPage.Flip -> FlipTab(settings, onUpdate, autoFlipping, canToggleAutoFlip, onToggleAutoFlip)
                        SettingsPage.More -> MoreTab(
                            settings, isNetBook, onUpdate, typography.onAdjust,
                            textSelectionEnabled, onTextSelectionChange,
                        )
                    }
                }
            }
        }
        SettingsTabs(
            page = page,
            onOpen = onOpen,
            shown = shown,
            enabled = dragging == null,
            modifier = Modifier.padding(
                start = cardMargin(), end = cardMargin(), top = Dimens.gapXS,
                bottom = Dimens.gapS + bottomInset,
            ),
        )
    }
}

/**
 * 设置面板里的一张分组卡：标题在卡外，用次要文字色（不跟选中态抢强调色）；内容在卡里（底色见 [readerCardColor]）。
 * 卡内的行自己带左右内缩（[ReaderRow] / [ReaderBlock] / 分组 [SwitchRow] 都是 listHorizontal），
 * 卡本身只给上下一点留白。
 *
 * @param trailing 标题行尾的文字按钮（字体的「导入」、排版方案的「存为方案」）。动作放这儿，卡里只剩选项：
 *   从前「导入字体」是 chip 条末尾的一个 chip，和「默认」「衬线」长得一模一样，看着像一款叫「导入字体」的字。
 *   有它时标题行垫到触控高度（同 SectionHeader），按钮的字右边对齐卡里的字
 * @param color 卡片底色。只有一级页会换：拖着字号 / 亮度时卡片淡掉（见 [MainPage]）
 */
@Composable
internal fun ReaderGroup(
    title: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    color: Color = readerCardColor(),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column {
        if (title != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(
                        start = Dimens.listHorizontal,
                        end = if (trailing != null) Dimens.listHorizontal - Dimens.gapM else Dimens.listHorizontal,
                        bottom = if (trailing != null) 0.dp else Dimens.gapS,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title,
                    style = MiuixTheme.textStyles.body2,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() },
                )
                trailing?.invoke(this)
            }
        }
        // Miuix 的卡片（squircle 圆角，和面板、按钮、分段同一种弯法）。从前是 clip(RoundedCornerShape)，
        // 普通圆弧夹在一堆 squircle 中间，几种圆角放一起就显得不统一
        Card(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = AppShapes.radiusLarge,
            insideMargin = PaddingValues(vertical = Dimens.gapXS),
            colors = CardDefaults.defaultColors(color = color),
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
 * 一级页：最常调、要边看边调的五样 + 进二级页的三个入口，一屏放下。
 *
 * - 第一张卡：亮度、字号两条**拉满宽的滑轨**（[BrightnessRow]、[FontSizeRow]）+ 行距。
 * - 第二张卡：纸张色板 + 字体条，都是一点就换（字体条见 [FontSection]）。
 * - 入口：排版 › 翻页 › 更多 ›（[PageLinks]）。
 *
 * [dragging] 不为 null（手正拖着某条滑轨）时，别的一概淡掉、卡片底色也淡掉，只有那一行自己铺一块卡片底、浮在原位。
 * [onPreview] / [onCommit]：连续拖动的滑块拖着时只预览、松手才落库（见 ReaderViewModel.previewSettings）；点一下就定的项走 [onUpdate]。
 */
@Composable
private fun MainPage(
    settings: ReaderSettings,
    onUpdate: (ReaderSettings) -> Unit,
    onPreview: (ReaderSettings) -> Unit,
    onCommit: () -> Unit,
    onSelectPaper: (String) -> Unit,
    onCustomPaperChange: (ReaderTheme, commit: Boolean) -> Unit,
    typography: TypographyActions,
    dragging: SettingsSlider?,
    onDragging: (SettingsSlider?) -> Unit,
    shown: Float,
    pageBackdrop: LayerBackdrop?,
) {
    // 松手后 dragging 立刻变回 null，面板却还在淡回来：留住刚才拖的是哪一条，淡回来的这段它别跟着闪没
    var active by remember { mutableStateOf<SettingsSlider?>(null) }
    if (dragging != null && active != dragging) active = dragging
    val card = readerCardColor()
    ReaderGroup(color = card.copy(alpha = card.alpha * shown)) {
        ReaderSettingsSlider(pageBackdrop, card, shown, active == SettingsSlider.Brightness) {
            BrightnessRow(
                settings = settings,
                onPreview = onPreview,
                onCommit = onCommit,
                onDragging = { onDragging(if (it) SettingsSlider.Brightness else null) },
            )
        }
        ReaderSettingsSlider(pageBackdrop, card, shown, active == SettingsSlider.FontSize) {
            FontSizeRow(
                settings = settings,
                onAdjust = typography.onAdjust,
                onPreview = onPreview,
                onCommit = onCommit,
                onDragging = { onDragging(if (it) SettingsSlider.FontSize else null) },
            )
        }
        Box(Modifier.alpha(shown)) {
            SegmentRow("行距", LINE_SPACING_OPTIONS, settings.lineSpacingMult, 0.001f) {
                typography.onAdjust { copy(lineSpacingMult = it) }
            }
        }
    }

    // 纸张和字体同一张卡：都是「点一下就换、当场看效果」的，不用两个小标题各占一行
    Box(Modifier.alpha(shown)) {
        ReaderGroup {
            Column(Modifier.padding(top = Dimens.gapXS)) {
                Text(
                    "纸色",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = Dimens.listHorizontal),
                )
                ThemeSwatches(settings, onSelectPaper, horizontalPadding = Dimens.listHorizontal)
                // 选中自定义才展开调色 —— 平时它是一整块滑块，占掉半个面板
                if (settings.theme.id == ReaderTheme.CUSTOM_ID) {
                    Column(Modifier.padding(horizontal = Dimens.listHorizontal)) {
                        CustomPaperEditor(
                            theme = settings.theme,
                            onChange = onCustomPaperChange,
                        )
                    }
                }
                HorizontalDivider(Modifier.padding(horizontal = Dimens.listHorizontal, vertical = Dimens.gapXS))
                FontSection(
                    settings = settings,
                    actions = typography,
                    onSelect = { onUpdate(settings.copy(fontId = it)) },
                )
            }
        }
    }

}

/**
 * 亮度：小太阳 · 滑轨 · 大太阳 · 「系统」。
 *
 * - 两头的太阳就是标签（不再占一列 80dp 的「亮度」），滑轨因此拉满整行，同样一拖调得更细。图标只是标签，不可点。
 * - 跟随系统时滑轨照样能拖，一拖就接管（「系统」自动熄掉）；从前跟随系统时滑轨是禁用的，得先点掉「系统」才拖得动，
 *   而禁用的滑轨看着和能拖的差不多，拖了没反应。跟随系统时滑轨换成禁用色，表示它此刻不管事，
 *   位置停在系统此刻的亮度上（[systemBrightnessLevel]）—— 一拖从那儿接手，不会先跳到一半亮度。
 * - 再点「系统」切回手动时回到这次面板里最后拖到的位置。
 */
@Composable
private fun BrightnessRow(
    settings: ReaderSettings,
    onPreview: (ReaderSettings) -> Unit,
    onCommit: () -> Unit,
    onDragging: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val following = settings.brightnessOverride == null
    var systemLevel by remember(context) { mutableFloatStateOf(systemBrightnessLevel(context) ?: HALF_BRIGHTNESS) }
    DisposableEffect(context, following) {
        if (!following) return@DisposableEffect onDispose { }
        systemBrightnessLevel(context)?.let { systemLevel = it }
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                systemBrightnessLevel(context)?.let { systemLevel = it }
            }
        }
        val resolver = context.contentResolver
        resolver.registerContentObserver(Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS), false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    var manual by remember { mutableFloatStateOf(settings.brightnessOverride ?: systemLevel) }
    val scheme = MiuixTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.listHorizontal, vertical = Dimens.gapXS),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.gapS),
    ) {
        Icon(Icons.Outlined.BrightnessLow, contentDescription = null, modifier = Modifier.size(Dimens.iconMd))
        AppSlider(
            value = settings.brightnessOverride ?: systemLevel,
            onValueChange = { v ->
                manual = v.coerceIn(MIN_BRIGHTNESS, 1f)
                onPreview(settings.copy(brightnessOverride = manual))
            },
            onValueChangeFinished = onCommit,
            label = "亮度",
            activeColor = if (following) scheme.disabledPrimarySlider else null,
            inactiveColor = if (following) scheme.disabledSecondary else null,
            modifier = Modifier
                .weight(1f)
                .reportDragging(onDragging),
        )
        Icon(Icons.Outlined.BrightnessHigh, contentDescription = null, modifier = Modifier.size(Dimens.iconMd))
        AppFilterChip(
            selected = following,
            onClick = {
                // 先解除窗口亮度覆盖，再保存，和拖动亮度走同一条预览路径。
                onPreview(settings.copy(brightnessOverride = if (following) manual else null))
                onCommit()
            },
            label = "系统",
            solidSelection = true,
        )
    }
}

/**
 * 字号：小 A · 分档滑轨 · 大 A · 当前字号。
 *
 * 从 16 调到 24 一拖就到（每过一档轻震一下，旁边的数字跟着变），不用连点八下；要微调就点两头的 A，一次 ±1，
 * 到头（[ReaderSettings.FONT_SIZE_MIN] / [ReaderSettings.FONT_SIZE_MAX]）的那一头变灰。
 * 拖着时每过一档预览一次（正文重排一次），松手落盘 —— 和亮度滑轨一样不在拖动中写 DataStore。
 * 「A」读屏只会念成「A」，两头补上说清用途的名字。
 */
@Composable
private fun FontSizeRow(
    settings: ReaderSettings,
    onAdjust: (ReaderSettings.() -> ReaderSettings) -> Unit,
    onPreview: (ReaderSettings) -> Unit,
    onCommit: () -> Unit,
    onDragging: (Boolean) -> Unit,
) {
    val min = ReaderSettings.FONT_SIZE_MIN
    val max = ReaderSettings.FONT_SIZE_MAX
    val step = ReaderSettings.FONT_SIZE_STEP
    var pendingSize by remember { mutableStateOf<Float?>(null) }
    val size = pendingSize ?: settings.fontSizeSp
    val previewSize by rememberUpdatedState<(Float) -> Unit> { sp ->
        onPreview(settings.copy(fontSizeSp = sp))
    }
    LaunchedEffect(Unit) {
        // 滑杆立即跟手。分页合并快速经过的档位，松手时再同步提交最后一档。
        snapshotFlow { pendingSize }.filterNotNull().collect { sp ->
            previewSize(sp)
            delay(FONT_PREVIEW_INTERVAL_MS)
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.listHorizontal, vertical = Dimens.gapXS),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.gapS),
    ) {
        FontSizeEnd(
            text = "A",
            style = MiuixTheme.textStyles.footnote1,
            description = "字号减小",
            enabled = size > min,
            onClick = { onAdjust { copy(fontSizeSp = (fontSizeSp - step).coerceAtLeast(min)) } },
        )
        AppSlider(
            value = size,
            valueRange = min..max,
            steps = ((max - min) / step).toInt() - 1,
            onValueChange = { v ->
                pendingSize = (v / step).roundToInt() * step
            },
            onValueChangeFinished = {
                pendingSize?.let(previewSize)
                onCommit()
                pendingSize = null
            },
            label = "字号",
            hapticEffect = SliderDefaults.SliderHapticEffect.Step,
            modifier = Modifier
                .weight(1f)
                .reportDragging(onDragging),
        )
        FontSizeEnd(
            text = "A",
            style = MiuixTheme.textStyles.title3,
            description = "字号增大",
            enabled = size < max,
            onClick = { onAdjust { copy(fontSizeSp = (fontSizeSp + step).coerceAtMost(max)) } },
        )
        val numberStyle = MiuixTheme.textStyles.body1.copy(fontFeatureSettings = "tnum")
        // 数字栏预留两位整数和一位小数，跨整数档时轨道宽度也保持稳定。
        Box(Modifier.clearAndSetSemantics { }, contentAlignment = Alignment.CenterEnd) {
            Text("00.0", modifier = Modifier.alpha(0f), style = numberStyle, fontWeight = FontWeight.Medium)
            Text(size.toString().removeSuffix(".0"), style = numberStyle, fontWeight = FontWeight.Medium)
        }
    }
}

private const val FONT_PREVIEW_INTERVAL_MS = 48L

/** 排版微调按钮：带读屏标签，并保留完整触控区域。 */
@Composable
private fun FontSizeEnd(
    text: String,
    style: TextStyle,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val color = MiuixTheme.colorScheme.onSurface
    Box(
        Modifier
            .size(Dimens.touchTarget)
            .squircleClip(AppShapes.radiusMedium)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = style,
            fontWeight = FontWeight.Medium,
            color = if (enabled) color else color.copy(alpha = color.alpha * DISABLED_CONTENT_ALPHA),
        )
    }
}

/**
 * 进二级页的三个入口：一排三块，各写着页名和 ›。和设置页「点了进下一层」的行同一个意思（行尾 ›），
 * 只是并排成一行 —— 一级页要一屏放下，三行入口太占地方。
 */
@Composable
private fun SettingsTabs(
    page: SettingsPage,
    onOpen: (SettingsPage) -> Unit,
    shown: Float,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val pages = SettingsPage.entries
    SegmentedChoice(
        options = pages.map { it.title ?: "常用" },
        selectedIndex = page.ordinal,
        onSelect = { onOpen(pages[it]) },
        modifier = modifier.alpha(shown),
        enabled = enabled,
    )
}

/**
 * 手指按在这条滑轨上、**真拖起来**（过了 touch slop）时报 true，抬手报 false；只点一下不报 —— 点按不该让整个面板闪一下。
 * 只在 Initial 阶段旁观、不消费，滑轨自己的拖动照常。被掐（面板收起）时也报一次 false，别把面板留在「让开」的样子。
 */
@Composable
private fun Modifier.reportDragging(onDragging: (Boolean) -> Unit): Modifier {
    val latest by rememberUpdatedState(onDragging)
    return pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var dragging = false
            try {
                while (true) {
                    val change = awaitPointerEvent(PointerEventPass.Initial).changes
                        .firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    if (!dragging && (change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                        dragging = true
                        latest(true)
                    }
                }
            } finally {
                if (dragging) latest(false)
            }
        }
    }
}

/**
 * 排版页（二级）：段距、字距、缩进、对齐，以及标题与边距。都是「调一次就很少再动」的细节，
 * 从一级页挪出来，让一级页一屏就能放下字号、字体和纸色。排版方案在这页最上面（见 TypographyPanel）。
 */
@Composable
private fun TypesetTab(
    settings: ReaderSettings,
    typography: TypographyActions,
) {
    val onAdjust = typography.onAdjust
    var schemesExpanded by rememberSaveable { mutableStateOf(false) }
    val currentScheme = typography.schemes.firstOrNull { it.matches(settings) }
    ReaderGroup {
        ReaderRow("排版范围", slots = 2) {
            SegmentedChoice(
                options = listOf("通用", "仅本书"),
                selectedIndex = if (typography.bookLayoutEnabled) 1 else 0,
                onSelect = { typography.onSetBookLayoutEnabled(it == 1) },
            )
        }
    }
    ReaderGroup {
        SettingRow(
            title = "排版方案",
            value = currentScheme?.name,
            onClick = { schemesExpanded = !schemesExpanded },
            position = SettingGroupPosition.Alone,
        )
    }
    if (schemesExpanded) LayoutSchemeSection(settings, typography)

    ReaderGroup("正文") {
        LayoutNumberRow(
            "正文字号", settings, onAdjust, ReaderSettings::fontSizeSp,
            ReaderSettings.FONT_SIZE_MIN..ReaderSettings.FONT_SIZE_MAX, ReaderSettings.FONT_SIZE_STEP, "sp", 1,
        ) { copy(fontSizeSp = it) }

        val fileFont = ReaderSettings.isFileFont(settings.fontId)
        val weights = if (fileFont) {
            listOf(ReaderSettings.FONT_WEIGHT_NORMAL, ReaderSettings.FONT_WEIGHT_BOLD)
        } else {
            ReaderSettings.BODY_FONT_WEIGHTS.sorted()
        }
        ReaderRow("正文字重", slots = weights.size) {
            SegmentedChoice(
                options = if (fileFont) listOf("常规", "偏粗") else listOf("偏细", "常规", "偏粗"),
                selectedIndex = weights.indexOf(settings.bodyFontWeight).takeIf { it >= 0 }
                    ?: weights.indexOf(ReaderSettings.FONT_WEIGHT_NORMAL),
                onSelect = { index -> onAdjust { copy(bodyFontWeight = weights[index]) } },
            )
        }
        LayoutNumberRow("行距", settings, onAdjust, ReaderSettings::lineSpacingMult, 1.2f..2.2f, 0.05f, "倍", 2) {
            copy(lineSpacingMult = it)
        }
        LayoutNumberRow("字距", settings, onAdjust, ReaderSettings::letterSpacingEm, 0f..0.15f, 0.01f, "em", 2) {
            copy(letterSpacingEm = it)
        }
    }

    ReaderGroup("段落") {
        SegmentRow("段距", PARA_SPACING_OPTIONS, settings.paragraphSpacingEm, 0.05f) {
            onAdjust { copy(paragraphSpacingEm = it) }
        }
        SegmentRow("首行缩进", INDENT_OPTIONS, settings.firstLineIndentEm, 0.05f) {
            onAdjust { copy(firstLineIndentEm = it) }
        }
        SwitchRow(
            title = "两端对齐",
            subtitle = "正文按两侧边距对齐，段落末行自然留白",
            checked = settings.justify,
            onCheckedChange = { onAdjust { copy(justify = it) } },
            position = SettingGroupPosition.Last,
        )
    }

    ReaderGroup("页面边距") {
        LayoutNumberRow("头部留白", settings, onAdjust, ReaderSettings::effectiveHeaderTopSpacingDp, -48f..48f, 1f, "dp") {
            copy(headerTopSpacingDp = it)
        }
        LayoutNumberRow("下边距", settings, onAdjust, ReaderSettings::marginVerticalDp, 0f..48f, 1f, "dp") {
            copy(marginVerticalDp = it)
        }
        LayoutNumberRow("左右边距", settings, onAdjust, ReaderSettings::marginHorizontalDp, 8f..48f, 1f, "dp") {
            copy(marginHorizontalDp = it)
        }
        ReaderBlock {
            Text(
                "关闭页眉和系统状态栏时，负的头部留白可能使摄像头挖孔遮住正文。",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }

    ReaderGroup("章标题") {
        SegmentRow("字号", TITLE_SCALE_OPTIONS, settings.titleScale, 0.05f) {
            onAdjust { copy(titleScale = it) }
        }
        SegmentRow("上间距", TITLE_TOP_OPTIONS, settings.effectiveTitleTopSpacingDp, 1f) {
            onAdjust { copy(titleTopSpacingDp = it) }
        }
    }

}

internal fun ReaderSettings.resetTypography(): ReaderSettings {
    val defaults = ReaderSettings()
    return copy(
        fontSizeSp = defaults.fontSizeSp,
        bodyFontWeight = defaults.bodyFontWeight,
        lineSpacingMult = defaults.lineSpacingMult,
        paragraphSpacingEm = defaults.paragraphSpacingEm,
        firstLineIndentEm = defaults.firstLineIndentEm,
        justify = defaults.justify,
        titleScale = defaults.titleScale,
        letterSpacingEm = defaults.letterSpacingEm,
        headerTopSpacingDp = defaults.headerTopSpacingDp,
        titleTopSpacingDp = defaults.titleTopSpacingDp,
        marginVerticalDp = defaults.marginVerticalDp,
        marginHorizontalDp = defaults.marginHorizontalDp,
    )
}

@Composable
private fun LayoutNumberRow(
    label: String,
    settings: ReaderSettings,
    onAdjust: (ReaderSettings.() -> ReaderSettings) -> Unit,
    read: (ReaderSettings) -> Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    unit: String,
    decimals: Int = 0,
    write: ReaderSettings.(Float) -> ReaderSettings,
) {
    val value = read(settings)
    fun adjust(delta: Float) = onAdjust {
        val next = ((read(this) + delta) * 100).roundToInt() / 100f
        write(next.coerceIn(range.start, range.endInclusive))
    }
    ReaderRow(label, slots = 4) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val style = MiuixTheme.textStyles.body1
            FontSizeEnd("−", style, "减小$label", value > range.start) { adjust(-step) }
            Text(
                text = String.format(LocalLocale.current.platformLocale, "%.${decimals}f %s", value, unit),
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                style = style,
            )
            FontSizeEnd("+", style, "增大$label", value < range.endInclusive) { adjust(step) }
        }
    }
}

@Composable
private fun FlipTab(
    settings: ReaderSettings,
    onUpdate: (ReaderSettings) -> Unit,
    autoFlipping: Boolean,
    canToggleAutoFlip: Boolean,
    onToggleAutoFlip: () -> Unit,
) {
    val scrollMode = settings.flipAnimation == FlipAnimation.SCROLL
    var zonesExpanded by rememberSaveable { mutableStateOf(false) }
    ReaderGroup("翻页方式") {
        ReaderBlock {
            SegmentedChoice(
                options = FlipAnimation.entries.map { it.label },
                selectedIndex = FlipAnimation.entries.indexOf(settings.flipAnimation),
                onSelect = { onUpdate(settings.copy(flipAnimation = FlipAnimation.entries[it])) },
            )
        }
    }

    ReaderGroup("操作方式") {
        if (scrollMode) {
            SwitchRow(
                title = "点击翻页",
                subtitle = "打开后按下面的点击区域翻页；默认关，点击只唤出菜单",
                checked = settings.scrollClickToFlip,
                onCheckedChange = { onUpdate(settings.copy(scrollClickToFlip = it)) },
                position = SettingGroupPosition.Alone,
            )
        } else {
            SettingRow(
                title = "点击区域",
                value = TapZones.Preset.entries.firstOrNull { it.zones == settings.tapZones }?.label ?: "自定义",
                onClick = { zonesExpanded = !zonesExpanded },
                position = SettingGroupPosition.First,
            )
            if (zonesExpanded) {
                ReaderBlock { TapZoneEditor(settings.tapZones) { onUpdate(settings.copy(tapZones = it)) } }
            }
            SwitchRow(
                title = "音量键翻页",
                checked = settings.volumeKeyFlip,
                onCheckedChange = { onUpdate(settings.copy(volumeKeyFlip = it)) },
                position = SettingGroupPosition.Middle,
            )
            SwitchRow(
                title = "翻页震动",
                subtitle = "翻过一页时轻震一下。连续翻页会一直震，默认关",
                checked = settings.flipHaptic,
                onCheckedChange = { onUpdate(settings.copy(flipHaptic = it)) },
                position = SettingGroupPosition.Last,
            )
        }
    }

    if (scrollMode && settings.scrollClickToFlip) {
        ReaderGroup("点击区域") {
            ReaderBlock { TapZoneEditor(settings.tapZones) { onUpdate(settings.copy(tapZones = it)) } }
        }
    }

    if (!scrollMode || autoFlipping) {
        ReaderGroup("自动翻页") {
            if (!scrollMode) {
                ReaderBlock {
                    Text("翻页间隔", style = MiuixTheme.textStyles.body2)
                }
                ChipRow(
                    options = AUTO_FLIP_OPTIONS.map { "${it} 秒" },
                    selectedIndex = AUTO_FLIP_OPTIONS.indexOf(settings.autoFlipSeconds),
                    onSelect = { onUpdate(settings.copy(autoFlipSeconds = AUTO_FLIP_OPTIONS[it])) },
                    contentPadding = PaddingValues(horizontal = Dimens.listHorizontal),
                    solidSelection = true,
                )
            }
            ReaderBlock {
                PrimaryButton(
                    text = if (autoFlipping) "停止自动翻页" else "开始自动翻页",
                    onClick = onToggleAutoFlip,
                    enabled = canToggleAutoFlip,
                    modifier = Modifier.fillMaxWidth(),
                )
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
            solidSelection = true,
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
            // 和分段按钮的浮块同一档圆角、同一种 squircle 弯法
            .squircleSurface(container, AppShapes.radiusSmall)
            .then(
                if (action == TapAction.NONE) {
                    Modifier.squircleBorder(Dimens.strokeThin, scheme.dividerLine, AppShapes.radiusSmall)
                } else {
                    Modifier
                }
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
    isNetBook: Boolean,
    onUpdate: (ReaderSettings) -> Unit,
    onAdjust: (ReaderSettings.() -> ReaderSettings) -> Unit,
    textSelectionEnabled: Boolean,
    onTextSelectionChange: (Boolean) -> Unit,
) {
    ReaderGroup("性能") {
        SwitchRow(
            title = "轻量显示",
            subtitle = "关闭阅读控件实时模糊，仿真翻页临时使用覆盖效果",
            checked = settings.lightweightEffects,
            onCheckedChange = { onUpdate(settings.copy(lightweightEffects = it)) },
            position = SettingGroupPosition.Alone,
        )
    }
    ReaderGroup("显示") {
        SwitchRow(
            title = "阅读时显示系统状态栏",
            checked = settings.showStatusBar,
            onCheckedChange = { onUpdate(settings.copy(showStatusBar = it)) },
            position = SettingGroupPosition.First,
        )
        SwitchRow(
            title = "显示页眉",
            subtitle = "显示当前章节名",
            checked = settings.showHeader,
            onCheckedChange = { onUpdate(settings.copy(showHeader = it)) },
            position = SettingGroupPosition.Middle,
        )
        ReaderRow("页眉位置", slots = 4) {
            SegmentedChoice(
                options = listOf("正文上方", "状态栏区域"),
                selectedIndex = if (settings.raiseHeader) 1 else 0,
                onSelect = { index -> onAdjust { copy(raiseHeader = index == 1) } },
            )
        }
        if (settings.raiseHeader && settings.showStatusBar) {
            ReaderBlock {
                Text(
                    "隐藏系统状态栏后，页眉会移到状态栏区域。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        SwitchRow(
            title = "显示页脚",
            subtitle = if (settings.showFooter) "正文下方的页码、进度和时间" else "已隐藏，正文向下多排一行左右",
            checked = settings.showFooter,
            onCheckedChange = { onUpdate(settings.copy(showFooter = it)) },
            position = SettingGroupPosition.Middle,
        )
        if (settings.showFooter) {
            SwitchRow(
                title = "页脚显示电量",
                checked = settings.showBattery,
                onCheckedChange = { onUpdate(settings.copy(showBattery = it)) },
                position = SettingGroupPosition.Middle,
            )
            if (settings.showBattery) {
                SpinnerSettingRow(
                    title = "电量样式",
                    options = BatteryDisplayStyle.entries.map { PickerOption(it.name, it.label) },
                    selectedId = settings.batteryDisplayStyle.name,
                    onSelect = { onUpdate(settings.copy(batteryDisplayStyle = BatteryDisplayStyle.valueOf(it.id))) },
                    position = SettingGroupPosition.Middle,
                    optionIcon = { option, modifier ->
                        val scheme = MiuixTheme.colorScheme
                        Box(
                            modifier.widthIn(min = Dimens.readerBatteryPreviewMinWidth).clearAndSetSemantics {},
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            BatteryIndicator(
                                level = 75,
                                style = BatteryDisplayStyle.valueOf(option.id),
                                color = scheme.onSurfaceContainer,
                                background = scheme.surfaceContainer,
                            )
                        }
                    },
                )
            }
        }
        SwitchRow(
            title = "阅读时保持屏幕常亮",
            checked = settings.keepScreenOn,
            onCheckedChange = { onUpdate(settings.copy(keepScreenOn = it)) },
            position = SettingGroupPosition.Last,
        )
    }

    ReaderGroup("操作手势") {
        SwitchRow(
            title = "长按选字",
            subtitle = if (textSelectionEnabled) {
                "长按正文后可拖动两端手柄，精确选择要复制或替换的文字"
            } else {
                "已关闭。翻页时手指停顿久了不会再误触选中"
            },
            checked = textSelectionEnabled,
            onCheckedChange = onTextSelectionChange,
            position = SettingGroupPosition.First,
        )
        SwitchRow(
            title = "双指缩放调字号",
            subtitle = "不用开菜单，在正文上双指张开、捏合",
            checked = settings.pinchFontSize,
            onCheckedChange = { onUpdate(settings.copy(pinchFontSize = it)) },
            position = SettingGroupPosition.Middle,
        )
        SwitchRow(
            title = "右边缘上下滑调亮度",
            subtitle = "沿屏幕右边缘竖着滑，往上亮、往下暗。滚动翻页时不可用",
            checked = settings.edgeBrightness,
            onCheckedChange = { onUpdate(settings.copy(edgeBrightness = it)) },
            position = SettingGroupPosition.Last,
        )
    }

    // 本地书和在线书分别保存图片设置。
    ReaderGroup("内容加载") {
        SwitchRow(
            title = "正文显示图片",
            subtitle = if (isNetBook) {
                if (settings.showImages) "在线书图片需要下载，会使用流量。长按图片可以看大图"
                else "当前只显示文字。打开后显示在线书插图，会使用流量"
            } else {
                if (settings.showImages) "显示本地书插图，长按图片可以看大图"
                else "当前只显示文字。打开后显示本地书插图"
            },
            checked = settings.showImages,
            onCheckedChange = { onUpdate(settings.copy(showImages = it)) },
            position = SettingGroupPosition.First,
        )
        ReaderBlock {
            Text("预加载章节", style = MiuixTheme.textStyles.body2)
        }
        ReaderBlock {
            SegmentedChoice(
                options = PRELOAD_OPTIONS.map { if (it == 0) "关闭" else "$it 章" },
                selectedIndex = PRELOAD_OPTIONS.indexOf(settings.preloadChapters),
                onSelect = { onUpdate(settings.copy(preloadChapters = PRELOAD_OPTIONS[it])) },
            )
        }
    }
}

/**
 * 自定义纸张：调纸色（色相 / 浓度 / 明度）与字色。
 *
 * **一行一条、各带名字、轨道画成渐变**（[AppColorSlider]）。从前是「纸色」小标题下三条 28dp 的粗轨上下零间距：
 * 拇指压到下一条上、看着是一整块，三条也分不清谁是色相谁是明度（名字只有读屏念得出来）；轨道一律填强调色，
 * 看不出往哪拖会变成什么色（被指出过）。现在名字在行头，轨道上看到的就是拖到那儿得到的颜色：
 * - 色相：最浓那档（[PAPER_SAT_MAX]）的一圈色相。纸太暗时按 [HUE_TRACK_MIN_VALUE] 的明度画，深色纸上也看得出色相
 * - 浓度：从灰到这个色相最浓（上限 [PAPER_SAT_MAX]，纸不会太艳）
 * - 明度：从黑到亮
 * - 字色：这张纸上从最深到最浅的字
 *
 * **实时报对比度**，低于 7:1 就明说。不拦着用户配，但得让他知道代价 ——
 * 「墨绿纸配灰字」看着挺雅，读两章眼睛就疼，而用户根本不会把眼疼归因到自己调的色上。
 *
 * **编辑中以手里这份 HSV（[PaperHsv]）为准**，不每次从 ARGB 反解：纸色是 8 位 RGB，浓度很低时反解出的色相会跳；
 * 浓度拖到 0（纯灰）、明度拖到 0（纯黑）时干脆反解不出色相 / 浓度，色相轨的拇指会一下跳回最左（红）。
 * 别处改了纸（换了预设又切回来、同步下来一份新配色）、对不上手里这份时，才从 ARGB 重新反解。
 */
@Composable
private fun CustomPaperEditor(
    theme: ReaderTheme,
    onChange: (ReaderTheme, commit: Boolean) -> Unit,
) {
    val held = remember { HeldPaper() }
    val scope = rememberCoroutineScope()
    val latestOnChange by rememberUpdatedState(onChange)
    val parsed = remember(theme.background, theme.textColor) { PaperHsv.of(theme) }
    fun currentPaper(): PaperHsv = held.value?.takeIf {
        held.changed || (it.background == theme.background && it.text == theme.textColor)
    } ?: parsed
    val paper = currentPaper()

    fun update(change: PaperHsv.() -> PaperHsv) {
        val current = currentPaper()
        val next = current.change()
        if (next == current) return
        held.value = next
        held.changed = true
        if (held.preview?.isActive == true) return
        // 高采样率触摸屏一帧能送来多个坐标。正文和面板只按这一帧最新的颜色重新绘制。
        held.preview = scope.launch {
            withFrameNanos {
                held.value?.let { latestOnChange(ReaderTheme.custom(it.background, it.text), false) }
            }
        }
    }

    fun finish() {
        if (!held.changed) return
        held.changed = false
        held.preview?.cancel()
        held.preview = null
        // 抬手可能早于下一帧，直接提交最后收到的坐标所对应的颜色。
        held.value?.let { latestOnChange(ReaderTheme.custom(it.background, it.text), true) }
    }

    val hueValue = paper.v.coerceAtLeast(HUE_TRACK_MIN_VALUE)
    val textSat = paperTextSaturation(paper.s)
    val hueTrack = remember(hueValue) {
        List(HUE_TRACK_STOPS) { i -> hsvToColor(360f * i / (HUE_TRACK_STOPS - 1), PAPER_SAT_MAX, hueValue) }
    }
    val satTrack = remember(paper.h, paper.v) {
        List(TRACK_STOPS) { i -> hsvToColor(paper.h, PAPER_SAT_MAX * i / (TRACK_STOPS - 1), paper.v) }
    }
    val valueTrack = remember(paper.h, paper.s) {
        List(TRACK_STOPS) { i -> hsvToColor(paper.h, paper.s, i / (TRACK_STOPS - 1f)) }
    }
    val textTrack = remember(paper.h, textSat) {
        List(TRACK_STOPS) { i -> hsvToColor(paper.h, textSat, i / (TRACK_STOPS - 1f)) }
    }
    val paperColor = Color(paper.background)
    val ratio = ReaderTheme.contrastRatio(theme.background, theme.textColor)

    Column(
        Modifier.padding(top = Dimens.gapL),
        verticalArrangement = Arrangement.spacedBy(Dimens.gapM),
    ) {
        PaperSliderRow("色相") {
            AppColorSlider(
                value = paper.h,
                valueRange = 0f..360f,
                onValueChange = { update { copy(h = it) } },
                onValueChangeFinished = ::finish,
                trackColors = hueTrack,
                thumbColor = hsvToColor(paper.h, PAPER_SAT_MAX, hueValue),
                label = "纸色色相",
                modifier = Modifier.weight(1f),
            )
        }
        PaperSliderRow("浓度") {
            AppColorSlider(
                value = paper.s,
                valueRange = 0f..PAPER_SAT_MAX,
                onValueChange = { update { copy(s = it) } },
                onValueChangeFinished = ::finish,
                trackColors = satTrack,
                thumbColor = paperColor,
                label = "纸色浓度",
                modifier = Modifier.weight(1f),
            )
        }
        PaperSliderRow("明度") {
            AppColorSlider(
                value = paper.v,
                onValueChange = { update { copy(v = it) } },
                onValueChangeFinished = ::finish,
                trackColors = valueTrack,
                thumbColor = paperColor,
                label = "纸色明度",
                modifier = Modifier.weight(1f),
            )
        }
        // 纸、字两组之间比组内多空一档：一眼看出前三条管纸、这条管字
        PaperSliderRow("字色", Modifier.padding(top = Dimens.gapXS)) {
            AppColorSlider(
                value = paper.t,
                onValueChange = { update { copy(t = it) } },
                onValueChangeFinished = ::finish,
                trackColors = textTrack,
                thumbColor = Color(paper.text),
                label = "字色深浅",
                modifier = Modifier.weight(1f),
            )
        }
    }

    Text(
        text = "正文对比度 %.1f:1".format(ratio) + when {
            ratio >= 7.0 -> " · 长时间阅读没问题"
            ratio >= 4.5 -> " · 能看清，但读久了会累"
            else -> " · 太低，读起来费劲"
        },
        style = MiuixTheme.textStyles.footnote1,
        color = if (ratio >= 7.0) MiuixTheme.colorScheme.onSurfaceVariantSummary
        else MiuixTheme.colorScheme.error,
        modifier = Modifier.padding(top = Dimens.gapS, bottom = Dimens.gapXS),
    )
}

/**
 * 调色区的一行：两个字的名字 + 一条调色滑块。名字都是两个汉字，宽度天然一样，几条滑轨左端对得齐，不用定宽。
 * 名字只给看：读屏念滑块自己的 label（「纸色色相」比「色相」说得全），这里不再念一遍。
 */
@Composable
private fun PaperSliderRow(
    name: String,
    modifier: Modifier = Modifier,
    slider: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.gapM),
    ) {
        Text(
            name,
            style = MiuixTheme.textStyles.body2,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.clearAndSetSemantics {},
        )
        slider()
    }
}

/**
 * 自定义纸张手里那份 HSV：纸的色相 [h]（0~360）/ 浓度 [s] / 明度 [v]，加上字的明度 [t]（0 = 黑，1 = 白）。
 * 字的色相跟纸走、浓度由纸的浓度推出来（[paperTextSaturation]），不单独调。
 */
private data class PaperHsv(val h: Float, val s: Float, val v: Float, val t: Float) {
    val background: Long = hsvToArgb(h, s, v)
    val text: Long = hsvToArgb(h, paperTextSaturation(s), t)

    companion object {
        fun of(theme: ReaderTheme): PaperHsv {
            val bg = argbToHsv(theme.background)
            return PaperHsv(bg[0], bg[1], bg[2], argbToHsv(theme.textColor)[2])
        }
    }
}

/** 保留手指调整的 HSV 精度，以及等待合并到下一帧的颜色。 */
private class HeldPaper {
    var value: PaperHsv? = null
    var preview: Job? = null
    var changed: Boolean = false
}

/** 字色沿用纸的色相，但压低浓度 —— 完全同色相的浓字会显脏，完全无彩的灰字又跟纸不搭 */
private fun paperTextSaturation(paperSat: Float): Float = (paperSat * 0.45f).coerceAtMost(0.35f)

/** 纸色浓度上限：再浓就不像纸了 */
private const val PAPER_SAT_MAX = 0.25f

/** 色相轨至少按这个明度画：纸调得很暗时，按纸的明度画出来是一条看不出色相的黑带 */
private const val HUE_TRACK_MIN_VALUE = 0.6f

/** 色相轨每 30° 一个色标，一圈 13 个（首尾都是红） */
private const val HUE_TRACK_STOPS = 13

/** 其余几条轨道的色标数。HSV 的浓度 / 明度在 RGB 里近乎线性，几个色标就够平滑 */
private const val TRACK_STOPS = 6

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
    onSelect: (String) -> Unit,
    /** 左右内缩放在滚动内容里：色板随内容滚到分组卡的边缘，而不是在内缩线上被截断 */
    horizontalPadding: Dp = 0.dp,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = horizontalPadding)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(Dimens.gapM),
    ) {
        ReaderTheme.ALL.forEach { theme ->
            ThemeSwatch(
                theme = theme,
                selected = theme.id == settings.theme.id,
                onClick = { onSelect(theme.id) },
            )
        }
        val custom = if (settings.theme.id == ReaderTheme.CUSTOM_ID) settings.theme else settings.customPaper
        ThemeSwatch(
            theme = custom,
            selected = settings.theme.id == ReaderTheme.CUSTOM_ID,
            onClick = { onSelect(ReaderTheme.CUSTOM_ID) },
        )
    }
}

@Composable
private fun ThemeSwatch(theme: ReaderTheme, selected: Boolean, onClick: () -> Unit) {
    // 按下色块微微沉下去（见 sinkOnPress）
    val press = remember { MutableInteractionSource() }
    Column(
        modifier = Modifier.selectable(
            selected = selected,
            interactionSource = press,
            indication = null,
            role = Role.RadioButton,
            onClick = onClick,
        ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimens.gapXS),
    ) {
        Box(
            Modifier
                .size(Dimens.swatch)
                .sinkOnPress(press)
                .clip(CircleShape)
                .background(Color(theme.background))
                .indication(press, LocalIndication.current)
                .border(
                    width = if (selected) Dimens.strokeThick else Dimens.strokeThin,
                    color = if (selected) MiuixTheme.colorScheme.primary
                    else MiuixTheme.colorScheme.dividerLine,
                    shape = CircleShape,
                )
                .clearAndSetSemantics {},
            contentAlignment = Alignment.Center,
        ) {
            // 选中的打勾；没选中的放一个"文"字，直接把这款纸的正文对比度演示出来 ——
            // 光看纸色选不出好不好读
            if (selected) {
                Icon(
                    MiuixIcons.Basic.Check,
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

/** 阅读设置面板的一级页与三个二级页。[title] 是二级页标题行上的页名，也是一级页入口上的字；一级页没有标题 */
private enum class SettingsPage(val title: String?) {
    Main(null),
    Typeset("排版"),
    Flip("翻页"),
    More("更多"),
}

/** 一级页上拖起来会让面板让开的两条滑轨 */
private enum class SettingsSlider { Brightness, FontSize }

/** 到头不可点的字号 A 的透明度，同 AppIconButton 等封装的禁用态 */
private const val DISABLED_CONTENT_ALPHA = 0.38f

/** 设置面板滚动区的窗口比例，给固定入口和字体标题行留出空间。 */
private const val PANEL_HEIGHT_FRACTION = 0.52f

/** 自动翻页可选间隔（秒）。太快来不及读，太慢不如自己翻 */
private val AUTO_FLIP_OPTIONS = listOf(5, 10, 15, 20, 30, 45, 60)

/** 预取多少章正文。给多了只是白耗流量：读者不会一口气跳着读十章 */
private val PRELOAD_OPTIONS = listOf(0, 1, 3, 5, 10)
