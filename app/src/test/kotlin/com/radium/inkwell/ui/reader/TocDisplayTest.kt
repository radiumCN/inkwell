package com.radium.inkwell.ui.reader

import com.radium.inkwell.data.prefs.BookTocRule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class TocDisplayTest {
    @Test fun `grouped views keep chapter identity and reverse both levels`() {
        val toc = listOf(
            TocItem(0, "第一卷 七玄门风云 第一章 山边小村", cached = true),
            TocItem(1, "第一卷 七玄门风云 第二章 青牛镇", anchor = "town", key = 7),
            TocItem(2, "第２卷：初入江湖 · 第3回 炼骨崖"),
        )
        val volumes = tocVolumes(toc.map(TocItem::title))
        val grouped = displayToc(toc, volumes, BookTocRule.GROUP_VOLUMES, reversed = true)

        assertEquals(listOf("第3回 炼骨崖", "第二章 青牛镇", "第一章 山边小村"), grouped.map { it.title })
        assertEquals(listOf("第２卷：初入江湖", "第一卷 七玄门风云", null), grouped.map { it.volumeHeading })
        assertSame(toc[1], grouped[1].item)
        assertSame(toc[0], grouped[2].item)
    }

    @Test fun `search includes original title and keeps separate runs of the same volume`() {
        val toc = tocOf("第一卷 北城 第1章 茶馆", "后记", "第一卷 北城 第2章 渡口", "第二卷 南城 第3章 街巷")
        val volumes = tocVolumes(toc.map(TocItem::title))
        val grouped = displayToc(toc, volumes, BookTocRule.GROUP_VOLUMES, query = "北城")

        assertEquals(listOf("第一卷 北城", "第一卷 北城"), grouped.map { it.volumeHeading })
        val hidden = displayToc(toc, volumes, BookTocRule.HIDE_VOLUME, query = "北城")
        assertEquals(listOf("第1章 茶馆", "第2章 渡口"), hidden.map { it.title })
        assertEquals(listOf(null, null), hidden.map { it.volumeHeading })
        assertEquals(listOf("第2章 渡口"), displayToc(toc, volumes, BookTocRule.HIDE_VOLUME, query = "渡口").map { it.title })
    }

    @Test fun `ambiguous titles and structured editions keep original text`() {
        val toc = tocOf("楔子", "第一卷 番外", "第一卷 卷名 第一章 原文提到 第二章", "第一卷 章名第一章 看不清分界")
        val volumes = tocVolumes(toc.map(TocItem::title))
        val grouped = displayToc(toc, volumes, BookTocRule.GROUP_VOLUMES)
        assertEquals(toc.map { it.title }, grouped.map { it.title })
        grouped.forEach { assertNull(it.volumeHeading) }

        val nested = TocItem(0, "第一卷第一章 渡船靠岸", anchor = "begin", depth = 1)
        val unchanged = displayToc(listOf(nested), emptyList(), BookTocRule.GROUP_VOLUMES).single()
        assertSame(nested, unchanged.item)
        assertEquals(nested.title, unchanged.title)
        assertNull(unchanged.volumeHeading)
    }

    @Test fun `preview shows a match near the reading position or the earliest match`() {
        val toc = tocOf("第一卷第一章 茶馆", "章名二", "章名三", "第四章 街巷", "第二卷 第五章 渡口")
        val volumes = tocVolumes(toc.map(TocItem::title))

        assertEquals("第五章 渡口", previewToc(toc, volumes, BookTocRule.GROUP_VOLUMES, currentKey = 3).first().title)
        assertEquals("第一章 茶馆", previewToc(toc, volumes, BookTocRule.HIDE_VOLUME, currentKey = 2).first().title)
        assertEquals(toc[2].title, previewToc(toc, volumes, BookTocRule.ORIGINAL, currentKey = 2).first().title)
    }

    private fun tocOf(vararg titles: String) = titles.mapIndexed { index, title -> TocItem(index, title) }
}
