package com.radium.inkwell.ui.detail

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoverSeedTest {

    private fun argb(r: Int, g: Int, b: Int, a: Int = 0xFF) = (a shl 24) or (r shl 16) or (g shl 8) or b

    private fun red(c: Int) = (c shr 16) and 0xFF
    private fun green(c: Int) = (c shr 8) and 0xFF
    private fun blue(c: Int) = c and 0xFF

    @Test
    fun `灰封面没有主色`() {
        val gray = IntArray(100) { i -> argb(i + 50, i + 50, i + 50) }
        assertNull(pickCoverSeed(gray))
    }

    @Test
    fun `灰底上一小撮红字撑不起主色`() {
        val pixels = IntArray(1000) { argb(200, 200, 200) }
        repeat(10) { pixels[it] = argb(220, 30, 30) }
        assertNull(pickCoverSeed(pixels))
    }

    @Test
    fun `取面积大又鲜明的那种颜色，而不是平均出来的泥色`() {
        // 六成蓝、四成橙：平均色是一种灰褐，挑出来的应当是蓝
        val pixels = IntArray(100) { i -> if (i < 60) argb(30, 80, 200) else argb(230, 140, 30) }
        val seed = assertNotNull(pickCoverSeed(pixels))
        assertTrue(blue(seed) > red(seed) && blue(seed) > green(seed), "应偏蓝：${seed.toUInt().toString(16)}")
    }

    @Test
    fun `透明像素不投票`() {
        val pixels = IntArray(100) { i -> if (i < 80) argb(230, 30, 30, a = 0) else argb(30, 160, 60) }
        val seed = assertNotNull(pickCoverSeed(pixels))
        assertTrue(green(seed) > red(seed))
    }

    @Test
    fun `空图不崩`() {
        assertEquals(null, pickCoverSeed(IntArray(0)))
    }
}
