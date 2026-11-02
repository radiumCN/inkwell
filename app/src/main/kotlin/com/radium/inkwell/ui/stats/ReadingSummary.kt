package com.radium.inkwell.ui.stats

import com.radium.inkwell.data.db.entity.ReadingStatEntity
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * 阅读记录页要的全部数字。各设备的行在这里加总（同步来的别的设备的行也在表里）。
 *
 * @property daily epochDay → 当天所有设备、所有书的总时长；热力图按它上色
 * @property topBooks 今年读得最多的书（bookId → 时长），时长降序
 */
data class ReadingSummary(
    val todayMillis: Long = 0,
    val weekMillis: Long = 0,
    val yearMillis: Long = 0,
    val totalMillis: Long = 0,
    val activeDays: Int = 0,
    val streakDays: Int = 0,
    val daily: Map<Long, Long> = emptyMap(),
    val topBooks: List<Pair<String, Long>> = emptyList(),
) {
    val isEmpty: Boolean get() = totalMillis <= 0
}

/** 一天读满这么久才算「读过」：打开书看一眼目录就退出的那种，不该续上连续天数 */
internal const val ACTIVE_DAY_MILLIS = 60_000L

/**
 * 「本周」是周一开始的自然周，「今年」是自然年 —— 跟人嘴里说的一致，而不是「最近 7 天 / 365 天」。
 *
 * 连续天数从今天往回数；今天还没读不算断：早上打开 App 看到「连续 0 天」很扫兴，
 * 这种时候从昨天往回数，今天读了再加上。
 */
fun summarize(rows: List<ReadingStatEntity>, today: LocalDate, topN: Int = 5): ReadingSummary {
    if (rows.isEmpty()) return ReadingSummary()
    val daily = HashMap<Long, Long>()
    val yearBooks = HashMap<String, Long>()
    val todayDay = today.toEpochDay()
    val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toEpochDay()
    val yearStart = today.withDayOfYear(1).toEpochDay()
    var total = 0L
    rows.forEach { r ->
        daily.merge(r.day, r.millis, Long::plus)
        total += r.millis
        if (r.day in yearStart..todayDay) yearBooks.merge(r.bookId, r.millis, Long::plus)
    }

    fun active(day: Long) = (daily[day] ?: 0L) >= ACTIVE_DAY_MILLIS
    var cursor = if (active(todayDay)) todayDay else todayDay - 1
    var streak = 0
    while (active(cursor)) {
        streak++
        cursor--
    }

    return ReadingSummary(
        todayMillis = daily[todayDay] ?: 0L,
        weekMillis = daily.filterKeys { it in weekStart..todayDay }.values.sum(),
        yearMillis = daily.filterKeys { it in yearStart..todayDay }.values.sum(),
        totalMillis = total,
        activeDays = daily.count { it.value >= ACTIVE_DAY_MILLIS },
        streakDays = streak,
        daily = daily,
        topBooks = yearBooks.entries
            .filter { it.value >= ACTIVE_DAY_MILLIS }
            .sortedByDescending { it.value }
            .take(topN)
            .map { it.key to it.value },
    )
}

/** 时长的说法：不满一分钟说「不到 1 分钟」而不是「0 分钟」—— 读过就是读过 */
fun formatDuration(millis: Long): String {
    val minutes = millis / 60_000
    val hours = minutes / 60
    return when {
        millis <= 0 -> "0 分钟"
        minutes < 1 -> "不到 1 分钟"
        hours < 1 -> "$minutes 分钟"
        // 一百小时往上，分钟已经没有意义
        hours >= 100 -> "$hours 小时"
        minutes % 60 == 0L -> "$hours 小时"
        else -> "$hours 小时 ${minutes % 60} 分"
    }
}

/**
 * 热力图的深浅档：0 = 没读，1~4 越读越久。按分钟给死档而不是按自己的最大值归一化 ——
 * 归一化的话，一周只读了一天十分钟，那一格也是最深色，看着像读了很多。
 */
fun heatLevel(millis: Long): Int {
    val minutes = millis / 60_000
    return when {
        millis < ACTIVE_DAY_MILLIS -> 0
        minutes < 15 -> 1
        minutes < 30 -> 2
        minutes < 60 -> 3
        else -> 4
    }
}
