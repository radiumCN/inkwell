package com.radium.inkwell.reader.flip

import android.os.SystemClock
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import com.radium.inkwell.reader.api.FlipAnimation
import com.radium.inkwell.reader.api.FlipDirection
import com.radium.inkwell.reader.api.ReaderTheme
import com.radium.inkwell.reader.api.TapZones
import com.radium.inkwell.reader.measure.MeasuredParagraph
import com.radium.inkwell.reader.paginate.LayoutSpec
import com.radium.inkwell.reader.paginate.PageItem
import com.radium.inkwell.reader.paginate.PageSpec
import com.radium.inkwell.reader.render.PageCanvas
import com.radium.inkwell.reader.render.TextSelection
import com.radium.inkwell.reader.render.RenderablePage
import com.radium.inkwell.reader.render.drawPage
import com.radium.inkwell.reader.render.imageAt
import com.radium.inkwell.reader.render.renderPageBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
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

/** 收尾被打断时本地挪一格 prev/cur/next，等 ViewModel 换页追上再丢。 */
private data class HeldPages(
    val current: RenderablePage?,
    val prev: RenderablePage?,
    val next: RenderablePage?,
)

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
    /** 点击分区：哪一格翻上一页、下一页、开菜单。见 [TapZones] */
    tapZones: TapZones = TapZones.DEFAULT,
    modifier: Modifier = Modifier,
    /** 长按选中的文字；只在静止态（没在翻页）才可能非空 */
    selection: TextSelection? = null,
    /**
     * 点在当前页的一张图上（含没取到的那条提示）。返回 true = 这一下处理掉了（点提示条重试），不再翻页 / 开菜单；
     * 点在真图上照旧按点击分区走，由调用方返回 false。
     */
    onImageTap: ((PageItem.ImageBox) -> Boolean)? = null,
    onTrace: (ReaderTrace) -> Unit = {},
) {
    val traceLatest = rememberUpdatedState(onTrace)
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val flipSlopPx = with(density) { FLIP_SLOP_DP.dp.toPx() }
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
    var flipId by remember { mutableStateOf(0) }
    var settling by remember { mutableStateOf(false) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val settleJob = remember { arrayOf<Job?>(null) }
    val settleGen = remember { intArrayOf(0) }
    val settleStartedAt = remember { longArrayOf(0L) }
    // 收尾动画还在飞时下一刀按上屏幕：必须先把这一页提交掉。
    // 只 cancel + 复位的话，纸会弹回当前页，连翻就像翻不动。
    val inFlightCommitDir = remember { arrayOf<FlipDirection?>(null) }
    var heldPages by remember { mutableStateOf<HeldPages?>(null) }
    val currentLatest = rememberUpdatedState(current)
    val prevLatest = rememberUpdatedState(prev)
    val nextLatest = rememberUpdatedState(next)
    val canFlipLatest by rememberUpdatedState(canFlip)
    val onCommitLatest by rememberUpdatedState(onCommit)
    fun trace(
        action: ReaderTrace.Action,
        dir: FlipDirection? = null,
        reason: String? = null,
        settleMs: Long? = null,
        id: Int? = flipId.takeIf { direction != null },
    ) {
        traceLatest.value(ReaderTrace(action, dir, id, reason = reason, settleMs = settleMs))
    }
    SideEffect {
        if (heldPages != null && current?.spec != null && current.spec == heldPages?.current?.spec) {
            heldPages = null
        }
    }
    // ViewModel 追上来了（当前页对上）就用它给的三页，别等上面那个 SideEffect：它清掉 heldPages 要到下一次重组，
    // 而 held 的邻页是翻之前不知道的（前翻没有 next、后翻没有 prev）。这一帧正好起手下一刀（连着快翻），
    // 底下露出来的就是一张白纸，字闪一下才出来。
    val held = heldPages?.takeUnless { current?.spec != null && current.spec == it.current?.spec }
    val shownCurrent = held?.current ?: current
    val shownPrev = if (held == null) prev else held.prev
    val shownNext = if (held == null) next else held.next

    // 松手速度越快，收尾动画越短（更跟手）；范围 [minMs, maxMs]
    fun settleDuration(velocity: Float, minMs: Int, maxMs: Int): Int {
        val speed = abs(velocity)
        val t = (speed / 4000f).coerceIn(0f, 1f) // 4000px/s 视为最快
        return (maxMs - (maxMs - minMs) * t).toInt()
    }

    fun commitFlip(dir: FlipDirection, reason: String, settleMs: Long? = null) {
        trace(ReaderTrace.Action.COMMIT, dir, reason, settleMs)
        onCommitLatest(dir)
    }

    fun resetFlipState() {
        direction = null
        settling = false
    }

    // direction 在组合阶段切换图层，offset 则由 graphicsLayer 延后读取。收尾时立刻归零，
    // 旧的动画图层可能还没被移除，就先回到原位，把已经滑走的旧页闪回来一帧。
    // 等静止页的组合成功提交、动画图层卸下后再清位移；新手势仍在起手时自行设初值。
    SideEffect {
        if (direction == null) offset.floatValue = 0f
    }

    fun holdAfterCommit(dir: FlipDirection) {
        val cur = currentLatest.value
        val prv = prevLatest.value
        val nxt = nextLatest.value
        val shifted = when (dir) {
            FlipDirection.FORWARD -> nxt?.let { HeldPages(current = it, prev = cur, next = null) }
            FlipDirection.BACKWARD -> prv?.let { HeldPages(current = it, prev = null, next = cur) }
        }
        if (shifted != null) heldPages = shifted
    }

    /** 把还在飞的那一刀落地。ViewModel 换页比重组慢一拍，先本地挪 prev/cur/next，避免弹回旧页。 */
    fun consumeInFlightCommit(elapsedMs: Long) {
        val dir = inFlightCommitDir[0] ?: return
        inFlightCommitDir[0] = null
        holdAfterCommit(dir)
        if (hapticOnFlip) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        commitFlip(dir, "interrupted", elapsedMs)
    }

    suspend fun settle(commit: Boolean, durationMs: Int, gen: Int) {
        val dir = direction ?: return
        val width = size.width.toFloat()
        val targetOffset = when {
            !commit -> 0f
            dir == FlipDirection.FORWARD -> -width
            else -> width
        }
        try {
            if (effectiveAnim == FlipAnimation.CURL) {
                // 后翻用相对位移（downX 为折叠原点），目标要换算回绝对触点。
                val target = when {
                    commit && dir == FlipDirection.FORWARD -> -width * 0.7f
                    commit -> downX + width
                    dir == FlipDirection.FORWARD -> width - 1.5f
                    else -> downX - width * 0.3f
                }
                val startOffset = offset.floatValue
                val startTouchX = touchX.floatValue
                animate(0f, 1f, animationSpec = tween(durationMs, easing = LinearOutSlowInEasing)) { progress, _ ->
                    touchX.floatValue = startTouchX + (target - startTouchX) * progress
                    // 位图未就绪时仍会画平移层，两条路径都要完成收尾。
                    offset.floatValue = startOffset + (targetOffset - startOffset) * progress
                }
            } else {
                animate(offset.floatValue, targetOffset, animationSpec = tween(durationMs, easing = LinearOutSlowInEasing)) { v, _ ->
                    offset.floatValue = v
                }
            }
            val elapsedMs = SystemClock.uptimeMillis() - settleStartedAt[0]
            if (commit) {
                // 先换页并复位 direction，再归零：任何中间帧都只会画新当前页。
                inFlightCommitDir[0] = null
                holdAfterCommit(dir)
                if (hapticOnFlip) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                commitFlip(dir, "animation_finished", elapsedMs)
            } else {
                trace(ReaderTrace.Action.ROLLBACK, dir, "animation_finished", elapsedMs)
            }
        } finally {
            // 只复位自己这一轮。新一轮 settle 已经加过 gen 的话，这里动 direction 会把新动画掐死
            if (gen == settleGen[0]) {
                inFlightCommitDir[0] = null
                resetFlipState()
            }
        }
    }

    fun launchSettle(commit: Boolean, velocity: Float = 0f) {
        if (settling) return
        val dir = direction ?: return
        val durationMs = when {
            effectiveAnim == FlipAnimation.CURL && commit -> settleDuration(velocity, 200, 320)
            effectiveAnim == FlipAnimation.CURL -> settleDuration(velocity, 160, 240)
            commit -> settleDuration(velocity, 180, 260)
            else -> settleDuration(velocity, 140, 200)
        }
        val gen = ++settleGen[0]
        settleStartedAt[0] = SystemClock.uptimeMillis()
        settling = true
        trace(ReaderTrace.Action.ANIMATION_START, dir, if (commit) "commit" else "rollback", durationMs.toLong())
        inFlightCommitDir[0] = if (commit) direction else null
        settleJob[0] = scope.launch { settle(commit, durationMs, gen) }
    }

    /** 下一刀接手：该翻过去的立刻提交，再清动画。只清不提交会把纸弹回来。 */
    fun interruptSettle(reason: String) {
        if (settling) {
            val elapsedMs = SystemClock.uptimeMillis() - settleStartedAt[0]
            trace(ReaderTrace.Action.INTERRUPTED, direction, reason, elapsedMs)
            consumeInFlightCommit(elapsedMs)
        }
        settleGen[0] += 1
        settleJob[0]?.cancel()
        resetFlipState()
    }

    fun startProgrammaticFlip(dir: FlipDirection, reason: String) {
        interruptSettle(reason)
        if (!canFlipLatest(dir)) {
            trace(ReaderTrace.Action.BLOCKED, dir)
            if (dir == FlipDirection.FORWARD) commitFlip(dir, "boundary") // 让上层弹"最后一页"提示
            return
        }
        if (effectiveAnim == FlipAnimation.NONE) {
            commitFlip(dir, if (animationsEnabled) "animation_none" else "system_animations_disabled")
            return
        }
        // 点击翻页 = 翻整页，走竖直圆柱：触点 Y 钉到页底（与中间横划一致），别卷出斜角
        cornerBottom = true
        flatSwipe = true
        touchY.floatValue = size.height.toFloat()
        downX = if (dir == FlipDirection.FORWARD) size.width * 0.92f else size.width * 0.08f
        touchX.floatValue = downX
        offset.floatValue = 0f
        flipId++
        direction = dir
        launchSettle(commit = true)
    }

    // startProgrammaticFlip 是每次重组都重新捕获 effectiveAnim / onCommit / canFlip 的局部函数；
    // LaunchedEffect 只随 controller 重启，直接在里面调它就永远是**首帧**那一份 —— 用户在
    // 阅读中把仿真换成滑动、或系统关掉动画后，音量键与自动翻页仍按旧类型跑。
    // 经 rememberUpdatedState 取最新，与下面 pointerInput 里读到的保持同一帧。
    val programmaticFlip by rememberUpdatedState<(FlipDirection, String) -> Unit> { dir, reason ->
        startProgrammaticFlip(dir, reason)
    }
    val selectionLatest = rememberUpdatedState(selection)
    // 手势循环的 pointerInput 不以它为 key：在面板里改分区不该把正在进行的手势循环拆了重建
    val tapZonesLatest = rememberUpdatedState(tapZones)
    val onImageTapLatest = rememberUpdatedState(onImageTap)
    val layoutLatest = rememberUpdatedState(layout)
    LaunchedEffect(controller) {
        controller.requests.collect { dir ->
            // 选字中不接程序化翻页（音量键、自动翻页、菜单上的滑动翻页）。拖拽/点按手势早就因为选区停了，
            // 这条路从前却不看：选区和工具条还亮着，底下那页先翻走了，「复制」「净化」拿到的是上一页的字。
            // 这里**不**看 gesturesEnabled —— 菜单上滑动翻页是先关菜单再发请求，请求到的那一刻
            // gesturesEnabled 还没跟上，按它拦就把这一下吞了。
            if (selectionLatest.value != null) {
                trace(ReaderTrace.Action.BLOCKED, dir)
                return@collect
            }
            programmaticFlip(dir, "controller")
        }
    }
    LaunchedEffect(gesturesEnabled) {
        if (!gesturesEnabled) interruptSettle("gestures_disabled")
    }

    Box(
        modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            // 读屏：本页文字 + 翻页 / 开菜单两个动作（见 readerPageSemantics）
            .readerPageSemantics(
                page = current,
                canFlip = { gesturesEnabled && canFlip(it) },
                onFlip = { trace(ReaderTrace.Action.ACCESSIBILITY, it); programmaticFlip(it, "accessibility") },
                onMenu = onCenterTap,
            )
            .pointerInput(gesturesEnabled, effectiveAnim, flipSlopPx) {
                if (!gesturesEnabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    // 先让上一页继续播，等横向翻页手势成立再接管。
                    val startedDuringFlip = direction != null
                    val tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, down.position)
                    var last = down
                    var dragDir: FlipDirection? = null
                    var followedFlipId: Int? = null
                    fun isFollowing() = followedFlipId == flipId && direction != null && !settling
                    var cancelled = false
                    // 快甩时位移经常整段落在抬手那一帧。必须先判 slop 再看 pressed ——
                    // 先因 UP 跳出的话，offset 几乎是 0，松手当回弹，中间还会被当成开菜单。
                    // 也不走 horizontalDrag：它不把 UP 交给回调，抬手位移和速度都会丢；
                    // 若 UP 已经发生，它还会一直等下一个事件，仿真就停在半页。
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            // 系统收走手势（边缘侧滑返回、下拉通知栏、弹窗抢焦点）时，Compose 把 ACTION_CANCEL
                            // 合成为一次「已消费的抬手」。只看 pressed 会当成真松手：没过 slop 判成点按 →
                            // 翻页或开菜单，过了 slop 按松手速度提交翻页。真抬手到这里还没人消费过。
                            // 手指还按着、事件却已被消费，是外层在 Initial 阶段把这一手接走了（阅读页的双指调字号、
                            // 右边缘调亮度）：同样按取消收场 —— 否则第二根手指按下前那一截位移会被当成翻页，
                            // 边调字号边卷页。这一层自己的 consume 只作用于当次事件，不会让下一次事件看着「已消费」
                            if (change.isConsumed) {
                                cancelled = true
                                break
                            }
                            tracker.addPosition(change.uptimeMillis, change.position)
                            last = change
                            val dx = change.position.x - down.position.x
                            val dy = change.position.y - down.position.y
                            if (dragDir == null) {
                                val dir = passedFlipSlop(dx, dy, flipSlopPx)
                                if (dir == null) {
                                    if (!change.pressed) break
                                    continue
                                }
                                change.consume()
                                dragDir = dir
                                // 连翻：收尾还在播时下一刀接手。先把上一页提交掉，再跟手。
                                if (settling || direction != null || inFlightCommitDir[0] != null) {
                                    interruptSettle("next_swipe")
                                }
                                val flippable = canFlipLatest(dir)
                                val width = size.width.toFloat()
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
                                    touchY.floatValue = if (flatSwipe) {
                                        if (cornerBottom) h else 0f
                                    } else {
                                        change.position.y
                                    }
                                    downX = down.position.x
                                    touchX.floatValue = change.position.x
                                    offset.floatValue = seedFlipOffset(dx, dir, width)
                                    flipId++
                                    direction = dir
                                    followedFlipId = flipId
                                }
                                if (!change.pressed) break
                                continue
                            }
                            change.consume()
                            if (isFollowing()) {
                                val width = size.width.toFloat()
                                val dir = dragDir
                                // 拖拽路径直写状态，不经协程（每事件 launch 会造成输入延迟与分配抖动）。
                                // 中间横划时 Y 保持钉在页边，不跟手指上下漂 —— 否则折痕会随手抖来抖去
                                touchY.floatValue = if (flatSwipe) {
                                    if (cornerBottom) size.height.toFloat() else 0f
                                } else {
                                    change.position.y
                                }
                                touchX.floatValue = change.position.x
                                // 用相对落点的总位移，不累加 positionChange：UP 没有 delta，累加会少最后一截。
                                offset.floatValue = seedFlipOffset(
                                    change.position.x - down.position.x, dir, width,
                                )
                            }
                            if (!change.pressed) break
                        }
                    } catch (e: CancellationException) {
                        trace(ReaderTrace.Action.SWIPE_CANCELLED, dragDir, "input_restarted", id = followedFlipId)
                        // pointerInput 被掐时别把 direction 晾着：仿真会停在半页，此后点击也不翻。
                        if (isFollowing()) {
                            val dir = direction
                            if (dir != null && canFlipLatest(dir)) {
                                val width = size.width.toFloat()
                                val elapsed = (last.uptimeMillis - down.uptimeMillis).coerceAtLeast(0L)
                                if (shouldCommitHorizontalFlip(
                                        offset.floatValue,
                                        tracker.calculateVelocity().x,
                                        width,
                                        dir,
                                        elapsed,
                                    )
                                ) {
                                    holdAfterCommit(dir)
                                    commitFlip(dir, "gesture_cancelled")
                                }
                            }
                            resetFlipState()
                        }
                        throw e
                    }
                    if (cancelled) {
                        trace(ReaderTrace.Action.SWIPE_CANCELLED, dragDir, "input_consumed", id = followedFlipId)
                        // 跟手到一半被收走：弹回当前页，不提交
                        if (isFollowing()) launchSettle(commit = false)
                        return@awaitEachGesture
                    }
                    if (dragDir == null) {
                        val dx = last.position.x - down.position.x
                        val dy = last.position.y - down.position.y
                        val action = classifyReleaseBeforeSlop(
                            dx, dy, tracker.calculateVelocity().x, flipSlopPx, FLIP_COMMIT_VELOCITY_PX,
                        )
                        when (action) {
                            is FlipReleaseBeforeSlop.Flick -> {
                                trace(ReaderTrace.Action.SWIPE_RELEASE, action.direction, id = null)
                                programmaticFlip(action.direction, "next_flick")
                            }
                            FlipReleaseBeforeSlop.Tap -> {
                                val idle = !startedDuringFlip && direction == null
                                val hit = if (!idle) null else onImageTapLatest.value?.let {
                                    (heldPages?.current ?: currentLatest.value)
                                        ?.imageAt(down.position.x, down.position.y, layoutLatest.value)
                                }
                                if (hit == null || onImageTapLatest.value?.invoke(hit) != true) {
                                    tapAction(
                                        down.position.x, down.position.y,
                                        size.width.toFloat(), size.height.toFloat(),
                                        tapZonesLatest.value,
                                    ).dispatch(
                                        onMenu = {
                                            if (idle) {
                                                trace(ReaderTrace.Action.TAP)
                                                onCenterTap()
                                            }
                                        },
                                        onFlip = {
                                            trace(ReaderTrace.Action.TAP, it, id = null)
                                            programmaticFlip(it, "next_tap")
                                        },
                                    )
                                }
                            }
                            FlipReleaseBeforeSlop.Ignore -> Unit
                        }
                        return@awaitEachGesture
                    }
                    val dir = dragDir
                    trace(ReaderTrace.Action.SWIPE_RELEASE, dir, id = followedFlipId)
                    if (followedFlipId != null && !isFollowing()) return@awaitEachGesture
                    val flippable = canFlipLatest(dir)
                    val width = size.width.toFloat()
                    if (!flippable) {
                        trace(ReaderTrace.Action.BLOCKED, dir)
                        // 书首/书末：无动画，前翻到底时提示
                        if (dir == FlipDirection.FORWARD) commitFlip(dir, "boundary")
                        if (isFollowing()) resetFlipState()
                        return@awaitEachGesture
                    }
                    if (effectiveAnim == FlipAnimation.NONE) {
                        commitFlip(dir, if (animationsEnabled) "animation_none" else "system_animations_disabled")
                        return@awaitEachGesture
                    }
                    val velocity = tracker.calculateVelocity().x
                    val elapsed = (last.uptimeMillis - down.uptimeMillis).coerceAtLeast(0L)
                    val displacement = seedFlipOffset(last.position.x - down.position.x, dir, width)
                    val commit = shouldCommitHorizontalFlip(displacement, velocity, width, dir, elapsed)
                    if (isFollowing()) {
                        launchSettle(commit, velocity)
                    } else if (commit) {
                        programmaticFlip(dir, "next_swipe")
                    }
                }
            },
    ) {
        when (effectiveAnim) {
            // 滚动模式根本不该走到这里 —— 它是另一条渲染路径，由 ReaderScreen 分流。
            // 万一走到了（比如设置迁移遗漏），静态画当前页，总好过崩溃或白屏。
            FlipAnimation.NONE, FlipAnimation.SCROLL ->
                PageCanvas(shownCurrent, layout, theme, selection = selection)
            FlipAnimation.SLIDE -> SlideLayers(
                selection, shownCurrent, shownPrev, shownNext, layout, theme, direction, offset, size.width.toFloat(),
            )
            FlipAnimation.COVER -> CoverLayers(
                selection, shownCurrent, shownPrev, shownNext, layout, theme, direction, offset, size.width.toFloat(),
            )
            FlipAnimation.CURL -> CurlLayer(
                selection = selection,
                current = shownCurrent, prev = shownPrev, next = shownNext,
                layout = layout, theme = theme, direction = direction,
                offset = offset,
                touchX = touchX,
                downX = downX,
                touchY = touchY,
                cornerBottom = cornerBottom,
                size = size, density = density,
                flipId = flipId, onTrace = { traceLatest.value(it) },
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
    offset: MutableFloatState,
    touchX: MutableFloatState,
    downX: Float,
    touchY: MutableFloatState,
    cornerBottom: Boolean,
    size: IntSize,
    density: androidx.compose.ui.unit.Density,
    flipId: Int,
    onTrace: (ReaderTrace) -> Unit,
    selection: TextSelection? = null,
) {
    // 闲时也预渲，但放到 Default：主线程不再被 remember { renderPageBitmap } 卡住。
    // 位图是不可变 ARGB（见 renderPageBitmap）。哪一页的旧图还对得上就原样留用，
    // 换下来的旧图延后 recycle，避免正画着的那一帧被回收。
    var bitmaps by remember { mutableStateOf<CurlBitmaps?>(null) }
    DisposableEffect(Unit) {
        onDispose { bitmaps?.recycleAll() }
    }
    // 一条常驻的渲染循环，最新的请求说了算。
    //
    // 从前是 LaunchedEffect(页, 版式, 纸张) —— 每变一次就起一轮，而每轮都在 NonCancellable 里烤满三张：
    // 被取代的那轮照样跑完，只是交不出去。拖进度条、拖自定义纸色时一秒几十次变化，Default 上就堆着
    // 几十轮全屏 ARGB（一张 10~20MB）并行在烤，内存冲高、真正要的那一轮反而排在最后。
    // 现在同一时刻只烤一轮；烤的时候来的请求 conflate 成最新的一个，烤完再接着烤它，中间的全部跳过。
    //
    // 正在烤的那一轮仍然 NonCancellable：渲染本身没有挂起点，取消也停不下来，取消只会让 withContext
    // 把结果扔掉 —— 新渲出来的位图就没人 recycle 了。烤完总能拿到结果，要么交出去、要么回收。
    val request = CurlRequest(current, prev, next, layout, theme, density)
    val latestRequest = rememberUpdatedState(request)
    LaunchedEffect(Unit) {
        snapshotFlow { latestRequest.value }
            .distinctUntilChanged { a, b -> a.sameContent(b) }
            .conflate()
            .collect { req ->
                val old = bitmaps
                val produced = withContext(Dispatchers.Default + NonCancellable) {
                    renderCurlBitmaps(old, req)
                }
                if (produced === old) return@collect
                if (!isActive) {
                    // 没交出去：只回收相对 old 新渲的那几张，共用槽不能动
                    produced.recycleFresh(old)
                    return@collect
                }
                bitmaps = produced
                // 回收宽限期不阻塞下一轮位图渲染。之后的请求只会复用 produced 里仍持有的槽。
                if (old != null) launch(start = CoroutineStart.UNDISPATCHED) {
                    withContext(NonCancellable) {
                        delay(32)
                        old.recycleUnused(produced)
                    }
                }
            }
    }
    if (size.width <= 1 || size.height <= 1) {
        PageCanvas(current, layout, theme, selection = selection)
        return
    }
    val renderer = remember(size) { CurlRenderer(size.width.toFloat(), size.height.toFloat()) }

    if (direction == null) {
        PageCanvas(current, layout, theme, selection = selection)
        return
    }
    val frontPage = if (direction == FlipDirection.FORWARD) current else prev
    val underPage = if (direction == FlipDirection.FORWARD) next else current
    // 连翻后可直接找上一轮相同内容的图，不必等后台把位图挪到新的槽位。
    val matching = bitmaps?.takeIf { it.matchesStyle(request) }
    val frontBitmap = frontPage?.let { matching?.bitmapFor(it.curlKey()) }
    val under = underPage?.let { matching?.bitmapFor(it.curlKey()) }
    val startedWithFront = remember(flipId) { frontBitmap != null }
    val front = frontBitmap.takeIf { startedWithFront }
    // 后翻的卷曲页是上一页。连翻时分页通常已就绪，位图却要等上一次提交后才开始生成。
    // 起手缺位图时直接在卷页可见区画正文，且这一刀保持直接绘制，避免位图就绪后换绘制方式。
    val liveFront = frontPage.takeIf { direction == FlipDirection.BACKWARD && front == null }
    val render = when {
        liveFront != null -> ReaderTrace.Render.CURL_LIVE_FRONT
        front == null -> ReaderTrace.Render.SLIDE
        under != null -> ReaderTrace.Render.CURL_BITMAPS
        underPage != null -> ReaderTrace.Render.CURL_LIVE_UNDER
        else -> ReaderTrace.Render.CURL_PAPER_UNDER
    }
    val reason = when (render) {
        ReaderTrace.Render.SLIDE, ReaderTrace.Render.CURL_LIVE_FRONT -> when {
            frontPage == null -> "front_page_unavailable"
            startedWithFront -> "front_bitmap_invalidated"
            else -> "front_bitmap_not_ready_at_start"
        }
        ReaderTrace.Render.CURL_LIVE_UNDER -> "under_bitmap_not_ready"
        ReaderTrace.Render.CURL_PAPER_UNDER -> "under_page_unavailable"
        else -> null
    }
    LaunchedEffect(flipId, render, reason) {
        onTrace(ReaderTrace(ReaderTrace.Action.RENDER_SELECTED, direction, flipId, render, reason))
    }
    if (render == ReaderTrace.Render.SLIDE) {
        SlideLayers(
            selection, current, prev, next, layout, theme, direction, offset, size.width.toFloat(),
        )
        return
    }
    Spacer(
        Modifier.fillMaxSize().drawBehind {
            val tx = if (direction == FlipDirection.BACKWARD) {
                touchX.floatValue - downX
            } else {
                touchX.floatValue
            }
            // 底页不参与卷曲变形，位图未完成时可在露出的区域画真实页面。
            val drawLiveUnder: (() -> Unit)? = if (under == null && underPage != null) {
                { drawPage(underPage, layout, theme) }
            } else null
            val drawLiveFront: (() -> Unit)? = liveFront?.let { page -> { drawPage(page, layout, theme) } }
            drawIntoCanvas { canvas ->
                renderer.draw(
                    canvas.nativeCanvas,
                    front,
                    under,
                    tx, touchY.floatValue, cornerBottom,
                    paperColor = theme.background.toInt(),
                    drawLiveUnder = drawLiveUnder,
                    drawLiveFront = drawLiveFront,
                )
            }
        }
    )
}

/**
 * 一张卷页位图画的是哪一页。
 *
 * 光比 [PageSpec] 不够：改了净化规则后重排，同一页的 spec 可能一模一样（行数、偏移都没变），字却换过了。
 * 那时 measured 一定是新的一份（每次分页都新建），所以再按引用比一次 measured。
 * 同一份分页结果里的页共用同一个 measured，翻页挪槽时照样认得出来。
 * 图片异步解码后 spec 和 measured 不变，必须同时比较 images，避免复用带占位框的旧快照。
 */
internal class CurlPageKey(
    val spec: PageSpec,
    val measured: Map<Int, MeasuredParagraph>,
    val images: Map<Int, androidx.compose.ui.graphics.ImageBitmap>,
    val failedImages: Set<Int> = emptySet(),
) {
    override fun equals(other: Any?): Boolean =
        other is CurlPageKey && other.measured === measured && other.spec == spec &&
            other.images == images && other.failedImages == failedImages

    override fun hashCode(): Int = spec.hashCode()
}

private fun RenderablePage.curlKey() = CurlPageKey(spec, measured, images, failedImages)

private fun RenderablePage?.matches(key: CurlPageKey?): Boolean =
    if (this == null) key == null else key == curlKey()

/** 渲染循环的一份输入。页面对象每次出页都是新建的，比较时按 [CurlPageKey] 比，不按引用 */
private class CurlRequest(
    val current: RenderablePage?,
    val prev: RenderablePage?,
    val next: RenderablePage?,
    val layout: LayoutSpec,
    val theme: ReaderTheme,
    val density: androidx.compose.ui.unit.Density,
) {
    fun sameContent(other: CurlRequest): Boolean =
        layout == other.layout && theme == other.theme && density == other.density &&
            current.matches(other.current?.curlKey()) &&
            prev.matches(other.prev?.curlKey()) &&
            next.matches(other.next?.curlKey())
}

/**
 * 能留用就留用：三页里哪一页的旧图还对得上（前翻后的 prev←current、current←next，
 * 拖进度条时没动的邻页……），就直接搬过来，只渲真正新出现的那几页。
 * 版式、纸张、密度变了则一张都用不上 —— 字的位置、颜色、像素全不一样。
 * 什么都没变时原样返回 [old]，调用方据此跳过提交。
 */
private fun renderCurlBitmaps(old: CurlBitmaps?, req: CurlRequest): CurlBitmaps {
    val reusable = old?.takeIf { it.matchesStyle(req) }
    if (reusable != null &&
        req.current.matches(reusable.currentKey) &&
        req.prev.matches(reusable.prevKey) &&
        req.next.matches(reusable.nextKey)
    ) {
        return reusable
    }
    fun obtain(page: RenderablePage?): android.graphics.Bitmap? {
        if (page == null) return null
        reusable?.bitmapFor(page.curlKey())?.let { return it }
        return renderPageAndroidBitmap(page, req.layout, req.theme, req.density)
    }
    return CurlBitmaps(
        current = obtain(req.current),
        prev = obtain(req.prev),
        next = obtain(req.next),
        currentKey = req.current?.curlKey(),
        prevKey = req.prev?.curlKey(),
        nextKey = req.next?.curlKey(),
        layout = req.layout,
        theme = req.theme,
        density = req.density,
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

/**
 * CURL 用的三页。空闲时持有，手势开始零合成。位图直接是 Android Bitmap，draw 路径不再每帧 asAndroidBitmap。
 * 邻页没排好时那一层传 null，CurlRenderer 露纸色 —— 不再垫一张全屏空白位图（见 CurlRenderer.draw）。
 */
private class CurlBitmaps(
    val current: android.graphics.Bitmap?,
    val prev: android.graphics.Bitmap?,
    val next: android.graphics.Bitmap?,
    val currentKey: CurlPageKey?,
    val prevKey: CurlPageKey?,
    val nextKey: CurlPageKey?,
    val layout: LayoutSpec,
    val theme: ReaderTheme,
    val density: androidx.compose.ui.unit.Density,
) {
    fun bitmapFor(key: CurlPageKey): android.graphics.Bitmap? = when (key) {
        currentKey -> current
        prevKey -> prev
        nextKey -> next
        else -> null
    }

    fun matchesStyle(req: CurlRequest): Boolean =
        layout == req.layout && theme == req.theme && density == req.density

    fun recycleAll() {
        recycleBitmap(current)
        recycleBitmap(prev)
        recycleBitmap(next)
    }

    fun recycleUnused(keep: CurlBitmaps) {
        recycleFresh(keep)
    }

    /** 回收 [this] 里有、[keep] 里没有的位图 */
    fun recycleFresh(keep: CurlBitmaps?) {
        val live = keep?.let { setOfNotNull(it.current, it.prev, it.next) } ?: emptySet()
        listOfNotNull(current, prev, next).distinct().forEach { bmp ->
            if (bmp !in live) recycleBitmap(bmp)
        }
    }
}
