package com.radium.inkwell.ui.rss

import com.radium.inkwell.data.db.entity.RssArticleRow
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 订阅页的排法：「全部」按时间、按天分段；只看一个源照源自己的顺序 */
class RssTimelineTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    private fun at(d: Int, h: Int): Long = ZonedDateTime.of(2026, 11, d, h, 0, 0, 0, zone).toInstant().toEpochMilli()

    private fun row(
        key: String,
        source: String = "a",
        pubTime: Long = 0,
        firstSeenAt: Long = 0,
        lastSeenAt: Long = 0,
        position: Int = 0,
    ) = RssArticleRow(
        feed = "$source\nfeed",
        articleKey = key,
        sourceId = source,
        title = key,
        link = key,
        image = null,
        pubDate = null,
        pubTime = pubTime,
        firstSeenAt = firstSeenAt,
        lastSeenAt = lastSeenAt,
        position = position,
    )

    private val names = mapOf("a" to "少数派", "b" to "爱范儿")

    @Test
    fun `全部：几个源排成一条，新的在上，按天分段`() {
        val sections = buildTimeline(
            listOf(
                row("a1", "a", pubTime = at(11, 9)),
                row("b1", "b", pubTime = at(11, 14)),
                row("a2", "a", pubTime = at(10, 21)),
            ),
            names,
            single = false,
            zone = zone,
        )
        assertEquals(listOf(LocalDate.of(2026, 11, 11), LocalDate.of(2026, 11, 10)), sections.map { it.day })
        assertEquals(listOf("b1", "a1"), sections[0].items.map { it.row.articleKey })
        assertEquals(listOf("爱范儿", "少数派"), sections[0].items.map { it.sourceName })
    }

    @Test
    fun `没有发布时间的按第一次取到的时刻排，不按最近一次`() {
        // 最近一次取到是刚才（每刷一次都更新）：按它排的话，没时间的文章每刷一次都蹿到最上面
        val sections = buildTimeline(
            listOf(
                row("old", "a", firstSeenAt = at(9, 8), lastSeenAt = at(11, 15)),
                row("new", "b", pubTime = at(11, 10), lastSeenAt = at(11, 15)),
            ),
            names,
            single = false,
            zone = zone,
        )
        assertEquals(listOf("new", "old"), sections.flatMap { it.items }.map { it.row.articleKey })
    }

    @Test
    fun `只看一个源：照源自己的顺序，撤下的排后面，不分天、不写来源`() {
        val sections = buildTimeline(
            listOf(
                row("gone", lastSeenAt = at(10, 8), position = 0, pubTime = at(11, 14)),
                row("second", lastSeenAt = at(11, 15), position = 1, pubTime = at(9, 8)),
                row("first", lastSeenAt = at(11, 15), position = 0, pubTime = at(8, 8)),
            ),
            names,
            single = true,
            zone = zone,
        )
        val only = sections.single()
        assertNull(only.day)
        assertEquals(listOf("first", "second", "gone"), only.items.map { it.row.articleKey })
        assertEquals(listOf(null, null, null), only.items.map { it.sourceName })
    }

    @Test
    fun `分段小标题`() {
        val today = LocalDate.of(2026, 11, 11)
        assertEquals("今天", sectionTitle(today, today))
        assertEquals("昨天", sectionTitle(today.minusDays(1), today))
        assertEquals("11月9日", sectionTitle(today.minusDays(2), today))
        assertEquals("2025年12月31日", sectionTitle(LocalDate.of(2025, 12, 31), today))
    }
}
