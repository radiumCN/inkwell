package com.radium.inkwell.reader.flip

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import com.radium.inkwell.reader.api.FlipAnimation
import com.radium.inkwell.reader.api.FlipDirection
import com.radium.inkwell.reader.api.ReaderTheme
import com.radium.inkwell.reader.paginate.LayoutSpec
import com.radium.inkwell.reader.render.PageCanvas
import com.radium.inkwell.reader.render.RenderablePage
import com.radium.inkwell.reader.render.drawPage
import com.radium.inkwell.reader.render.renderPageBitmap
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

/** 程序化翻页入口（点击区域 / 音量键共用动画路径） */
class FlipController {
    internal val requests = MutableSharedFlow<FlipDirection>(extraBufferCapacity = 2)
    fun requestFlip(direction: FlipDirection) {
        requests.tryEmit(direction)
    }
}

/**
 * 翻页容器：手势判向 → 跟手拖拽 → 松手按位移/速度裁决 commit/回滚。
 * COVER/SLIDE 用图层位移，CURL 用位图仿真卷页，NONE 直接换页。
 * commit 动画结束后回调 onCommit（父层换页），随后 offset 归零——新当前页
 * 即动画终点画面，无闪烁。
 */
@Composable
fun PageFlipContainer(
    current: RenderablePage?,
    prev: RenderablePage?,
    next: RenderablePage?,
    layout: LayoutSpec,
    theme: ReaderTheme,
    animation: FlipAnimation,
    gesturesEnabled: Boolean,
    canFlip: (FlipDirection) -> Boolean,
    onCommit: (FlipDirection) -> Unit,
    onCenterTap: () -> Unit,
    controller: FlipController,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    // offset: FORWARD ∈ [-w,0]，BACKWARD ∈ [0,w]
    val offset = remember { Animatable(0f) }
    var direction by remember { mutableStateOf<FlipDirection?>(null) }
    var touchY by remember { mutableFloatStateOf(0f) }
    var size by remember { mutableStateOf(IntSize(1, 1)) }

    suspend fun settle(commit: Boolean) {
        val dir = direction ?: return
        val width = size.width.toFloat()
        val target = if (dir == FlipDirection.FORWARD) -width else width
        if (commit) {
            offset.animateTo(target, tween(240))
            // 先换页并复位 direction，再归零 offset：任何中间帧都只会画新当前页
            onCommit(dir)
            direction = null
            offset.snapTo(0f)
        } else {
            offset.animateTo(0f, tween(180))
            direction = null
        }
    }

    fun startProgrammaticFlip(dir: FlipDirection) {
        if (direction != null || offset.isRunning) return
        if (!canFlip(dir)) {
            if (dir == FlipDirection.FORWARD) onCommit(dir) // 让上层弹"最后一页"提示
            return
        }
        if (animation == FlipAnimation.NONE) {
            onCommit(dir)
            return
        }
        direction = dir
        touchY = size.height * 0.8f
        scope.launch { settle(commit = true) }
    }

    LaunchedEffect(controller) {
        controller.requests.collect { startProgrammaticFlip(it) }
    }

    Box(
        modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(gesturesEnabled, animation) {
                if (!gesturesEnabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    touchY = down.position.y
                    val tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, down.position)
                    var dragDir: FlipDirection? = null
                    val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
                        dragDir = if (over < 0) FlipDirection.FORWARD else FlipDirection.BACKWARD
                        change.consume()
                    }
                    if (drag == null) {
                        // 松手未越过 slop = 点击
                        if (!offset.isRunning && direction == null) {
                            val x = down.position.x
                            when {
                                x < size.width / 3f -> startProgrammaticFlip(FlipDirection.BACKWARD)
                                x > size.width * 2 / 3f -> startProgrammaticFlip(FlipDirection.FORWARD)
                                else -> onCenterTap()
                            }
                        }
                        return@awaitEachGesture
                    }
                    val dir = dragDir ?: return@awaitEachGesture
                    if (offset.isRunning || direction != null) return@awaitEachGesture
                    val flippable = canFlip(dir)
                    if (flippable && animation != FlipAnimation.NONE) direction = dir

                    val width = size.width.toFloat()
                    horizontalDrag(drag.id) { change ->
                        tracker.addPosition(change.uptimeMillis, change.position)
                        touchY = change.position.y
                        val delta = change.positionChange().x
                        change.consume()
                        if (!flippable || animation == FlipAnimation.NONE) return@horizontalDrag
                        val range = if (dir == FlipDirection.FORWARD) -width..0f else 0f..width
                        val newValue = (offset.value + delta).coerceIn(range)
                        scope.launch { offset.snapTo(newValue) }
                    }

                    if (!flippable) {
                        // 书首/书末：无动画，前翻到底时提示
                        if (dir == FlipDirection.FORWARD) onCommit(dir)
                        return@awaitEachGesture
                    }
                    if (animation == FlipAnimation.NONE) {
                        onCommit(dir)
                        return@awaitEachGesture
                    }
                    val velocity = tracker.calculateVelocity().x
                    val commit = when (dir) {
                        FlipDirection.FORWARD -> offset.value < -width / 4f || velocity < -1200f
                        FlipDirection.BACKWARD -> offset.value > width / 4f || velocity > 1200f
                    }
                    scope.launch { settle(commit) }
                }
            },
    ) {
        when (animation) {
            FlipAnimation.NONE -> PageCanvas(current, layout, theme)
            FlipAnimation.SLIDE -> SlideLayers(
                current, prev, next, layout, theme, direction, offset.value, size.width.toFloat(),
            )
            FlipAnimation.COVER -> CoverLayers(
                current, prev, next, layout, theme, direction, offset.value, size.width.toFloat(),
            )
            FlipAnimation.CURL -> CurlLayer(
                current, prev, next, layout, theme, direction,
                offset.value, size, touchY, density,
            )
        }
    }
}

