package com.radium.inkwell.ui.reader

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
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
    LaunchedEffect(state.settings.volumeKeyFlip, state.menuVisible) {
        keyBus.volumeFlipEnabled = state.settings.volumeKeyFlip && !state.menuVisible
    }
    LaunchedEffect(Unit) {
        keyBus.flipEvents.collect { flipController.requestFlip(it) }
    }
    DisposableEffect(Unit) {
        onDispose { keyBus.volumeFlipEnabled = false }
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
            // 避开挖孔/刘海，页眉章节名不会顶进摄像头区域
            .windowInsetsPadding(WindowInsets.displayCutout)
            .onSizeChanged { viewport = it },
    ) {
        val spec = if (viewport.width > 0) buildLayoutSpec(viewport, state.settings, density) else null

        when {
            state.error != null -> Text(
                state.error!!,
                Modifier.align(Alignment.Center),
                color = Color(state.settings.theme.textColor),
            )
            state.loading || spec == null -> CircularProgressIndicator(
                Modifier.align(Alignment.Center),
                color = Color(state.settings.theme.textColor),
            )
            else -> {
                PageFlipContainer(
                    current = state.page,
                    prev = state.prevPage,
                    next = state.nextPage,
                    layout = spec,
                    theme = state.settings.theme,
                    animation = state.settings.flipAnimation,
                    gesturesEnabled = !state.menuVisible,
                    canFlip = { dir ->
                        if (dir == FlipDirection.FORWARD) state.hasNext else state.hasPrev
                    },
                    onCommit = { viewModel.flip(it) },
                    onCenterTap = { viewModel.toggleMenu() },
                    controller = flipController,
                )
                PageInfoBar(state, spec)
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

        if (state.menuVisible) {
            ReaderMenu(
                state = state,
                onExit = onExit,
                onGotoChapter = { viewModel.gotoChapter(it) },
                onSeekPercent = { viewModel.seekToPercent(it) },
                onUpdateSettings = { viewModel.updateSettings(it) },
                onSearchSources = { viewModel.searchOtherSources() },
                onApplySource = { viewModel.applyChangeSource(it) },
                onDismissSourcePanel = { viewModel.dismissSourcePanel() },
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
