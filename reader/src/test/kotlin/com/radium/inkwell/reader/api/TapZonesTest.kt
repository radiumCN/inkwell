package com.radium.inkwell.reader.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TapZonesTest {

    @Test
    fun `编码再解码原样回来`() {
        val z = TapZones.Preset.TOP_BOTTOM.zones.with(0, TapAction.NONE)
        assertEquals(z, TapZones.decode(z.encode()))
    }

    @Test
    fun `正中那格怎么改都还是菜单`() {
        assertEquals(TapAction.MENU, TapZones.DEFAULT.with(TapZones.CENTER, TapAction.NEXT).at(1, 1))
        val allNext = List(9) { TapAction.NEXT }
        assertEquals(TapAction.MENU, TapZones.of(allNext).at(1, 1))
        assertEquals(TapAction.MENU, TapZones.decode(allNext.joinToString(",") { it.name }).at(1, 1))
    }

    @Test
    fun `读不懂的值 decodeOrNull 给 null，备份导入据此保留本地`() {
        assertNull(TapZones.decodeOrNull(null))
        assertNull(TapZones.decodeOrNull("PREV,MENU,NEXT,PREV,MENU,NEXT,PREV,MENU,SPEAK"))
        val custom = TapZones.Preset.ALL_NEXT.zones
        assertEquals(custom, TapZones.decodeOrNull(custom.encode()))
    }

    @Test
    fun `残缺或不认识的存储值整体退回默认`() {
        assertEquals(TapZones.DEFAULT, TapZones.decode(null))
        assertEquals(TapZones.DEFAULT, TapZones.decode(""))
        assertEquals(TapZones.DEFAULT, TapZones.decode("PREV,MENU,NEXT"))
        assertEquals(
            TapZones.DEFAULT,
            TapZones.decode("PREV,MENU,NEXT,PREV,MENU,NEXT,PREV,MENU,SPEAK"),
        )
    }

    @Test
    fun `默认就是从前写死的左右分区`() {
        assertEquals(TapZones.Preset.LEFT_RIGHT.zones, TapZones.DEFAULT)
        for (row in 0..2) {
            assertEquals(TapAction.PREV, TapZones.DEFAULT.at(0, row))
            assertEquals(TapAction.MENU, TapZones.DEFAULT.at(1, row))
            assertEquals(TapAction.NEXT, TapZones.DEFAULT.at(2, row))
        }
    }
}
