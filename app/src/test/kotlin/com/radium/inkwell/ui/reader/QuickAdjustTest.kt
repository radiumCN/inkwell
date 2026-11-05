package com.radium.inkwell.ui.reader

import com.radium.inkwell.reader.api.ReaderSettings
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 不开菜单直接调（QuickAdjust.kt）的两段换算：双指缩放后的字号、右边缘竖滑后的亮度。
 * 手势本身要真机试，这里钉住「手指走多远对应调多少」不被改歪。
 */
class QuickAdjustTest {

    @Test
    fun `pinch scales font size and rounds to whole sp`() {
        assertEquals(18, pinchFontSize(18f, 1f))
        assertEquals(27, pinchFontSize(18f, 1.5f))
        // 18 × 1.03 = 18.54：两指稍一张开就进一档，不用张很大才有反应
        assertEquals(19, pinchFontSize(18f, 1.03f))
        assertEquals(16, pinchFontSize(18f, 0.9f))
    }

    @Test
    fun `pinch is clamped to the font size range`() {
        assertEquals(ReaderSettings.FONT_SIZE_MAX.toInt(), pinchFontSize(18f, 3f))
        assertEquals(ReaderSettings.FONT_SIZE_MIN.toInt(), pinchFontSize(18f, 0.1f))
    }

    @Test
    fun `edge swipe up brightens and down dims`() {
        val h = 1000f
        // 0.6 屏高走完整个范围：往上 300px = +0.5
        assertEquals(1f, edgeBrightness(0.5f, -300f, h), 1e-4f)
        assertEquals(0.75f, edgeBrightness(0.5f, -150f, h), 1e-4f)
        assertEquals(0.25f, edgeBrightness(0.5f, 150f, h), 1e-4f)
        assertEquals(0.5f, edgeBrightness(0.5f, 0f, h), 1e-4f)
    }

    @Test
    fun `edge brightness never reaches zero and never exceeds one`() {
        assertEquals(MIN_BRIGHTNESS, edgeBrightness(0.5f, 2000f, 1000f), 1e-6f)
        assertEquals(1f, edgeBrightness(0.9f, -2000f, 1000f), 1e-6f)
    }

    @Test
    fun `edge brightness ignores an unmeasured page`() {
        assertEquals(0.4f, edgeBrightness(0.4f, -300f, 0f), 1e-6f)
    }
}
