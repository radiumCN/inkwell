package com.radium.inkwell.data.repo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LocalSeriesTest {
    @Test fun `progress follows the volume when reordered`() {
        val first = LocalSeriesVolume("a", "卷一", 12, null)
        val second = LocalSeriesVolume("b", "卷二", 8, null)
        val third = LocalSeriesVolume("c", "卷三", 3, null)
        val current = seriesPosition(listOf(first, second, third), 14)!!

        assertEquals("b" to 2, current)
        assertEquals(2, seriesIndex(listOf(second, third, first), current))
        assertNull(seriesIndex(listOf(first, third), current))
    }
}
