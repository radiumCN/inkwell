package com.radium.inkwell.ui.reader

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animate
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.IntSize
import com.radium.inkwell.ui.components.AppIconButton
import com.radium.inkwell.ui.components.AppLoadingIndicator
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.ui.components.blockTouchesBelow
import com.radium.inkwell.ui.theme.AppTheme
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 长按正文里的一张图看大图：黑底全屏、双指缩放、双击放大 / 复原、没放大时往下拖关掉。
 *
 * 黑底白字不跟纸张主题走：看图的地方背景越暗越好，相册、浏览器的看图都是这样（这是媒体查看器，不是页面，
 * 所以这里直接用黑白两色，不取主题槽位）。叠在阅读页最上层，挂 [blockTouchesBelow]：点在空白处不能漏到底下去翻页。
 *
 * 图另解一张（[load] 给的是屏幕两倍上限的），页上那张是按框解的小图，放大就糊。
 */
@Composable
internal fun ReaderImageViewer(
    resourceId: String,
    load: suspend (maxWidth: Int, maxHeight: Int) -> ImageBitmap?,
    onDismiss: () -> Unit,
) {
    val window = LocalWindowInfo.current.containerSize
    var bitmap by remember(resourceId) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(resourceId) { mutableStateOf(false) }
    val loadLatest by rememberUpdatedState(load)
    LaunchedEffect(resourceId) {
        val limitW = (window.width * VIEWER_OVERSAMPLE).coerceIn(1, VIEWER_MAX_PX)
        val limitH = (window.height * VIEWER_OVERSAMPLE).coerceIn(1, VIEWER_MAX_PX)
        val loaded = loadLatest(limitW, limitH)
        bitmap = loaded
        failed = loaded == null
    }
    BackHandler(onBack = onDismiss)

    val scope = rememberCoroutineScope()
    var size by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    // 没放大时往下拖：图跟着手指走、背景渐渐透出底下的正文，拖过一截就关
    var dismissDrag by remember { mutableFloatStateOf(0f) }
    val spatial = AppTheme.motionScheme.defaultSpatialSpec<Float>()
    val onDismissLatest by rememberUpdatedState(onDismiss)

    /** 放大后不许把图拖出屏幕：平移量夹在「图比屏幕多出来的那一半」以内 */
    fun clamp(o: Offset, s: Float): Offset {
        val maxX = (size.width * (s - 1f) / 2f).coerceAtLeast(0f)
        val maxY = (size.height * (s - 1f) / 2f).coerceAtLeast(0f)
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    val dragFraction = if (size.height > 0) (dismissDrag / size.height).coerceIn(0f, 1f) else 0f
    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .background(Color.Black.copy(alpha = 1f - dragFraction * BACKDROP_FADE))
            .blockTouchesBelow()
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var event = awaitPointerEvent()
                    while (true) {
                        val pressed = event.changes.count { it.pressed }
                        val zoom = event.calculateZoom()
                        val pan = event.calculatePan()
                        if (pressed >= 2 || scale > 1f) {
                            // 双指缩放，或者已经放大了的单指平移
                            val next = (scale * zoom).coerceIn(1f, MAX_SCALE)
                            scale = next
                            offset = clamp(offset + pan, next)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        } else if (pan.y != 0f) {
                            // 没放大、单指：往下拖关掉（往上拖不动）
                            dismissDrag = (dismissDrag + pan.y).coerceAtLeast(0f)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                        if (event.changes.none { it.pressed }) break
                        event = awaitPointerEvent()
                    }
                    if (scale <= 1f) offset = Offset.Zero
                    if (dismissDrag > size.height * DISMISS_FRACTION) {
                        onDismissLatest()
                    } else if (dismissDrag > 0f) {
                        // 没拖够：弹回原位
                        scope.launch { animate(dismissDrag, 0f, animationSpec = spatial) { v, _ -> dismissDrag = v } }
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { tap ->
                        if (scale > 1f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            // 放大到点的那一处：把点到的位置移到屏幕中间那一侧
                            val center = Offset(size.width / 2f, size.height / 2f)
                            scale = DOUBLE_TAP_SCALE
                            offset = clamp((center - tap) * (DOUBLE_TAP_SCALE - 1f), DOUBLE_TAP_SCALE)
                        }
                    },
                )
            },
    ) {
        val shown = bitmap
        when {
            shown != null -> Image(
                bitmap = shown,
                contentDescription = "插图",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y + dismissDrag
                    },
            )
            failed -> Text(
                "图片打不开",
                color = Color.White,
                style = MiuixTheme.textStyles.body2,
                modifier = Modifier.align(Alignment.Center),
            )
            else -> AppLoadingIndicator(modifier = Modifier.align(Alignment.Center), color = Color.White)
        }

        AppIconButton(
            onClick = onDismiss,
            contentColor = Color.White,
            backgroundColor = Color.White.copy(alpha = CLOSE_BUTTON_ALPHA),
            modifier = Modifier
                .align(Alignment.TopStart)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(Dimens.gapL),
        ) {
            Icon(MiuixIcons.Close, contentDescription = "关闭")
        }

        if (shown != null && dragFraction == 0f) {
            Text(
                "双指缩放 · 双击放大 · 下滑关闭",
                color = Color.White.copy(alpha = HINT_ALPHA),
                style = MiuixTheme.textStyles.footnote1,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                    .padding(bottom = Dimens.gapXXL),
            )
        }
    }
}

/** 大图按屏幕的几倍解：放大到 2.5 倍左右仍清楚 */
private const val VIEWER_OVERSAMPLE = 2

/** 再大也不超过这么多像素一边（4096 是不少 GPU 的纹理上限） */
private const val VIEWER_MAX_PX = 4096

private const val MAX_SCALE = 5f
private const val DOUBLE_TAP_SCALE = 2.5f

/** 往下拖过屏幕高的这么多就关 */
private const val DISMISS_FRACTION = 0.18f

/** 拖到底时背景透掉多少（不全透：底下的正文只是隐约露出来） */
private const val BACKDROP_FADE = 0.7f

private const val CLOSE_BUTTON_ALPHA = 0.14f
private const val HINT_ALPHA = 0.62f
