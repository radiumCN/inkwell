package com.radium.inkwell.core.webdav

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** WebDAV 备份载荷：书架元数据 + 进度 + 书源。不含书籍文件与正文缓存。 */
@Serializable
data class BackupPayload(
    val version: Int = 1,
    val deviceId: String,
    val exportedAt: Long,
    val books: List<BackupBook> = emptyList(),
    val sources: List<BackupSource> = emptyList(),
)

@Serializable
data class BackupBook(
    val id: String,
    val type: Int,
    val title: String,
    val author: String = "",
    val intro: String? = null,
    val sourceId: String? = null,
    val bookUrl: String? = null,
    val tocUrl: String? = null,
    val totalChapters: Int = 0,
    val readChapterIndex: Int = 0,
    val readCharOffset: Int = 0,
    val readAt: Long = 0,
    val addedAt: Long = 0,
    val updatedAt: Long = 0,
)

@Serializable
data class BackupSource(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val json: String,
    val updatedAt: Long = 0,
)

object BackupCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(payload: BackupPayload): ByteArray {
        val raw = json.encodeToString(BackupPayload.serializer(), payload).toByteArray()
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(raw) }
        return out.toByteArray()
    }

    fun decode(bytes: ByteArray): BackupPayload {
        val raw = GZIPInputStream(bytes.inputStream()).use { it.readBytes() }
        return json.decodeFromString(BackupPayload.serializer(), String(raw, Charsets.UTF_8))
    }
}

/**
 * 字段级 Last-Write-Wins 合并：
 * 同一本书进度取 readAt 较大者、元数据取 updatedAt 较大者；书源按 updatedAt。
 * 不做删除墓碑：远端有本地无 → 视为新增。
 */
object BackupMerger {

    data class MergeResult(
        val books: List<BackupBook>,
        val sources: List<BackupSource>,
        /** 合并后与本地不同的书（需要写回本地库） */
        val changedBooks: List<BackupBook>,
        val changedSources: List<BackupSource>,
    )

    fun merge(local: BackupPayload, remote: BackupPayload): MergeResult {
        val localBooks = local.books.associateBy { it.id }
        val remoteBooks = remote.books.associateBy { it.id }
        val mergedBooks = (localBooks.keys + remoteBooks.keys).map { id ->
            val l = localBooks[id]
            val r = remoteBooks[id]
            when {
                l == null -> r!!
                r == null -> l
                else -> mergeBook(l, r)
            }
        }
        val changedBooks = mergedBooks.filter { it != localBooks[it.id] }

        val localSources = local.sources.associateBy { it.id }
        val remoteSources = remote.sources.associateBy { it.id }
        val mergedSources = (localSources.keys + remoteSources.keys).map { id ->
            val l = localSources[id]
            val r = remoteSources[id]
            when {
                l == null -> r!!
                r == null -> l
                else -> if (r.updatedAt > l.updatedAt) r else l
            }
        }
        val changedSources = mergedSources.filter { it != localSources[it.id] }

        return MergeResult(mergedBooks, mergedSources, changedBooks, changedSources)
    }

    private fun mergeBook(l: BackupBook, r: BackupBook): BackupBook {
        val meta = if (r.updatedAt > l.updatedAt) r else l
        val progress = if (r.readAt > l.readAt) r else l
        return meta.copy(
            readChapterIndex = progress.readChapterIndex,
            readCharOffset = progress.readCharOffset,
            readAt = progress.readAt,
        )
    }
}
