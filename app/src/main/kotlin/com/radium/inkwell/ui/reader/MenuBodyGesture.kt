package com.radium.inkwell.ui.reader

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import com.radium.inkwell.reader.api.FlipDirection
import com.radium.inkwell.reader.flip.FLIP_COMMIT_VELOCITY_PX
import com.radium.inkwell.reader.flip.FlipReleaseBeforeSlop
import com.radium.inkwell.reader.flip.classifyReleaseBeforeSlop
import com.radium.inkwell.reader.flip.classifyReleaseBeforeSlopVertical
import com.radium.inkwell.reader.flip.passedFlipSlop
import com.radium.inkwell.reader.flip.passedScrollSlop

/**
 * 菜单中间空白：点一下只关菜单；按当前翻页方向滑一下 = 关菜单并翻一页。
 *
 * 目录 / 设置 / 换源这些 Modal 面板盖着时不要挂 —— 会跟列表滚动抢手势。
 * 只在 [enabled] 时拦截：菜单退场动画还在播时若仍吃手势，关完会短暂翻不了页。
 */
fun Modifier.menuBodyGesture(
    enabled: Boolean,
    vertical: Boolean,
    slopPx: Float,
    onTap: () -> Unit,
    onFlip: (FlipDirection) -> Unit,
): Modifier {
    if (!enabled) return this
    return pointerInput(vertical, slopPx) {
        awaitEachGesture {
            val down = awaitFirstDown()
            val tracker = VelocityTracker()
            tracker.addPosition(down.uptimeMillis, down.position)
            var last = down
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                tracker.addPosition(change.uptimeMillis, change.position)
                last = change
                val dx = change.position.x - down.position.x
                val dy = change.position.y - down.position.y
                val dir = if (vertical) {
                    passedScrollSlop(dx, dy, slopPx)
                } else {
                    passedFlipSlop(dx, dy, slopPx)
                }
                if (dir != null) {
                    change.consume()
                    onFlip(dir)
                    // 菜单已经在关、翻页已经发出；剩下的位移必须吃掉，
                    // 否则会落到刚解禁的正文上再翻一页。
                    if (change.pressed) {
                        while (true) {
                            val rest = awaitPointerEvent()
                            val leftover = rest.changes.firstOrNull { it.id == down.id } ?: break
                            leftover.consume()
                            if (!leftover.pressed) break
                        }
                    }
                    return@awaitEachGesture
                }
                if (!change.pressed) break
            }
            val dx = last.position.x - down.position.x
            val dy = last.position.y - down.position.y
            val vel = tracker.calculateVelocity()
            val action = if (vertical) {
                classifyReleaseBeforeSlopVertical(dx, dy, vel.y, slopPx, FLIP_COMMIT_VELOCITY_PX)
            } else {
                classifyReleaseBeforeSlop(dx, dy, vel.x, slopPx, FLIP_COMMIT_VELOCITY_PX)
            }
            when (action) {
                is FlipReleaseBeforeSlop.Flick -> onFlip(action.direction)
                FlipReleaseBeforeSlop.Tap -> onTap()
                FlipReleaseBeforeSlop.Ignore -> Unit
            }
        }
    }
}
