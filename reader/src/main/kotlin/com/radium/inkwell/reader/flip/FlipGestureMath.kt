package com.radium.inkwell.reader.flip

import com.radium.inkwell.reader.api.FlipDirection
import kotlin.math.abs

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
    return FlipReleaseBeforeSlop.Tap
}
