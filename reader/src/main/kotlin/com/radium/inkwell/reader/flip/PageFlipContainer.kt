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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
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
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs
import com.radium.inkwell.reader.api.FlipAnimation
import com.radium.inkwell.reader.api.FlipDirection
import com.radium.inkwell.reader.api.ReaderTheme
import com.radium.inkwell.reader.paginate.LayoutSpec
import com.radium.inkwell.reader.paginate.PageSpec
import com.radium.inkwell.reader.render.PageCanvas
import com.radium.inkwell.reader.render.TextSelection
import com.radium.inkwell.reader.render.RenderablePage
import com.radium.inkwell.reader.render.drawPage
import com.radium.inkwell.reader.render.renderPageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 程序化翻页入口（点击区域 / 音量键 / 自动翻页共用动画路径） */
class FlipController {
    internal val requests = MutableSharedFlow<FlipDirection>(extraBufferCapacity = 2)
    fun requestFlip(direction: FlipDirection) {
        requests.tryEmit(direction)
    }
}

/** 相对屏宽：走过这么多就算翻过去（越小越灵敏） */
private const val COMMIT_DISTANCE_FRACTION = 8f
/** 甩页速度阈值（px/s）。1200 对平移/覆盖偏狠，轻轻一滑会回弹 */
private const val COMMIT_VELOCITY_PX = 700f

/**
 * 翻页容器：手势判向 → 跟手拖拽 → 松手按位移/速度裁决 commit/回滚。
 * COVER/SLIDE 用图层位移驱动（offset），CURL 用真实触点驱动仿真卷页。
 *
 * 性能要点：拖拽路径直接写 State（不经协程）；CURL 位图在 Default 上渲成
 * 不可变 ARGB 图（主线程零开销，GPU 纹理只上传一次）；settle 用 animate() 驱动同一 State。
 */
