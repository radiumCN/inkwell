package com.radium.inkwell.reader.flip

import com.radium.inkwell.reader.api.FlipDirection
import com.radium.inkwell.reader.api.TapAction
import com.radium.inkwell.reader.api.TapZones
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
    fun `过了 slop 的短促一甩即使位移不够也提交`() {
        assertEquals(
            true,
            shouldCommitHorizontalFlip(
                -25f, -100f, width = 1080f, FlipDirection.FORWARD, elapsedMs = 120L,
            ),
        )
        assertEquals(
            false,
            shouldCommitHorizontalFlip(
                -25f, -100f, width = 1080f, FlipDirection.FORWARD, elapsedMs = 400L,
            ),
        )
    }

    @Test
    fun `抬手才过 slop 且速度为 0 也按甩页`() {
        val action = classifyReleaseBeforeSlop(
            dx = -40f, dy = 4f, velocityX = 0f, slop = 10f, flickVelocity = 700f,
        )
        assertEquals(FlipReleaseBeforeSlop.Flick(FlipDirection.FORWARD), action)
    }

    @Test
    fun `过不了 slop 的竖向快甩按速度翻页`() {
        val action = classifyReleaseBeforeSlopVertical(
            dx = 2f, dy = -8f, velocityY = -800f, slop = 10f, flickVelocity = 700f,
        )
        assertEquals(FlipReleaseBeforeSlop.Flick(FlipDirection.FORWARD), action)
    }

    @Test
    fun `默认分区只看横向：左中右对应上一页、菜单、下一页`() {
        val z = TapZones.DEFAULT
        for (y in listOf(50f, 800f, 1550f)) {
            assertEquals(TapAction.PREV, tapAction(100f, y, 900f, 1600f, z))
            assertEquals(TapAction.MENU, tapAction(450f, y, 900f, 1600f, z))
            assertEquals(TapAction.NEXT, tapAction(800f, y, 900f, 1600f, z))
        }
        // 分界线归右边那格：300 落中列，600 落右列（从前 600 算菜单，见 TapZones.actionAt）
        assertEquals(TapAction.PREV, tapAction(299f, 800f, 900f, 1600f, z))
        assertEquals(TapAction.MENU, tapAction(300f, 800f, 900f, 1600f, z))
        assertEquals(TapAction.MENU, tapAction(599f, 800f, 900f, 1600f, z))
        assertEquals(TapAction.NEXT, tapAction(600f, 800f, 900f, 1600f, z))
    }

    @Test
    fun `关掉点击翻页时左右也只当菜单`() {
        val z = TapZones.DEFAULT
        assertEquals(TapAction.MENU, tapAction(100f, 800f, 900f, 1600f, z, enabled = false))
        assertEquals(TapAction.MENU, tapAction(800f, 800f, 900f, 1600f, z, enabled = false))
        assertEquals(TapAction.MENU, tapAction(450f, 800f, 900f, 1600f, z, enabled = false))
    }

    @Test
    fun `上下分区按行落格，压在屏幕外一点也夹回边上那格`() {
        val z = TapZones.Preset.TOP_BOTTOM.zones
        assertEquals(TapAction.PREV, tapAction(800f, 100f, 900f, 1600f, z))
        assertEquals(TapAction.NEXT, tapAction(100f, 1500f, 900f, 1600f, z))
        assertEquals(TapAction.PREV, tapAction(-3f, -3f, 900f, 1600f, z))
        assertEquals(TapAction.NEXT, tapAction(903f, 1603f, 900f, 1600f, z))
    }

    @Test
    fun `分发：翻页两向、菜单各走各的，无动作什么都不做`() {
        fun run(a: TapAction): String {
            var got = "nothing"
            a.dispatch(onMenu = { got = "menu" }, onFlip = { got = it.name })
            return got
        }
        assertEquals("BACKWARD", run(TapAction.PREV))
        assertEquals("FORWARD", run(TapAction.NEXT))
        assertEquals("menu", run(TapAction.MENU))
        assertEquals("nothing", run(TapAction.NONE))
    }

    @Test
    fun `明显竖滑不当点击`() {
        val action = classifyReleaseBeforeSlop(
            dx = 2f, dy = 30f, velocityX = 50f, slop = 10f, flickVelocity = 700f,
        )
        assertEquals(FlipReleaseBeforeSlop.Ignore, action)
    }

    @Test
    fun `后翻从左上起手：右下角且钉 Y，不卷斜角`() {
        // 0.6.1 反馈的录屏：1220×2656 的屏，落在 y=811（上三分之一）往回划。
        // 从前 Y 跟手，卷角被防撕裂钳制拽到右下、离手指一千多像素
        assertEquals(CurlGrip(cornerBottom = true, pinTouchY = true), curlGrip(FlipDirection.BACKWARD, 811f, 2656f))
        assertEquals(CurlGrip(cornerBottom = true, pinTouchY = true), curlGrip(FlipDirection.BACKWARD, 10f, 2656f))
    }

    @Test
    fun `后翻无论从哪一带起手都钉 Y，和点击、快甩往回翻同一个竖直圆柱`() {
        for (y in listOf(1300f, 2400f, 2650f)) {
            assertEquals(CurlGrip(cornerBottom = true, pinTouchY = true), curlGrip(FlipDirection.BACKWARD, y, 2656f))
        }
    }

    @Test
    fun `前翻上下三分之一揪角、Y 跟手，卷角挑近的那个`() {
        assertEquals(CurlGrip(cornerBottom = false, pinTouchY = false), curlGrip(FlipDirection.FORWARD, 300f, 2400f))
        assertEquals(CurlGrip(cornerBottom = true, pinTouchY = false), curlGrip(FlipDirection.FORWARD, 2100f, 2400f))
    }

    @Test
    fun `前翻中间那一带钉 Y，卷角按上下半屏挑`() {
        assertEquals(CurlGrip(cornerBottom = false, pinTouchY = true), curlGrip(FlipDirection.FORWARD, 1000f, 2400f))
        assertEquals(CurlGrip(cornerBottom = true, pinTouchY = true), curlGrip(FlipDirection.FORWARD, 1400f, 2400f))
        // 分界线本身归上下三分之一（开区间），和从前的写法一致
        assertEquals(false, curlGrip(FlipDirection.FORWARD, 800f, 2400f).pinTouchY)
        assertEquals(false, curlGrip(FlipDirection.FORWARD, 1600f, 2400f).pinTouchY)
    }
}
