package com.radium.inkwell.ui.reader

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.PaddingValues
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
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

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

    val viewport = IntSize(
        (windowSize.width - cutout.left - cutout.right).coerceAtLeast(0),
        (windowSize.height - cutout.top - cutout.bottom).coerceAtLeast(0),
    )

    val spec = remember(viewport, state.settings, density) {
        if (viewport.width <= 0 || viewport.height <= 0) null
        else buildLayoutSpec(viewport, state.settings, density)
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

    val activity = LocalActivity.current
    BrightnessEffect(activity, state.settings.brightnessOverride)
    KeepScreenOnEffect(activity, state.settings.keepScreenOn)
    // 阅读时隐藏系统状态栏/导航栏，呼出菜单时恢复
    ImmersiveEffect(activity, immersive = !state.menuVisible)

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(state.settings.theme.background))
            // 避开挖孔/刘海，页眉章节名不会顶进摄像头区域。
            // 用自己算的 cutout 而不是 windowInsetsPadding：后者首帧是 0，会让 Box 先铺满
            // 再缩回来 —— 正文画在 Box 里，Box 一动正文跟着动。两边同源才不会打架。
            .absolutePadding(
                left = with(density) { cutout.left.toDp() },
                top = with(density) { cutout.top.toDp() },
                right = with(density) { cutout.right.toDp() },
                bottom = with(density) { cutout.bottom.toDp() },
            )
            // **消费掉刚扣的那部分**。windowInsetsPadding 会自动做这件事，absolutePadding 不会 ——
            // 不声明的话，子树里的 statusBarsPadding()（阅读菜单顶栏就有）看不到我们已经让开过，
            // 会把状态栏高度再加一遍：顶栏上方凭空多出一整条纸色，菜单被压得又高又空。
            .consumeWindowInsets(
                PaddingValues(
                    start = with(density) { cutout.left.toDp() },
                    top = with(density) { cutout.top.toDp() },
                    end = with(density) { cutout.right.toDp() },
                    bottom = with(density) { cutout.bottom.toDp() },
                )
            ),
    ) {
        val layout = spec

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
                Modifier.align(Alignment.Center).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(color = Color(state.settings.theme.textColor))
                Spacer(Modifier.height(20.dp))
                Text(
                    "正在自动换源…",
                    color = Color(state.settings.theme.textColor),
                    textAlign = TextAlign.Center,
                )
                if (state.autoChangeTotal > 0) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "已试 ${state.autoChangeDone}/${state.autoChangeTotal} 个书源",
                        color = Color(state.settings.theme.footerColor),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            state.error != null -> Column(
                Modifier.align(Alignment.Center).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    state.error!!,
                    color = Color(state.settings.theme.textColor),
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(20.dp))
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
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onExit) {
                    Text("返回书架", color = Color(state.settings.theme.footerColor))
                }
            }
            state.loading || layout == null -> CircularProgressIndicator(
                Modifier.align(Alignment.Center),
                color = Color(state.settings.theme.textColor),
            )
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
        // 不自动消失：撤销是有价值的操作，不该在用户还没读到不对劲的地方之前就溜走。
        state.autoChangedTo?.let { sourceName ->
            ReaderThemeScope(state.settings.theme) {
                Surface(
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(horizontal = Dimens.listHorizontal, vertical = Dimens.listVertical),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    tonalElevation = 3.dp,
                ) {
                    Row(
                        Modifier.padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "已自动换到「$sourceName」",
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
            Text(
                "已经是最后一页了",
                Modifier.align(Alignment.BottomCenter),
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
                theme = state.settings.theme,
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

        state.toast?.let { msg ->
            LaunchedEffect(msg) {
                kotlinx.coroutines.delay(1800)
                viewModel.clearToast()
            }
            Text(
                msg,
                Modifier.align(Alignment.TopCenter).padding(top = 48.dp),
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
): LayoutSpec = with(density) {
    val fontSizePx = settings.fontSizeSp.sp.toPx()
    LayoutSpec(
        viewportWidthPx = viewport.width,
        viewportHeightPx = viewport.height,
        marginLeftPx = settings.marginHorizontalDp.dp.toPx(),
        marginRightPx = settings.marginHorizontalDp.dp.toPx(),
        marginTopPx = settings.marginVerticalDp.dp.toPx(),
        marginBottomPx = settings.marginVerticalDp.dp.toPx(),
        headerHeightPx = 18.dp.toPx(),
        footerHeightPx = 18.dp.toPx(),
        fontSizePx = fontSizePx,
        lineHeightPx = fontSizePx * settings.lineSpacingMult,
        paragraphSpacingPx = fontSizePx * settings.paragraphSpacingEm,
        titleTopSpacingPx = 24.dp.toPx(),
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

/**
 * 视口安静多久才算稳定。
 *
 * 一帧 16ms；insets 通常在首次 layout 之后紧接着派发。给两帧的余量 ——
 * 太短会漏掉那次变化（抖动照旧），太长则进书时会多闪一下转圈。
 */
