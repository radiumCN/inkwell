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
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal const val MAX_LOCAL_SERIES_VOLUMES = 16

@Serializable
data class LocalSeriesVolume(val bookId: String, val title: String, val chapterCount: Int, val fileHash: String?)

@Serializable
internal data class VolumeCatalog(val titles: List<String>, val toc: List<TocEntry>) {
    constructor(handle: BookHandle) : this(handle.chapters.map { it.title }, handle.toc)
}

/** 每次保存卷序都使用新文件名，书行指向一次完整的修订。只有卷序需要云备份。 */
internal class LocalSeriesFiles(filesDir: File, cacheDir: File) {
    private val manifests = File(filesDir, "series")
    private val legacy = File(filesDir, "books")
    private val catalogs = File(cacheDir, "series-index")

    fun newManifest(): File {
        manifests.mkdirs()
        return File(manifests, "${UUID.randomUUID()}.series")
    }

    @Synchronized
    fun migrateLegacy() {
        for (file in legacy.listFiles { f -> f.extension == "series" && f.isFile }.orEmpty()) {
            manifests.mkdirs()
            // 同一私有文件系统内移动；若失败，仍可读旧文件，下次启动再试。
            file.renameTo(File(manifests, file.name))
        }
    }

    private fun filename(path: String?): String? = path?.let { File(it) }
        ?.takeIf { it.extension == "series" }?.name

    @Synchronized
    fun read(path: String?): List<LocalSeriesVolume> {
        val name = filename(path) ?: error("合订本目录文件缺失")
        val file = File(manifests, name).takeIf { it.isFile } ?: File(legacy, name)
        check(file.isFile) { "合订本目录文件缺失" }
        return Json.decodeFromString(file.readText())
    }

    fun readCatalog(path: String): List<VolumeCatalog>? {
        val file = File(catalogs, checkNotNull(filename(path)))
        return try {
            if (file.isFile) Json.decodeFromString<List<VolumeCatalog>>(file.readText()) else null
        } catch (_: IOException) {
            null
        } catch (_: kotlinx.serialization.SerializationException) {
            null
        }
    }

    @Synchronized
    fun cacheCatalog(path: String, catalog: List<VolumeCatalog>) {
        val name = checkNotNull(filename(path))
        if (!File(manifests, name).isFile && !File(legacy, name).isFile) return
        try {
            catalogs.mkdirs()
            val temp = File.createTempFile("index-", ".part", catalogs)
            try {
                temp.writeText(Json.encodeToString(catalog))
                Files.move(temp.toPath(), File(catalogs, name).toPath(), REPLACE_EXISTING, ATOMIC_MOVE)
            } finally {
                temp.delete()
            }
        } catch (_: IOException) {
            // 缓存可重建；空间不足时仍可使用内存中已生成的目录。
        }
    }

    @Synchronized
    fun delete(path: String?) {
        val name = filename(path) ?: return
        for (dir in listOf(manifests, legacy, catalogs)) deleteOwnedFile(File(dir, name).path, dir)
    }
}

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
    catalogs: List<VolumeCatalog>,
    private val openVolume: (Int) -> BookHandle,
) : BookHandle {
    private var activeIndex = -1
    private var active: BookHandle? = null
    private val access = ReentrantLock()
    @Volatile private var closed = false
    override val metadata: BookMetadata
        get() = withVolume(0) { BookMetadata(series.title, series.author, series.intro, cover = it.metadata.cover) }
    private val indexById = volumes.mapIndexed { index, volume -> volume.bookId to index }.toMap()

    override val chapters: List<ChapterRef> = buildList {
        catalogs.forEach { catalog -> catalog.titles.forEach { add(ChapterRef(size, it)) } }
    }

    override val toc: List<TocEntry> = buildList {
        var offset = 0
        volumes.zip(catalogs).forEach { (volume, catalog) ->
            add(TocEntry(volume.title, offset))
            catalog.toc.forEach { entry -> add(entry.copy(chapterIndex = offset + entry.chapterIndex, level = entry.level + 1)) }
            offset += volume.chapterCount
        }
    }

    private fun <T> withVolume(index: Int, read: (BookHandle) -> T): T {
        try {
            return access.withLock {
                check(!closed) { "合订本已关闭" }
                if (activeIndex != index || active == null) {
                    val previous = active
                    active = null
                    previous?.close()
                    active = openVolume(index)
                    activeIndex = index
                }
                read(checkNotNull(active))
            }
        } finally {
            if (closed) close()
        }
    }

    override fun loadChapter(index: Int): ChapterContent {
        val (bookId, chapterIndex) = checkNotNull(seriesPosition(volumes, index))
        val volume = indexById.getValue(bookId)
        val content = withVolume(volume) { it.loadChapter(chapterIndex) }
        return content.copy(elements = content.elements.map { element ->
            if (element is ContentElement.Image) element.copy(resourceId = "$volume:${element.resourceId}") else element
        })
    }

    override fun loadResource(resourceId: String): ImageBlob? {
        val volume = resourceId.substringBefore(':').toIntOrNull() ?: return null
        if (volume !in volumes.indices) return null
        return withVolume(volume) { it.loadResource(resourceId.substringAfter(':')) }
    }

    override fun close() {
        closed = true
        // 阅读页在主线程关闭；若 IO 线程还在解析分卷，由它在释放锁后收尾。
        if (!access.tryLock()) return
        try {
            val previous = active
            active = null
            previous?.close()
        } finally {
            access.unlock()
        }
    }
}
