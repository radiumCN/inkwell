package com.radium.inkwell.ui.components

import java.time.Instant
import java.time.ZoneId

/**
 * 「3 小时前」「昨天」「9 月 1 日」这种说法：书架「继续阅读」的上次阅读、设置页的上次同步共用。
 * 跨天按日历算，不按 24 小时算：昨晚 11 点读的，今早就是「昨天」。
 */
fun formatRelativeTime(at: Long, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String {
    val minutes = (now - at).coerceAtLeast(0) / 60_000
    val day = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return when {
        minutes < 1 -> "刚刚"
        day == today && minutes < 60 -> "$minutes 分钟前"
        day == today -> "${minutes / 60} 小时前"
        day == today.minusDays(1) -> "昨天"
        day.isAfter(today.minusDays(7)) -> "${today.toEpochDay() - day.toEpochDay()} 天前"
        day.year == today.year -> "${day.monthValue} 月 ${day.dayOfMonth} 日"
        else -> "${day.year} 年 ${day.monthValue} 月"
    }
}
