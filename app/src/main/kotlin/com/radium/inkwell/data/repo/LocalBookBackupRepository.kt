package com.radium.inkwell.data.repo

import com.radium.inkwell.core.webdav.WebDavClient
import com.radium.inkwell.data.db.dao.BookDao
import com.radium.inkwell.data.db.entity.BookEntity
import com.radium.inkwell.data.db.entity.BookType
import com.radium.inkwell.util.UsageLog
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.time.TimeSource

data class BookFileBackupItem(
    val bookId: String,
    val title: String,
    val extension: String,
    val size: Long,
    val remoteName: String? = null,
) {
    val key: String get() = remoteName ?: bookId
}

class LocalBookBackupRepository(
    private val webDav: WebDavRepository,
    private val bookDao: BookDao,
    private val books: BookRepository,
) {
    suspend fun list(restore: Boolean): List<BookFileBackupItem> = withContext(Dispatchers.IO) {
        val shelf = bookDao.getAllVisible().filter { it.canBackUpFile() }.associateBy { it.id }
        val items = if (restore) {
            val client = webDav.bookFilesClient()
            val names = try {
                client.list(DIRECTORY)
            } catch (e: WebDavClient.WebDavException) {
                if (e.code != 409) throw e
                emptyList()
            }
            names.distinct().mapNotNull { name ->
                val remote = RemoteBookFile.parse(name) ?: return@mapNotNull null
                val book = shelf[remote.bookId] ?: return@mapNotNull null
                if (book.existingFile() != null || book.type != localBookTypeForExtension(remote.extension)) return@mapNotNull null
                BookFileBackupItem(book.id, book.title, remote.extension, remote.size, name)
            }
        } else {
            shelf.values.mapNotNull { book ->
                val file = book.existingFile() ?: return@mapNotNull null
                val extension = file.extension.lowercase()
                if (book.type != localBookTypeForExtension(extension)) return@mapNotNull null
                BookFileBackupItem(book.id, book.title, extension, file.length())
            }
        }
        items.sortedWith(compareBy({ it.title }, { it.key }))
    }

    suspend fun transfer(
        restore: Boolean,
        items: List<BookFileBackupItem>,
        onResult: (BookFileBackupItem, String?) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val started = TimeSource.Monotonic.markNow()
        var outcome = "cancelled"
        var operationError: String? = null
        var succeeded = 0
        var hidden = 0
        val failures = mutableMapOf<String, Int>()
        try {
            // 书架先上云，换机时才能按原来的 ID 找回正文和进度。
            val client = webDav.bookFilesClient(syncShelf = !restore)
            if (!restore) {
                client.mkcol("inkwell")
                client.mkcol(DIRECTORY)
            }
            for (item in items) {
                ensureActive()
                val error = try {
                    if (restore) restore(client, item) else backup(client, item)
                    succeeded++
                    null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: HiddenBookFileException) {
                    hidden++
                    e.message
                } catch (e: Exception) {
                    val category = syncErrorCategory(e)
                    failures[category] = (failures[category] ?: 0) + 1
                    describeSyncError(e)
                }
                onResult(item, error)
            }
            outcome = when {
                failures.isNotEmpty() -> if (succeeded > 0) "partial" else "failed"
                hidden > 0 -> if (succeeded > 0) "partial" else "skipped"
                else -> "ok"
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            outcome = "failed"
            operationError = syncErrorCategory(e)
            throw e
        } finally {
            UsageLog.event(
                "webdav.book_files.result", "operation" to if (restore) "restore" else "backup",
                "outcome" to outcome, "duration_ms" to started.elapsedNow().inWholeMilliseconds,
                "requested" to items.size, "succeeded" to succeeded,
                "skipped_hidden" to hidden, "failed" to failures.values.sum(),
                "error_category" to operationError,
                "failure_categories" to failures.entries.joinToString(",") { "${it.key}=${it.value}" },
            )
        }
    }

    private suspend fun backup(client: WebDavClient, item: BookFileBackupItem) {
        val file = checkNotNull(bookForTransfer(item.bookId).existingFile()) { "本机原文件已移除" }
        check(file.extension.equals(item.extension, ignoreCase = true)) { "本机文件格式已变更，请重新打开备份列表" }
        val name = RemoteBookFile(item.bookId, item.extension, file.length(), file.sha256()).name
        // 大文件校验期间书架仍可通过同步更新，发出 PUT 前再检查当前状态。
        check(bookForTransfer(item.bookId).localPath == file.path) { "本机原文件已变更，请重新打开备份列表" }
        client.putFileIfAbsent("$DIRECTORY/$name", file)
    }

    private suspend fun restore(client: WebDavClient, item: BookFileBackupItem) {
        val remote = checkNotNull(RemoteBookFile.parse(item.remoteName.orEmpty()))
        check(remote.bookId == item.bookId) { "云端文件与书架条目不匹配" }
        bookForTransfer(item.bookId)
        books.restoreLocalBook(item.bookId, remote.extension) { dest ->
            client.downloadFile("$DIRECTORY/${remote.name}", dest, remote.size)
            check(dest.sha256() == remote.hash) { "云端文件校验失败" }
        }
    }

    private suspend fun bookForTransfer(id: String): BookEntity {
        val book = bookDao.getById(id)
        if (book?.hidden == true) throw HiddenBookFileException()
        return checkNotNull(book?.takeIf { it.canBackUpFile() }) { "书架条目已变更，请重新打开书籍列表" }
    }

    companion object {
        const val DIRECTORY = "inkwell/books"
    }
}

internal class HiddenBookFileException : IllegalStateException("此书已移入隐藏书架，已跳过")

internal fun BookEntity.canBackUpFile(): Boolean =
    inShelf && !deleted && !hidden && type in BookType.LOCAL_TXT..BookType.LOCAL_MOBI && BOOK_ID.matches(id)

private fun BookEntity.existingFile(): File? = localPath?.let(::File)?.takeIf { it.isFile }

private const val BOOK_ID_PATTERN = "[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}"
private val BOOK_ID = Regex(BOOK_ID_PATTERN)

// 内容放进文件名，同一本书的同一份字节可重试，不同版本则各自保留。
internal data class RemoteBookFile(val bookId: String, val extension: String, val size: Long, val hash: String) {
    val name: String get() = "$bookId-$size-$hash.$extension"

    companion object {
        private val pattern = Regex("($BOOK_ID_PATTERN)-(0|[1-9][0-9]{0,18})-([0-9a-f]{64})\\.([a-z0-9]+)")

        fun parse(name: String): RemoteBookFile? {
            val match = pattern.matchEntire(name) ?: return null
            val (id, length, hash, extension) = match.destructured
            if (localBookTypeForExtension(extension) == null) return null
            return RemoteBookFile(id, extension, length.toLongOrNull() ?: return null, hash)
        }
    }
}

internal suspend fun File.sha256(): String = withContext(Dispatchers.IO) {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    digest.digest().toHexString()
}
