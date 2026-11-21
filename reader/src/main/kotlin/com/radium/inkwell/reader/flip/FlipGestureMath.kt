package com.radium.inkwell.reader.flip

import com.radium.inkwell.reader.api.FlipDirection
import com.radium.inkwell.reader.api.TapAction
import com.radium.inkwell.reader.api.TapZones
import kotlin.math.abs

/** 甩页速度阈值（px/s） */
const val FLIP_COMMIT_VELOCITY_PX = 700f
/** 相对屏宽：走过这么多就算翻过去 */
const val FLIP_COMMIT_DISTANCE_FRACTION = 8f
/** 开始跟手所需位移（dp）。系统 touchSlop 约 18dp，斜着短甩经常过不了。 */
const val FLIP_SLOP_DP = 10f
/**
 * 短促一甩的时限。快翻时手指在屏幕上往往只有 80–180ms，
 * 位移过不了 1/8 屏、VelocityTracker 还可能是 0，必须靠时长兜底。
 */
const val FLIP_FLICK_MAX_MS = 220L

/**
 * 左右翻页在 slop 之前的松手该怎么办。
 * 系统 [awaitHorizontalTouchSlop] 约 18dp：斜着短甩经常过不了，中间区域就被当成开菜单。
 */
sealed class FlipReleaseBeforeSlop {
    data object Tap : FlipReleaseBeforeSlop()
    data class Flick(val direction: FlipDirection) : FlipReleaseBeforeSlop()
    /** 明显在竖着划，不当点击、也不翻页 */
    data object Ignore : FlipReleaseBeforeSlop()
}

/**
 * 位移是否已经构成「横向翻页」。允许最多约 63° 偏斜（|dy| ≤ 2|dx|），
 * 阅读时拇指很少走纯水平。
 */
fun passedFlipSlop(dx: Float, dy: Float, slop: Float): FlipDirection? {
    if (abs(dx) < slop) return null
    if (abs(dy) > abs(dx) * 2f) return null
    return if (dx < 0f) FlipDirection.FORWARD else FlipDirection.BACKWARD
}

/**
 * 还没过 slop 就抬手：快甩按速度翻页，竖滑丢掉，其余当点击。
 */
fun classifyReleaseBeforeSlop(
    dx: Float,
    dy: Float,
    velocityX: Float,
    slop: Float,
    flickVelocity: Float,
): FlipReleaseBeforeSlop {
    if (abs(dy) > slop && abs(dy) > abs(dx) * 2f) return FlipReleaseBeforeSlop.Ignore
    if (abs(velocityX) >= flickVelocity) {
        // 位移和速度反号 = 来回搓，不当快甩
        if (abs(dx) >= 1f && ((dx < 0f) != (velocityX < 0f))) {
            return FlipReleaseBeforeSlop.Tap
        }
        return FlipReleaseBeforeSlop.Flick(
            if (velocityX < 0f) FlipDirection.FORWARD else FlipDirection.BACKWARD,
        )
    }
    // 快甩的位移经常整段落在 UP 上，两三个采样点算出来的速度是 0。
    // 位移本身已经过 slop 就按甩页，别退化成点击（中间点一下会开菜单）。
    if (abs(dx) >= slop) {
        return FlipReleaseBeforeSlop.Flick(
            if (dx < 0f) FlipDirection.FORWARD else FlipDirection.BACKWARD,
        )
    }
    return FlipReleaseBeforeSlop.Tap
}

/**
 * 滚动模式：手指上滑（dy < 0）翻下一页。允许最多约 63° 偏斜。
 */
fun passedScrollSlop(dx: Float, dy: Float, slop: Float): FlipDirection? {
    if (abs(dy) < slop) return null
    if (abs(dx) > abs(dy) * 2f) return null
    return if (dy < 0f) FlipDirection.FORWARD else FlipDirection.BACKWARD
}

/** 还没过 slop 就抬手：快甩按速度翻页，横滑丢掉，其余当点击。 */
fun classifyReleaseBeforeSlopVertical(
    dx: Float,
    dy: Float,
    velocityY: Float,
    slop: Float,
    flickVelocity: Float,
): FlipReleaseBeforeSlop {
    if (abs(dx) > slop && abs(dx) > abs(dy) * 2f) return FlipReleaseBeforeSlop.Ignore
    if (abs(velocityY) >= flickVelocity) {
        if (abs(dy) >= 1f && ((dy < 0f) != (velocityY < 0f))) {
            return FlipReleaseBeforeSlop.Tap
        }
        return FlipReleaseBeforeSlop.Flick(
            if (velocityY < 0f) FlipDirection.FORWARD else FlipDirection.BACKWARD,
        )
    }
    return FlipReleaseBeforeSlop.Tap
}

