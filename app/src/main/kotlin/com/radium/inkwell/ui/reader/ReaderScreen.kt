package com.radium.inkwell.ui.reader

import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import android.app.Activity
import android.content.ClipData
import android.view.WindowManager
import com.radium.inkwell.MainActivity
import com.radium.inkwell.ui.theme.AppTheme
import com.radium.inkwell.util.UsageLog
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.areStatusBarsVisible
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import com.radium.inkwell.reader.render.TextSelection
import com.radium.inkwell.reader.render.SelectionEnd
import com.radium.inkwell.reader.render.moveSelectionEnd
import com.radium.inkwell.reader.render.stepSelectionEnd
import com.radium.inkwell.reader.render.selectionHandlePosition
import com.radium.inkwell.reader.render.imageAt
import com.radium.inkwell.reader.paginate.PageItem
import com.radium.inkwell.reader.render.extendSelection
import com.radium.inkwell.reader.render.selectWordAt
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.text.style.TextAlign
import com.radium.inkwell.ui.components.ErrorState
import com.radium.inkwell.ui.components.AppTextButton
import com.radium.inkwell.ui.components.FloatingGlassBar
import com.radium.inkwell.ui.components.LocalBarBackdrop
import com.radium.inkwell.ui.theme.AppThemes
import com.radium.inkwell.ui.components.AppAlertDialog
import com.radium.inkwell.ui.components.AppLoadingIndicator
import androidx.compose.runtime.snapshotFlow
import com.radium.inkwell.ui.components.BookCover
import com.radium.inkwell.ui.components.Motion
import com.radium.inkwell.ui.components.animationsEnabled
import com.radium.inkwell.ui.components.bottomBarEnter
import com.radium.inkwell.ui.components.bottomBarExit
import com.radium.inkwell.ui.components.scrimEnter
import com.radium.inkwell.ui.components.scrimExit
import com.radium.inkwell.reader.api.FlipAnimation
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.radium.inkwell.ui.components.Dimens
import androidx.compose.ui.unit.sp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radium.inkwell.reader.api.FlipDirection
import com.radium.inkwell.reader.api.ReaderSettings
import com.radium.inkwell.reader.flip.FlipController
import com.radium.inkwell.reader.flip.PageFlipContainer
import com.radium.inkwell.reader.flip.ScrollPageReader
import com.radium.inkwell.reader.flip.scrollContentBottom
import com.radium.inkwell.reader.flip.scrollContentTop
import com.radium.inkwell.reader.measure.ComposeTextMeasureFacade
import com.radium.inkwell.data.repo.ReaderFontStore
import com.radium.inkwell.reader.paginate.LayoutSpec
import com.radium.inkwell.reader.paginate.PageSpec
import com.radium.inkwell.reader.paginate.RaisedReaderHeader
import com.radium.inkwell.util.KeyEventBus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.ui.graphics.luminance

/** 自动换源提示胶囊的停留时长：够看清并有机会撤销，又不至于长期挡住正文 */
private const val AUTO_CHANGED_HINT_MS = 8_000L

/** 纸面上一次性提示的停留时长 */
private const val READER_TOAST_MS = 1_800L

/**
 * 毛玻璃收起后，翻页区再录这么久才停：菜单 / 工具条的退场动画还在播，停早了栏会在半路变成全透明。
 * 退场走 fast* 档的 spring，几百毫秒内就落定，这里留足余量。
 */
private const val PAGE_GLASS_LINGER_MS = 600L

private data class ScrollSelectionTarget(val page: PageSpec, val offsetY: Float)

