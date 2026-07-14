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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    var viewport by remember { mutableStateOf(IntSize.Zero) }

    // 视口或排版设置变化 → 重建 LayoutSpec 注入引擎
    LaunchedEffect(viewport, state.settings, density) {
        if (viewport.width > 0 && viewport.height > 0) {
            val facade = ComposeTextMeasureFacade(fontFamilyResolver, density, SystemFontRegistry)
            viewModel.onLayoutReady(facade, buildLayoutSpec(viewport, state.settings, density))
        }
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
            // 避开挖孔/刘海，页眉章节名不会顶进摄像头区域
            .windowInsetsPadding(WindowInsets.displayCutout)
            .onSizeChanged { viewport = it },
    ) {
        val spec = if (viewport.width > 0) buildLayoutSpec(viewport, state.settings, density) else null

        // 排版一就绪就排滚动模式的章节。不能放在下面的 SCROLL 分支里：
        // 初始 loading=true 时那个分支根本走不到，prepareScroll 永远不会被调用 —— 死锁。
        if (state.settings.flipAnimation == FlipAnimation.SCROLL) {
            LaunchedEffect(spec, state.chapterCount) {
                if (spec != null && state.chapterCount > 0) viewModel.prepareScroll()
            }
        }

        when {
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
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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
            state.loading || spec == null -> CircularProgressIndicator(
                Modifier.align(Alignment.Center),
                color = Color(state.settings.theme.textColor),
            )
            // 滚动模式：另一条渲染路径，不走翻页容器
            state.settings.flipAnimation == FlipAnimation.SCROLL -> {
                ScrollReader(
                    chapters = scrollChapters,
                    layout = spec,
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
                                    val sel = page.selectWordAt(pos.x, pos.y, spec)
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
                                        a, change.position.x, change.position.y, spec,
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
                        layout = spec,
                        theme = state.settings.theme,
                        animation = state.settings.flipAnimation,
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
                    PageInfoBar(state, spec)
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

        if (state.menuVisible) {
            ReaderMenu(
                state = state,
                onExit = onExit,
                onGotoChapter = { viewModel.gotoChapter(it) },
                onSeekPercent = { viewModel.seekToPercent(it) },
                onUpdateSettings = { viewModel.updateSettings(it) },
                onSearchSources = { viewModel.searchOtherSources() },
                onDismiss = { viewModel.toggleMenu() },
            )
        }
    }
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
