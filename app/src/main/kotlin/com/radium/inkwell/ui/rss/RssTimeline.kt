package com.radium.inkwell.ui.rss

import com.radium.inkwell.data.db.entity.RssArticleEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 时间线上的一篇 */
data class TimelineItem(
    val row: RssArticleEntity,
    /** 来源名；只看一个源时为 null（整页都是它，不用每行再写一遍） */
    val sourceName: String?,
) {
    /** LazyColumn 的 key：库的主键，「源 + 分类 + 文章」一定不重 */
    val key: String get() = row.feed + "\u0000" + row.articleKey
}

/** 按天分的一段；只看一个源时只有一段，[day] 为 null（不分天，照源自己的顺序） */
data class TimelineSection(val day: LocalDate?, val items: List<TimelineItem>)

/**
 * 缓存的文章 → 时间线。
 *
 * **「全部」**（[single] = false）：所有源排成一条，新的在上，按天分段。有发布时间按发布时间，没有（或认不出）的
 * 按第一次取到的时刻 —— 不用最近一次取到的：那样每刷一次，没时间的文章就全部「变新」、蹿到最上面。
 * 同一刻的按源里的位置。
 *
 * **只看一个源**：照源自己的顺序（最近一次取到的那批按位置），之前取到、这次没再出现的排在后面。
 * 排行榜、书单这类源，顺序本身就是内容，不能按时间重排。
 */
internal fun buildTimeline(
    rows: List<RssArticleEntity>,
    sourceNames: Map<String, String>,
    single: Boolean,
    zone: ZoneId = ZoneId.systemDefault(),
): List<TimelineSection> {
    if (rows.isEmpty()) return emptyList()
    if (single) {
        val ordered = rows.sortedWith(compareByDescending<RssArticleEntity> { it.lastSeenAt }.thenBy { it.position })
        return listOf(TimelineSection(null, ordered.map { TimelineItem(it, null) }))
    }
    val ordered = rows.sortedWith(
        compareByDescending<RssArticleEntity> { it.sortTime }.thenBy { it.position }.thenBy { it.sourceId },
    )
    return ordered
        .groupBy { Instant.ofEpochMilli(it.sortTime).atZone(zone).toLocalDate() }
        .map { (day, items) -> TimelineSection(day, items.map { TimelineItem(it, sourceNames[it.sourceId]) }) }
}

/** 「全部」里排先后用的时刻：发布时间，没有就第一次取到的时刻 */
internal val RssArticleEntity.sortTime: Long get() = if (pubTime > 0) pubTime else firstSeenAt

/** 分段小标题：今天 / 昨天 / 11月9日 / 2025年12月31日（不是今年才写年份） */
internal fun sectionTitle(day: LocalDate, today: LocalDate): String = when {
    day == today -> "今天"
    day == today.minusDays(1) -> "昨天"
    day.year == today.year -> "${day.monthValue}月${day.dayOfMonth}日"
    else -> "${day.year}年${day.monthValue}月${day.dayOfMonth}日"
}