/** 弹层宿主与正文共用窗口，供阅读设置局部取样。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReaderScreen(
    bookId: String,
    onExit: () -> Unit,
    viewModel: ReaderViewModel,
) = Scaffold(Modifier.fillMaxSize(), containerColor = Color.Transparent) {
    val pageUi by viewModel.page.collectAsStateWithLifecycle()
    val session by viewModel.session.collectAsStateWithLifecycle()
    val overlay by viewModel.overlay.collectAsStateWithLifecycle()
    val speech = viewModel.speech
    val speechState by speech.state.collectAsStateWithLifecycle()
    val readerContext = LocalContext.current
    var showSpeechSheet by rememberSaveable { mutableStateOf(false) }
    var showTextSearch by rememberSaveable { mutableStateOf(false) }
    var menuSheetOpen by remember { mutableStateOf(false) }
    LaunchedEffect(menuSheetOpen, showTextSearch) {
        viewModel.setReaderSheetOpen(menuSheetOpen || showTextSearch)
    }
    val showOverlayBars = overlay.menuVisible || showSpeechSheet || showTextSearch
    val showRaisedHeader = !WindowInsets.areStatusBarsVisible
    val motion = AppTheme.motionScheme
    val menuProgress = animateFloatAsState(
        targetValue = if (overlay.menuVisible) 1f else 0f,
        animationSpec = if (overlay.menuVisible) motion.defaultEffectsSpec() else motion.fastEffectsSpec(),
        label = "readerMenuChapterTitle",
    )
    var miniPlayerHeight by remember { mutableStateOf(0.dp) }
    val speechInset = if (speechState.active && miniPlayerHeight > 0.dp) miniPlayerHeight + Dimens.gapS else 0.dp
    LaunchedEffect(speechState.active) {
        if (!speechState.active) showSpeechSheet = false
    }
    val speechChapter = speechState.chapter?.index
    val speechParagraph = speechState.paragraph
    val speechOffset = speechState.chapterOffset
    LaunchedEffect(speechChapter, speechOffset, speechState.status) {
        if (speechChapter != null && speechOffset != null && speechState.status != SpeechStatus.LOADING) {
            viewModel.followSpeech(speechChapter, speechOffset)
        }
    }
    val speechSelection = speechParagraph?.takeIf {
        speechChapter == pageUi.chapterIndex && speechState.status != SpeechStatus.LOADING
    }
        ?.let { TextSelection(it.elementIndex, 0, it.text.length, it.text) }
    val textMatch by viewModel.textMatch.collectAsStateWithLifecycle()
    val searchDestination by viewModel.textSearchDestination.collectAsStateWithLifecycle()
    val onSearchResultPage = searchDestination?.isOnPage(pageUi.page?.spec) == true
    val readingHighlight = textMatch?.takeIf { it.position.chapterIndex == pageUi.chapterIndex }?.selection
        ?: speechSelection
    val density = LocalDensity.current
    val fontFamilyResolver = LocalFontFamilyResolver.current
    val keyBus = koinInject<KeyEventBus>()
    val flipController = remember { FlipController() }

    // 阅读纸张跟 App 的日夜走：算出此刻生效的是日间还是夜间，喂给 ViewModel 切日/夜槽。
    val appPrefs = koinInject<com.radium.inkwell.data.prefs.AppPrefs>()
    // 读预热好的那份：冷流配 initialValue 的话，进书第一帧按默认日夜算，夜间用户会先切一次日间纸
    val themeConfig = appPrefs.themeState.collectAsStateWithLifecycle().value
        ?: com.radium.inkwell.ui.theme.ThemeConfig()
    val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
    val effectiveDark = when (themeConfig.mode) {
        com.radium.inkwell.ui.theme.ThemeMode.LIGHT -> false
        com.radium.inkwell.ui.theme.ThemeMode.DARK -> true
        com.radium.inkwell.ui.theme.ThemeMode.SYSTEM -> systemDark
    }
    LaunchedEffect(effectiveDark) { viewModel.setDarkActive(effectiveDark) }

    /**
     * 挖孔尺寸。**首帧就得是对的** —— 这是"进书抖一下"的根。
     *
     * Compose 的 `WindowInsets.displayCutout` 在一棵新子树里首帧还没派发（值是 0），
     * 第二帧才到位。从前正文的视口是「Box 扣掉 windowInsetsPadding 之后的实测尺寸」，
     * 于是：
     *   帧 1 —— inset 还是 0 → 视口 = 全屏 → 按全屏排了一遍 → 正文铺出来
     *   帧 2 —— inset 到位 → Box 变小 → 视口变 → 重排 → 文字整体挪一下
     * 那一下就是抖动。（也是"返回再进来退回上一页"的同一个根因：这次重排会把
     * 已保存的字符偏移吸附掉。）
     *
     * 但挖孔在哪，**窗口本来就知道** —— 我们是从书架页导航进来的，insets 老早派发过；
     * 晚一帧的只是 Compose 在这棵新子树里的那次分发。所以首帧直接问 window 要
     * （同步），之后以 Compose 的响应式值为准 —— 旋转、折叠、
     * 分屏时它会自己更新，不必去猜什么时候该让缓存失效。
     *
     * 两者一致时（绝大多数情况）视口从头到尾没变过，压根不会有第二次排版。
     */
    val view = LocalView.current
    val windowSize = LocalWindowInfo.current.containerSize
    val rootInsets = remember(view, windowSize) { ViewCompat.getRootWindowInsets(view) }
    val seed = rootInsets?.getInsets(WindowInsetsCompat.Type.displayCutout())

    val live = WindowInsets.displayCutout
    val layoutDirection = LocalLayoutDirection.current
    // 逐个字段读，让 inset 变化能触发重组；全 0 就说明这一帧还没派发，用同步种子顶上。
    // （真没有挖孔的设备上，种子本身也是 0，取谁都一样。）
    val l = live.getLeft(density, layoutDirection)
    val t = live.getTop(density)
    val r = live.getRight(density, layoutDirection)
    val b = live.getBottom(density)
    val cutout = if (l == 0 && t == 0 && r == 0 && b == 0 && seed != null) {
        IntInsets(seed.left, seed.top, seed.right, seed.bottom)
    } else {
        IntInsets(l, t, r, b)
    }
    // 菜单临时显示系统栏时，正文仍按用户设置排版。这里读可见时的高度，避免来回重排。
    val statusBarTop = WindowInsets.statusBarsIgnoringVisibility.getTop(density).takeIf { it > 0 }
        ?: rootInsets?.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.statusBars())?.top ?: 0
    val statusArea = remember(view, windowSize, cutout, statusBarTop) {
        val displayCutout = ViewCompat.getRootWindowInsets(view)?.displayCutout
        ReaderStatusArea(
            statusBarTop,
            displayCutout?.boundingRects.orEmpty().map { IntRect(it.left, it.top, it.right, it.bottom) },
            displayCutout?.waterfallInsets?.top ?: 0,
        )
    }

    // 视口 = **整个窗口**（边到边）。挖孔不再从视口里扣掉，而是折进正文边距（见下）——
    // 这样正文页与卷页都铺满全屏，顶部不再留一条状态栏/挖孔高度的空带（卷页时会露出来）。
    val viewport = IntSize(windowSize.width, windowSize.height)

    // 页眉页脚小字的行高：分页给那两条带的预留按它兜底（见 pageInfoTextStyle）
    val infoLineHeight = pageInfoTextStyle().lineHeight
    val spec = remember(viewport, session.settings, density, cutout, infoLineHeight, speechInset, statusArea, layoutDirection) {
        if (viewport.width <= 0 || viewport.height <= 0) null
        else buildLayoutSpec(
            viewport, session.settings, density, cutout, infoLineHeight, with(density) { speechInset.toPx() },
            statusArea, layoutDirection,
        )
    }
    // 快速调字号时让旧页面按旧尺寸显示，等重排完成再一起换正文和排版参数。
    val layout = pageUi.layout ?: spec

    // 导入的字体与预设走同一个注册表；应用级单例，FontFamily 缓存跨章、跨书都有效
    val fontRegistry = koinInject<ReaderFontStore>().registry
    LaunchedEffect(spec) {
        val settled = spec ?: return@LaunchedEffect
        val facade = ComposeTextMeasureFacade(fontFamilyResolver, density, fontRegistry)
        viewModel.onLayoutReady(facade, settled)
    }

    LaunchedEffect(Unit) {
        keyBus.flipEvents.collect {
            UsageLog.event("reader.input.volume", "direction" to it.name)
            flipController.requestFlip(it)
        }
    }
    DisposableEffect(Unit) {
        onDispose { keyBus.volumeFlipEnabled = false }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) viewModel.onReaderStarted()
            if (event == Lifecycle.Event.ON_STOP) {
                viewModel.flushProgress()
                viewModel.onReaderStopped()
                viewModel.stopAutoFlip()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.flushProgress()
            viewModel.onReaderStopped()
        }
    }

    var selection by remember { mutableStateOf<TextSelection?>(null) }
    var anchor by remember { mutableStateOf<TextSelection?>(null) }
    var scrollSelectionTarget by remember { mutableStateOf<ScrollSelectionTarget?>(null) }
    fun clearSelection() {
        selection = null
        anchor = null
        scrollSelectionTarget = null
    }
    LaunchedEffect(
        pageUi.page?.spec, spec, session.settings.flipAnimation, session.textSelectionEnabled, overlay.menuVisible,
    ) {
        clearSelection()
    }
    val scrollTarget = scrollSelectionTarget
    val scrollPageSpec = scrollTarget?.page
    val currentPage = pageUi.page
    val previousPage = pageUi.prevPage
    val nextPage = pageUi.nextPage
    val selectionPage = if (scrollPageSpec == null) currentPage else when (scrollPageSpec) {
        currentPage?.spec -> currentPage
        previousPage?.spec -> previousPage
        nextPage?.spec -> nextPage
        else -> null
    }
    LaunchedEffect(scrollPageSpec, selectionPage?.spec) {
        if (scrollPageSpec != null && selectionPage == null) clearSelection()
    }
    val selectionHandles = remember(selectionPage, layout, selection, scrollTarget?.offsetY) {
        val selected = selection
        if (selectionPage == null || layout == null || selected == null) emptyMap()
        else SelectionEnd.entries.mapNotNull { edge ->
            val position = selectionPage.selectionHandlePosition(selected, edge, layout) ?: return@mapNotNull null
            if (scrollTarget == null) return@mapNotNull edge to position
            val shifted = position.anchor + Offset(0f, scrollTarget.offsetY)
            val lineCenterY = position.lineCenterY + scrollTarget.offsetY
            val lineTop = lineCenterY - (shifted.y - lineCenterY)
            val contentBottom = scrollContentBottom(layout)
            if (shifted.y <= scrollContentTop(layout) || lineTop >= contentBottom) return@mapNotNull null
            edge to position.copy(anchor = Offset(shifted.x, minOf(shifted.y, contentBottom)), lineCenterY = lineCenterY)
        }.toMap()
    }
    val selectionHandlesLatest = rememberUpdatedState(selectionHandles)
    /** 长按看大图：正在看的那张图（resourceId）；null = 没在看 */
    var viewingImage by remember { mutableStateOf<String?>(null) }
    /** 正在双指调字号 / 右边缘调亮度时的读数（见 QuickAdjustOverlay）；null = 没在调 */
    var quickAdjust by remember { mutableStateOf<QuickAdjust?>(null) }
    val haptic = LocalHapticFeedback.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var addShelfPrompt by remember { mutableStateOf(false) }
    var settlingShelf by remember { mutableStateOf(false) }

    // 音量键翻页（与点击共用动画路径）。只在**真能翻**的时候吃掉音量键，其余时候放行给系统调音量：
    // - 滚动模式下没有"页"，音量键翻页无从谈起 —— 按下去只会把游标推到别处、把人转晕。
    //   这个条件必须真的写进来：漏了的话滚动模式下 MainActivity 照样把音量键吃掉，事件发到
    //   没人订阅的 flipController 上凭空消失 —— 既翻不了页，系统音量也调不了。
    // - 报错页、加载/进书 splash、自动换源进度页上根本没有翻页容器，同理会被凭空吃掉。
    // - 选字中不翻：选区和工具条还亮着，底下那页翻走了，复制/净化拿到的是上一页的字。
    // - 菜单、目录/设置面板、换源面板、加入书架确认开着时，与翻页手势同一套开关。
    val volumeFlip = session.settings.volumeKeyFlip &&
        !speechState.active &&
        session.settings.flipAnimation != FlipAnimation.SCROLL &&
        !overlay.menuVisible &&
        !overlay.readerSheetOpen &&
        overlay.sourceCandidates == null &&
        !overlay.autoChanging &&
        pageUi.error == null &&
        !pageUi.loading &&
        spec != null &&
        selection == null &&
        viewingImage == null &&
        !addShelfPrompt
    LaunchedEffect(volumeFlip) { keyBus.volumeFlipEnabled = volumeFlip }

    // 选字期间自动翻页暂停：ViewModel 停表（松手后重新计满一个间隔再翻，不会一取消选区就立刻跳页），
    // 这里再拦一道 —— 万一请求已经在路上，也别在选区底下把页翻走
    // 看大图时同理：底下那页别自己翻走，关掉大图还得找回刚才那一页
    LaunchedEffect(selection != null || viewingImage != null) {
        viewModel.setSelectionActive(selection != null || viewingImage != null)
    }
    // 自动翻页与音量键共用 FlipController，沿用用户选的仿真/覆盖/平移等效果
    LaunchedEffect(Unit) {
        viewModel.animatedFlipRequests.collect {
            UsageLog.event("reader.input.auto", "direction" to it.name, "blocked" to (selection != null))
            if (selection == null && !viewModel.overlay.value.menuVisible) flipController.requestFlip(it)
        }
    }

    // 预览直读还没上架：退出先问要不要加入，而不是默默留在书架上。
    val requestExit: () -> Unit = {
        if (session.inShelf) onExit() else addShelfPrompt = true
    }

    // 系统返回：先收起暂态浮层（选字/面板/菜单），都关着才真正退出阅读页。
    // 否则菜单开着按返回会直接跳出阅读，用户以为只是想关掉菜单。
    // 试读未上架时再拦一层，弹出加入书架的确认。
    BackHandler(
        enabled = !overlay.readerSheetOpen && !showTextSearch && (
            overlay.menuVisible || selection != null || overlay.sourceCandidates != null ||
                (!session.inShelf && !addShelfPrompt)
            ),
    ) {
        when {
            selection != null -> clearSelection()
            overlay.sourceCandidates != null -> viewModel.dismissSourcePanel()
            overlay.menuVisible -> viewModel.toggleMenu()
            else -> addShelfPrompt = true
        }
    }

    val activity = LocalActivity.current
    BrightnessEffect((activity as? MainActivity)?.readerBrightness, session.settings.brightnessOverride)
    KeepScreenOnEffect(activity, session.settings.keepScreenOn)
    ReaderSystemBarsEffect(
        activity,
        showNavigationBar = showOverlayBars,
        showStatusBar = session.settings.showStatusBar || (showOverlayBars && !session.settings.usesRaisedHeader),
        lightPaper = !session.settings.theme.isDark,
        lightApp = MiuixTheme.colorScheme.surface.luminance() > 0.5f,
    )

    val paper = remember(session.settings.theme.background) { Color(session.settings.theme.background) }
    val backdrop = rememberLayerBackdrop {
        drawRect(paper)
        drawContent()
    }
    val pageBackdrop = backdrop.takeUnless { session.settings.lightweightEffects }
    // 录图层要把整页再画一遍：滚动模式下每一帧都是一整页字。所以只在有毛玻璃露着时录（菜单、选字工具条），
    // 收起后再录一小段给退场动画（见 PAGE_GLASS_LINGER_MS）。lingering 在亮起时就置上 ——
    // 否则收起那一帧 needsGlass 已经是 false、lingering 还没被协程置上，录制会断一帧，栏闪一下透明。
    // 自动换源的提示胶囊也是这块玻璃：它只亮 8 秒（AUTO_CHANGED_HINT_MS），这几秒录着不值一提
    val needsGlass = speechState.active || overlay.menuVisible || selection != null ||
        overlay.autoChangedTo != null || onSearchResultPage
    var glassLingering by remember { mutableStateOf(false) }
    LaunchedEffect(needsGlass) {
        if (needsGlass) {
            glassLingering = true
        } else {
            delay(PAGE_GLASS_LINGER_MS)
            glassLingering = false
        }
    }
    val recordPage = needsGlass || glassLingering
    Box(
        Modifier
            .fillMaxSize()
            .background(paper)
            // 不在这里扣挖孔 —— 页要铺满全屏（卷页才不会在顶上露出空带）。避开摄像头改由
            // 正文边距承担（buildLayoutSpec 把 cutout 折进 margin）。阅读菜单顶栏单独让开顶部安全区。
    ) {
        /*
         * 进书 splash：正文还没就位时，在纸面上居中显示这本书的封面。
         *
         * 时机是这套东西的全部难点，三条约束缺一不可：
         *   1) 热开书不能变慢 —— 所以先等 READER_SPLASH_DELAY_MS，这段时间内就绪就**永不出场**。
         *      绝大多数进书走的是这条，用户根本见不到它，一毫秒都没多花。
         *   2) 出场了就不能一闪而过 —— 露面即保底停留 READER_SPLASH_MIN_MS，否则正文在第 210ms
         *      就绪时封面只闪 10ms，比不显示更难看。
         *   3) **只属于「这本书正在打开」这一刻** —— 章节切换/换源时 loading 同样会为 true，
         *      那时候再蹦出封面就是打断阅读。firstContentShown 一旦为 true 永不复位，闸住这种情况。
         *
         * 整段生命周期用一个协程串起来，而不是几个 effect 互相触发 —— 后者在「内容早于保底时间
         * 就绪」时会互相打架，要么提前隐藏要么再也不隐藏。
         */
        var firstContentShown by remember { mutableStateOf(false) }
        var splashVisible by remember { mutableStateOf(false) }
        LaunchedEffect(pageUi.loading, layout) {
            if (!pageUi.loading && layout != null) firstContentShown = true
        }
        LaunchedEffect(Unit) {
            delay(Motion.READER_SPLASH_DELAY_MS)
            if (firstContentShown) return@LaunchedEffect // 热开书：这段窗口内已就绪，不出场
            splashVisible = true
            delay(Motion.READER_SPLASH_MIN_MS)           // 露了面就待满，杜绝一闪
            snapshotFlow { firstContentShown }.first { it }
            splashVisible = false
        }

        // 不开菜单直接调：双指缩放调字号、右边缘竖滑调亮度（见 QuickAdjust.kt）。只在正文就位、
        // 没有任何浮层（菜单、面板、换源、选字）时挂 —— 浮层开着时这些手势要么被盖住、要么会和面板里的滑块抢
        val context = LocalContext.current
        val quickHandler = rememberUpdatedState(
            QuickAdjustHandler(
                fontSize = { session.settings.fontSizeSp },
                brightness = {
                    session.settings.brightnessOverride ?: systemBrightnessLevel(context) ?: HALF_BRIGHTNESS
                },
                onFontSize = { sp ->
                    // 每过一档轻震一下：手指在张，眼睛在看正文，靠手感数档
                    haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    viewModel.previewSettings(session.settings.copy(fontSizeSp = sp))
                },
                onBrightness = { level -> viewModel.previewSettings(session.settings.copy(brightnessOverride = level)) },
                onFinish = { viewModel.commitSettings() },
                onHud = { quickAdjust = it },
            ),
        )
        val quickAdjustEnabled = !overlay.menuVisible &&
            !overlay.readerSheetOpen &&
            overlay.sourceCandidates == null &&
            selection == null &&
            viewingImage == null &&
            !overlay.autoChanging &&
            pageUi.error == null &&
            !pageUi.loading &&
            layout != null

        // 点在图上：没取到的那条提示是「点按重试」，点了就重取、不翻页；点在真图上照旧翻页 / 开菜单
        val onImageTap: (PageItem.ImageBox) -> Boolean = { box ->
            if (box.failed) {
                viewModel.retryImage(box)
                true
            } else {
                false
            }
        }

        // 翻页区（含页眉页脚）录进 pageBackdrop：阅读菜单与选字工具条对它取样做毛玻璃（readerGlass），只在 recordPage 时录。
        // 页面全是 Compose 自己画的（PageCanvas、仿真页的位图也画在 Compose 画布上），图层录得下来；
        // 菜单和各种浮层都在这一层**外面**，不会自己糊自己。录下来的那份不带纸色（纸色是外层 Box 画的），
        // 透明处先垫一层纸，否则模糊时边缘会渗进黑色。
        Box(
            Modifier
                .fillMaxSize()
                .then(if (recordPage && pageBackdrop != null) Modifier.layerBackdrop(pageBackdrop) else Modifier)
                // 挂在翻页容器、长按选字的父节点上：它在 Initial 阶段先看到事件，认出是自己的手势才接走
                .quickAdjustGestures(
                    enabled = quickAdjustEnabled,
                    pinch = session.settings.pinchFontSize,
                    // 滚动模式下上下滑就是翻页，边缘竖滑不接
                    edge = session.settings.edgeBrightness && session.settings.flipAnimation != FlipAnimation.SCROLL,
                    handler = quickHandler,
                ),
        ) {
            when {
                // 排在 error 前面：自动换源正是由报错触发的，底下那条 error 还挂着。
                // 让用户对着"章节加载失败"干等，却不知道 App 其实正在替他找源 —— 那叫失联。
                overlay.autoChanging -> Column(
                    Modifier.align(Alignment.Center).padding(Dimens.gapXXL),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    AppLoadingIndicator(color = Color(session.settings.theme.textColor))
                    Spacer(Modifier.height(Dimens.gapXL))
                    Text(
                        "正在自动换源…",
                        color = Color(session.settings.theme.textColor),
                        style = MiuixTheme.textStyles.body1,
                        textAlign = TextAlign.Center,
                    )
                    if (overlay.autoChangeTotal > 0) {
                        Spacer(Modifier.height(Dimens.gapS))
                        Text(
                            "已试 ${overlay.autoChangeDone}/${overlay.autoChangeTotal} 个来源",
                            color = Color(session.settings.theme.footerColor),
                            style = MiuixTheme.textStyles.footnote1,
                        )
                    }
                }

                pageUi.error != null -> ReaderThemeScope(session.settings.theme) {
                    ErrorState(
                        title = "章节加载失败",
                        message = checkNotNull(pageUi.error),
                        onRetry = viewModel::retry,
                        secondaryLabel = if (session.isNetBook) "换源" else null,
                        onSecondary = if (session.isNetBook) ({ viewModel.searchOtherSources() }) else null,
                        tertiaryLabel = "返回书架",
                        onTertiary = requestExit,
                    )
                }
                // 头 READER_SPLASH_DELAY_MS 内纸面保持干净（连转圈都不出），让展开动画那段不被打扰；
                // 之后仍没就位才亮出封面 + 转圈。包一层 ReaderThemeScope，让书封的表面色/圆角跟纸张
                // 主题走，不然浅色书封压在深色纸上会打架。
                pageUi.loading || layout == null -> ReaderThemeScope(session.settings.theme) {
                    // 首屏之后的再加载（跳章、失败重试、换源重载）：splash 的一次性协程早已收场、
                    // splashVisible 恒为 false，这里必须回到无条件转圈 —— 否则慢源下是长达
                    // CONTENT_TIMEOUT_MS 的纯白纸，用户只会当它卡死。splash 只属于「这本书正在打开」。
                    // 转圈同样先让开 READER_SPLASH_DELAY_MS：本地书、已缓存的章节几十毫秒就排好，
                    // 从前跨章时邻章没排完就会一闪转圈；等不到那么久的加载，纸面干干净净地过去。
                    if (firstContentShown) {
                        var spinnerDue by remember { mutableStateOf(false) }
                        LaunchedEffect(Unit) {
                            delay(Motion.READER_SPLASH_DELAY_MS)
                            spinnerDue = true
                        }
                        if (spinnerDue) {
                            AppLoadingIndicator(
                                modifier = Modifier.align(Alignment.Center),
                                color = Color(session.settings.theme.textColor),
                            )
                        }
                    } else AnimatedVisibility(
                        visible = splashVisible,
                        modifier = Modifier.align(Alignment.Center),
                        enter = scrimEnter(),
                        exit = scrimExit(),
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            // 自然尺寸居中，**不铺满** —— 书封是 3:4 缩略图，撑到全屏必糊
                            // （0.1.6-beta.3~6 那条失败路线就栽在这）。
                            BookCover(
                                title = session.bookTitle,
                                coverModel = session.coverPath,
                                modifier = Modifier.size(
                                    Dimens.readerSplashCoverWidth,
                                    Dimens.readerSplashCoverHeight,
                                ),
                                placeholderChars = 14,
                            )
                            Spacer(Modifier.height(Dimens.gapXL))
                            // 封面只交代"在开哪本书"，还得有个东西说明"仍在工作中"，
                            // 否则慢网络下一张静止的封面像卡死了
                            AppLoadingIndicator(color = Color(session.settings.theme.textColor))
                        }
                    }
                }
                // 滚动：与仿真同一套分页。越过页高就 flip，标题跟当前页走。
                session.settings.flipAnimation == FlipAnimation.SCROLL -> {
                    val layoutLatest = rememberUpdatedState(layout)
                    val selectionPageLatest = rememberUpdatedState(selectionPage)
                    Box(
                        Modifier.fillMaxSize().dragSelectionHandles(selectionHandlesLatest) { edge, position ->
                            val selected = selection ?: return@dragSelectionHandles
                            val target = scrollSelectionTarget ?: return@dragSelectionHandles
                            val page = selectionPageLatest.value ?: return@dragSelectionHandles
                            val activeLayout = layoutLatest.value
                            val y = position.y.coerceIn(scrollContentTop(activeLayout), scrollContentBottom(activeLayout))
                            selection = page.moveSelectionEnd(selected, edge, position.x, y - target.offsetY, activeLayout)
                        },
                    ) {
                        ScrollPageReader(
                            onTrace = viewModel::traceInteraction,
                            highlight = readingHighlight,
                            selectedPage = scrollPageSpec,
                            textSelection = selection,
                            onTextSelection = if (!session.textSelectionEnabled) null else { page, offsetY, selected ->
                                if (selection == null) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    scrollSelectionTarget = ScrollSelectionTarget(page, offsetY)
                                }
                                selection = selected
                            },
                            onClearSelection = ::clearSelection,
                            current = pageUi.page,
                            prev = pageUi.prevPage,
                            next = pageUi.nextPage,
                            layout = layout,
                            theme = session.settings.theme,
                            hasPrev = pageUi.hasPrev,
                            hasNext = pageUi.hasNext,
                            gesturesEnabled = !overlay.menuVisible &&
                                !overlay.readerSheetOpen &&
                                overlay.sourceCandidates == null,
                            clickToFlip = session.settings.scrollClickToFlip,
                            tapZones = session.settings.tapZones,
                            onFlip = { viewModel.flip(it) },
                            onCenterTap = { viewModel.toggleMenu() },
                            onImageTap = onImageTap,
                            onImageLongPress = if (!session.settings.showImages) null else { box ->
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewingImage = box.resourceId
                            },
                        )
                        PageInfoBar(pageUi, session, layout, showRaisedHeader, menuProgress)
                        SelectionHandles(selectionHandles, Color(session.settings.theme.textColor), Modifier.matchParentSize())
                    }
                }

                else -> {
                    val page = pageUi.page
                    val pageLatest = rememberUpdatedState(page)
                    val layoutLatest = rememberUpdatedState(layout)
                    Box(
                        Modifier
                            .fillMaxSize()
                            .dragSelectionHandles(selectionHandlesLatest) { edge, position ->
                                val selected = selection ?: return@dragSelectionHandles
                                val currentPage = pageLatest.value ?: return@dragSelectionHandles
                                selection = currentPage.moveSelectionEnd(
                                    selected, edge, position.x, position.y, layoutLatest.value,
                                )
                            }
                            // 长按选字。放在**父节点**上而不是覆盖一层同级的 Box：
                            // 同级的覆盖层会把所有手势从翻页容器手里抢走（Compose 命中测试
                            // 只把事件给最上面那个兄弟节点）。父节点则是在子节点之后收到事件，
                            // 子节点没消费的才轮到它 —— 静止长按恰好没人消费。
                            // 长按一张图是看大图（图上没有字，和选字不冲突）；选字关着时长按图照样能看
                            .pointerInput(session.textSelectionEnabled, session.settings.showImages) {
                                val selectText = session.textSelectionEnabled
                                val viewImages = session.settings.showImages
                                if (!selectText && !viewImages) return@pointerInput
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { pos ->
                                        anchor = null
                                        val p = pageLatest.value ?: return@detectDragGesturesAfterLongPress
                                        if (viewImages) {
                                            val image = p.imageAt(pos.x, pos.y, layoutLatest.value)
                                            if (image != null && !image.failed) {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                viewingImage = image.resourceId
                                                return@detectDragGesturesAfterLongPress
                                            }
                                        }
                                        if (!selectText) return@detectDragGesturesAfterLongPress
                                        val sel = p.selectWordAt(pos.x, pos.y, layoutLatest.value)
                                        if (sel != null && !sel.isEmpty) {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            anchor = sel
                                            selection = sel
                                        }
                                    },
                                    onDragEnd = { anchor = null },
                                    onDragCancel = { anchor = null },
                                    onDrag = { change, _ ->
                                        val a = anchor ?: return@detectDragGesturesAfterLongPress
                                        val p = pageLatest.value ?: return@detectDragGesturesAfterLongPress
                                        change.consume()
                                        selection = p.extendSelection(
                                            a, change.position.x, change.position.y, layoutLatest.value,
                                        )
                                    },
                                )
                            }
                            // 选中状态下点空白处取消。翻页手势此时已关掉，不会有人抢这个 tap
                            .pointerInput(selection != null) {
                                if (selection == null) return@pointerInput
                                detectTapGestures { clearSelection() }
                            }
                    ) {
                        PageFlipContainer(
                            onTrace = viewModel::traceInteraction,
                            current = pageUi.page,
                            prev = pageUi.prevPage,
                            next = pageUi.nextPage,
                            layout = layout,
                            theme = session.settings.theme,
                            animation = session.settings.renderedFlipAnimation,
                            animationsEnabled = animationsEnabled(),
                            hapticOnFlip = session.settings.flipHaptic,
                            // 选中期间不翻页：手指还压在选区上，一动就翻页会让人抓狂
                            // 菜单、目录/设置 sheet、换源面板任一个开着都停手势。
                            // sheet 关掉后 pointerInput 因 key 变化重建，避免 ModalBottomSheet
                            // 把 down 留下、此后拖拽翻页全没反应。
                            gesturesEnabled = !overlay.menuVisible &&
                                !overlay.readerSheetOpen &&
                                overlay.sourceCandidates == null &&
                                selection == null,
                            canFlip = { dir ->
                                if (dir == FlipDirection.FORWARD) pageUi.hasNext else pageUi.hasPrev
                            },
                            onCommit = { viewModel.flip(it) },
                            onCenterTap = { viewModel.toggleMenu() },
                            controller = flipController,
                            tapZones = session.settings.tapZones,
                            selection = selection,
                            // 朗读到的那一段只画不拦：传进 selection 会被当成在选字，菜单上滑动翻页没反应
                            highlight = readingHighlight,
                            onImageTap = onImageTap,
                        )
                        PageInfoBar(pageUi, session, layout, showRaisedHeader, menuProgress)
                        SelectionHandles(selectionHandles, Color(session.settings.theme.textColor), Modifier.matchParentSize())
                    }
                }
            }
        }

        // 自动换源之后的提示胶囊。换到的可能是删减版/另一个译本，正文跟原来不是一回事 ——
        // 不说的话用户只会觉得"这书怎么突然变了"，压根想不到是 App 换的源。
        // 停留一段时间给用户留出撤销窗口，随后自动消失，不长期挡视线。
        // 留住最后一次的源名：退场动画播放期间 state 已清空，也得有内容可显示。
        val lastAutoChanged = remember { mutableStateOf("") }
        overlay.autoChangedTo?.let { sourceName ->
            lastAutoChanged.value = sourceName
            LaunchedEffect(sourceName) {
                delay(AUTO_CHANGED_HINT_MS)
                viewModel.dismissAutoChanged()
            }
        }
        // 落在页脚那行（页码 · 进度 · 时间）正上方：页脚照样露着，盖住的只是本页末尾一两行，糊在玻璃底下。
        // 排版还没出来（layout 为 null）时先按一个触控高度垫着
        val hintBottom = layout?.let { with(density) { (it.marginBottomPx + it.footerHeightPx).toDp() } }
            ?: Dimens.touchTarget
        AnimatedVisibility(
            // 阅读菜单、选字工具条也在底部：它们亮着时胶囊先让开（倒计时照走），免得两层玻璃叠在一起
            visible = overlay.autoChangedTo != null && !overlay.menuVisible && selection == null && !speechState.active,
            // 和别的底部悬浮胶囊同一套：从底下升起、降下去（均尊重系统"移除动画"）
            enter = bottomBarEnter(),
            exit = bottomBarExit(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = hintBottom + Dimens.gapS)
                .padding(horizontal = Dimens.screenPadding),
        ) {
            ReaderThemeScope(session.settings.theme) {
                AutoChangedSourceHint(
                    sourceName = lastAutoChanged.value,
                    onUndo = { viewModel.undoAutoChange() },
                    pageBackdrop = pageBackdrop,
                )
            }
        }

        // 长按看大图：盖在整个阅读页最上面。留住最后一张的 id，退场淡出时还有东西可画
        val lastViewed = remember { mutableStateOf("") }
        viewingImage?.let { lastViewed.value = it }
        AnimatedVisibility(
            visible = viewingImage != null,
            enter = scrimEnter(),
            exit = scrimExit(),
            modifier = Modifier.fillMaxSize(),
        ) {
            ReaderImageViewer(
                resourceId = lastViewed.value,
                load = { w, h -> viewModel.loadViewerImage(lastViewed.value, w, h) },
                onDismiss = { viewingImage = null },
            )
        }

        if (pageUi.atBookEnd) {
            LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(1500)
                viewModel.clearBookEnd()
            }
        }
        AnimatedVisibility(
            visible = pageUi.atBookEnd,
            enter = scrimEnter(),
            exit = scrimExit(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Text(
                "已经是最后一页了",
                color = Color(session.settings.theme.footerColor),
                style = MiuixTheme.textStyles.body1,
            )
        }

        // 阅读页里所有浮层（菜单、目录、设置、换源、选字工具条）都跟着**阅读主题**走。
        // 从前它们读的是 App 主题 —— 正文是米色纸张，弹出来的面板却是另一套配色，两套硬拼在一起。
        // 这里换掉这一片区域的 MiuixTheme，里头的页签 / Chip / 滑块 / 分隔线全都自动协调，
        // 不必挨个传颜色（漏一个就露馅）。
        ReaderThemeScope(session.settings.theme) {

        QuickAdjustOverlay(quickAdjust)

        val showSearchReturn = onSearchResultPage && !pageUi.loading && !overlay.menuVisible &&
            !showTextSearch && !overlay.readerSheetOpen && overlay.sourceCandidates == null &&
            !overlay.changingSource && !overlay.autoChanging && overlay.autoChangedTo == null &&
            selection == null && !speechState.active &&
            viewingImage == null && !addShelfPrompt
        val lastSearchPosition = remember { mutableStateOf("") }
        searchDestination?.let {
            lastSearchPosition.value = "第 ${it.matchNumber} / ${it.totalMatches} 处"
        }
        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = hintBottom)) {
            CompositionLocalProvider(LocalBarBackdrop provides pageBackdrop) {
                FloatingGlassBar(visible = showSearchReturn, onHeightChange = {}) { floor ->
                    val scheme = MiuixTheme.colorScheme
                    Text(
                        lastSearchPosition.value,
                        color = AppThemes.readableOn(scheme.onSurface, floor),
                        style = MiuixTheme.textStyles.footnote1,
                        modifier = Modifier.padding(start = Dimens.gapM, end = Dimens.gapS),
                    )
                    AppTextButton(
                        "返回结果",
                        onClick = { showTextSearch = true },
                        contentColor = AppThemes.readableOn(scheme.primary, floor),
                    )
                }
            }
        }
        ReaderTextSearchSheet(
            visible = showTextSearch,
            isNetBook = session.isNetBook,
            viewModel = viewModel,
            onDismiss = { showTextSearch = false },
            onOpenMatch = {
                showTextSearch = false
                viewModel.dismissMenu()
                viewModel.openTextMatch(it)
            },
        )

        if (showSpeechSheet && speechState.active) {
            ReaderSpeechSheet(
                speech = speech,
                state = speechState,
                onDismiss = { showSpeechSheet = false },
                onStop = viewModel::endSpeech,
                onRetry = {
                    val service = speech.service
                    if (service == null) {
                        speech.stop()
                        viewModel.startSpeech(readerContext)
                    } else if (speechState.status == SpeechStatus.NO_VOICE) {
                        service.retryVoice()
                    } else {
                        service.play()
                    }
                },
            )
        }
        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = Dimens.gapS)) {
            CompositionLocalProvider(LocalBarBackdrop provides pageBackdrop) {
                MiniSpeechPlayer(
                    speech = speech,
                    state = speechState,
                    visible = speechState.active && !showSpeechSheet && !overlay.menuVisible &&
                        !overlay.readerSheetOpen && overlay.sourceCandidates == null && selection == null &&
                        viewingImage == null && !addShelfPrompt,
                    onOpen = { showSpeechSheet = true },
                    onHeightChange = { miniPlayerHeight = it },
                )
            }
        }

        overlay.sourceCandidates?.let { candidates ->
            ChangeSourceSheet(
                overlay = overlay,
                currentSourceName = session.currentSourceName,
                bookTitle = session.bookTitle,
                bookAuthor = session.bookAuthor,
                candidates = candidates,
                onApplySource = { viewModel.applyChangeSource(it) },
                onToggleCheckAuthor = { viewModel.setCheckAuthor(it) },
                onRefresh = { viewModel.searchOtherSources(force = true) },
                onDismiss = { viewModel.dismissSourcePanel() },
            )
        }

        selection?.let { sel ->
            val header = layout?.let { with(density) { (it.marginTopPx + it.headerHeightPx).toDp() } } ?: 0.dp
            val toolbarSpace = Dimens.selectionToolbarClearance
            val closeToTop = selectionHandles.values.any { with(density) { it.anchor.y.toDp() } < header + toolbarSpace }
            val closeToBottom = selectionHandles.values.any {
                with(density) { (viewport.height - it.anchor.y).toDp() } < hintBottom + toolbarSpace
            }
            val toolbarPosition = when {
                closeToTop && closeToBottom -> Modifier.align(Alignment.Center)
                closeToBottom -> Modifier.align(Alignment.TopCenter).padding(top = header + Dimens.gapS)
                else -> Modifier.align(Alignment.BottomCenter).padding(bottom = hintBottom + Dimens.gapS)
            }
            SelectionToolbar(
                selectedText = sel.text,
                canPurify = sel.isSingleParagraph,
                onStepBoundary = step@{ edge, forward ->
                    val active = selection ?: return@step false
                    val updated = selectionPage?.stepSelectionEnd(active, edge, forward) ?: return@step false
                    if (updated == active) return@step false
                    selection = updated
                    true
                },
                onCopy = {
                    scope.launch {
                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("selection", sel.text)))
                        clearSelection()
                    }
                },
                onPurify = {
                    viewModel.purifySelection(sel.text)
                    clearSelection()
                },
                onReplace = { find, to ->
                    viewModel.purifySelection(find, to)
                    clearSelection()
                },
                // 原文每改一个字算一遍：本章各段从分页缓存现取（一串引用），数一遍是毫秒级。
                // 锚点是选区所在，预览优先给用户选的那一处
                replacePreview = { find ->
                    val chapterIndex = selectionPage?.spec?.chapterIndex ?: pageUi.chapterIndex
                    replacePreview(viewModel.chapterParagraphs(chapterIndex), find, anchor = sel.elementIndex to sel.start)
                },
                modifier = toolbarPosition,
                pageBackdrop = pageBackdrop,
            )
        }

        // 一次性提示（建了净化规则、换源失败、本章未净化……）。
        // 从 SharedFlow 收，不是从 StateFlow 字段读：后者对相同值去重，连着两次同样的提示第二次直接没了。
        // collectLatest：新提示来了直接替换正在显示的那条，重新计时，不排队（同 CollectMessages）。
        // 外层常驻、挂 liveRegion：读屏用户看不见这行字，内容一变 TalkBack 就念出来；
        // key(序号) 让同一句话再来一次时也是个新节点，照样会念。
        var toastText by remember { mutableStateOf("") }
        var toastSeq by remember { mutableIntStateOf(0) }
        var toastVisible by remember { mutableStateOf(false) }
        LaunchedEffect(viewModel) {
            viewModel.messages.collectLatest { msg ->
                toastText = msg
                toastSeq++
                toastVisible = true
                delay(READER_TOAST_MS)
                toastVisible = false
            }
        }
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .then(if (overlay.menuVisible) Modifier.windowInsetsPadding(readerMenuTopInsets()) else Modifier)
                .padding(top = if (overlay.menuVisible) Dimens.toolbarHeight + Dimens.gapS else Dimens.touchTarget)
                .semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            AnimatedVisibility(
                visible = toastVisible,
                enter = scrimEnter(),
                exit = scrimExit(),
            ) {
                key(toastSeq) {
                    Text(
                        toastText,
                        color = Color(session.settings.theme.footerColor),
                        style = MiuixTheme.textStyles.body1,
                    )
                }
            }
        }

        // 常驻组合，可见性交给 AnimatedVisibility，保留退场动画。
        ReaderMenu(
            visible = overlay.menuVisible,
            chapterHandoff = menuProgress.takeIf { layout?.raisedHeader != null },
            session = session,
            overlay = overlay,
            pageUi = pageUi,
            toc = viewModel.toc,
            tocRule = viewModel.tocRule,
            onApplyTocRule = viewModel::applyTocRule,
            onExit = requestExit,
            onGotoChapter = viewModel::jumpFromReaderMenu,
            onGotoTocEntry = viewModel::jumpFromToc,
            onShowTextSearch = {
                viewModel.stopAutoFlip()
                showTextSearch = true
            },
            onSeekPercent = { viewModel.seekToPercent(it) },
            onUpdateSettings = { viewModel.updateSettings(it) },
            onPreviewSettings = { viewModel.previewSettings(it) },
            onCommitSettings = { viewModel.commitSettings() },
            onSelectPaper = viewModel::selectPaper,
            onCustomPaperChange = viewModel::updateCustomPaper,
            onSetTextSelection = { viewModel.setTextSelectionEnabled(it) },
            typography = rememberTypographyActions(viewModel),
            onSearchSources = { viewModel.searchOtherSources() },
            onRefreshChapter = { viewModel.refreshCurrentChapter() },
            onToggleAutoFlip = viewModel::toggleAutoFlip,
            onReadAloud = {
                if (speechState.active) viewModel.dismissMenu() else viewModel.startSpeech(readerContext)
                showSpeechSheet = speech.state.value.active
            },
            speechActive = speechState.active,
            onDismiss = { viewModel.toggleMenu() },
            onSwipePage = { dir ->
                UsageLog.event("reader.input.menu_swipe", "direction" to dir.name)
                // 先关菜单再翻：菜单开着时 gesturesEnabled=false，会把正在播的 settle 掐掉。
                viewModel.dismissMenu()
                if (session.settings.flipAnimation == FlipAnimation.SCROLL) {
                    viewModel.flip(dir)
                } else {
                    flipController.requestFlip(dir)
                }
            },
            onReaderSheetOpen = { menuSheetOpen = it },
            pageBackdrop = pageBackdrop,
        )

        }
    }

    if (addShelfPrompt) {
        AppAlertDialog(
            onDismissRequest = { if (!settlingShelf) addShelfPrompt = false },
            title = "加入书架",
            text = "要把《${session.bookTitle}》加入书架吗？",
            confirmText = "加入",
            dismissText = "不加入",
            confirmEnabled = !settlingShelf,
            dismissEnabled = !settlingShelf,
            onConfirm = {
                settlingShelf = true
                scope.launch {
                    viewModel.commitToShelf()
                    addShelfPrompt = false
                    onExit()
                }
            },
            onDismiss = {
                settlingShelf = true
                scope.launch {
                    viewModel.discardUnshelved()
                    addShelfPrompt = false
                    onExit()
                }
            },
        )
    }
}

