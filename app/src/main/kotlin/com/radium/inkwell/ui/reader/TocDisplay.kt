package com.radium.inkwell.ui.reader

import com.radium.inkwell.core.util.VolumeChapterTitle
import com.radium.inkwell.data.prefs.BookTocRule

internal data class TocVolume(val firstChapter: Int, val title: VolumeChapterTitle)

internal data class TocDisplayRow(val item: TocItem, val title: String, val volumeHeading: String?)

/** 先在原目录上划分相邻卷，搜索隐藏中间条目时也能区分隔开的同名卷。 */
internal fun tocVolumes(titles: List<String>): List<TocVolume?> {
    var previous: String? = null
    var start = 0
    return titles.mapIndexed { index, title ->
        val parts = VolumeChapterTitle.parse(title)
        val volume = parts?.volume
        if (previous != volume) start = index
        previous = volume
        parts?.let { TocVolume(start, it) }
    }
}

internal fun displayToc(
    toc: List<TocItem>,
    volumes: List<TocVolume?>,
    rule: BookTocRule,
    reversed: Boolean = false,
    query: String = "",
): List<TocDisplayRow> = buildList {
    val indices = if (reversed) toc.indices.reversed() else toc.indices
    var previousVolume: Int? = null
    for (index in indices) {
        val item = toc[index]
        val volume = if (rule == BookTocRule.ORIGINAL) null else volumes.getOrNull(index)
        val title = volume?.title?.chapter ?: item.title
        if (query.isNotEmpty() && !item.title.contains(query, ignoreCase = true) && !title.contains(query, ignoreCase = true)) continue

        val heading = volume?.title?.volume.takeIf {
            rule == BookTocRule.GROUP_VOLUMES && volume?.firstChapter != previousVolume
        }
        add(TocDisplayRow(item, title, heading))
        previousVolume = volume?.firstChapter
    }
}

internal fun previewToc(
    toc: List<TocItem>,
    volumes: List<TocVolume?>,
    rule: BookTocRule,
    currentKey: Int?,
): List<TocDisplayRow> {
    val current = toc.indexOfFirst { it.key == currentKey }.coerceAtLeast(0)
    val match = if (rule == BookTocRule.ORIGINAL) -1 else {
        listOf(current, current - 1, current + 1).firstOrNull { volumes.getOrNull(it) != null }
            ?: volumes.indexOfFirst { it != null }
    }
    val from = if (match >= 0) match else current.coerceAtMost((toc.size - 2).coerceAtLeast(0))
    val to = (from + 2).coerceAtMost(toc.size)
    val sampleVolumes = if (volumes.isEmpty()) emptyList() else volumes.subList(from, to)
    return displayToc(toc.subList(from, to), sampleVolumes, rule)
}
