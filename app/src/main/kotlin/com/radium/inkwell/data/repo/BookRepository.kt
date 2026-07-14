package com.radium.inkwell.data.repo

import android.content.Context
import android.net.Uri
import com.radium.inkwell.core.model.BookHandle
import com.radium.inkwell.core.model.BookParserRegistry
import com.radium.inkwell.data.db.dao.BookDao
import com.radium.inkwell.data.db.dao.ChapterDao
import com.radium.inkwell.data.db.entity.BookEntity
import com.radium.inkwell.data.db.entity.BookType
import com.radium.inkwell.data.db.entity.ChapterEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class BookRepository(
    private val context: Context,
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao,
    private val parserRegistry: BookParserRegistry,
) {

    val books: Flow<List<BookEntity>> = bookDao.observeAll()

    fun observeBook(id: String): Flow<BookEntity?> = bookDao.observeById(id)

    suspend fun getBook(id: String): BookEntity? = bookDao.getById(id)

    private fun booksDir(): File = File(context.filesDir, "books").apply { mkdirs() }

    private fun coversDir(): File = File(context.filesDir, "covers").apply { mkdirs() }

    /** SAF 导入：复制到私有目录 → 解析元数据与目录 → 入库。返回 bookId 或错误。 */
    suspend fun importLocalBook(uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val displayName = queryDisplayName(uri) ?: "book_${System.currentTimeMillis()}.txt"
            val bookId = UUID.randomUUID().toString()
            val ext = displayName.substringAfterLast('.', "txt").lowercase()
            val dest = File(booksDir(), "$bookId.$ext")
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { input.copyTo(it) }
            } ?: error("无法读取所选文件")

            try {
                val handle = parserRegistry.open(dest)
                handle.use { book ->
                    val coverPath = book.metadata.cover?.let { cover ->
                        val f = File(coversDir(), "$bookId.img")
                        f.writeBytes(cover.data)
                        f.absolutePath
                    }
                    val now = System.currentTimeMillis()
                    // 文件已改名为 bookId，txt 的书名只能来自原始文件名；
                    // EPUB/MOBI 优先元数据，无标题时同样回落原始文件名
                    val type = when (ext) {
                        "epub" -> BookType.LOCAL_EPUB
                        "mobi", "azw3", "azw" -> BookType.LOCAL_MOBI
                        else -> BookType.LOCAL_TXT
                    }
                    val fallbackTitle = displayName.substringBeforeLast('.').trim()
                    val title = if (type == BookType.LOCAL_TXT) {
                        fallbackTitle
                    } else {
                        book.metadata.title.takeIf { it.isNotBlank() && it != "未命名" }
                            ?: fallbackTitle
                    }
                    bookDao.upsert(
                        BookEntity(
                            id = bookId,
                            type = type,
                            title = title.ifBlank { "未命名" },
                            author = book.metadata.author ?: "",
                            coverPath = coverPath,
                            intro = book.metadata.description,
                            localPath = dest.absolutePath,
                            totalChapters = book.chapters.size,
                            addedAt = now,
                            updatedAt = now,
                        )
                    )
                    chapterDao.upsertAll(
                        book.chapters.map { ChapterEntity(bookId, it.index, it.title) }
                    )
                }
                bookId
            } catch (e: Exception) {
                dest.delete()
                throw e
            }
        }
    }

    suspend fun deleteBook(id: String) = withContext(Dispatchers.IO) {
        bookDao.getById(id)?.let { book ->
            book.localPath?.let { File(it).delete() }
            book.coverPath?.let { File(it).delete() }
            File(File(context.filesDir, "cache"), id).deleteRecursively()
            chapterDao.deleteByBook(id)
            bookDao.delete(book)
        }
    }

    suspend fun setGroup(id: String, group: String) {
        bookDao.setGroup(id, group)
    }

    suspend fun setHidden(id: String, hidden: Boolean) {
        bookDao.setHidden(id, hidden)
    }

    suspend fun saveProgress(id: String, chapterIndex: Int, charOffset: Int) {
        bookDao.updateProgress(id, chapterIndex, charOffset, System.currentTimeMillis())
    }

    /** 打开本地书文件（调用方负责 close） */
    fun openLocal(book: BookEntity): BookHandle {
        val path = book.localPath ?: error("非本地书籍")
        return parserRegistry.open(File(path))
    }

    private fun queryDisplayName(uri: Uri): String? =
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
}