/** 挖孔的四边留白，单位 px。只是个不带 Android 类型的小载体，方便比较相等。 */
internal data class IntInsets(val left: Int, val top: Int, val right: Int, val bottom: Int)

internal data class ReaderStatusArea(
    val heightPx: Int,
    val cutoutBounds: List<IntRect> = emptyList(),
    val waterfallTopPx: Int = 0,
)

internal fun buildLayoutSpec(
    viewport: IntSize,
    settings: ReaderSettings,
    density: Density,
    cutout: IntInsets,
    infoLineHeight: TextUnit,
    speechInsetPx: Float,
    statusArea: ReaderStatusArea = ReaderStatusArea(0),
    layoutDirection: LayoutDirection = LayoutDirection.Ltr,
): LayoutSpec = with(density) {
    val fontSizePx = settings.fontSizeSp.sp.toPx()
    val infoLinePx = if (infoLineHeight.isSp) infoLineHeight.toPx() else 18.dp.toPx()
    val leftMargin = settings.marginHorizontalDp.dp.toPx() + cutout.left
    val rightMargin = settings.marginHorizontalDp.dp.toPx() + cutout.right
    val raisedHeader = if (settings.usesRaisedHeader) {
        raisedReaderHeader(
            viewport.width, leftMargin, rightMargin, cutout.top, statusArea, layoutDirection,
            infoLinePx,
        )
    } else null
    val preferredTop = (settings.effectiveHeaderTopSpacingDp.dp.toPx() + cutout.top).coerceAtLeast(0f)
    val marginTop = when {
        raisedHeader != null -> maxOf(preferredTop, cutout.top.toFloat(), raisedHeader.topPx + infoLinePx + Dimens.gapS.toPx())
        settings.showStatusBar || settings.showHeader -> maxOf(preferredTop, cutout.top.toFloat(), statusArea.heightPx.toFloat())
        else -> preferredTop
    }
    // 挖孔折进边距：视口铺满全屏，靠加大这一侧的留白把页眉/正文推开摄像头，而不是把整页缩进去。
    LayoutSpec(
        viewportWidthPx = viewport.width,
        viewportHeightPx = viewport.height,
        marginLeftPx = leftMargin,
        marginRightPx = rightMargin,
        marginTopPx = marginTop,
        marginBottomPx = settings.marginVerticalDp.coerceAtLeast(0f).dp.toPx() + cutout.bottom + speechInsetPx,
        // 页眉上移后已经由 marginTop 让开，正文无需再留一条页眉带。
        headerHeightPx = if (settings.showHeader && raisedHeader == null) maxOf(26.dp.toPx(), infoLinePx + Dimens.gapS.toPx()) else 0f,
        footerHeightPx = if (settings.showFooter) maxOf(18.dp.toPx(), infoLinePx) else 0f,
        fontSizePx = fontSizePx,
        lineHeightPx = fontSizePx * settings.lineSpacingMult,
        paragraphSpacingPx = fontSizePx * settings.paragraphSpacingEm,
        titleFontScale = settings.titleScale,
        titleTopSpacingPx = settings.effectiveTitleTopSpacingDp.dp.toPx(),
        firstLineIndentEm = settings.firstLineIndentEm,
        fontId = settings.fontId,
        justify = settings.justify,
        letterSpacingEm = settings.letterSpacingEm,
        bodyFontWeight = settings.bodyFontWeight,
        showImages = settings.showImages,
        density = density.density,
        raisedHeader = raisedHeader,
    )
}

