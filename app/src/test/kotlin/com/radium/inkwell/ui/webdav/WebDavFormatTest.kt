package com.radium.inkwell.ui.webdav

import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class WebDavFormatTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    /** 一周内：相对时间 + 钟点；再早：直接写日期时间（相对时间那时已是日期，再括一个就重复了） */
    @Test
    fun `上次同步的说法`() {
        val now = at(2026, 11, 5, 14, 35)
        assertEquals("3 分钟前（14:32）", syncTimeLabel(at(2026, 11, 5, 14, 32), now, zone))
        assertEquals("刚刚（14:35）", syncTimeLabel(now, now, zone))
        assertEquals("昨天（21:05）", syncTimeLabel(at(2026, 11, 4, 21, 5), now, zone))
        assertEquals("6 天前（08:00）", syncTimeLabel(at(2026, 10, 30, 8, 0), now, zone))
        assertEquals("2026-10-29 08:00", syncTimeLabel(at(2026, 10, 29, 8, 0), now, zone))
    }

    @Test
    fun `应用码遮住中间，留头尾认得出是哪一个`() {
        assertEquals("wd_c7e1 •••••••••••• 58e9", maskSecret("wd_c7e1cf0498a4cf8ee9180f9a126458e9"))
        // 短的只留头尾各两个字
        assertEquals("ab •••••••••••• yz", maskSecret("abcdefghuvwxyz"))
        // 太短的整个遮住
        assertEquals("••••", maskSecret("abcd"))
    }
}
