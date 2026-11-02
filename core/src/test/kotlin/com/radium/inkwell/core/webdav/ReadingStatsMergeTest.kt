package com.radium.inkwell.core.webdav

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReadingStatsMergeTest {

    private fun stat(device: String, day: Long, book: String, millis: Long) =
        BackupReadingStat(deviceId = device, day = day, bookId = book, millis = millis)

    private fun payload(vararg stats: BackupReadingStat) =
        BackupPayload(deviceId = "test", exportedAt = 0, readingStats = stats.toList())

    private fun total(rows: List<BackupReadingStat>) = rows.sumOf { it.millis }

    @Test
    fun `两台设备同一天读同一本书，时长相加而不是互相覆盖`() {
        val phone = payload(stat("phone", 100, "b", 30_000))
        val pad = payload(stat("pad", 100, "b", 20_000))
        val merged = BackupMerger.merge(phone, pad)
        assertEquals(50_000, total(merged.readingStats))
        assertEquals(listOf(stat("pad", 100, "b", 20_000)), merged.changedReadingStats)
    }

    @Test
    fun `同一台设备的同一行取较大值（较新的那次）`() {
        val local = payload(stat("phone", 100, "b", 30_000))
        val remote = payload(stat("phone", 100, "b", 10_000))
        val merged = BackupMerger.merge(local, remote)
        assertEquals(30_000, total(merged.readingStats))
        assertTrue(merged.changedReadingStats.isEmpty())
    }

    @Test
    fun `本机清过数据：远端存着本机从前记的更大值，合并后补回本地`() {
        val local = payload(stat("phone", 100, "b", 1_000))
        val remote = payload(stat("phone", 100, "b", 60_000), stat("phone", 99, "b", 5_000))
        val merged = BackupMerger.merge(local, remote)
        assertEquals(65_000, total(merged.readingStats))
        assertEquals(2, merged.changedReadingStats.size)
    }

    @Test
    fun `合并顺序与次数都不影响结果`() {
        val a = listOf(stat("a", 1, "x", 5), stat("b", 1, "x", 7))
        val b = listOf(stat("a", 1, "x", 9), stat("c", 2, "y", 3))
        val ab = BackupMerger.mergeReadingStats(a, b).toSet()
        val ba = BackupMerger.mergeReadingStats(b, a).toSet()
        assertEquals(ab, ba)
        assertEquals(ab, BackupMerger.mergeReadingStats(ab.toList(), b).toSet())
    }

    @Test
    fun `老备份没有这个字段，按空列表读`() {
        val decoded = BackupCodec.decode(BackupCodec.encode(BackupPayload(deviceId = "d", exportedAt = 0)))
        assertTrue(decoded.readingStats.isEmpty())
    }
}