@Composable
private fun SlideLayers(
    current: RenderablePage?,
    prev: RenderablePage?,
    next: RenderablePage?,
    layout: LayoutSpec,
    theme: ReaderTheme,
    direction: FlipDirection?,
    offset: Float,
    width: Float,
) {
    when (direction) {
        null -> PageCanvas(current, layout, theme)
        FlipDirection.FORWARD -> {
            PageCanvas(current, layout, theme, Modifier.graphicsLayer { translationX = offset })
            PageCanvas(next, layout, theme, Modifier.graphicsLayer { translationX = offset + width })
        }
        FlipDirection.BACKWARD -> {
            PageCanvas(current, layout, theme, Modifier.graphicsLayer { translationX = offset })
            PageCanvas(prev, layout, theme, Modifier.graphicsLayer { translationX = offset - width })
        }
    }
}

@Composable
private fun CoverLayers(
    current: RenderablePage?,
    prev: RenderablePage?,
    next: RenderablePage?,
    layout: LayoutSpec,
    theme: ReaderTheme,
    direction: FlipDirection?,
    offset: Float,
    width: Float,
) {
    when (direction) {
        null -> PageCanvas(current, layout, theme)
        FlipDirection.FORWARD -> {
            // 下页静止在底，当前页被拖走并带右缘阴影
            PageCanvas(next, layout, theme)
            PageCanvas(
                current, layout, theme,
                Modifier.graphicsLayer { translationX = offset }.edgeShadow(),
            )
        }
        FlipDirection.BACKWARD -> {
            // 上一页从左滑入盖住当前页
            PageCanvas(current, layout, theme)
            PageCanvas(
                prev, layout, theme,
                Modifier.graphicsLayer { translationX = offset - width }.edgeShadow(),
            )
        }
    }
}

/** 页面右缘 16px 渐变投影 */
private fun Modifier.edgeShadow(): Modifier = drawBehind {
    val shadow = 16f
    drawRect(
        brush = androidx.compose.ui.graphics.Brush.horizontalGradient(
            colors = listOf(Color(0x33000000), Color.Transparent),
            startX = size.width,
            endX = size.width + shadow,
        ),
        topLeft = Offset(size.width, 0f),
        size = androidx.compose.ui.geometry.Size(shadow, size.height),
    )
}

@Composable
private fun CurlLayer(
    current: RenderablePage?,
    prev: RenderablePage?,
    next: RenderablePage?,
    layout: LayoutSpec,
    theme: ReaderTheme,
    direction: FlipDirection?,
    offset: Float,
    size: IntSize,
    touchY: Float,
    density: androidx.compose.ui.unit.Density,
) {
    // 位图只在需要的方向上渲染，随页面/主题/排版变化重建
    val frontBitmap: ImageBitmap? = remember(current, prev, direction, layout, theme) {
        when (direction) {
            FlipDirection.FORWARD -> renderPageBitmap(current, layout, theme, density)
            FlipDirection.BACKWARD -> renderPageBitmap(prev, layout, theme, density)
            null -> null
        }
    }
    val underBitmap: ImageBitmap? = remember(current, next, direction, layout, theme) {
        when (direction) {
            FlipDirection.FORWARD -> renderPageBitmap(next, layout, theme, density)
            FlipDirection.BACKWARD -> renderPageBitmap(current, layout, theme, density)
            null -> null
        }
    }
    val renderer = remember(size) { CurlRenderer(size.width.toFloat(), size.height.toFloat()) }

    if (direction == null || frontBitmap == null || underBitmap == null) {
        PageCanvas(current, layout, theme)
        return
    }

    val width = size.width.toFloat()
    Spacer(
        Modifier.fillMaxSize().drawBehind {
            drawIntoCanvas { canvas ->
                // 两个方向都从右角起卷：前翻是当前页被卷走（触点右→左），
                // 后翻是上一页从完全折叠展开盖回（触点左→右）
                val touchX = when (direction) {
                    FlipDirection.FORWARD -> width + offset // offset ∈ [-w,0]
                    FlipDirection.BACKWARD -> offset        // offset ∈ [0,w]
                }
                renderer.draw(
                    canvas.nativeCanvas,
                    frontBitmap.asAndroidBitmap(),
                    underBitmap.asAndroidBitmap(),
                    touchX, touchY, rightSide = true,
                )
            }
        }
    )
}
