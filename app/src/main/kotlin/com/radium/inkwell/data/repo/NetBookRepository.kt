package com.radium.inkwell.data.repo

import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import com.radium.inkwell.core.source.BookSourceEngine
import com.radium.inkwell.core.source.BookSourceRule
import com.radium.inkwell.core.source.RemoteBookDetail
import com.radium.inkwell.core.source.RemoteChapter
import com.radium.inkwell.core.source.SearchResult
import com.radium.inkwell.core.source.SourceVariables
import androidx.room3.withWriteTransaction
import com.radium.inkwell.data.db.InkwellDb
import com.radium.inkwell.data.db.dao.BookDao
import com.radium.inkwell.data.db.dao.ChapterDao
import com.radium.inkwell.data.db.entity.BookEntity
import com.radium.inkwell.data.db.entity.BookType
import com.radium.inkwell.data.db.entity.ChapterEntity
import com.radium.inkwell.core.util.htmlToPlainText
import com.radium.inkwell.core.util.toHex
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException

/** 网络书：加书架、刷新目录、换源 */
class NetBookRepository(
    private val db: InkwellDb,
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao,
    private val engine: BookSourceEngine,
    private val cache: ChapterContentCache,
) {

    /** 详情 + 目录。加书架/换源/预览页/书源测试全都走这里，避免各写一份兜底逻辑写出分歧 */
    suspend fun fetchDetailAndToc(
        rule: BookSourceRule,
        bookUrl: String,
    ): Pair<RemoteBookDetail, List<RemoteChapter>> {
        val detail = engine.getDetail(rule, bookUrl)
        val toc = engine.getToc(
            rule, detail.tocUrl.ifBlank { bookUrl },
            bookUrl = bookUrl, bookTitle = detail.title, bookAuthor = detail.author.orEmpty(),
            // 详情页 @put 下的书号之类，目录接口地址要拼它（Legado 挂在 Book 上的那张变量表）
            variables = detail.variables,
        )
        return detail to toc
    }

    /** 搜索结果 → 详情 + 目录 → 入库，返回 bookId */
    suspend fun addToShelf(result: SearchResult, rule: BookSourceRule): Result<String> = try {
        val (detail, toc) = fetchDetailAndToc(rule, result.bookUrl)
        Result.success(persist(rule.id, result.bookUrl, detail, toc, fallback = result, inShelf = true))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    /** 详情与目录已在预览页抓到时直接入库，避免重复请求 */
    suspend fun addToShelf(
        sourceId: String,
        bookUrl: String,
        detail: RemoteBookDetail,
        toc: List<RemoteChapter>,
        fallback: SearchResult? = null,
        /**
         * true = 用户点了「加入书架」；false = 预览直读，落库供阅读器翻页，但不出现在书架上。
         * 退出阅读时再问要不要留下。已在架的书不会被降成试读。
         */
        inShelf: Boolean = true,
    ): Result<String> = try {
        Result.success(persist(sourceId, bookUrl, detail, toc, fallback, inShelf))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    /** 该书是否已在书架；在则返回 bookId */
    suspend fun shelfBookId(sourceId: String, bookUrl: String): String? =
        netBookId(sourceId, bookUrl).takeIf { bookDao.getById(it) != null }

    /** 从指定章节开始读：预览页点目录用 */
    /**
     * 预览页点了第 [chapterIndex] 章。书架上的那本可能是别的书源（甚至本地书）加的，
     * 预览目录的序号在它那里不通用：直接写序号会打开错的章，超出它的目录时进书即报错。
     * 所以按标题在那本书自己的目录里找回对应章；对不上就不动原进度。
     */
    suspend fun setReadPosition(bookId: String, chapterIndex: Int, chapterTitle: String) {
        val chapters = chapterDao.getByBook(bookId)
        val index = chapters.firstOrNull { it.index == chapterIndex && it.title == chapterTitle }?.index
            ?: TitleMatch.alignChapter(
                chapterTitle, chapterIndex, chapters,
                titleOf = { it.title },
                indexOf = { it.index },
            )?.index
            ?: return
        bookDao.updateProgress(bookId, index, 0, System.currentTimeMillis())
    }

    private suspend fun persist(
        sourceId: String,
        bookUrl: String,
        detail: RemoteBookDetail,
        toc: List<RemoteChapter>,
        fallback: SearchResult?,
        inShelf: Boolean,
    ): String {
        check(toc.isNotEmpty()) { "目录解析为空" }
        val bookId = netBookId(sourceId, bookUrl)
        val now = System.currentTimeMillis()
        val existing = bookDao.getById(bookId)
        val flags = resolveShelfFlags(
            existingInShelf = existing?.inShelf,
            existingDeleted = existing?.deleted,
            requestedInShelf = inShelf,
        )
        val (readIndex, readOffset) = alignProgress(bookId, existing, toc)
        val entities = buildToc(bookId, toc)
        // 书行与目录一起落库：中途被杀不会留下「有书行没目录」的半成品
        db.withWriteTransaction {
            bookDao.upsert(
                BookEntity(
                    id = bookId,
                    type = BookType.NET,
                    title = detail.title.ifBlank { fallback?.title.orEmpty() },
                    author = detail.author ?: fallback?.author ?: "",
                    coverPath = detail.coverUrl ?: fallback?.coverUrl,
                    intro = (detail.intro ?: fallback?.intro)?.htmlToPlainText(),
                    sourceId = sourceId,
                    bookUrl = bookUrl,
                    tocUrl = detail.tocUrl.ifBlank { bookUrl },
                    latestChapterTitle = toc.lastOrNull()?.title,
                    totalChapters = toc.size,
                    readChapterIndex = readIndex,
                    readCharOffset = readOffset,
                    readAt = existing?.readAt ?: 0,
                    addedAt = existing?.addedAt ?: now,
                    // 只有在架/墓碑状态真变了（新加、删过又加回来）才算一次用户修改、盖新时间戳。
                    // 已在架的书再从预览进一次也盖 now 的话，这一行就会带着本机的旧分组/隐藏
                    // 在 WebDAV 合并里赢过别的设备刚做的修改（合并按 updatedAt 做 LWW）。
                    updatedAt = existing
                        ?.takeIf { it.inShelf == flags.inShelf && it.deleted == flags.deleted }
                        ?.updatedAt
                        ?: now,
                    // upsert 是**整行**替换：这里没列出来的列会被写回默认值。下面这些是用户
                    // 和追更攒下来的状态，跟「这本书从哪个源抓的」无关，重新入库必须原样带过来 ——
                    // 否则分组掉回未分组、隐藏的书自己冒回书架、红点被抹平。
                    // 兄弟方法 changeSource 用 fresh.copy 天然保住了它们，就这条路径漏了。
                    // 书级变量例外：它跟着书源走，这一趟详情刚抓出来的才算数；书源这次没存变量就沿用旧的
                    variable = SourceVariables.encode(detail.variables).ifEmpty { existing?.variable.orEmpty() },
                    groupName = existing?.groupName ?: "",
                    hidden = existing?.hidden ?: false,
                    newChapterCount = existing?.newChapterCount ?: 0,
                    deleted = flags.deleted,
                    inShelf = flags.inShelf,
                )
            )
            replaceToc(bookId, entities)
        }
        return bookId
    }

    /** 刷新目录（追更）。返回**新增的章节数**（0 = 已经是最新的） */
    suspend fun refreshToc(book: BookEntity, rule: BookSourceRule): Result<Int> = try {
        val toc = engine.getToc(
            rule, book.tocUrl ?: book.bookUrl ?: error("缺少目录地址"),
            bookUrl = book.bookUrl.orEmpty(),
            bookTitle = book.title,
            bookAuthor = book.author.orEmpty(),
            // 加书架时随书存下的详情页变量：追更不重抓详情，不交回去目录规则的 @get 就全是空串
            variables = SourceVariables.decode(book.variable),
        )
        check(toc.isNotEmpty()) { "目录解析为空" }

        // 旧版 WebDAV 根本不带封面，同步下来的网络书 coverPath 一直是 null；
        // 下拉刷新只追目录、不抓详情，所以联网也永远补不回来。
        // 追更本来就要联网 —— 封面缺了就顺手拉一次详情补上，别逼用户重新加书架。
        // 详情失败不影响追更本身（封面可有可无，目录才是这次要的）。
        // 这一趟也是网络往返，所以必须放在下面「重读最新行」**之前** —— 放在之后，
        // 这几秒里用户删书/隐藏/改分组/读了几页，都会被随后的整行 copy 抹回去（删掉的书复活）。
        val detailUrl = book.bookUrl
        val detail = if (book.coverPath.isNullOrBlank() && !detailUrl.isNullOrBlank()) {
            try {
                engine.getDetail(rule, detailUrl)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
        // 逐章查缓存是文件 IO，算好了再进事务
        val entities = buildToc(book.id, toc)

        var added = 0
        db.withWriteTransaction {
            // 网络往返可能长达数分钟，其间用户可能已经进书读了几页、删了书、改了分组。
            // 用传入的旧快照整行 copy 会把这些变化抹掉 —— 所有网络都走完之后、在写事务里重读一次为准。
            val fresh = bookDao.getById(book.id)
            // 这趟抓的是**快照那一刻的书源**的目录。书架追更是拿一份快照列表在后台慢慢跑的，
            // 这期间手动/自动换源、或 WebDAV 同步来别处的换源都可能先落了库：再把旧源的章节 URL、
            // 按旧目录对齐的进度写到新源的书行上，正文必然抓不出来 → 触发自动换源 → 又换一个源，来回乒乓。
            // 行没了（试读被丢弃）也一样：写下去就是一套没有书行的孤儿目录。
            // 身份变了就整趟作废，当成「没有新章节」—— 新源的目录由换源那条路径自己负责。
            if (fresh == null || fresh.deleted ||
                fresh.sourceId != book.sourceId || fresh.bookUrl != book.bookUrl || fresh.tocUrl != book.tocUrl
            ) {
                return@withWriteTransaction
            }
            // 追更同样要对齐进度：站点在前面插入公告章/防盗章后目录整体后移，沿用旧序号会直接跳章
            // （从前只有换源做了对齐，追更没做）。对齐要读旧目录，必须在 replaceToc 之前
            val (readIndex, readOffset) = alignProgress(fresh.id, fresh, toc)
            added = (toc.size - fresh.totalChapters).coerceAtLeast(0)
            val refreshed = fresh.copy(
                totalChapters = toc.size,
                latestChapterTitle = toc.lastOrNull()?.title,
                readChapterIndex = readIndex,
                readCharOffset = readOffset,
                coverPath = fresh.coverPath.takeUnless { it.isNullOrBlank() } ?: detail?.coverUrl ?: fresh.coverPath,
                intro = fresh.intro.takeUnless { it.isNullOrBlank() }
                    ?: detail?.intro?.htmlToPlainText()
                    ?: fresh.intro,
                // 累加而不是覆盖：连刷两次、第二次没新章，不该把第一次的 5 章抹成 0 ——
                // 红点记的是"自从你上次打开之后"，不是"自从上次刷新之后"
                newChapterCount = fresh.newChapterCount + added,
            )
            replaceToc(book.id, entities)
            // 一个字段都没变就别写库。否则书架的 observeAll 就整表重发一轮 ——
            // 下拉刷"没有新章节"时，全书架反复重组、转圈跟着掉帧。
            //
            // **不盖 updatedAt**。它是 WebDAV 合并书籍元数据（分组/隐藏/删除）的 LWW 裁决依据，
            // 而追更只是「站点上多了几章」，不是用户改了什么。从前这里盖了 now：另一台设备刚改过
            // 分组、还没同步上来，这台一追更，带着**旧分组**的这一行反而成了"更新的那一份"，
            // 同步时把对方的修改盖回去 —— 删掉的书复活、隐藏的书冒回书架。连载中的书几乎必踩。
            // 章数不靠它同步（合并取大），封面走「谁有算谁的」，都不需要这个时间戳背书。
            if (refreshed != fresh) bookDao.update(refreshed)
        }
        Result.success(added)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    /**
     * 目录条目（含 isCached 判定）。isCached 按章节 URL 而非序号：目录变动后序号会错位，缓存得跟着章节走。
     * cache.has 会做文件 stat，**在事务外**先算好，别把一堆磁盘 IO 圈进 DB 事务里拉长锁。
     */
    /** 逐章 stat 缓存文件、外加一次目录清理：几千章就是几千次文件 IO，书架/阅读页/预览都在主线程上调 */
    private suspend fun buildToc(bookId: String, toc: List<RemoteChapter>): List<ChapterEntity> =
        withContext(Dispatchers.IO) { buildTocOnIo(bookId, toc) }

    private fun buildTocOnIo(bookId: String, toc: List<RemoteChapter>): List<ChapterEntity> {
        cache.purgeLegacy(bookId)
        // 目录只列一次，别逐章 stat（见 ChapterContentCache.cachedChecker）
        val isCached = cache.cachedChecker(bookId)
        return toc.map {
            ChapterEntity(
                bookId, it.index, it.title,
                url = it.url,
                isCached = isCached(it.url),
                variable = it.variable,
            )
        }
    }

    /**
     * 换目录 = 先清后写，务必在事务里成对执行：中途被杀不能留下空目录或半套目录。
     *
     * 跟库里那份一模一样就不动：下拉追更绝大多数时候没有新章节，从前照样把几千行整本删掉重插 ——
     * 写一大片不说，章节表一失效，阅读页、详情页的目录流就各自把整本目录重查一遍。
     */
    private suspend fun replaceToc(bookId: String, entities: List<ChapterEntity>) {
        if (chapterDao.getByBook(bookId) == entities) return
        chapterDao.deleteByBook(bookId)
        chapterDao.upsertAll(entities)
    }

    /**
     * 目录换过之后重新定位阅读进度：按当前章节标题在新目录里找回它的新序号。
     * 标题能对上说明还是同一章，字符偏移仍然有效；对不上就只能按序号夹取，偏移已无意义。
     */
    private suspend fun alignProgress(
        bookId: String,
        book: BookEntity?,
        toc: List<RemoteChapter>,
    ): Pair<Int, Int> {
        if (book == null) return 0 to 0
        // 本机没有这一章的目录行（同步来的书目录不进备份，或进度来自追更更快的另一台设备）：
        // 没有旧标题可比，也就没有「目录错位」的证据，偏移照旧信任；只有序号被夹取时才作废。
        val oldTitle = chapterDao.get(bookId, book.readChapterIndex)?.title
            ?: return book.readChapterIndex.coerceIn(0, toc.lastIndex).let { index ->
                index to if (index == book.readChapterIndex) book.readCharOffset else 0
            }
        val matched = TitleMatch.alignChapter(
            oldTitle, book.readChapterIndex, toc,
            titleOf = { it.title },
            indexOf = { it.index },
        )
        return if (matched != null) matched.index to book.readCharOffset
        else book.readChapterIndex.coerceIn(0, toc.lastIndex) to 0
    }

    /**
     * 换源：新源的搜索结果 → 详情+目录 → 按当前阅读章节标题相似度对齐进度 → 更新原书。
     * 旧正文缓存整目录删除。
     */
    suspend fun changeSource(
        book: BookEntity,
        newResult: SearchResult,
        newRule: BookSourceRule,
        /**
         * 精确还原进度，供「撤销自动换源」用。
         *
         * 常规换源靠 [alignProgress] 按章节标题对齐，且把字符偏移抹成 0（换了站，同一章的分段
         * 与字数都不同，偏移没有意义）。但撤销是**回到原来那个源**，偏移在那儿是有效的 ——
         * 走对齐反而会把用户读到的位置弄丢，撤销就成了另一次破坏。
         */
        restore: ReadAnchor? = null,
        /**
         * 按这个位置（旧目录里的章号）去对齐，而不是库里存的进度。供自动换源用：
         * 用户是在**翻向**某一章时失败的，库里存的还是上一章 —— 按它对齐，换完源就被送回
         * 刚读完那章的开头。
         */
        alignFrom: ReadAnchor? = null,
    ): Result<Unit> = try {
        val (detail, toc) = fetchDetailAndToc(newRule, newResult.bookUrl)
        check(toc.isNotEmpty()) { "新书源目录为空" }

        withContext(Dispatchers.IO) { cache.clear(book.id) } // 旧源的正文缓存整本作废（成千个文件）
        val entities = buildToc(book.id, toc)
        db.withWriteTransaction {
            // 网络往返期间用户可能已翻了很多页，进度早不是传入快照那一刻的了。在写事务里重读最新行，
            // 既让 alignProgress 按**当前**章节标题对齐（否则换源后跳回进书时的章节），
            // 也避免整行 copy 把 readAt/红点/分组等抹回旧值。从前是在清缓存、算目录**之前**读的 ——
            // 那两步是成千次文件 IO，期间落库的进度/同步照样会被盖掉。
            // 行没了（试读在这期间被丢弃）就别写：给不存在的书写一套目录只会留下孤儿行。
            val fresh = bookDao.getById(book.id) ?: error("这本书已不在库里")
            val (newIndex, newOffset) = restore
                ?.let { it.chapterIndex.coerceIn(0, toc.lastIndex) to it.charOffset }
                ?: alignProgress(
                    fresh.id,
                    alignFrom?.let { fresh.copy(readChapterIndex = it.chapterIndex, readCharOffset = it.charOffset) }
                        ?: fresh,
                    toc,
                ).let { it.first to 0 }
            replaceToc(book.id, entities)
            val now = System.currentTimeMillis()
            bookDao.update(
                fresh.copy(
                    sourceId = newRule.id,
                    bookUrl = newResult.bookUrl,
                    tocUrl = detail.tocUrl.ifBlank { newResult.bookUrl },
                    // 旧源的变量对新源毫无意义（书号都不是一套），整张换成新源详情页存下的
                    variable = SourceVariables.encode(detail.variables),
                    totalChapters = toc.size,
                    latestChapterTitle = toc.lastOrNull()?.title,
                    readChapterIndex = newIndex,
                    readCharOffset = newOffset,
                    // 章号只在它所属的那个源里有意义：换源把进度换算进了新目录的坐标系，这本身就是一次
                    // 进度变动，readAt 必须跟着盖新。从前不盖：另一台设备同步时，书源按 updatedAt 取了这边的
                    // 新源，进度却因 readAt 打平留在它自己那份**旧源**的章号上 —— 新源前面多几章公告，
                    // 它就落后几章，还会把这个错位的进度再推回来。WebDAV 合并把「书源 + 进度」当成一个整体
                    // 按 readAt 裁决（见 BackupMerger.mergeBook），这里盖了新时间戳，换源才能连同进度一起赢。
                    readAt = now,
                    updatedAt = now,
                )
            )
        }
        Result.success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    /** 阅读位置的真身：第几章 + 章内第几个字 */
    data class ReadAnchor(val chapterIndex: Int, val charOffset: Int)

    private fun netBookId(sourceId: String, bookUrl: String): String =
        MessageDigest.getInstance("MD5").digest("$sourceId|$bookUrl".toByteArray()).toHex()
}

/** 试读入库 vs 正式加架时，inShelf / deleted 怎么落。 */
data class ShelfFlags(val inShelf: Boolean, val deleted: Boolean)

/**
 * 决定落库后的在架/墓碑状态。
 *
 * - 显式加架：一定上架，并抹掉墓碑（用户又把它加回来了）。
 * - 预览直读：新书不上架；已在架的不降成试读；若行还是墓碑，保持墓碑且不上架
 *   （删过的书试读不该自己冒回书架，退出时再问）。
 */
fun resolveShelfFlags(
    existingInShelf: Boolean?,
    existingDeleted: Boolean?,
    requestedInShelf: Boolean,
): ShelfFlags {
    val inShelf = when {
        requestedInShelf -> true
        existingInShelf == null -> false
        existingDeleted == true -> false
        else -> existingInShelf
    }
    val deleted = if (inShelf) false else (existingDeleted ?: false)
    return ShelfFlags(inShelf, deleted)
}