/**
 * 点击落点按九宫格 [zones] 该做的事。
 * [enabled] 为 false 时一律 [TapAction.MENU] —— 滚动模式默认如此，避免误触整页跳。
 */
fun tapAction(
    x: Float,
    y: Float,
    width: Float,
    height: Float,
    zones: TapZones,
    enabled: Boolean = true,
): TapAction = if (enabled) zones.actionAt(x, y, width, height) else TapAction.MENU

/**
 * 点击动作分发，翻页容器与滚动阅读共用。`when` 不留 else：以后加一种 [TapAction]（比如朗读），
 * 编译器会在这一处逼着把它接上，而不是两种阅读模式各漏一处、点同一格表现不一样。
 */
inline fun TapAction.dispatch(onMenu: () -> Unit, onFlip: (FlipDirection) -> Unit) {
    when (this) {
        TapAction.PREV -> onFlip(FlipDirection.BACKWARD)
        TapAction.NEXT -> onFlip(FlipDirection.FORWARD)
        TapAction.MENU -> onMenu()
        TapAction.NONE -> Unit
    }
}

/** 仿真卷页起手时锁定的握法，见 [curlGrip]。 */
data class CurlGrip(
    /** 卷角在右下（true）还是右上（false） */
    val cornerBottom: Boolean,
    /** 触点 Y 钉到卷角所在的页边（折痕竖直、整页绕竖轴卷过去），不跟手指上下走 */
    val pinTouchY: Boolean,
)

/**
 * 仿真卷页拖拽过 slop 那一刻锁定卷角与触点 Y 的取法，拖拽中不再改变（避免翻页中途跳变）。
 * 两条规则互相牵连，放在一起定：
 *
 * - **后翻一律用右下角**：从上半屏往回翻若按触点选顶角，会卷出很别扭的对角。
 * - **钉 Y**：从屏幕中间那一带（H/3 ~ 2H/3）起手 = 想翻整页，不是揪角；**所有后翻**也钉。
 *   只有前翻从上 / 下三分之一起手才让 Y 跟手，卷出斜的揪角。从前中间横划也跟手，就卷出一条
 *   贯穿全页的大斜折；钉边曾因退化处理把控制点塌回卷角而崩过，已在 CurlRenderer 用 ÷0.1 修好。
 *
 * 后翻必须钉，否则和第一条相克：卷角锁在右下，后翻的触点 X 又是相对位移（起手时贴着左边），
 * 这时 Y 再停在上半屏，触点到卷角的连线陡得很，CurlRenderer 的防撕裂钳制（折线底端不许越过书脊）
 * 会沿连线把触点一路拽回右下 —— 手指在左上，卷角在右下离它一千多像素，手指走一屏卷角只挪半屏，
 * 松手后还缩回角里而不是整页扫过去（0.6.1 反馈「左上角往回翻不跟手」）。legado 同样是后翻一律钉
 * （「上一页滑动不出现对角」）。这一半在修大斜折时被漏掉过一次，所以抽出来让单测钉着。
 */
fun curlGrip(direction: FlipDirection, downY: Float, height: Float): CurlGrip {
    val backward = direction == FlipDirection.BACKWARD
    val middleBand = downY > height / 3f && downY < height * 2f / 3f
    return CurlGrip(
        cornerBottom = backward || downY > height / 2f,
        pinTouchY = backward || middleBand,
    )
}

/** 相对落点的总位移种进 offset。快甩时 UP 没有 positionChange，累加会少最后一截。 */
fun seedFlipOffset(dx: Float, direction: FlipDirection, width: Float): Float {
    val w = width.coerceAtLeast(1f)
    val range = if (direction == FlipDirection.FORWARD) -w..0f else 0f..w
    return dx.coerceIn(range)
}

fun shouldCommitHorizontalFlip(
    offset: Float,
    velocityX: Float,
    width: Float,
    direction: FlipDirection,
    elapsedMs: Long = Long.MAX_VALUE,
): Boolean {
    val w = width.coerceAtLeast(1f)
    val directional = when (direction) {
        FlipDirection.FORWARD -> offset < 0f
        FlipDirection.BACKWARD -> offset > 0f
    }
    // 过了 slop 的短促一甩：位移可能只有几十像素，速度采样还经常是 0。
    if (elapsedMs <= FLIP_FLICK_MAX_MS && directional) return true
    return when (direction) {
        FlipDirection.FORWARD ->
            offset < -w / FLIP_COMMIT_DISTANCE_FRACTION || velocityX < -FLIP_COMMIT_VELOCITY_PX
        FlipDirection.BACKWARD ->
            offset > w / FLIP_COMMIT_DISTANCE_FRACTION || velocityX > FLIP_COMMIT_VELOCITY_PX
    }
}
