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
    fun `上滑构成滚动翻下一页`() {
        assertEquals(FlipDirection.FORWARD, passedScrollSlop(2f, -12f, slop = 10f))
        assertEquals(FlipDirection.BACKWARD, passedScrollSlop(-3f, 12f, slop = 10f))
        assertNull(passedScrollSlop(30f, -12f, slop = 10f))
    }

    @Test
    fun `快甩过 slop 的总位移要种进 offset`() {
        assertEquals(-180f, seedFlipOffset(-180f, FlipDirection.FORWARD, width = 1080f), 0.01f)
        assertEquals(0f, seedFlipOffset(40f, FlipDirection.FORWARD, width = 1080f), 0.01f)
    }

    @Test
    fun `offset 还小但甩速够也提交`() {
        assertEquals(
            true,
            shouldCommitHorizontalFlip(-20f, -800f, width = 1080f, FlipDirection.FORWARD),
        )
        assertEquals(
            true,
            shouldCommitHorizontalFlip(-200f, 0f, width = 1080f, FlipDirection.FORWARD),
        )
        assertEquals(
            false,
            shouldCommitHorizontalFlip(-20f, -100f, width = 1080f, FlipDirection.FORWARD),
        )
    }

    @Test
    fun `过不了 slop 的竖向快甩按速度翻页`() {
        val action = classifyReleaseBeforeSlopVertical(
            dx = 2f, dy = -8f, velocityY = -800f, slop = 10f, flickVelocity = 700f,
        )
        assertEquals(FlipReleaseBeforeSlop.Flick(FlipDirection.FORWARD), action)
    }

    @Test
    fun `明显竖滑不当点击`() {
        val action = classifyReleaseBeforeSlop(
            dx = 2f, dy = 30f, velocityX = 50f, slop = 10f, flickVelocity = 700f,
        )
        assertEquals(FlipReleaseBeforeSlop.Ignore, action)
    }
}
