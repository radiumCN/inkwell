package com.radium.inkwell.ui.reader

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import com.radium.inkwell.reader.render.TextSelection
import com.radium.inkwell.reader.render.extendSelection
import com.radium.inkwell.reader.render.selectWordAt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.style.TextAlign
import com.radium.inkwell.ui.components.PrimaryButton
import com.radium.inkwell.ui.components.SecondaryButton
import androidx.compose.runtime.snapshotFlow
import com.radium.inkwell.ui.components.BookCover
import com.radium.inkwell.ui.components.Motion
import com.radium.inkwell.ui.components.animationsEnabled
import com.radium.inkwell.ui.components.scrimEnter
import com.radium.inkwell.ui.components.scrimExit
import com.radium.inkwell.reader.api.FlipAnimation
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.radium.inkwell.ui.components.Dimens
import androidx.compose.ui.unit.sp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radium.inkwell.reader.api.FlipDirection
import com.radium.inkwell.reader.api.ReaderSettings
import com.radium.inkwell.reader.flip.FlipController
import com.radium.inkwell.reader.flip.PageFlipContainer
import com.radium.inkwell.reader.measure.ComposeTextMeasureFacade
import com.radium.inkwell.reader.measure.SystemFontRegistry
import com.radium.inkwell.reader.paginate.LayoutSpec
import com.radium.inkwell.util.KeyEventBus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

/** 自动换源提示条的停留时长：够看清并有机会撤销，又不至于长期挡住正文 */
private const val AUTO_CHANGED_HINT_MS = 8_000L