private fun Density.raisedReaderHeader(
    viewportWidthPx: Int,
    leftMarginPx: Float,
    rightMarginPx: Float,
    cutoutTopPx: Int,
    statusArea: ReaderStatusArea,
    layoutDirection: LayoutDirection,
    lineHeightPx: Float,
): RaisedReaderHeader? {
    if (statusArea.heightPx <= 0) return null
    val availableHeight = maxOf(statusArea.heightPx, cutoutTopPx) - statusArea.waterfallTopPx
    if (availableHeight < lineHeightPx) return null
    // 已知顶部有挖孔却拿不到它的横向位置时，退回安全区下方。
    if (cutoutTopPx > statusArea.waterfallTopPx && statusArea.cutoutBounds.none { it.top < cutoutTopPx && it.bottom > 0 }) {
        return null
    }
    val top = statusArea.waterfallTopPx + (availableHeight - lineHeightPx) / 2f
    val right = viewportWidthPx - rightMarginPx
    val clearancePx = Dimens.gapS.toPx()
    val obstacles = statusArea.cutoutBounds.filter {
        it.top < top + lineHeightPx && it.bottom > top &&
            it.left - clearancePx < right && it.right + clearancePx > leftMarginPx
    }
    val leftEdge = if (layoutDirection == LayoutDirection.Rtl) {
        obstacles.maxOfOrNull { it.right + clearancePx } ?: leftMarginPx
    } else leftMarginPx
    val rightEdge = if (layoutDirection == LayoutDirection.Ltr) {
        obstacles.minOfOrNull { it.left - clearancePx } ?: right
    } else right
    if (rightEdge - leftEdge < maxOf((Dimens.touchTarget * 2).toPx(), lineHeightPx * 4)) return null
    return RaisedReaderHeader(top, leftEdge, viewportWidthPx - rightEdge)
}

