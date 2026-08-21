package com.radium.inkwell.reader.flip

import com.radium.inkwell.reader.api.FlipDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FlipGestureMathTest {

    @Test
    fun `水平位移够了就判定翻页方向`() {
        assertEquals(FlipDirection.FORWARD, passedFlipSlop(-12f, 2f, slop = 10f))
        assertEquals(FlipDirection.BACKWARD, passedFlipSlop(12f, -3f, slop = 10f))
    }

    @Test
    fun `没过 slop 不开始拖`() {
        assertNull(passedFlipSlop(-8f, 0f, slop = 10f))
    }

    @Test
    fun `斜着滑只要横向够也能翻`() {
        assertEquals(FlipDirection.FORWARD, passedFlipSlop(-20f, -15f, slop = 10f))
    }

    @Test
    fun `竖向明显更大不开始拖`() {
        assertNull(passedFlipSlop(-12f, 30f, slop = 10f))
    }

    @Test
    fun `过不了 slop 的快甩按速度翻页`() {
        val action = classifyReleaseBeforeSlop(
            dx = -8f, dy = 2f, velocityX = -800f, slop = 10f, flickVelocity = 700f,
        )
        assertEquals(FlipReleaseBeforeSlop.Flick(FlipDirection.FORWARD), action)
    }

    @Test
    fun `慢抬手当点击`() {
        val action = classifyReleaseBeforeSlop(
            dx = -4f, dy = 1f, velocityX = -200f, slop = 10f, flickVelocity = 700f,
        )
        assertEquals(FlipReleaseBeforeSlop.Tap, action)
    }

    @Test
    fun `位移和甩速反号不当快甩`() {
        val action = classifyReleaseBeforeSlop(
            dx = 8f, dy = 0f, velocityX = -800f, slop = 10f, flickVelocity = 700f,
        )
        assertEquals(FlipReleaseBeforeSlop.Tap, action)
    }

    @Test
    fun `明显竖滑不当点击`() {
        val action = classifyReleaseBeforeSlop(
            dx = 2f, dy = 30f, velocityX = 50f, slop = 10f, flickVelocity = 700f,
        )
        assertEquals(FlipReleaseBeforeSlop.Ignore, action)
    }
}
