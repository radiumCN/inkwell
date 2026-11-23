package com.radium.inkwell.reader.flip

import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Test

class CurlShadowBoundsTest {
    private val width = 1268f
    private val height = 2756f
    private val diagonal = hypot(width, height)

    @Test
    fun `右上斜卷收尾时阴影延伸到下边缘`() {
        assertEquals(3365, curlShadowBottom(width, height, -677f, 0f, -31f, diagonal))
    }

    @Test
    fun `右下斜卷收尾时阴影延伸到上边缘`() {
        assertEquals(6003, curlShadowBottom(width, height, -523f, height, -156f, diagonal))
    }

    @Test
    fun `竖直卷曲时仍使用原来的阴影边界`() {
        assertEquals((height + diagonal).toInt(), curlShadowBottom(width, height, 500f, height, -180f, diagonal))
    }
}