@Composable
private fun BrightnessEffect(windowBrightness: ReaderWindowBrightness?, brightness: Float?) {
    if (windowBrightness == null) return
    val reader = remember(windowBrightness) { Any() }
    DisposableEffect(windowBrightness, reader) {
        windowBrightness.attach(reader)
        onDispose { windowBrightness.detach(reader) }
    }
    SideEffect { windowBrightness.update(reader, brightness) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun readerMenuTopInsets(): WindowInsets =
    WindowInsets.statusBarsIgnoringVisibility.union(WindowInsets.displayCutout).only(WindowInsetsSides.Top)

@Composable
private fun ReaderSystemBarsEffect(
    activity: Activity?,
    showNavigationBar: Boolean,
    showStatusBar: Boolean,
    lightPaper: Boolean,
    lightApp: Boolean,
) {
    val window = activity?.window ?: return
    val controller = remember(window) { WindowInsetsControllerCompat(window, window.decorView) }
    val latestLightApp by rememberUpdatedState(lightApp)
    val systemBars = WindowInsetsCompat.Type.systemBars()
    val statusBar = WindowInsetsCompat.Type.statusBars()
    val navigationBar = WindowInsetsCompat.Type.navigationBars()
    // 开合面板只切换可见性。退出阅读时才归还系统栏和 App 主题的图标颜色。
    DisposableEffect(controller) {
        val previousBehavior = controller.systemBarsBehavior
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose {
            controller.systemBarsBehavior = previousBehavior
            controller.isAppearanceLightStatusBars = latestLightApp
            controller.isAppearanceLightNavigationBars = latestLightApp
            controller.show(systemBars)
        }
    }
    SideEffect {
        if (controller.isAppearanceLightStatusBars != lightPaper) controller.isAppearanceLightStatusBars = lightPaper
        if (controller.isAppearanceLightNavigationBars != lightPaper) controller.isAppearanceLightNavigationBars = lightPaper
    }
    LaunchedEffect(controller, showStatusBar) {
        if (showStatusBar) controller.show(statusBar) else controller.hide(statusBar)
    }
    LaunchedEffect(controller, showNavigationBar) {
        if (showNavigationBar) controller.show(navigationBar) else controller.hide(navigationBar)
    }
}

@Composable
private fun KeepScreenOnEffect(activity: Activity?, keepOn: Boolean) {
    DisposableEffect(activity, keepOn) {
        val window = activity?.window
        if (keepOn) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}
