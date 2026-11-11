package com.radium.inkwell.core.source.rss

import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 订阅页按发布时间把各个源的文章排成一条，排错了就是「新文章沉在下面」 */
class RssDatesTest {

    private val shanghai = ZoneId.of("Asia/Shanghai")

    /** 「现在」= 2026-11-11 15:00 北京时间 */
    private val now = at(2026, 11, 11, 15, 0)

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int = 0, zone: ZoneId = shanghai): Long =
        ZonedDateTime.of(y, mo, d, h, mi, s, 0, zone).toInstant().toEpochMilli()

    private fun parse(text: String?) = RssDates.parse(text, now, shanghai)

    @Test
    fun `RSS 的 RFC 822`() {
        val utc = ZoneId.of("UTC")
        assertEquals(at(2026, 11, 11, 0, 26, 39, utc), parse("Wed, 11 Nov 2026 00:26:39 +0000"))
        assertEquals(at(2026, 11, 11, 0, 26, 39, utc), parse("Wed, 11 Nov 2026 00:26:39 GMT"))
        assertEquals(at(2026, 11, 11, 8, 26, 39), parse("Wed, 11 Nov 2026 08:26:39 +08:00"))
        // 星期写错、没有星期、没有秒、两位年份都见过
        assertEquals(at(2026, 11, 11, 0, 26, 39, utc), parse("Mon, 11 Nov 2026 00:26:39 +0000"))
        assertEquals(at(2026, 11, 1, 8, 26), parse("1 Nov 26 08:26 +0800"))
    }

    @Test
    fun `国内站点的 CST 是北京时间`() {
        assertEquals(at(2026, 11, 11, 8, 0), parse("Wed, 11 Nov 2026 08:00:00 CST"))
    }

    @Test
    fun `Atom 的 ISO 8601`() {
        val utc = ZoneId.of("UTC")
        assertEquals(at(2026, 11, 11, 0, 26, 39, utc), parse("2026-11-11T00:26:39Z"))
        assertEquals(at(2026, 11, 11, 8, 26, 39) + 123, parse("2026-11-11T08:26:39.123+08:00"))
        assertEquals(at(2026, 11, 11, 8, 26, 39), parse("2026-11-11 08:26:39 +0800"))
    }

    @Test
    fun `不带时区的按手机时区算`() {
        assertEquals(at(2026, 11, 11, 8, 26), parse("2026-11-11 08:26"))
        assertEquals(at(2026, 11, 11, 8, 26, 39), parse("2026-11-11T08:26:39"))
        assertEquals(at(2026, 11, 10, 0, 0), parse("2026/11/10"))
        assertEquals(at(2026, 11, 9, 21, 5), parse("2026年11月9日 21:05"))
    }

    @Test
    fun `没写年份的取最近的那一天`() {
        assertEquals(at(2026, 11, 10, 8, 26), parse("11-10 08:26"))
        // 12 月 31 日今年还没到：是去年的
        assertEquals(at(2025, 12, 31, 23, 0), parse("12-31 23:00"))
    }

    @Test
    fun `相对时间`() {
        assertEquals(now, parse("刚刚"))
        assertEquals(now - 15 * 60_000L, parse("15分钟前"))
        assertEquals(now - 3 * 3_600_000L, parse("3 小时前"))
        assertEquals(now - 2 * 86_400_000L, parse("2天前"))
        assertEquals(at(2026, 11, 10, 8, 26), parse("昨天 08:26"))
        assertEquals(at(2026, 11, 11, 0, 0), parse("今天"))
    }

    @Test
    fun `认不出的返回 null`() {
        assertNull(parse(null))
        assertNull(parse(""))
        assertNull(parse("第 3 期"))
        assertNull(parse("2026-13-45"))
        assertNull(parse("November 11, 2026"))
    }
}
