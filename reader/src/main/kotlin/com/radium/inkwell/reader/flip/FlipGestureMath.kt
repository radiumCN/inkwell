package com.radium.inkwell.reader.flip

import com.radium.inkwell.reader.api.FlipDirection
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
