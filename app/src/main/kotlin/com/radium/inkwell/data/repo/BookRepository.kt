package com.radium.inkwell.data.repo

import android.content.Context
import android.net.Uri
import androidx.room3.withWriteTransaction
import com.radium.inkwell.core.model.BookHandle
import com.radium.inkwell.core.model.BookParserRegistry
import com.radium.inkwell.core.util.htmlToPlainText
import com.radium.inkwell.data.db.InkwellDb
import com.radium.inkwell.data.db.dao.BookDao
import com.radium.inkwell.data.db.dao.BookSourceHitDao
import com.radium.inkwell.data.db.dao.ChapterDao
import com.radium.inkwell.data.db.dao.ReplaceRuleDao
import com.radium.inkwell.data.db.entity.BookEntity
import com.radium.inkwell.data.db.entity.BookType
import com.radium.inkwell.data.db.entity.ChapterEntity
import com.radium.inkwell.ui.bookshelf.DuplicateMerge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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
    private val cache: ChapterContentCache,
) {

    val books: Flow<List<BookEntity>> = bookDao.observeAll()

    /**
     * chapter 表每被写一次（哪本书都算）就来一声，不带数据、也不查表；订阅时先来一声。
     *
     * 给阅读页盯目录用（ReaderViewModel.observeToc）：Room 的失效是按整张表的，订阅一条 `SELECT *` 目录流，
     * 预取每落一章、离线缓存每存一章，就把整本目录重读一遍。只听这一声，读不读、读多少由订阅方自己定
     */
    fun chapterTableChanges(): Flow<Unit> = db.invalidationTracker.createFlow("chapter").map { }

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
     * **必须排除墓碑和试读未上架**（getAllVisible 而非 getAll）：删过的书行还在库里，若把它也
     * 认成"已在书架"，预览页会显示「已在书架」而书架上根本没有 —— 加不进去（被这里挡住）、
     * 点"读"打开的是那条墓碑（读得了但永远不回书架）。试读行同样不在架上，不能当成已加入。
     *
     * [includeHidden] = false：**隐藏的书也当作不在架上**。隐藏区上了锁时搜索/预览走这条 ——
     * 认成「已在书架」等于当着旁人说「这本我藏着」，点「开始阅读」还会不经验证直接把它打开。
     * 默认 true 保持原语义：没上锁时隐藏只是把书从列表里收起来，它仍然在架上。
     */
    suspend fun shelfBookIdByKey(title: String, author: String?, includeHidden: Boolean = true): String? {
        val key = bookKey(title, author)
        return bookDao.getAllVisible().firstOrNull {
            (includeHidden || !it.hidden) && bookKey(it.title, it.author) == key
        }?.id
    }

    /**
     * 书架上与该「书名+作者」匹配的**本地书** id。
     *
     * 只认本地：同名网络书不挡导入 —— 用户完全可能先从书源加过一本，再导一份本地备份。
     * 墓碑同样排除（见 [shelfBookIdByKey]）。
     */
    suspend fun shelfLocalBookIdByKey(title: String, author: String?): String? =
        shelfLocalBookByKey(title, author)?.id

    /**
     * 同 [shelfLocalBookIdByKey]，有本机文件的那本优先 —— 同名的既有真书、又有同步来的占位时，
     * 导入该撞上真书（跳过），而不是去补那条占位。
     */
    private suspend fun shelfLocalBookByKey(title: String, author: String?): BookEntity? {
        val key = bookKey(title, author)
        val matches = bookDao.getAllVisible().filter {
            it.type != BookType.NET && bookKey(it.title, it.author) == key
        }
        return matches.firstOrNull { it.hasLocalFile() } ?: matches.firstOrNull()
    }

    /** 本机有这本书的文件。WebDAV 同步来的本地书只有书行（文件不进备份），localPath 为 null */
    private fun BookEntity.hasLocalFile(): Boolean = localPath?.let { File(it).isFile } == true

    private fun booksDir(): File = File(context.filesDir, "books").apply { mkdirs() }

    private fun coversDir(): File = File(context.filesDir, "covers").apply { mkdirs() }

    /**
     * SAF 导入：复制到私有目录 → 解析元数据与目录 → 入库。
     *
     * 同一本本地书（书名+作者）已在书架则跳过，不另建副本 —— 以前每次导入都 new UUID，
     * 同一文件选两遍书架上就会出现两本一模一样的。
     */
    suspend fun importLocalBook(uri: Uri): Result<LocalImportResult> = withContext(Dispatchers.IO) {
        try {
            val displayName = queryDisplayName(uri) ?: "book_${System.currentTimeMillis()}.txt"
            Result.success(
                importCopy(displayName) { dest ->
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        dest.outputStream().use { input.copyTo(it) }
                    } ?: error("无法读取所选文件")
                }
            )
        } catch (e: CancellationException) {
            // 用户离开导入页 —— 这是取消，不是「导入失败」
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 从已有文件导入（Baseline Profile 夹具、测试用）。走和 SAF 导入同一套解析/去重。
     */
    suspend fun importLocalFile(file: File, displayName: String = file.name): Result<LocalImportResult> =
        withContext(Dispatchers.IO) {
            try {
                Result.success(importCopy(displayName) { dest -> file.copyTo(dest, overwrite = true) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * 复制进私有目录 → 解析 → 入库。中途任何一步失败（含取消）都回滚这次复制出来的书文件和封面 ——
     * 从前复制失败留半截文件、封面在哪条失败路径上都没删过，books/ covers/ 里越攒越多没人认领的文件。
     */
    private suspend fun importCopy(displayName: String, copy: (File) -> Unit): LocalImportResult {
        val bookId = UUID.randomUUID().toString()
        val ext = displayName.substringAfterLast('.', "txt").lowercase()
        val dest = File(booksDir(), "$bookId.$ext")
        try {
            copy(dest)
            return insertCopiedLocal(dest, displayName, bookId, ext)
        } catch (e: Exception) {
            // 回滚本身要写库，取消之后的挂起调用会直接抛 —— 不顶住的话一样什么都没清
            withContext(NonCancellable) { rollbackImport(dest, bookId) }
            throw e
        }
    }

    private suspend fun rollbackImport(dest: File, bookId: String) {
        // 事务**提交之后**才被取消（写事务返回时会补查一次取消）：书行已经指向这个文件，这次导入其实成了。
        // 从前一律当失败回滚，删掉的是一本已经在书架上的书的文件 —— 留下一条点开就报错的幽灵书。
        if (bookDao.countByLocalPath(dest.absolutePath) > 0) return
        // 解析时（txt）已经按这个路径写过缓存：文件删了它就再也没人用，跟着清
        parserRegistry.evictCaches(dest)
        dest.delete()
        // 封面在入库事务之前就落了盘，失败了也得跟着删（补占位那条路径同样用新 id 起名）
        File(coversDir(), "$bookId.img").delete()
        // 这是**导入失败的回滚**，不是用户删书：这行从没成功存在过，
        // 必须真删。留墓碑会把一条凭空的「删除」同步给其它设备。
        bookDao.hardDelete(bookId)
        chapterDao.deleteByBook(bookId)
    }

    private suspend fun insertCopiedLocal(
        dest: File,
        displayName: String,
        bookId: String,
        ext: String,
    ): LocalImportResult {
        val handle = parserRegistry.open(dest)
        return handle.use { book ->
            var coverFile: File? = null
            val coverPath = book.metadata.cover?.let { cover ->
                val f = File(coversDir(), "$bookId.img")
                f.writeBytes(cover.data)
                coverFile = f
                f.absolutePath
            }
            val now = System.currentTimeMillis()
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

            shelfLocalBookByKey(title, author)?.let { existing ->
                if (existing.hasLocalFile()) {
                    // 刚才 open 时 txt 已按 dest 写了解析缓存；dest 是这次导入的新路径，删了就是孤儿
                    parserRegistry.evictCaches(dest)
                    dest.delete()
                    coverFile?.delete()
                    return@use LocalImportResult.AlreadyOnShelf(existing.id)
                }
                // 书架上那本只是**占位**：别的设备同步来的本地书只有书行，文件不进备份。
                // 「换设备后本地书需要重新导入」说的就是这一步 —— 从前这里当成「已在书架」跳过、
                // 还把刚复制来的文件删了，用户怎么导都打不开，只能删掉那条坏书；而删除会同步回去，
                // 把原设备上的真书文件也一并删掉。补上文件、沿用原 id（进度/分组/隐藏都跟着这条书行走）。
                val fresh = bookDao.getById(existing.id) ?: existing
                // 只改本机才有意义的列，不动 updatedAt：书名/分组这些同步元数据没变，
                // 盖新时间戳只会让这台设备在下次合并里无故「赢」一回。
                val relinked = fresh.copy(
                    type = type,
                    localPath = dest.absolutePath,
                    coverPath = coverPath,
                    intro = fresh.intro ?: book.metadata.description?.htmlToPlainText(),
                    totalChapters = book.chapters.size,
                )
                val chapters = book.chapters.map { ChapterEntity(fresh.id, it.index, it.title) }
                db.withWriteTransaction {
                    bookDao.update(relinked)
                    chapterDao.deleteByBook(fresh.id)
                    chapterDao.upsertAll(chapters)
                }
                return@use LocalImportResult.Added(fresh.id)
            }

            val entity = BookEntity(
                id = bookId,
                type = type,
                title = title,
                author = author,
                coverPath = coverPath,
                intro = book.metadata.description?.htmlToPlainText(),
                localPath = dest.absolutePath,
                totalChapters = book.chapters.size,
                addedAt = now,
                updatedAt = now,
            )
            val chapters = book.chapters.map { ChapterEntity(bookId, it.index, it.title) }
            // 书行与目录成对落库。解析和封面写盘都在事务外，别把文件 IO 圈进来拉长锁。
            db.withWriteTransaction {
                bookDao.upsert(entity)
                chapterDao.upsertAll(chapters)
            }
            LocalImportResult.Added(bookId)
        }
    }

    /**
     * 试读结束选择加入书架。写库前重读最新行：阅读过程中进度/红点可能已经变了。
     */
    suspend fun commitToShelf(id: String) {
        val fresh = bookDao.getById(id) ?: return
        if (fresh.inShelf && !fresh.deleted) return
        bookDao.update(
            fresh.copy(inShelf = true, deleted = false, updatedAt = System.currentTimeMillis()),
        )
    }

    /**
     * 试读结束选择不加入。这本书从没上过架，不能打墓碑 ——
     * 留墓碑会同步出一条凭空的删除（和导入失败回滚同一条理由）。
     * 若落库前它已经是墓碑（删过又点开试读），则清掉试读写入的目录，墓碑行留下。
     */
    suspend fun discardUnshelved(id: String) = withContext(Dispatchers.IO) {
        val book = bookDao.getById(id) ?: return@withContext
        if (book.inShelf) return@withContext
        deleteLocalFiles(book)
        val now = System.currentTimeMillis()
        db.withWriteTransaction {
            chapterDao.deleteByBook(id)
            hitDao.deleteByBook(id)
            replaceRuleDao.softDeleteByBook(id, now)
            if (!book.deleted) bookDao.hardDelete(id)
        }
    }

    suspend fun deleteBook(id: String) = deleteBooks(listOf(id))

    /**
     * 删一批书。书架多选删除走这里：从前逐本调 [deleteBook]，每本一个事务，
     * 删 N 本书架流就重发 N 次、整架跟着重排 N 遍。
     */
    suspend fun deleteBooks(ids: Collection<String>) = withContext(Dispatchers.IO) {
        val books = ids.mapNotNull { bookDao.getById(it) }
        if (books.isEmpty()) return@withContext
        // 文件删除放在事务外：那是不可回滚的磁盘 IO，圈进来只会拉长锁。
        books.forEach { deleteLocalFiles(it) }
        // 几步写库成组提交：中途被杀会留下半套 —— 最糟的是目录已清、墓碑没打上，
        // 书还在书架里但点进去是空目录，而且再删一次也修不好（文件早没了）。
        val now = System.currentTimeMillis()
        db.withWriteTransaction {
            for (book in books) softDeleteRows(book.id, now)
        }
    }

    /** 删一本书在库里的几样东西。只在写事务里调（见 [deleteBooks]） */
    private suspend fun softDeleteRows(id: String, now: Long) {
        chapterDao.deleteByBook(id)
        // 换源记忆是按 bookId 存的，书没了就是孤儿行，越攒越多
        hitDao.deleteByBook(id)
        // 本书专属净化规则同样挂 bookId；软删打墓碑，WebDAV 合并才能把删除同步出去
        replaceRuleDao.softDeleteByBook(id, now)
        // 软删除：留下墓碑，否则 WebDAV 同步会把这本书从远端又拉回来。
        // 章节、缓存、封面这些本地附属物照旧真删 —— 它们不参与同步，留着只占地方
        bookDao.softDelete(id, now)
    }

    /**
     * 整理重复的书：每组留 [DuplicateMerge.keepId]、删其余（书架「整理重复的书」面板）。返回删了几本。
     *
     * 删的走 [deleteBooks] 同一套（文件在事务外删、库里的几步成组提交、打墓碑同步出去），另外两件事和删除同一个事务：
     * - **分组**：留下的那份自己没分组、被删的那份有，就接过来 —— 用户给其中一份分过组，整理完那本书不该掉回「未分组」；
     * - **隐藏**：组里有隐藏的书，留下的那份也隐藏。宁可多藏一本：最常见的重复正是「藏着一本、上锁时又加了一本」，
     *   留下看得见的那份而不藏，整理完藏着的书就摆回了书架上。
     *
     * 留下的那份**在事务里重读**再改：面板开着的时候，进度、分组可能已经被别处改过（同步、另一个页面），
     * 拿打开面板时的快照判断会把新改的分组盖掉。已经不在架上的（别处删了）整组跳过。
     */
    suspend fun mergeDuplicates(merges: List<DuplicateMerge>): Int = withContext(Dispatchers.IO) {
        val plan = merges.mapNotNull { m ->
            val remove = m.removeIds.mapNotNull { bookDao.getById(it) }.filter { !it.deleted && it.inShelf }
            if (remove.isEmpty()) null else m.keepId to remove
        }
        if (plan.isEmpty()) return@withContext 0
        // 先确认留下的那份还在：它若已经被删了，这一组一本都别动，否则整组就没了
        val live = plan.filter { (keepId, _) -> bookDao.getById(keepId)?.let { !it.deleted && it.inShelf } == true }
        live.forEach { (_, remove) -> remove.forEach(::deleteLocalFiles) }
        val now = System.currentTimeMillis()
        var removed = 0
        db.withWriteTransaction {
            for ((keepId, remove) in live) {
                val keep = bookDao.getById(keepId)?.takeIf { !it.deleted && it.inShelf } ?: continue
                if (keep.groupName.isBlank()) {
                    remove.firstOrNull { it.groupName.isNotBlank() }?.let { bookDao.setGroup(keep.id, it.groupName, now) }
                }
                if (!keep.hidden && remove.any { it.hidden }) bookDao.setHidden(keep.id, true, now)
                for (book in remove) softDeleteRows(book.id, now)
                removed += remove.size
            }
        }
        removed
    }

    /**
     * 全书架清一遍正文缓存，冷启动后在后台跑。
     *
     * 阅读页进书时的清理（ReaderViewModel.pruneStaleChapterCache）只管**那一本**：不再打开的书、
     * 读完扔在一边的书，进度之前的缓存永远没人清，几十本连载攒下来就是几百 MB。这里按同一条规则
     * （进度之前、超过 [maxAgeDays] 天没碰过）把每本网络书过一遍；[maxAgeDays] 为 0 即用户关了自动清理，只清孤儿。
     * 库里已经没有的书留下的整本目录，不论设置都清（没有任何入口能再读到它们）。
     */
    suspend fun sweepContentCache(maxAgeDays: Int) = withContext(Dispatchers.IO) {
        val all = bookDao.getAll()
        val live = all.filter { !it.deleted }
        cache.pruneOrphanBooks(live.map { it.id }, graceMs = ORPHAN_GRACE_MS)
        if (maxAgeDays <= 0) return@withContext
        val maxAgeMs = maxAgeDays * 86_400_000L
        for (book in live) {
            if (book.type != BookType.NET || book.readChapterIndex <= 0) continue
            // 本机从没缓存过的书（同步来的、只加没读的）占大多数：先看目录在不在，别白读一整本目录表
            if (!cache.hasAny(book.id)) continue
            // 刚在阅读页里清过的书不再过一遍；这里清了的，接下来打开它也不重来（见 claimPrune）
            if (!cache.claimPrune(book.id)) continue
            val urls = chapterUrlsByIndex(chapterDao.getByBook(book.id)).ifEmpty { continue }
            val pruned = cache.pruneBehindProgress(book.id, book.readChapterIndex, urls, maxAgeMs)
            if (pruned.isNotEmpty()) chapterDao.markUncached(book.id, pruned)
        }
    }

    /**
     * 清掉一本书在本机的附属文件：导入的书文件、本地封面、正文缓存。删书、丢弃试读、
     * 同步来的远端删除都走这里。
     *
     * 路径一律经 [deleteOwnedFile] 限定在 books/ 与 covers/ 之内：同步来的书行里，路径与 id
     * 都出自备份文件，不能拿来直接删（见那里的说明）。只做文件 IO，调用方负责在 IO 线程、事务之外调。
     */
    fun deleteLocalFiles(book: BookEntity) {
        // 解析缓存（txt 的解码正文 + 章节表）按书文件路径记，先于书文件本身清掉
        book.localPath?.let { parserRegistry.evictCaches(File(it)) }
        deleteOwnedFile(book.localPath, booksDir())
        deleteOwnedFile(book.coverPath, coversDir())
        cache.clear(book.id)
    }

    suspend fun setGroup(id: String, group: String) {
        bookDao.setGroup(id, group, System.currentTimeMillis())
    }

    /** 多选归组 / 隐藏：一个事务，书架流只重发一次（逐本写就是整架连着重排 N 遍） */
    suspend fun setGroupAll(ids: Collection<String>, group: String) {
        val now = System.currentTimeMillis()
        db.withWriteTransaction { for (id in ids) bookDao.setGroup(id, group, now) }
    }

    suspend fun setHiddenAll(ids: Collection<String>, hidden: Boolean) {
        val now = System.currentTimeMillis()
        db.withWriteTransaction { for (id in ids) bookDao.setHidden(id, hidden, now) }
    }

    /** 打开书就把「有新章节」的红点清掉 —— 打开即已知晓；同时记下打开时间（书架排序） */
    suspend fun markOpened(id: String) {
        bookDao.markOpened(id, System.currentTimeMillis())
    }

    suspend fun setHidden(id: String, hidden: Boolean) {
        bookDao.setHidden(id, hidden, System.currentTimeMillis())
    }

    suspend fun saveProgress(id: String, chapterIndex: Int, charOffset: Int) {
        bookDao.updateProgress(id, chapterIndex, charOffset, System.currentTimeMillis())
    }

    /** 打开本地书文件（调用方负责 close） */
    fun openLocal(book: BookEntity): BookHandle {
        // 有路径、文件却不在，和没有路径是一回事：从云备份恢复的书行带着原来的路径，
        // 书文件本身不进 Auto Backup（见 res/xml/data_extraction_rules.xml）。不拦的话报的是一串文件路径
        val file = book.localPath?.let(::File)?.takeIf { it.isFile } ?: error(
            if (book.type == BookType.NET) "非本地书籍"
            // 同步只带书架信息、不带文件：别的设备导入的书在这台设备上只是一条占位
            else "这本书的文件不在这台设备上，请在书架重新导入同一个文件",
        )
        return parserRegistry.open(file)
    }

    private fun queryDisplayName(uri: Uri): String? =
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
}

/** 孤儿缓存目录至少多久没碰过才删（[BookRepository.sweepContentCache]）：刚加的书可能还没落库，别跟它抢 */
private const val ORPHAN_GRACE_MS = 86_400_000L

/** 「同一本书」的判定键：书名 + 作者（去空白）。与搜索结果的合并键（SearchViewModel.merge）一致 */
fun bookKey(title: String, author: String?): Pair<String, String> =
    title.trim() to author?.trim().orEmpty()

/** 本地书导入结局：新建 vs 书架上已有同名同作者的本地书 */
sealed interface LocalImportResult {
    val bookId: String
    data class Added(override val bookId: String) : LocalImportResult
    data class AlreadyOnShelf(override val bookId: String) : LocalImportResult
}
