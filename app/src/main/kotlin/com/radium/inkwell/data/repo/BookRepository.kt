package com.radium.inkwell.data.repo

import android.content.Context
import android.net.Uri
import androidx.room3.withWriteTransaction
import com.radium.inkwell.core.model.BookHandle
import com.radium.inkwell.core.model.BookParserRegistry
import com.radium.inkwell.data.db.InkwellDb
import com.radium.inkwell.data.db.dao.BookDao
import com.radium.inkwell.data.db.dao.BookSourceHitDao
import com.radium.inkwell.data.db.dao.ChapterDao
import com.radium.inkwell.data.db.dao.ReplaceRuleDao
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
    private val db: InkwellDb,
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao,
    private val hitDao: BookSourceHitDao,
    private val replaceRuleDao: ReplaceRuleDao,
    private val parserRegistry: BookParserRegistry,
) {

    val books: Flow<List<BookEntity>> = bookDao.observeAll()

    /** 书架上已有书的 (书名, 作者) 键集合，用来判断某本网络书是否已在书架 */
    val shelfKeys: Flow<Set<Pair<String, String>>> =
        books.map { list -> list.mapTo(HashSet()) { bookKey(it.title, it.author) } }

    suspend fun getBook(id: String): BookEntity? = bookDao.getById(id)

    /** 单本书的实时流。详情页追更后封面/章数/简介会变，订阅它 UI 才跟得上。 */
    fun observeBook(id: String): Flow<BookEntity?> = bookDao.observeById(id)

    /**
     * 书架上与该「书名+作者」匹配的书 id。网络书按 (sourceId,bookUrl) 存，但同一本书跨书源
     * 合并靠 (书名,作者) —— 判断"已在书架"、以及直达已存在的那本，都得按这个键，否则换个
     * 代表书源就认不出是同一本，于是要么重复显示"加入"、要么再入库一份重复的。
     *
     * **必须排除墓碑**（getAllVisible 而非 getAll）：删过的书行还在库里，若把它也认成"已在书架"，
     * 预览页会显示「已在书架」而书架上根本没有 —— 加不进去（被这里挡住）、点"读"打开的是那条
     * 墓碑（读得了但永远不回书架）。删过的书从此再也加不回来，且没有任何应用内出路。
     */
    suspend fun shelfBookIdByKey(title: String, author: String?): String? {
        val key = bookKey(title, author)
        return bookDao.getAllVisible().firstOrNull { bookKey(it.title, it.author) == key }?.id
    }

    /**
     * 书架上与该「书名+作者」匹配的**本地书** id。
     *
     * 只认本地：同名网络书不挡导入 —— 用户完全可能先从书源加过一本，再导一份本地备份。
     * 墓碑同样排除（见 [shelfBookIdByKey]）。
     */
    suspend fun shelfLocalBookIdByKey(title: String, author: String?): String? {
        val key = bookKey(title, author)
        return bookDao.getAllVisible().firstOrNull {
            it.type != BookType.NET && bookKey(it.title, it.author) == key
        }?.id
    }

    private fun booksDir(): File = File(context.filesDir, "books").apply { mkdirs() }

    private fun coversDir(): File = File(context.filesDir, "covers").apply { mkdirs() }

    /**
     * SAF 导入：复制到私有目录 → 解析元数据与目录 → 入库。
     *
     * 同一本本地书（书名+作者）已在书架则跳过，不另建副本 —— 以前每次导入都 new UUID，
     * 同一文件选两遍书架上就会出现两本一模一样的。
     */
    suspend fun importLocalBook(uri: Uri): Result<LocalImportResult> = withContext(Dispatchers.IO) {
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
                    var coverFile: File? = null
                    val coverPath = book.metadata.cover?.let { cover ->
                        val f = File(coversDir(), "$bookId.img")
                        f.writeBytes(cover.data)
                        coverFile = f
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
                    }.ifBlank { "未命名" }
                    val author = book.metadata.author ?: ""

                    shelfLocalBookIdByKey(title, author)?.let { existingId ->
                        // 副本文件和刚写的封面都没用了，别占磁盘
                        dest.delete()
                        coverFile?.delete()
                        return@runCatching LocalImportResult.AlreadyOnShelf(existingId)
                    }

                    val entity = BookEntity(
                        id = bookId,
                        type = type,
                        title = title,
                        author = author,
                        coverPath = coverPath,
                        intro = book.metadata.description,
                        localPath = dest.absolutePath,
                        totalChapters = book.chapters.size,
                        addedAt = now,
                        updatedAt = now,
                    )
                    val chapters = book.chapters.map { ChapterEntity(bookId, it.index, it.title) }
                    // 书行与目录成对落库。下面的 catch 只兜得住异常，兜不住进程被杀 ——
                    // 两句之间被杀就留下一本"有书行、没目录"的书：书架上看得见，点进去空目录。
                    // 解析和封面写盘都在事务外，别把文件 IO 圈进来拉长锁。
                    db.withWriteTransaction {
                        bookDao.upsert(entity)
                        chapterDao.upsertAll(chapters)
                    }
                    LocalImportResult.Added(bookId)
                }
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
            // 三步写库成组提交：中途被杀会留下半套 —— 最糟的是目录已清、墓碑没打上，
            // 书还在书架里但点进去是空目录，而且再删一次也修不好（文件早没了）。
            // 文件删除放在事务外：那是不可回滚的磁盘 IO，圈进来只会拉长锁。
            val now = System.currentTimeMillis()
            db.withWriteTransaction {
                chapterDao.deleteByBook(id)
                // 换源记忆是按 bookId 存的，书没了就是孤儿行，越攒越多
                hitDao.deleteByBook(id)
                // 本书专属净化规则同样挂 bookId；软删打墓碑，WebDAV 合并才能把删除同步出去
                replaceRuleDao.softDeleteByBook(id, now)
                // 软删除：留下墓碑，否则 WebDAV 同步会把这本书从远端又拉回来。
                // 章节、缓存、封面这些本地附属物照旧真删 —— 它们不参与同步，留着只占地方
                bookDao.softDelete(id, now)
            }
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

/** 本地书导入结局：新建 vs 书架上已有同名同作者的本地书 */
sealed interface LocalImportResult {
    val bookId: String
    data class Added(override val bookId: String) : LocalImportResult
    data class AlreadyOnShelf(override val bookId: String) : LocalImportResult
}
