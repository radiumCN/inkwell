package com.radium.inkwell.ui.bookshelf

import com.radium.inkwell.data.db.entity.BookEntity
import com.radium.inkwell.data.db.entity.BookType
import com.radium.inkwell.ui.components.formatRelativeTime
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ShelfProgressTest {

    private fun book(chapter: Int = 0, total: Int = 100, readAt: Long = 1, openedAt: Long = 0) = BookEntity(
        id = "b",
        type = BookType.NET,
        title = "书",
        addedAt = 0,
        updatedAt = 0,
        readChapterIndex = chapter,
        totalChapters = total,
        readAt = readAt,
        openedAt = openedAt,
    )

    @Test
    fun `按章算进度，读到第一章也算读了一点`() {
        assertEquals(0.01f, book(chapter = 0).readingProgress())
        assertEquals(0.5f, book(chapter = 49).readingProgress())
        assertEquals(1f, book(chapter = 99).readingProgress())
    }

    @Test
    fun `没打开过或没有章数的书不画进度`() {
        assertNull(book(readAt = 0, openedAt = 0).readingProgress())
        assertNull(book(total = 0).readingProgress())
    }

    @Test
    fun `只打开过没翻动也算在读`() {
        assertEquals(0.01f, book(readAt = 0, openedAt = 5).readingProgress())
    }

    @Test
    fun `章号超出目录（换源后目录变短）时封顶在读完`() {
        assertEquals(1f, book(chapter = 150).readingProgress())
    }

    private val zone = ZoneId.of("Asia/Shanghai")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `上次阅读按日历天说，不按 24 小时`() {
        val now = at(2026, 11, 2, 9)
        assertEquals("刚刚", formatRelativeTime(now - 20_000, now, zone))
        assertEquals("15 分钟前", formatRelativeTime(at(2026, 11, 2, 8, 45), now, zone))
        assertEquals("3 小时前", formatRelativeTime(at(2026, 11, 2, 6), now, zone))
        // 昨晚 11 点到今早 9 点只隔 10 小时，但对人来说是「昨天」
        assertEquals("昨天", formatRelativeTime(at(2026, 11, 1, 23), now, zone))
        assertEquals("3 天前", formatRelativeTime(at(2026, 10, 30, 12), now, zone))
        assertEquals("9 月 1 日", formatRelativeTime(at(2026, 9, 1, 12), now, zone))
        assertEquals("2025 年 12 月", formatRelativeTime(at(2025, 12, 20, 12), now, zone))
    }
}
