package com.radium.inkwell.data.repo

import com.radium.inkwell.core.webdav.BackupBook
import com.radium.inkwell.core.webdav.BackupCodec
import com.radium.inkwell.core.webdav.BackupMerger
import com.radium.inkwell.core.webdav.BackupPayload
import com.radium.inkwell.core.webdav.BackupReplaceRule
import com.radium.inkwell.core.webdav.BackupRssSource
import com.radium.inkwell.core.webdav.BackupSource
import com.radium.inkwell.core.webdav.WebDavClient
import com.radium.inkwell.data.db.dao.BookDao
import com.radium.inkwell.data.db.dao.BookSourceDao
import com.radium.inkwell.data.db.entity.BookEntity
import com.radium.inkwell.data.db.entity.BookSourceEntity
import com.radium.inkwell.data.db.entity.BookType
import com.radium.inkwell.data.db.entity.ReplaceRuleEntity
import com.radium.inkwell.data.prefs.AppPrefs
import com.radium.inkwell.data.prefs.ReaderPrefs
import com.radium.inkwell.data.prefs.exportForBackup
import com.radium.inkwell.data.prefs.importFromBackup
import com.radium.inkwell.data.prefs.WebDavPrefs
import kotlinx.coroutines.flow.first

class WebDavRepository(
    private val bookDao: BookDao,
    private val sourceDao: BookSourceDao,
    private val rssDao: com.radium.inkwell.data.db.dao.RssSourceDao,
    private val prefs: WebDavPrefs,
    private val readerPrefs: ReaderPrefs,
    private val appPrefs: AppPrefs,
    private val replaceRules: ReplaceRuleRepository,
) {
    private companion object {
        const val DIR = "inkwell"
        const val BACKUP = "inkwell/backup.json.gz"
    }

    suspend fun testConnection(url: String, username: String, password: String): Result<Unit> =
        WebDavClient(url, username, password).check()

    /** 双向同步：GET 远端 → 字段级 LWW 合并 → 写回本地 → PUT 合并结果 */
    suspend fun sync(): Result<String> = runCatching {
        val config = prefs.config.first()
        check(config.isConfigured) { "尚未配置 WebDAV" }
        val client = WebDavClient(config.url, config.username, config.password)

        // 先建目录再读：首次同步时 inkwell/ 还不存在，直接 GET 备份文件，
        // 坚果云对「父目录不存在」回的是 409 而不是 404 —— 同步就一次都成不了。
        // MKCOL 是幂等的（已存在返回 405，客户端当成功）。
        client.mkcol(DIR)

        val local = buildLocalPayload()
        val remote = client.get(BACKUP)?.let { BackupCodec.decode(it) }

        val toUpload: BackupPayload
        var applied = 0
        if (remote == null) {
            toUpload = local
        } else {
            val merged = BackupMerger.merge(local, remote)
            applyToLocal(merged)
            applied = merged.changedBooks.size +
                merged.changedSources.size +
                merged.changedReplaceRules.size +
                merged.changedRssSources.size +
                listOfNotNull(merged.readerSettings, merged.appSettings).size
            toUpload = local.copy(
                books = merged.books,
                sources = merged.sources,
                replaceRules = merged.replaceRules,
                rssSources = merged.rssSources,
                readerSettings = merged.mergedReaderSettings,
                appSettings = merged.mergedAppSettings,
            )
        }

        client.put(BACKUP, BackupCodec.encode(toUpload), contentType = "application/gzip")
        prefs.markSynced(System.currentTimeMillis())
        if (applied > 0) "同步完成，合并了 $applied 项远端更新" else "同步完成"
    }

    private suspend fun buildLocalPayload(): BackupPayload = BackupPayload(
        deviceId = prefs.deviceId(),
        exportedAt = System.currentTimeMillis(),
        books = bookDao.getAll().map { b ->
            BackupBook(
                id = b.id, type = b.type, title = b.title, author = b.author,
                intro = b.intro, sourceId = b.sourceId, bookUrl = b.bookUrl, tocUrl = b.tocUrl,
                totalChapters = b.totalChapters,
                readChapterIndex = b.readChapterIndex, readCharOffset = b.readCharOffset,
                readAt = b.readAt, addedAt = b.addedAt, updatedAt = b.updatedAt,
                groupName = b.groupName,
            )
        },
        sources = sourceDao.getAll().map { s ->
            BackupSource(
                id = s.id, name = s.name, enabled = s.enabled, json = s.json,
                updatedAt = s.updatedAt,
                // 带上 legado 原文与转换器版本：换设备后书源才能被新版转换器重转，
                // 否则同步下来的书源 sourceJson 是空的，只能手动重新导入
                sourceJson = s.sourceJson,
                converterVersion = s.converterVersion,
                sortOrder = s.sortOrder,
            )
        },
        replaceRules = replaceRules.getAll().map { r ->
            BackupReplaceRule(
                id = r.id, name = r.name, pattern = r.pattern, replacement = r.replacement,
                isRegex = r.isRegex, scope = r.scope, bookId = r.bookId, enabled = r.enabled,
                sortOrder = r.sortOrder, updatedAt = r.updatedAt,
            )
        },
        rssSources = rssDao.getAll().map { r ->
            BackupRssSource(
                id = r.id, name = r.name, enabled = r.enabled, json = r.json,
                sourceJson = r.sourceJson, updatedAt = r.updatedAt,
            )
        },
        readerSettings = readerPrefs.exportForBackup(),
        appSettings = appPrefs.exportForBackup(),
    )

    private suspend fun applyToLocal(merged: BackupMerger.MergeResult) {
        merged.changedBooks.forEach { b ->
            val existing = bookDao.getById(b.id)
            if (existing != null) {
                bookDao.update(
                    existing.copy(
                        title = b.title, author = b.author, intro = b.intro,
                        sourceId = b.sourceId, bookUrl = b.bookUrl, tocUrl = b.tocUrl,
                        totalChapters = maxOf(existing.totalChapters, b.totalChapters),
                        readChapterIndex = b.readChapterIndex, readCharOffset = b.readCharOffset,
                        readAt = b.readAt, updatedAt = b.updatedAt,
                        groupName = b.groupName,
                    )
                )
            } else {
                // 远端新增：本地占位（本地书缺文件时详情页提示重新导入；网络书可直接读）
                bookDao.upsert(
                    BookEntity(
                        id = b.id, type = b.type, title = b.title, author = b.author,
                        intro = b.intro, localPath = null,
                        sourceId = b.sourceId, bookUrl = b.bookUrl, tocUrl = b.tocUrl,
                        totalChapters = b.totalChapters,
                        readChapterIndex = b.readChapterIndex, readCharOffset = b.readCharOffset,
                        readAt = b.readAt,
                        addedAt = if (b.addedAt > 0) b.addedAt else System.currentTimeMillis(),
                        updatedAt = b.updatedAt,
                        groupName = b.groupName,
                    )
                )
            }
        }
        merged.changedSources.forEach { s ->
            sourceDao.upsert(
                BookSourceEntity(
                    id = s.id, name = s.name, enabled = s.enabled, sortOrder = s.sortOrder,
                    json = s.json, updatedAt = s.updatedAt,
                    sourceJson = s.sourceJson, converterVersion = s.converterVersion,
                )
            )
        }
        replaceRules.upsertAll(
            merged.changedReplaceRules.map { r ->
                ReplaceRuleEntity(
                    id = r.id, name = r.name, pattern = r.pattern, replacement = r.replacement,
                    isRegex = r.isRegex, scope = r.scope, bookId = r.bookId, enabled = r.enabled,
                    sortOrder = r.sortOrder, updatedAt = r.updatedAt,
                )
            }
        )
        rssDao.upsertAll(
            merged.changedRssSources.map { r ->
                com.radium.inkwell.data.db.entity.RssSourceEntity(
                    id = r.id, name = r.name, enabled = r.enabled, json = r.json,
                    sourceJson = r.sourceJson, updatedAt = r.updatedAt,
                )
            }
        )
        merged.readerSettings?.let { readerPrefs.importFromBackup(it) }
        merged.appSettings?.let { appPrefs.importFromBackup(it) }
    }
}