@Composable
fun ReaderScreen(
    bookId: String,
    onExit: () -> Unit,
    viewModel: ReaderViewModel = koinViewModel { parametersOf(bookId) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scrollChapters by viewModel.scrollChapters.collectAsStateWithLifecycle()
    val density = LocalDensity.current
    val fontFamilyResolver = LocalFontFamilyResolver.current
    val keyBus = koinInject<KeyEventBus>()
    val flipController = remember { FlipController() }

    // 阅读纸张跟 App 的日夜走：算出此刻生效的是日间还是夜间，喂给 ViewModel 切日/夜槽。
    val appPrefs = koinInject<com.radium.inkwell.data.prefs.AppPrefs>()
    val themeConfig by appPrefs.themeConfig.collectAsStateWithLifecycle(
        initialValue = com.radium.inkwell.ui.theme.ThemeConfig(),
    )
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
     * （[rootDisplayCutout]，同步），之后以 Compose 的响应式值为准 —— 旋转、折叠、
     * 分屏时它会自己更新，不必去猜什么时候该让缓存失效。
     *
     * 两者一致时（绝大多数情况）视口从头到尾没变过，压根不会有第二次排版。
     */
    val view = LocalView.current
    val windowSize = LocalWindowInfo.current.containerSize
    val seed = remember(view, windowSize) { rootDisplayCutout(view) }

    val live = WindowInsets.displayCutout
    val layoutDirection = LocalLayoutDirection.current
    // 逐个字段读，让 inset 变化能触发重组；全 0 就说明这一帧还没派发，用同步种子顶上。
    // （真没有挖孔的设备上，种子本身也是 0，取谁都一样。）
    val l = live.getLeft(density, layoutDirection)
    val t = live.getTop(density)
    val r = live.getRight(density, layoutDirection)
    val b = live.getBottom(density)
    val cutout = if (l == 0 && t == 0 && r == 0 && b == 0) seed else IntInsets(l, t, r, b)

    // 视口 = **整个窗口**（边到边）。挖孔不再从视口里扣掉，而是折进正文边距（见下）——
    // 这样正文页与卷页都铺满全屏，顶部不再留一条状态栏/挖孔高度的空带（卷页时会露出来）。
    val viewport = IntSize(windowSize.width, windowSize.height)

    val spec = remember(viewport, state.settings, density, cutout) {
        if (viewport.width <= 0 || viewport.height <= 0) null
        else buildLayoutSpec(viewport, state.settings, density, cutout)
    }

    LaunchedEffect(spec) {
        val settled = spec ?: return@LaunchedEffect
        val facade = ComposeTextMeasureFacade(fontFamilyResolver, density, SystemFontRegistry)
        viewModel.onLayoutReady(facade, settled)
    }

    // 音量键翻页（与点击共用动画路径）
    LaunchedEffect(state.settings.volumeKeyFlip, state.menuVisible, state.settings.flipAnimation) {
        // 滚动模式下没有"页"，音量键翻页无从谈起 —— 按下去只会把游标推到别处、把人转晕
        keyBus.volumeFlipEnabled = state.settings.volumeKeyFlip &&
            !state.menuVisible &&
            state.settings.flipAnimation != FlipAnimation.SCROLL
    }
    LaunchedEffect(Unit) {
        keyBus.flipEvents.collect { flipController.requestFlip(it) }
    }
    DisposableEffect(Unit) {
        onDispose { keyBus.volumeFlipEnabled = false }
    }

    var selection by remember { mutableStateOf<TextSelection?>(null) }
    var anchor by remember { mutableStateOf<TextSelection?>(null) }
    val haptic = LocalHapticFeedback.current
    val clipboard = LocalClipboardManager.current

    // 系统返回：先收起暂态浮层（选字/面板/菜单），都关着才真正退出阅读页。
    // 否则菜单开着按返回会直接跳出阅读，用户以为只是想关掉菜单。
    BackHandler(
        state.menuVisible || selection != null ||
            state.searchResults != null || state.sourceCandidates != null,
    ) {
        when {
            selection != null -> { selection = null; anchor = null }
            state.searchResults != null -> viewModel.dismissSearch()
            state.sourceCandidates != null -> viewModel.dismissSourcePanel()
            state.menuVisible -> viewModel.toggleMenu()
        }
    }

    val activity = LocalActivity.current
    BrightnessEffect(activity, state.settings.brightnessOverride)
    KeepScreenOnEffect(activity, state.settings.keepScreenOn)
    // 阅读时隐藏系统状态栏/导航栏，呼出菜单时恢复
    ImmersiveEffect(activity, immersive = !state.menuVisible)

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(state.settings.theme.background))
            // 不在这里扣挖孔 —— 页要铺满全屏（卷页才不会在顶上露出空带）。避开摄像头改由
            // 正文边距承担（buildLayoutSpec 把 cutout 折进 margin）。阅读菜单顶栏用它自己的
            // statusBarsPadding 让开状态栏，不受这里影响。
    ) {
        val layout = spec

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
        LaunchedEffect(state.loading, layout) {
            if (!state.loading && layout != null) firstContentShown = true
        }
        LaunchedEffect(Unit) {
            delay(Motion.READER_SPLASH_DELAY_MS)
            if (firstContentShown) return@LaunchedEffect // 热开书：这段窗口内已就绪，不出场
            splashVisible = true
            delay(Motion.READER_SPLASH_MIN_MS)           // 露了面就待满，杜绝一闪
            snapshotFlow { firstContentShown }.first { it }
            splashVisible = false
        }

        // 排版一就绪就排滚动模式的章节。不能放在下面的 SCROLL 分支里：
        // 初始 loading=true 时那个分支根本走不到，prepareScroll 永远不会被调用 —— 死锁。
        if (state.settings.flipAnimation == FlipAnimation.SCROLL) {
            LaunchedEffect(layout, state.chapterCount) {
                if (layout != null && state.chapterCount > 0) viewModel.prepareScroll()
            }
        }

        when {
            // 排在 error 前面：自动换源正是由报错触发的，底下那条 error 还挂着。
            // 让用户对着"章节加载失败"干等，却不知道 App 其实正在替他找源 —— 那叫失联。
            state.autoChanging -> Column(
                Modifier.align(Alignment.Center).padding(Dimens.gapXXL),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(color = Color(state.settings.theme.textColor))
                Spacer(Modifier.height(Dimens.gapXL))
                Text(
                    "正在自动换源…",
                    color = Color(state.settings.theme.textColor),
                    textAlign = TextAlign.Center,
                )
                if (state.autoChangeTotal > 0) {
                    Spacer(Modifier.height(Dimens.gapS))
                    Text(
                        "已试 ${state.autoChangeDone}/${state.autoChangeTotal} 个书源",
                        color = Color(state.settings.theme.footerColor),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            // 包在 ReaderThemeScope 里：重试/换源按钮跟着**纸张主题**走，
            // 不再是米色纸面上浮着两颗 App 主题色的按钮（阅读主题与 App 主题常不一致）。
            state.error != null -> ReaderThemeScope(state.settings.theme) {
                Column(
                    Modifier.align(Alignment.Center).padding(Dimens.gapXXL),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        state.error!!,
                        color = Color(state.settings.theme.textColor),
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(Dimens.gapXL))
                    // 「正文规则未匹配到内容」正是最该换源的时刻。从前这里只有一行字，
                    // 菜单又呼不出来（翻页容器压根没渲染），用户只能退出去 —— 死路一条。
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.gapM)) {
                        SecondaryButton(text = "重试", onClick = { viewModel.retry() })
                        if (state.isNetBook) {
                            PrimaryButton(
                                text = "换源",
                                onClick = { viewModel.searchOtherSources() },
                            )
                        }
                    }
                    Spacer(Modifier.height(Dimens.gapS))
                    TextButton(onClick = onExit) {
                        Text("返回书架", color = Color(state.settings.theme.footerColor))
                    }
                }
            }
            // 头 READER_SPLASH_DELAY_MS 内纸面保持干净（连转圈都不出），让展开动画那段不被打扰；
            // 之后仍没就位才亮出封面 + 转圈。包一层 ReaderThemeScope，让书封的表面色/圆角跟纸张
            // 主题走，不然浅色书封压在深色纸上会打架。
            state.loading || layout == null -> ReaderThemeScope(state.settings.theme) {
                AnimatedVisibility(
                    visible = splashVisible,
                    modifier = Modifier.align(Alignment.Center),
                    enter = scrimEnter(),
                    exit = scrimExit(),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        // 自然尺寸居中，**不铺满** —— 书封是 3:4 缩略图，撑到全屏必糊
                        // （0.1.6-beta.3~6 那条失败路线就栽在这）。
                        BookCover(
                            title = state.bookTitle,
                            coverModel = state.coverPath,
                            modifier = Modifier.size(
                                Dimens.readerSplashCoverWidth,
                                Dimens.readerSplashCoverHeight,
                            ),
                            placeholderChars = 14,
                        )
                        Spacer(Modifier.height(Dimens.gapXL))
                        // 封面只交代"在开哪本书"，还得有个东西说明"仍在工作中"，
                        // 否则慢网络下一张静止的封面像卡死了
                        CircularProgressIndicator(color = Color(state.settings.theme.textColor))
                    }
                }
            }
            // 滚动模式：另一条渲染路径，不走翻页容器
            state.settings.flipAnimation == FlipAnimation.SCROLL -> {
                ScrollReader(
                    chapters = scrollChapters,
                    layout = layout,
                    theme = state.settings.theme,
                    onVisible = { chapterIndex, elementIndex ->
                        viewModel.onScrollTo(chapterIndex, elementIndex)
                    },
                    onCenterTap = { viewModel.toggleMenu() },
                )
            }

            else -> {
                val page = state.page
                Box(
                    Modifier
                        .fillMaxSize()
                        // 长按选字。放在**父节点**上而不是覆盖一层同级的 Box：
                        // 同级的覆盖层会把所有手势从翻页容器手里抢走（Compose 命中测试
                        // 只把事件给最上面那个兄弟节点）。父节点则是在子节点之后收到事件，
                        // 子节点没消费的才轮到它 —— 静止长按恰好没人消费。
                        .pointerInput(state.textSelectionEnabled, page, spec) {
                            if (!state.textSelectionEnabled || page == null) return@pointerInput
                            detectDragGesturesAfterLongPress(
                                onDragStart = { pos ->
                                    val sel = page.selectWordAt(pos.x, pos.y, layout)
                                    if (sel != null && !sel.isEmpty) {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        anchor = sel
                                        selection = sel
                                    }
                                },
                                onDrag = { change, _ ->
                                    val a = anchor ?: return@detectDragGesturesAfterLongPress
                                    change.consume()
                                    selection = page.extendSelection(
                                        a, change.position.x, change.position.y, layout,
                                    )
                                },
                            )
                        }
                        // 选中状态下点空白处取消。翻页手势此时已关掉，不会有人抢这个 tap
                        .pointerInput(selection != null) {
                            if (selection == null) return@pointerInput
                            detectTapGestures { selection = null; anchor = null }
                        }
                ) {
                    PageFlipContainer(
                        current = state.page,
                        prev = state.prevPage,
                        next = state.nextPage,
                        layout = layout,
                        theme = state.settings.theme,
                        animation = state.settings.flipAnimation,
                        animationsEnabled = animationsEnabled(),
                        hapticOnFlip = state.settings.flipHaptic,
                        // 选中期间不翻页：手指还压在选区上，一动就翻页会让人抓狂
                        gesturesEnabled = !state.menuVisible && selection == null,
                        canFlip = { dir ->
                            if (dir == FlipDirection.FORWARD) state.hasNext else state.hasPrev
                        },
                        onCommit = { viewModel.flip(it) },
                        onCenterTap = { viewModel.toggleMenu() },
                        controller = flipController,
                        selection = selection,
                    )
                    PageInfoBar(state, layout)
                }
            }
        }

        // 自动换源之后的提示条。换到的可能是删减版/另一个译本，正文跟原来不是一回事 ——
        // 不说的话用户只会觉得"这书怎么突然变了"，压根想不到是 App 换的源。
        // 停留一段时间给用户留出撤销窗口，随后自动消失，不长期挡视线（撤销/知道了 仍可手动触发）。
        // 提示条淡入淡出，跟阅读菜单浮层同一套动效（scrimEnter/Exit，均尊重系统"关闭动画"）。
        // 留住最后一次的源名：退场动画播放期间 state 已清空，也得有内容可显示。
        val lastAutoChanged = remember { mutableStateOf("") }
        state.autoChangedTo?.let { sourceName ->
            lastAutoChanged.value = sourceName
            LaunchedEffect(sourceName) {
                delay(AUTO_CHANGED_HINT_MS)
                viewModel.dismissAutoChanged()
            }
        }
        AnimatedVisibility(
            visible = state.autoChangedTo != null,
            enter = scrimEnter(),
            exit = scrimExit(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            ReaderThemeScope(state.settings.theme) {
                Surface(
                    Modifier.padding(horizontal = Dimens.listHorizontal, vertical = Dimens.listVertical),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    tonalElevation = 3.dp,
                ) {
                    Row(
                        Modifier.padding(
                            start = Dimens.gapM,
                            end = Dimens.gapXS,
                            top = Dimens.gapXS,
                            bottom = Dimens.gapXS,
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "已自动换到「${lastAutoChanged.value}」",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        TextButton(onClick = { viewModel.undoAutoChange() }) { Text("撤销") }
                        TextButton(onClick = { viewModel.dismissAutoChanged() }) { Text("知道了") }
                    }
                }
            }
        }

        if (state.atBookEnd) {
            LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(1500)
                viewModel.clearBookEnd()
            }
        }
        AnimatedVisibility(
            visible = state.atBookEnd,
            enter = scrimEnter(),
            exit = scrimExit(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Text(
                "已经是最后一页了",
                color = Color(state.settings.theme.footerColor),
            )
        }

        // 阅读页里所有浮层（菜单、目录、设置、换源、全书搜索、选字工具条）都跟着**阅读主题**走。
        // 从前它们读的是 App 的 MaterialTheme —— 正文是米色纸张，弹出来的面板却是 M3 的
        // 淡紫白，两套配色硬拼在一起。这里换掉这一片区域的 MaterialTheme，
        // 里头的 TabRow / Chip / Slider / 分隔线全都自动协调，不必挨个传颜色（漏一个就露馅）。
        ReaderThemeScope(state.settings.theme) {

        if (state.searchResults != null) {
            BookSearchSheet(
                state = state,
                onSearch = { viewModel.searchInBook(it) },
                onCancel = { viewModel.cancelSearch() },
                onSelect = { viewModel.gotoHit(it) },
                onDismiss = { viewModel.dismissSearch() },
            )
        }

        state.sourceCandidates?.let { candidates ->
            ChangeSourceSheet(
                state = state,
                candidates = candidates,
                onApplySource = { viewModel.applyChangeSource(it) },
                onToggleCheckAuthor = { viewModel.setCheckAuthor(it) },
                onDismiss = { viewModel.dismissSourcePanel() },
            )
        }

        selection?.let { sel ->
            SelectionToolbar(
                selectedText = sel.text,
                onCopy = {
                    clipboard.setText(AnnotatedString(sel.text))
                    selection = null; anchor = null
                },
                onPurify = {
                    viewModel.purifySelection(sel.text)
                    selection = null; anchor = null
                },
                onReplace = { to ->
                    viewModel.purifySelection(sel.text, to)
                    selection = null; anchor = null
                },
                onDismiss = { selection = null; anchor = null },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        val lastToast = remember { mutableStateOf("") }
        state.toast?.let { msg ->
            lastToast.value = msg
            LaunchedEffect(msg) {
                kotlinx.coroutines.delay(1800)
                viewModel.clearToast()
            }
        }
        AnimatedVisibility(
            visible = state.toast != null,
            enter = scrimEnter(),
            exit = scrimExit(),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 48.dp),
        ) {
            Text(
                lastToast.value,
                color = Color(state.settings.theme.footerColor),
            )
        }

        // 常驻组合，可见性交给 AnimatedVisibility —— 否则退场动画根本没机会播
        ReaderMenu(
            visible = state.menuVisible,
            state = state,
            onExit = onExit,
            onGotoChapter = { viewModel.gotoChapter(it) },
            onSeekPercent = { viewModel.seekToPercent(it) },
            onUpdateSettings = { viewModel.updateSettings(it) },
            onSetTextSelection = { viewModel.setTextSelectionEnabled(it) },
            onSearchSources = { viewModel.searchOtherSources() },
            onToggleAutoFlip = { viewModel.toggleAutoFlip() },
            onOpenSearch = { viewModel.openSearchPanel() },
            onDismiss = { viewModel.toggleMenu() },
        )

        }
    }
}

/** 挖孔的四边留白，单位 px。只是个不带 Android 类型的小载体，方便比较相等。 */
private data class IntInsets(val left: Int, val top: Int, val right: Int, val bottom: Int)

/**
 * 同步读窗口当前的挖孔 inset。
 *
 * Compose 的 `WindowInsets.displayCutout` 是**响应式**的，代价是新子树的首帧读不到值。
 * 而 window 上挂着的 rootWindowInsets 是**已经在那儿**的 —— Activity 早就布局过了。
 * 首帧要一个正确的值，只能问它。
 *
 * view 还没 attach 时拿不到（返回 null），退回全 0：那就是从前的行为（首帧按全屏排、
 * 第二帧纠正），不会更糟。
 */
private fun rootDisplayCutout(view: android.view.View): IntInsets {
    val insets = ViewCompat.getRootWindowInsets(view)
        ?.getInsets(WindowInsetsCompat.Type.displayCutout())
        ?: return IntInsets(0, 0, 0, 0)
    return IntInsets(insets.left, insets.top, insets.right, insets.bottom)
}

private fun buildLayoutSpec(
    viewport: IntSize,
    settings: ReaderSettings,
    density: androidx.compose.ui.unit.Density,
    cutout: IntInsets,
): LayoutSpec = with(density) {
    val fontSizePx = settings.fontSizeSp.sp.toPx()
    // 挖孔折进边距：视口铺满全屏，靠加大这一侧的留白把页眉/正文推开摄像头，而不是把整页缩进去。
    LayoutSpec(
        viewportWidthPx = viewport.width,
        viewportHeightPx = viewport.height,
        marginLeftPx = settings.marginHorizontalDp.dp.toPx() + cutout.left,
        marginRightPx = settings.marginHorizontalDp.dp.toPx() + cutout.right,
        // 上边距 = 头部留白：它就是正文顶端的位置（页眉小标题落在这条带里）。缩小它，
        // 正文顶跟着上移、多出显示空间。下边距用 marginVerticalDp。
        marginTopPx = settings.headerTopSpacingDp.dp.toPx() + cutout.top,
        marginBottomPx = settings.marginVerticalDp.dp.toPx() + cutout.bottom,
        // 正文首行落在 marginTop + headerHeight，而页眉章节名画在 marginTop —— 两者的间距就是
        // headerHeight 减去页眉字高。18dp 里塞进 11sp 的章节名后只剩 ~4dp，比段间距还窄，
        // 页眉像贴在正文上。抬到 26dp，让页眉与正文拉开一个不小于段间距的舒服距离。
        headerHeightPx = 26.dp.toPx(),
        footerHeightPx = 18.dp.toPx(),
        fontSizePx = fontSizePx,
        lineHeightPx = fontSizePx * settings.lineSpacingMult,
        paragraphSpacingPx = fontSizePx * settings.paragraphSpacingEm,
        titleFontScale = settings.titleScale,
        titleTopSpacingPx = settings.titleTopSpacingDp.dp.toPx(),
        firstLineIndentEm = settings.firstLineIndentEm,
        fontId = settings.fontId,
        justify = settings.justify,
    )
}

@Composable
private fun BrightnessEffect(activity: Activity?, brightness: Float?) {
    DisposableEffect(activity, brightness) {
        val window = activity?.window
        if (window != null) {
            val attrs = window.attributes
            attrs.screenBrightness = brightness?.coerceIn(0.01f, 1f)
                ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            window.attributes = attrs
        }
        onDispose {
            val w = activity?.window ?: return@onDispose
            val attrs = w.attributes
            attrs.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            w.attributes = attrs
        }
    }
}

@Composable
private fun ImmersiveEffect(activity: Activity?, immersive: Boolean) {
    DisposableEffect(activity, immersive) {
        val window = activity?.window
        if (window != null) {
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (immersive) {
                controller.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            // 退出阅读页恢复系统栏
            activity?.window?.let {
                WindowInsetsControllerCompat(it, it.decorView)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
        }
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
