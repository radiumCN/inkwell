package com.radium.inkwell.reader.flip

import androidx.compose.animation.core.animate
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

/** 程序化翻页入口（点击区域 / 音量键共用动画路径） */
class FlipController {
    internal val requests = MutableSharedFlow<FlipDirection>(extraBufferCapacity = 2)
    fun requestFlip(direction: FlipDirection) {
        requests.tryEmit(direction)
    }
}

/**
 * 翻页容器：手势判向 → 跟手拖拽 → 松手按位移/速度裁决 commit/回滚。
 * COVER/SLIDE 用图层位移驱动（offset），CURL 用真实触点驱动仿真卷页。
 *
 * 性能要点：拖拽路径直接写 State（不经协程）；页面位图按页预渲染为
 * 不可变位图（GPU 纹理只上传一次）；settle 用 animate() 驱动同一 State。
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
    // offset：拖拽累计位移，FORWARD ∈ [-w,0]，BACKWARD ∈ [0,w]；驱动 COVER/SLIDE 与松手裁决
    var offset by remember { mutableFloatStateOf(0f) }
    // CURL 的真实触点（跟手）
    var touchX by remember { mutableFloatStateOf(0f) }
    var touchY by remember { mutableFloatStateOf(0f) }
    var downX by remember { mutableFloatStateOf(0f) }
    var cornerBottom by remember { mutableStateOf(true) }
    var direction by remember { mutableStateOf<FlipDirection?>(null) }
    var settling by remember { mutableStateOf(false) }
    var size by remember { mutableStateOf(IntSize(1, 1)) }

    suspend fun settle(commit: Boolean) {
        val dir = direction ?: return
        settling = true
        val width = size.width.toFloat()
        if (animation == FlipAnimation.CURL) {
            // 后翻用相对位移（downX 为折叠原点），目标要换算回绝对触点
            val target = when {
                commit && dir == FlipDirection.FORWARD -> -width * 0.7f  // 卷出左侧
                commit -> downX + width                                  // prev 展开盖满
                dir == FlipDirection.FORWARD -> width - 1.5f             // 回滚：贴回右缘
                else -> downX - width * 0.3f                             // 回滚：重新折叠到屏外
            }
            animate(touchX, target, animationSpec = tween(300)) { v, _ -> touchX = v }
        } else {
            val target = when {
                commit -> if (dir == FlipDirection.FORWARD) -width else width
                else -> 0f
            }
            animate(offset, target, animationSpec = tween(if (commit) 240 else 180)) { v, _ ->
                offset = v
            }
        }
        if (commit) {
            // 先换页并复位 direction，再归零：任何中间帧都只会画新当前页
            onCommit(dir)
        }
        direction = null
        offset = 0f
        settling = false
    }

    fun startProgrammaticFlip(dir: FlipDirection) {
        if (direction != null || settling) return
        if (!canFlip(dir)) {
            if (dir == FlipDirection.FORWARD) onCommit(dir) // 让上层弹"最后一页"提示
            return
        }
        if (animation == FlipAnimation.NONE) {
            onCommit(dir)
            return
        }
        cornerBottom = true
        touchY = size.height * 0.82f
        downX = if (dir == FlipDirection.FORWARD) size.width * 0.92f else size.width * 0.08f
        touchX = downX
        offset = 0f
        direction = dir
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
                    val tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, down.position)
                    var dragDir: FlipDirection? = null
                    val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
                        dragDir = if (over < 0) FlipDirection.FORWARD else FlipDirection.BACKWARD
                        change.consume()
                    }
                    if (drag == null) {
                        // 松手未越过 slop = 点击
                        if (!settling && direction == null) {
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
                    if (settling || direction != null) return@awaitEachGesture
                    val flippable = canFlip(dir)
                    if (flippable && animation != FlipAnimation.NONE) {
                        // 卷角在手势开始时锁定，拖拽中不再改变（避免翻页中途跳变）
                        cornerBottom = down.position.y > size.height / 2f
                        touchY = down.position.y
                        downX = down.position.x
                        touchX = down.position.x
                        direction = dir
                    }

                    val width = size.width.toFloat()
                    horizontalDrag(drag.id) { change ->
                        tracker.addPosition(change.uptimeMillis, change.position)
                        change.consume()
                        if (!flippable || animation == FlipAnimation.NONE) return@horizontalDrag
                        // 拖拽路径直写状态，不经协程（每事件 launch 会造成输入延迟与分配抖动）
                        touchY = change.position.y
                        touchX = change.position.x
                        val range = if (dir == FlipDirection.FORWARD) -width..0f else 0f..width
                        offset = (offset + change.positionChange().x).coerceIn(range)
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
                        FlipDirection.FORWARD -> offset < -width / 4f || velocity < -1200f
                        FlipDirection.BACKWARD -> offset > width / 4f || velocity > 1200f
                    }
                    scope.launch { settle(commit) }
                }
            },
    ) {
        when (animation) {
            FlipAnimation.NONE -> PageCanvas(current, layout, theme)
            FlipAnimation.SLIDE -> SlideLayers(
                current, prev, next, layout, theme, direction, offset, size.width.toFloat(),
            )
            FlipAnimation.COVER -> CoverLayers(
                current, prev, next, layout, theme, direction, offset, size.width.toFloat(),
            )
            FlipAnimation.CURL -> CurlLayer(
                current, prev, next, layout, theme, direction,
                // 前翻卷角完全跟手；后翻以按下点为折叠原点（起始全折叠，随位移展开）
                touchX = if (direction == FlipDirection.BACKWARD) touchX - downX else touchX,
                touchY = touchY, cornerBottom = cornerBottom, size = size, density = density,
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
    touchX: Float,
    touchY: Float,
    cornerBottom: Boolean,
    size: IntSize,
    density: androidx.compose.ui.unit.Density,
) {
    // 位图按页预渲染（页面/排版/主题变化时重建），手势开始时零合成开销
    val curBitmap = remember(current, layout, theme) {
        current?.let { renderPageBitmap(it, layout, theme, density) }
    }
    val prevBitmap = remember(prev, layout, theme) {
        prev?.let { renderPageBitmap(it, layout, theme, density) }
    }
    val nextBitmap = remember(next, layout, theme) {
        next?.let { renderPageBitmap(it, layout, theme, density) }
    }
    // 邻页为 null（未分页完成）时用空白页位图兜底
    val blankBitmap = remember(layout, theme) {
        renderPageBitmap(null, layout, theme, density)
    }
    val renderer = remember(size) { CurlRenderer(size.width.toFloat(), size.height.toFloat()) }

    if (direction == null || curBitmap == null) {
        PageCanvas(current, layout, theme)
        return
    }

    val front: ImageBitmap
    val under: ImageBitmap
    when (direction) {
        FlipDirection.FORWARD -> {
            front = curBitmap
            under = nextBitmap ?: blankBitmap
        }
        FlipDirection.BACKWARD -> {
            front = prevBitmap ?: blankBitmap
            under = curBitmap
        }
    }

    Spacer(
        Modifier.fillMaxSize().drawBehind {
            drawIntoCanvas { canvas ->
                renderer.draw(
                    canvas.nativeCanvas,
                    front.asAndroidBitmap(),
                    under.asAndroidBitmap(),
                    touchX, touchY, cornerBottom,
                )
            }
        }
    )
}
