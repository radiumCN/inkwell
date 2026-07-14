package com.radium.inkwell.reader.flip

import androidx.compose.animation.core.LinearOutSlowInEasing
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs
import com.radium.inkwell.reader.api.FlipAnimation
import com.radium.inkwell.reader.api.FlipDirection
import com.radium.inkwell.reader.api.ReaderTheme
import com.radium.inkwell.reader.paginate.LayoutSpec
import com.radium.inkwell.reader.render.PageCanvas
import com.radium.inkwell.reader.render.TextSelection
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
    /** 长按选中的文字；只在静止态（没在翻页）才可能非空 */
    selection: TextSelection? = null,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    // 系统「移除动画」开启时降级为无动画直切（无障碍）
    val animationsOff = remember {
        android.provider.Settings.Global.getFloat(
            context.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
        ) == 0f
    }
    val effectiveAnim = if (animationsOff) FlipAnimation.NONE else animation
    // offset：拖拽累计位移，FORWARD ∈ [-w,0]，BACKWARD ∈ [0,w]；驱动 COVER/SLIDE 与松手裁决
    var offset by remember { mutableFloatStateOf(0f) }
    // CURL 的真实触点（跟手）
    var touchX by remember { mutableFloatStateOf(0f) }
    var touchY by remember { mutableFloatStateOf(0f) }
    var downX by remember { mutableFloatStateOf(0f) }
    var cornerBottom by remember { mutableStateOf(true) }
    // 中间横划（揪整页）vs 从角起手（揪角）。前者把触点 Y 钉在页边卷出竖直圆柱
    var flatSwipe by remember { mutableStateOf(false) }
    var direction by remember { mutableStateOf<FlipDirection?>(null) }
    var settling by remember { mutableStateOf(false) }
    var size by remember { mutableStateOf(IntSize(1, 1)) }

    // 松手速度越快，收尾动画越短（更跟手）；范围 [minMs, maxMs]
    fun settleDuration(velocity: Float, minMs: Int, maxMs: Int): Int {
        val speed = abs(velocity)
        val t = (speed / 4000f).coerceIn(0f, 1f) // 4000px/s 视为最快
        return (maxMs - (maxMs - minMs) * t).toInt()
    }

    suspend fun settle(commit: Boolean, velocity: Float = 0f) {
        val dir = direction ?: return
        settling = true
        val width = size.width.toFloat()
        if (commit) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        if (effectiveAnim == FlipAnimation.CURL) {
            // 后翻用相对位移（downX 为折叠原点），目标要换算回绝对触点
            val target = when {
                commit && dir == FlipDirection.FORWARD -> -width * 0.7f  // 卷出左侧
                commit -> downX + width                                  // prev 展开盖满
                dir == FlipDirection.FORWARD -> width - 1.5f             // 回滚：贴回右缘
                else -> downX - width * 0.3f                             // 回滚：重新折叠到屏外
            }
            val dur = if (commit) settleDuration(velocity, 200, 320) else settleDuration(velocity, 160, 240)
            // 减速曲线（≈DecelerateInterpolator）：纸张甩出后自然减速停下
            animate(touchX, target, animationSpec = tween(dur, easing = LinearOutSlowInEasing)) { v, _ ->
                touchX = v
            }
        } else {
            val target = when {
                commit -> if (dir == FlipDirection.FORWARD) -width else width
                else -> 0f
            }
            val dur = if (commit) settleDuration(velocity, 180, 260) else settleDuration(velocity, 140, 200)
            animate(offset, target, animationSpec = tween(dur, easing = LinearOutSlowInEasing)) { v, _ ->
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
        if (effectiveAnim == FlipAnimation.NONE) {
            onCommit(dir)
            return
        }
        // 点击翻页 = 翻整页，走竖直圆柱：触点 Y 钉到页底（与中间横划一致），别卷出斜角
        cornerBottom = true
        flatSwipe = true
        touchY = size.height.toFloat()
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
            .pointerInput(gesturesEnabled, effectiveAnim) {
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
                    // 必须用 effectiveAnim：系统把动画时长设为 0 时（开发者选项/无障碍）它降级为 NONE，
                    // 而 animation 仍是 CURL/COVER/SLIDE。用后者会把 direction 置上，紧接着下面
                    // NONE 分支提前 return、永不复位 direction，此后点击与拖拽翻页全被挡死。
                    if (flippable && effectiveAnim != FlipAnimation.NONE) {
                        val h = size.height.toFloat()
                        // 卷角在手势开始时锁定，拖拽中不再改变（避免翻页中途跳变）。
                        // 后翻一律用底角：从上半屏往回翻若按触点选顶角，会卷出很别扭的对角。
                        cornerBottom = dir == FlipDirection.BACKWARD || down.position.y > h / 2f
                        // 从屏幕**中间那一带**起手 = 想翻整页，不是揪角。这时把触点 Y 钉到卷角所在的
                        // 页边（不跟手指的 Y），折痕于是接近竖直、页面绕竖轴卷过去 —— 真书翻页的样子。
                        // 只有从上/下三分之一起手才让 Y 跟手，卷出斜的揪角效果。
                        //
                        // 从前一律跟手，中间横划就卷出一条贯穿全页的大斜折（"天差地别"那张图）。
                        // 当年试过钉边但崩了，根因是退化处理会把控制点塌回卷角 —— 那个已在
                        // CurlRenderer 用 ÷0.1 修好，这里才敢钉。
                        flatSwipe = down.position.y > h / 3f && down.position.y < h * 2 / 3f
                        touchY = if (flatSwipe) (if (cornerBottom) h else 0f) else down.position.y
                        downX = down.position.x
                        touchX = down.position.x
                        direction = dir
                    }

                    val width = size.width.toFloat()
                    horizontalDrag(drag.id) { change ->
                        tracker.addPosition(change.uptimeMillis, change.position)
                        change.consume()
                        if (!flippable || effectiveAnim == FlipAnimation.NONE) return@horizontalDrag
                        // 拖拽路径直写状态，不经协程（每事件 launch 会造成输入延迟与分配抖动）。
                        // 中间横划时 Y 保持钉在页边，不跟手指上下漂 —— 否则折痕会随手抖来抖去
                        touchY = if (flatSwipe) (if (cornerBottom) size.height.toFloat() else 0f) else change.position.y
                        touchX = change.position.x
                        val range = if (dir == FlipDirection.FORWARD) -width..0f else 0f..width
                        offset = (offset + change.positionChange().x).coerceIn(range)
                    }

                    if (!flippable) {
                        // 书首/书末：无动画，前翻到底时提示
                        if (dir == FlipDirection.FORWARD) onCommit(dir)
                        return@awaitEachGesture
                    }
                    if (effectiveAnim == FlipAnimation.NONE) {
                        onCommit(dir)
                        return@awaitEachGesture
                    }
                    val velocity = tracker.calculateVelocity().x
                    val commit = when (dir) {
                        FlipDirection.FORWARD -> offset < -width / 4f || velocity < -1200f
                        FlipDirection.BACKWARD -> offset > width / 4f || velocity > 1200f
                    }
                    scope.launch { settle(commit, velocity) }
                }
            },
    ) {
        when (effectiveAnim) {
            // 滚动模式根本不该走到这里 —— 它是另一条渲染路径，由 ReaderScreen 分流。
            // 万一走到了（比如设置迁移遗漏），静态画当前页，总好过崩溃或白屏。
            FlipAnimation.NONE, FlipAnimation.SCROLL ->
                PageCanvas(current, layout, theme, selection = selection)
            FlipAnimation.SLIDE -> SlideLayers(
                selection, current, prev, next, layout, theme, direction, offset, size.width.toFloat(),
            )
            FlipAnimation.COVER -> CoverLayers(
                selection, current, prev, next, layout, theme, direction, offset, size.width.toFloat(),
            )
            FlipAnimation.CURL -> CurlLayer(
                selection = selection,
                current = current, prev = prev, next = next,
                layout = layout, theme = theme, direction = direction,
                // 前翻卷角完全跟手；后翻以按下点为折叠原点（起始全折叠，随位移展开）
                touchX = if (direction == FlipDirection.BACKWARD) touchX - downX else touchX,
                touchY = touchY, cornerBottom = cornerBottom, size = size, density = density,
            )
        }
    }
}

@Composable
private fun SlideLayers(
    selection: TextSelection? = null,
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
        null -> PageCanvas(current, layout, theme, selection = selection)
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
    selection: TextSelection? = null,
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
        null -> PageCanvas(current, layout, theme, selection = selection)
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
    selection: TextSelection? = null,
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
        PageCanvas(current, layout, theme, selection = selection)
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
                    paperColor = theme.background.toInt(),
                )
            }
        }
    )
}
