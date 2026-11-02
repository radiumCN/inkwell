package com.radium.inkwell.ui.stats

import com.radium.inkwell.data.db.entity.ReadingStatEntity
import com.radium.inkwell.data.repo.ReadingClock
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReadingStatsTest {

    // ---- 计时 ----

    private var t = 0L
    private val clock = ReadingClock(now = { t }, idleCapMs = 180_000)

    @Test
    fun `翻页之间的时间计入，start 之前的不算`() {
        t = 10_000
        clock.start()
        t += 40_000; clock.touch()
        t += 50_000; clock.touch()
        assertEquals(90_000, clock.drain())
        assertEquals(0, clock.drain())
    }

    @Test
    fun `一页上停太久只算上限`() {
        clock.start()
        t += 60 * 60_000; clock.touch()
        assertEquals(180_000, clock.drain())
    }

    @Test
    fun `进后台收尾；后台那段不算，回来重新开始`() {
        clock.start()
        t += 30_000; clock.stop()
        t += 10 * 60_000 // 在后台
        clock.start()
        t += 20_000; clock.touch()
        assertEquals(50_000, clock.drain())
    }

    @Test
    fun `没 start 就翻页只是开始计，不凭空加时长`() {
        t = 5_000
        clock.touch()
        assertEquals(0, clock.drain())
        t += 10_000; clock.touch()
        assertEquals(10_000, clock.drain())
    }

    @Test
    fun `连着 stop 两次不重复计`() {
        clock.start()
        t += 30_000
        clock.stop(); clock.stop()
        assertEquals(30_000, clock.drain())
    }

    // ---- 汇总 ----

    private val today = LocalDate.of(2026, 11, 4) // 周三
    private fun row(daysAgo: Long, millis: Long, book: String = "b", device: String = "d") =
        ReadingStatEntity(device, today.minusDays(daysAgo).toEpochDay(), book, millis, 0)

    @Test
    fun `各设备同一天的时长相加`() {
        val s = summarize(listOf(row(0, 60_000, device = "a"), row(0, 120_000, device = "b")), today)
        assertEquals(180_000, s.todayMillis)
    }

    @Test
    fun `本周从周一算，今年从一月一日算`() {
        val rows = listOf(
            row(0, 60_000), // 周三
            row(2, 60_000), // 周一
            row(3, 60_000), // 上周日：不在本周
            row(400, 60_000), // 去年
        )
        val s = summarize(rows, today)
        assertEquals(120_000, s.weekMillis)
        assertEquals(180_000, s.yearMillis)
        assertEquals(240_000, s.totalMillis)
    }

    @Test
    fun `今天还没读不算断，连续天数从昨天往回数`() {
        val rows = listOf(row(1, 120_000), row(2, 120_000), row(4, 120_000))
        assertEquals(2, summarize(rows, today).streakDays)
        assertEquals(3, summarize(rows + row(0, 120_000), today).streakDays)
    }

    @Test
    fun `不满一分钟的那天不算读过`() {
        val s = summarize(listOf(row(0, 30_000), row(1, 120_000)), today)
        assertEquals(1, s.streakDays)
        assertEquals(1, s.activeDays)
    }

    @Test
    fun `今年读得最多按时长降序`() {
        val rows = listOf(row(0, 120_000, "x"), row(1, 600_000, "y"), row(2, 300_000, "x"), row(400, 9_000_000, "z"))
        assertEquals(listOf("y" to 600_000L, "x" to 420_000L), summarize(rows, today).topBooks)
    }

    @Test
    fun `空表就是空`() {
        assertTrue(summarize(emptyList(), today).isEmpty)
    }

    @Test
    fun `时长的说法`() {
        assertEquals("不到 1 分钟", formatDuration(20_000))
        assertEquals("25 分钟", formatDuration(25 * 60_000L))
        assertEquals("2 小时", formatDuration(120 * 60_000L))
        assertEquals("1 小时 5 分", formatDuration(65 * 60_000L))
        assertEquals("120 小时", formatDuration(120 * 60 * 60_000L + 5 * 60_000L))
    }

    @Test
    fun `热力档按分钟定死`() {
        assertEquals(0, heatLevel(30_000))
        assertEquals(1, heatLevel(5 * 60_000L))
        assertEquals(3, heatLevel(45 * 60_000L))
        assertEquals(4, heatLevel(3 * 60 * 60_000L))
    }
}