@Composable
fun PageFlipContainer(
    current: RenderablePage?,
    prev: RenderablePage?,
    next: RenderablePage?,
    layout: LayoutSpec,
    theme: ReaderTheme,
    animation: FlipAnimation,
    /**
     * 是否允许播放动画（系统「移除动画」的取反）。由 ReaderScreen 透传 ——
     * 它用 ContentObserver 实时监听，用户在系统设置里一改立刻生效；
     * 本模块（:reader）看不到 :app 的 animationsEnabled()，故上提为参数。
     */
    animationsEnabled: Boolean,
    /** 翻页落定时震一下。默认关；见 ReaderSettings.flipHaptic */
    hapticOnFlip: Boolean = false,
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
    // 系统「移除动画」开启时降级为无动画直切（无障碍）
    val effectiveAnim = if (!animationsEnabled) FlipAnimation.NONE else animation
    // 位移/触点用 State 对象本身传给图层，组合阶段不读 .floatValue ——
    // 否则每一帧拖动都会重组两张 PageCanvas，跟手路径变成「每事件重绘整页」。
    val offset = remember { mutableFloatStateOf(0f) }
    val touchX = remember { mutableFloatStateOf(0f) }
    val touchY = remember { mutableFloatStateOf(0f) }
    var downX by remember { mutableFloatStateOf(0f) }
    var cornerBottom by remember { mutableStateOf(true) }
    // 中间横划（揪整页）vs 从角起手（揪角）。前者把触点 Y 钉在页边卷出竖直圆柱
    var flatSwipe by remember { mutableStateOf(false) }
    var direction by remember { mutableStateOf<FlipDirection?>(null) }
    var settling by remember { mutableStateOf(false) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val settleJob = remember { arrayOf<Job?>(null) }
    val settleGen = remember { intArrayOf(0) }

    // 松手速度越快，收尾动画越短（更跟手）；范围 [minMs, maxMs]
    fun settleDuration(velocity: Float, minMs: Int, maxMs: Int): Int {
        val speed = abs(velocity)
        val t = (speed / 4000f).coerceIn(0f, 1f) // 4000px/s 视为最快
        return (maxMs - (maxMs - minMs) * t).toInt()
    }

    fun resetFlipState() {
        direction = null
        offset.floatValue = 0f
        settling = false
    }

    suspend fun settle(commit: Boolean, velocity: Float = 0f, gen: Int = settleGen[0]) {
        val dir = direction ?: return
        settling = true
        val width = size.width.toFloat()
        try {
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
            animate(touchX.floatValue, target, animationSpec = tween(dur, easing = LinearOutSlowInEasing)) { v, _ ->
                touchX.floatValue = v
            }
        } else {
            val target = when {
                commit -> if (dir == FlipDirection.FORWARD) -width else width
                else -> 0f
            }
            val dur = if (commit) settleDuration(velocity, 180, 260) else settleDuration(velocity, 140, 200)
            animate(offset.floatValue, target, animationSpec = tween(dur, easing = LinearOutSlowInEasing)) { v, _ ->
                offset.floatValue = v
            }
        }
        if (commit && hapticOnFlip) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        if (commit) {
            // 先换页并复位 direction，再归零：任何中间帧都只会画新当前页
            onCommit(dir)
        }
        } finally {
            // 只复位自己这一轮。新一轮 settle 已经加过 gen 的话，这里动 direction 会把新动画掐死
            if (gen == settleGen[0]) resetFlipState()
        }
    }

    fun launchSettle(commit: Boolean, velocity: Float = 0f) {
        settleGen[0] += 1
        val gen = settleGen[0]
        settleJob[0]?.cancel()
        settleJob[0] = scope.launch { settle(commit, velocity, gen) }
    }

    fun startProgrammaticFlip(dir: FlipDirection) {
        if (!canFlip(dir)) {
            settleGen[0] += 1
            settleJob[0]?.cancel()
            resetFlipState()
            if (dir == FlipDirection.FORWARD) onCommit(dir) // 让上层弹"最后一页"提示
            return
        }
        if (effectiveAnim == FlipAnimation.NONE) {
            settleGen[0] += 1
            settleJob[0]?.cancel()
            resetFlipState()
            onCommit(dir)
            return
        }
        // 点击翻页 = 翻整页，走竖直圆柱：触点 Y 钉到页底（与中间横划一致），别卷出斜角
        cornerBottom = true
        flatSwipe = true
        touchY.floatValue = size.height.toFloat()
        downX = if (dir == FlipDirection.FORWARD) size.width * 0.92f else size.width * 0.08f
        touchX.floatValue = downX
        offset.floatValue = 0f
        direction = dir
        launchSettle(commit = true)
    }

    LaunchedEffect(controller) {
        controller.requests.collect { startProgrammaticFlip(it) }
    }
    LaunchedEffect(gesturesEnabled) {
        if (!gesturesEnabled) {
            settleGen[0] += 1
            settleJob[0]?.cancel()
            resetFlipState()
        }
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
                        touchY.floatValue = if (flatSwipe) (if (cornerBottom) h else 0f) else down.position.y
                        downX = down.position.x
                        touchX.floatValue = down.position.x
                        direction = dir
                    }

                    val width = size.width.toFloat()
                    horizontalDrag(drag.id) { change ->
                        tracker.addPosition(change.uptimeMillis, change.position)
                        change.consume()
                        if (!flippable || effectiveAnim == FlipAnimation.NONE) return@horizontalDrag
                        // 拖拽路径直写状态，不经协程（每事件 launch 会造成输入延迟与分配抖动）。
                        // 中间横划时 Y 保持钉在页边，不跟手指上下漂 —— 否则折痕会随手抖来抖去
                        touchY.floatValue = if (flatSwipe) (if (cornerBottom) size.height.toFloat() else 0f) else change.position.y
                        touchX.floatValue = change.position.x
                        val range = if (dir == FlipDirection.FORWARD) -width..0f else 0f..width
                        offset.floatValue = (offset.floatValue + change.positionChange().x).coerceIn(range)
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
                    // 走过 1/8 屏或甩得够快就算翻过去。从前是 1/4 + 1200px/s，
                    // 平移/覆盖跟手幅度小，轻轻一滑经常回弹，看起来像没反应。
                    val commit = when (dir) {
                        FlipDirection.FORWARD ->
                            offset.floatValue < -width / COMMIT_DISTANCE_FRACTION ||
                                velocity < -COMMIT_VELOCITY_PX
                        FlipDirection.BACKWARD ->
                            offset.floatValue > width / COMMIT_DISTANCE_FRACTION ||
                                velocity > COMMIT_VELOCITY_PX
                    }
                    launchSettle(commit, velocity)
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
                touchX = touchX,
                downX = downX,
                touchY = touchY,
                cornerBottom = cornerBottom,
                size = size, density = density,
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
    offset: MutableFloatState,
    width: Float,
) {
    when (direction) {
        null -> PageCanvas(current, layout, theme, selection = selection)
        FlipDirection.FORWARD -> {
            PageCanvas(current, layout, theme, Modifier.graphicsLayer { translationX = offset.floatValue })
            PageCanvas(next, layout, theme, Modifier.graphicsLayer { translationX = offset.floatValue + width })
        }
        FlipDirection.BACKWARD -> {
            PageCanvas(current, layout, theme, Modifier.graphicsLayer { translationX = offset.floatValue })
            PageCanvas(prev, layout, theme, Modifier.graphicsLayer { translationX = offset.floatValue - width })
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
    offset: MutableFloatState,
    width: Float,
) {
    when (direction) {
        null -> PageCanvas(current, layout, theme, selection = selection)
        FlipDirection.FORWARD -> {
            // 下页静止在底，当前页被拖走并带右缘阴影
            PageCanvas(next, layout, theme)
            PageCanvas(
                current, layout, theme,
                Modifier.graphicsLayer { translationX = offset.floatValue }.edgeShadow(),
            )
        }
        FlipDirection.BACKWARD -> {
            // 上一页从左滑入盖住当前页
            PageCanvas(current, layout, theme)
            PageCanvas(
                prev, layout, theme,
                Modifier.graphicsLayer { translationX = offset.floatValue - width }.edgeShadow(),
            )
        }
    }
}

/** 页面右缘渐变投影。颜色常驻，避免 COVER 每帧 listOf */
private val EDGE_SHADOW_COLORS = listOf(Color(0x33000000), Color.Transparent)

private fun Modifier.edgeShadow(): Modifier = drawBehind {
    val shadow = 16f
    drawRect(
        brush = androidx.compose.ui.graphics.Brush.horizontalGradient(
            colors = EDGE_SHADOW_COLORS,
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
    touchX: MutableFloatState,
    downX: Float,
    touchY: MutableFloatState,
    cornerBottom: Boolean,
    size: IntSize,
    density: androidx.compose.ui.unit.Density,
    selection: TextSelection? = null,
) {
    // 闲时也预渲，但放到 Default：主线程不再被 remember { renderPageBitmap } 卡住。
    // 位图是不可变 ARGB（见 renderPageBitmap）。按 PageSpec 相等判断能否环形挪槽，
    // 换下来的旧图延后 recycle，避免正画着的那一帧被回收。
    var bitmaps by remember { mutableStateOf<CurlBitmaps?>(null) }
    DisposableEffect(Unit) {
        onDispose { bitmaps?.recycleAll() }
    }
    LaunchedEffect(current?.spec, prev?.spec, next?.spec, layout, theme, density) {
        val old = bitmaps
        val produced = withContext(Dispatchers.Default + NonCancellable) {
            shiftOrRender(old, current, prev, next, layout, theme, density)
        }
        if (!isActive) {
            // 没交出去：只回收相对 old 新渲的那几张，共用槽不能动
            produced.recycleFresh(old)
            return@LaunchedEffect
        }
        bitmaps = produced
        // 新图已经交给状态；等两帧再 recycle 卸下来的槽，Canvas 不会画到已回收的 Bitmap
        kotlinx.coroutines.delay(32)
        old?.recycleUnused(produced)
    }
    if (size.width <= 1 || size.height <= 1) {
        PageCanvas(current, layout, theme, selection = selection)
        return
    }
    val renderer = remember(size) { CurlRenderer(size.width.toFloat(), size.height.toFloat()) }

    val ready = bitmaps
    if (direction == null || ready?.current == null) {
        PageCanvas(current, layout, theme, selection = selection)
        return
    }

    val front: android.graphics.Bitmap
    val under: android.graphics.Bitmap
    when (direction) {
        FlipDirection.FORWARD -> {
            front = ready.current
            under = ready.next ?: ready.blank
        }
        FlipDirection.BACKWARD -> {
            front = ready.prev ?: ready.blank
            under = ready.current
        }
    }

    Spacer(
        Modifier.fillMaxSize().drawBehind {
            val tx = if (direction == FlipDirection.BACKWARD) {
                touchX.floatValue - downX
            } else {
                touchX.floatValue
            }
            drawIntoCanvas { canvas ->
                renderer.draw(
                    canvas.nativeCanvas,
                    front,
                    under,
                    tx, touchY.floatValue, cornerBottom,
                    paperColor = theme.background.toInt(),
                )
            }
        }
    )
}

/**
 * 能挪槽就挪：前翻时 prev←current、current←next，只渲新的 next。
 * 对不上（跳章、改主题）才三张全渲。blank 只在纸色/尺寸变时重做。
 */
private fun shiftOrRender(
    old: CurlBitmaps?,
    current: RenderablePage?,
    prev: RenderablePage?,
    next: RenderablePage?,
    layout: LayoutSpec,
    theme: ReaderTheme,
    density: androidx.compose.ui.unit.Density,
): CurlBitmaps {
    val curSpec = current?.spec
    val prevSpec = prev?.spec
    val nextSpec = next?.spec
    val themeKey = theme.background xor theme.textColor
    val canReuseBlank = old != null && old.layout == layout && old.themeKey == themeKey
    val blank = if (canReuseBlank) old.blank else renderPageAndroidBitmap(null, layout, theme, density)

    if (old != null && curSpec != null && curSpec == old.nextSpec) {
        // 前翻一页：旧 next 变成当前
        return CurlBitmaps(
            current = old.next,
            prev = old.current,
            next = next?.let { renderPageAndroidBitmap(it, layout, theme, density) },
            blank = blank,
            currentSpec = curSpec,
            prevSpec = prevSpec,
            nextSpec = nextSpec,
            layout = layout,
            themeKey = themeKey,
        )
    }
    if (old != null && curSpec != null && curSpec == old.prevSpec) {
        // 后翻一页：旧 prev 变成当前
        return CurlBitmaps(
            current = old.prev,
            prev = prev?.let { renderPageAndroidBitmap(it, layout, theme, density) },
            next = old.current,
            blank = blank,
            currentSpec = curSpec,
            prevSpec = prevSpec,
            nextSpec = nextSpec,
            layout = layout,
            themeKey = themeKey,
        )
    }
    return CurlBitmaps(
        current = current?.let { renderPageAndroidBitmap(it, layout, theme, density) },
        prev = prev?.let { renderPageAndroidBitmap(it, layout, theme, density) },
        next = next?.let { renderPageAndroidBitmap(it, layout, theme, density) },
        blank = blank,
        currentSpec = curSpec,
        prevSpec = prevSpec,
        nextSpec = nextSpec,
        layout = layout,
        themeKey = themeKey,
    )
}

private fun renderPageAndroidBitmap(
    page: RenderablePage?,
    layout: LayoutSpec,
    theme: ReaderTheme,
    density: androidx.compose.ui.unit.Density,
): android.graphics.Bitmap = renderPageBitmap(page, layout, theme, density).asAndroidBitmap()

private fun recycleBitmap(bmp: android.graphics.Bitmap?) {
    if (bmp != null && !bmp.isRecycled) bmp.recycle()
}

/** CURL 用的三页 + 空白占位。空闲时持有，手势开始零合成。位图直接是 Android Bitmap，draw 路径不再每帧 asAndroidBitmap。 */
private class CurlBitmaps(
    val current: android.graphics.Bitmap?,
    val prev: android.graphics.Bitmap?,
    val next: android.graphics.Bitmap?,
    val blank: android.graphics.Bitmap,
    val currentSpec: PageSpec?,
    val prevSpec: PageSpec?,
    val nextSpec: PageSpec?,
    val layout: LayoutSpec,
    val themeKey: Long,
) {
    fun recycleAll() {
        recycleBitmap(current)
        recycleBitmap(prev)
        recycleBitmap(next)
        recycleBitmap(blank)
    }

    fun recycleUnused(keep: CurlBitmaps) {
        recycleFresh(keep)
    }

    /** 回收 [this] 里有、[keep] 里没有的位图 */
    fun recycleFresh(keep: CurlBitmaps?) {
        val live = keep?.let { setOfNotNull(it.current, it.prev, it.next, it.blank) } ?: emptySet()
        listOfNotNull(current, prev, next, blank).distinct().forEach { bmp ->
            if (bmp !in live) recycleBitmap(bmp)
        }
    }
}
