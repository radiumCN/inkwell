package com.radium.inkwell.data.repo

import com.radium.inkwell.core.webdav.BackupBook
import com.radium.inkwell.core.webdav.BackupCodec
import com.radium.inkwell.core.webdav.BackupMerger
import com.radium.inkwell.core.webdav.BackupPayload
import com.radium.inkwell.core.webdav.BackupSource
import com.radium.inkwell.core.webdav.WebDavClient
import com.radium.inkwell.data.db.dao.BookDao
import com.radium.inkwell.data.db.dao.BookSourceDao
import com.radium.inkwell.data.db.entity.BookEntity
import com.radium.inkwell.data.db.entity.BookSourceEntity
import com.radium.inkwell.data.db.entity.BookType
import com.radium.inkwell.data.prefs.WebDavPrefs
import kotlinx.coroutines.flow.first

class WebDavRepository(
    private val bookDao: BookDao,
    private val sourceDao: BookSourceDao,
    private val prefs: WebDavPrefs,
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
            applied = merged.changedBooks.size + merged.changedSources.size
            toUpload = local.copy(books = merged.books, sources = merged.sources)
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
            )
        },
        sources = sourceDao.getAll().map { s ->
            BackupSource(s.id, s.name, s.enabled, s.json, s.updatedAt)
        },
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
                    )
                )
            }
        }
        merged.changedSources.forEach { s ->
            sourceDao.upsert(BookSourceEntity(s.id, s.name, s.enabled, 0, s.json, s.updatedAt))
        }
    }
}
