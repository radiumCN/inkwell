package com.radium.inkwell.core.webdav

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * WebDAV 备份载荷：书架元数据 + 阅读进度 + 书源 + 净化规则 + 应用/阅读设置。
 * 不含书籍文件与正文缓存 —— 本地书的文件不上传，换设备要重新导入。
 *
 * 老版本的备份文件没有后加的字段，反序列化时按默认值补上（ignoreUnknownKeys + 默认值），
 * 所以新旧版本可以共用同一个 backup.json.gz，不需要迁移。
 */
@Serializable
data class BackupPayload(
    val version: Int = 2,
    val deviceId: String,
    val exportedAt: Long,
    val books: List<BackupBook> = emptyList(),
    val sources: List<BackupSource> = emptyList(),
    val replaceRules: List<BackupReplaceRule> = emptyList(),
    val rssSources: List<BackupRssSource> = emptyList(),
    /** 阅读排版设置；整块 LWW */
    val readerSettings: BackupSettings? = null,
    /** 应用设置（主题、更新渠道…）；整块 LWW */
    val appSettings: BackupSettings? = null,
)

/**
 * 一组设置的快照。
 *
 * 用 Map<String,String> 而不是强类型：BackupPayload 在 core，而阅读设置的类型定义在 reader 模块，
 * core 不该反过来依赖它。键与取值语义由 app 层负责，读到不认识的键直接忽略 —— 老版本
 * 也就能安全地读新版本写的备份。
 *
 * 整块 LWW（而非逐字段）：设置项之间常有关联（比如自定义配色的几个色值），
 * 逐字段合并会拼出一套谁都没设过的配色。阅读设置与应用设置各有自己的时间戳，互不牵连。
 */
@Serializable
data class BackupSettings(
    val updatedAt: Long = 0,
    val values: Map<String, String> = emptyMap(),
)

@Serializable
data class BackupRssSource(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val json: String,
    val sourceJson: String = "",
    val updatedAt: Long = 0,
)

@Serializable
data class BackupReplaceRule(
    val id: String,
    val name: String,
    val pattern: String,
    val replacement: String = "",
    val isRegex: Boolean = true,
    val scope: String = "",
    /** 只对这一本书生效；空 = 通用规则 */
    val bookId: String = "",
    val enabled: Boolean = true,
    val sortOrder: Int = 0,
    val updatedAt: Long = 0,
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
    /** 书架分组 */
    val groupName: String = "",
    /** 从书架隐藏（不是删除） */
    val hidden: Boolean = false,
)

@Serializable
data class BackupSource(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val json: String,
    val updatedAt: Long = 0,
    val sortOrder: Int = 0,
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
        val replaceRules: List<BackupReplaceRule>,
        val rssSources: List<BackupRssSource>,
        /** 合并后与本地不同的条目（需要写回本地库） */
        val changedBooks: List<BackupBook>,
        val changedSources: List<BackupSource>,
        val changedReplaceRules: List<BackupReplaceRule>,
        val changedRssSources: List<BackupRssSource>,
        /** 远端更新（需要写回本地）；null = 本地的更新或一样新 */
        val readerSettings: BackupSettings?,
        val appSettings: BackupSettings?,
        /** 合并后要上传的设置（取较新的那份） */
        val mergedReaderSettings: BackupSettings?,
        val mergedAppSettings: BackupSettings?,
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

        val localRules = local.replaceRules.associateBy { it.id }
        val remoteRules = remote.replaceRules.associateBy { it.id }
        val mergedRules = (localRules.keys + remoteRules.keys).map { id ->
            val l = localRules[id]
            val r = remoteRules[id]
            when {
                l == null -> r!!
                r == null -> l
                else -> if (r.updatedAt > l.updatedAt) r else l
            }
        }
        val changedRules = mergedRules.filter { it != localRules[it.id] }

        val localRss = local.rssSources.associateBy { it.id }
        val remoteRss = remote.rssSources.associateBy { it.id }
        val mergedRss = (localRss.keys + remoteRss.keys).map { id ->
            val l = localRss[id]
            val r = remoteRss[id]
            when {
                l == null -> r!!
                r == null -> l
                else -> if (r.updatedAt > l.updatedAt) r else l
            }
        }
        val changedRss = mergedRss.filter { it != localRss[it.id] }

        val readerWinner = newer(local.readerSettings, remote.readerSettings)
        val appWinner = newer(local.appSettings, remote.appSettings)

        return MergeResult(
            books = mergedBooks,
            sources = mergedSources,
            replaceRules = mergedRules,
            rssSources = mergedRss,
            changedBooks = changedBooks,
            changedSources = changedSources,
            changedReplaceRules = changedRules,
            changedRssSources = changedRss,
            // 只有远端确实更新时才回写本地；否则别拿一份等价的设置去覆盖用户当前的
            readerSettings = remote.readerSettings
                ?.takeIf { it === readerWinner && it != local.readerSettings },
            appSettings = remote.appSettings
                ?.takeIf { it === appWinner && it != local.appSettings },
            mergedReaderSettings = readerWinner,
            mergedAppSettings = appWinner,
        )
    }

    /** 整块 LWW；时间戳相同时本地优先（免得每次同步都来回覆盖） */
    private fun newer(local: BackupSettings?, remote: BackupSettings?): BackupSettings? = when {
        local == null -> remote
        remote == null -> local
        remote.updatedAt > local.updatedAt -> remote
        else -> local
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
