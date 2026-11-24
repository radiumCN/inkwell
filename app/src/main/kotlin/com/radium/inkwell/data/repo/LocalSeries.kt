package com.radium.inkwell.data.repo

import com.radium.inkwell.core.model.BookHandle
import com.radium.inkwell.core.model.BookMetadata
import com.radium.inkwell.core.model.ChapterContent
import com.radium.inkwell.core.model.ChapterRef
import com.radium.inkwell.core.model.ContentElement
import com.radium.inkwell.core.model.ImageBlob
import com.radium.inkwell.core.model.TocEntry
import com.radium.inkwell.data.db.entity.BookEntity
import com.radium.inkwell.data.db.entity.BookType
import kotlinx.serialization.Serializable

internal const val MAX_LOCAL_SERIES_VOLUMES = 16

@Serializable
data class LocalSeriesVolume(val bookId: String, val title: String, val chapterCount: Int, val fileHash: String?)

internal fun BookEntity.isLocalOriginal(): Boolean =
    inShelf && !deleted && type in BookType.LOCAL_TXT..BookType.LOCAL_MOBI

internal fun seriesPosition(volumes: List<LocalSeriesVolume>, index: Int): Pair<String, Int>? {
    var remaining = index
    for (volume in volumes) {
        if (remaining in 0 until volume.chapterCount) return volume.bookId to remaining
        remaining -= volume.chapterCount
    }
    return null
}

internal fun seriesIndex(volumes: List<LocalSeriesVolume>, position: Pair<String, Int>): Int? {
    var offset = 0
    for (volume in volumes) {
        if (volume.bookId == position.first && position.second in 0 until volume.chapterCount) return offset + position.second
        offset += volume.chapterCount
    }
    return null
}

internal class LocalSeriesHandle(
    private val series: BookEntity,
    private val volumes: List<LocalSeriesVolume>,
    private val handles: List<BookHandle>,
) : BookHandle {
    override val metadata = BookMetadata(series.title, series.author, series.intro, cover = handles.first().metadata.cover)
    private val indexById = volumes.mapIndexed { index, volume -> volume.bookId to index }.toMap()

    override val chapters: List<ChapterRef> = buildList {
        handles.forEach { handle -> handle.chapters.forEach { add(ChapterRef(size, it.title)) } }
    }

    override val toc: List<TocEntry> = buildList {
        var offset = 0
        volumes.zip(handles).forEach { (volume, handle) ->
            add(TocEntry(volume.title, offset))
            handle.toc.forEach { entry -> add(entry.copy(chapterIndex = offset + entry.chapterIndex, level = entry.level + 1)) }
            offset += handle.chapters.size
        }
    }

    override fun loadChapter(index: Int): ChapterContent {
        val (bookId, chapterIndex) = checkNotNull(seriesPosition(volumes, index))
        val volume = indexById.getValue(bookId)
        val content = handles[volume].loadChapter(chapterIndex)
        return content.copy(elements = content.elements.map { element ->
            if (element is ContentElement.Image) element.copy(resourceId = "$volume:${element.resourceId}") else element
        })
    }

    override fun loadResource(resourceId: String): ImageBlob? {
        val volume = resourceId.substringBefore(':').toIntOrNull() ?: return null
        return handles.getOrNull(volume)?.loadResource(resourceId.substringAfter(':'))
    }

    override fun close() {
        handles.forEach { runCatching { it.close() } }
    }
}
