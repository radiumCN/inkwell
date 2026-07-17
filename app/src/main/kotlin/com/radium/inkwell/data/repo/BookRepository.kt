package com.radium.inkwell.data.repo

import android.content.Context
import android.net.Uri
import com.radium.inkwell.core.model.BookHandle
import com.radium.inkwell.core.model.BookParserRegistry
import com.radium.inkwell.data.db.dao.BookDao
import com.radium.inkwell.data.db.dao.BookSourceHitDao
import com.radium.inkwell.data.db.dao.ChapterDao
import com.radium.inkwell.data.db.entity.BookEntity
import com.radium.inkwell.data.db.entity.BookType
import com.radium.inkwell.data.db.entity.ChapterEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class BookRepository(
    private val context: Context,
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao,
    private val hitDao: BookSourceHitDao,
    private val parserRegistry: BookParserRegistry,
) {

    val books: Flow<List<BookEntity>> = bookDao.observeAll()

    /** 书架上已有书的 (书名, 作者) 键集合，用来判断某本网络书是否已在书架 */
    val shelfKeys: Flow<Set<Pair<String, String>>> =
        books.map { list -> list.mapTo(HashSet()) { bookKey(it.title, it.author) } }

    suspend fun getBook(id: String): BookEntity? = bookDao.getById(id)

    /**
     * 书架上与该「书名+作者」匹配的书 id。网络书按 (sourceId,bookUrl) 存，但同一本书跨书源
     * 合并靠 (书名,作者) —— 判断"已在书架"、以及直达已存在的那本，都得按这个键，否则换个
     * 代表书源就认不出是同一本，于是要么重复显示"加入"、要么再入库一份重复的。
     */
    suspend fun shelfBookIdByKey(title: String, author: String?): String? {
        val key = bookKey(title, author)
        return bookDao.getAll().firstOrNull { bookKey(it.title, it.author) == key }?.id
    }

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
                // 书行可能已插入、章节写失败 —— 回滚掉，别留一条指向已删文件的幽灵书
                dest.delete()
                // 这是**导入失败的回滚**，不是用户删书：这行从没成功存在过，
                // 必须真删。留墓碑会把一条凭空的「删除」同步给其它设备。
                bookDao.hardDelete(bookId)
                chapterDao.deleteByBook(bookId)
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
            // 换源记忆是按 bookId 存的，书没了就是孤儿行，越攒越多
            hitDao.deleteByBook(id)
            // 软删除：留下墓碑，否则 WebDAV 同步会把这本书从远端又拉回来。
            // 章节、缓存、封面这些本地附属物照旧真删 —— 它们不参与同步，留着只占地方
            bookDao.softDelete(id, System.currentTimeMillis())
        }
    }

    suspend fun setGroup(id: String, group: String) {
        bookDao.setGroup(id, group)
    }

    /** 打开书就把「有新章节」的红点清掉 —— 打开即已知晓 */
    suspend fun clearNewChapters(id: String) {
        bookDao.clearNewChapters(id)
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

/** 「同一本书」的判定键：书名 + 作者（去空白）。与搜索结果的合并键（SearchViewModel.merge）一致 */
fun bookKey(title: String, author: String?): Pair<String, String> =
    title.trim() to author?.trim().orEmpty()
