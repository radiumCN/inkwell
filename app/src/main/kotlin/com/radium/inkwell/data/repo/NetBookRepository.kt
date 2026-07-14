package com.radium.inkwell.data.repo

import com.radium.inkwell.core.source.BookSourceEngine
import com.radium.inkwell.core.source.BookSourceRule
import com.radium.inkwell.core.source.RemoteBookDetail
import com.radium.inkwell.core.source.RemoteChapter
import com.radium.inkwell.core.source.SearchResult
import com.radium.inkwell.data.db.dao.BookDao
import com.radium.inkwell.data.db.dao.ChapterDao
import com.radium.inkwell.data.db.entity.BookEntity
import com.radium.inkwell.data.db.entity.BookType
import com.radium.inkwell.data.db.entity.ChapterEntity
import java.security.MessageDigest

/** 网络书：加书架、刷新目录、换源 */
class NetBookRepository(
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
        val toc = engine.getToc(rule, detail.tocUrl.ifBlank { bookUrl })
        return detail to toc
    }

    /** 搜索结果 → 详情 + 目录 → 入库，返回 bookId */
    suspend fun addToShelf(result: SearchResult, rule: BookSourceRule): Result<String> = runCatching {
        val (detail, toc) = fetchDetailAndToc(rule, result.bookUrl)
        persist(rule.id, result.bookUrl, detail, toc, fallback = result)
    }

    /** 详情与目录已在预览页抓到时直接入库，避免重复请求 */
    suspend fun addToShelf(
        sourceId: String,
        bookUrl: String,
        detail: RemoteBookDetail,
        toc: List<RemoteChapter>,
        fallback: SearchResult? = null,
    ): Result<String> = runCatching {
        persist(sourceId, bookUrl, detail, toc, fallback)
    }

    /** 该书是否已在书架；在则返回 bookId */
    suspend fun shelfBookId(sourceId: String, bookUrl: String): String? =
        netBookId(sourceId, bookUrl).takeIf { bookDao.getById(it) != null }

    /** 从指定章节开始读：预览页点目录用 */
    suspend fun setReadPosition(bookId: String, chapterIndex: Int) {
        bookDao.updateProgress(bookId, chapterIndex, 0, System.currentTimeMillis())
    }

    private suspend fun persist(
        sourceId: String,
        bookUrl: String,
        detail: RemoteBookDetail,
        toc: List<RemoteChapter>,
        fallback: SearchResult?,
    ): String {
        check(toc.isNotEmpty()) { "目录解析为空" }
        val bookId = netBookId(sourceId, bookUrl)
        val now = System.currentTimeMillis()
        val existing = bookDao.getById(bookId)
        val (readIndex, readOffset) = alignProgress(bookId, existing, toc)
        bookDao.upsert(
            BookEntity(
                id = bookId,
                type = BookType.NET,
                title = detail.title.ifBlank { fallback?.title.orEmpty() },
                author = detail.author ?: fallback?.author ?: "",
                coverPath = detail.coverUrl ?: fallback?.coverUrl,
                intro = detail.intro ?: fallback?.intro,
                sourceId = sourceId,
                bookUrl = bookUrl,
                tocUrl = detail.tocUrl.ifBlank { bookUrl },
                latestChapterTitle = toc.lastOrNull()?.title,
                totalChapters = toc.size,
                readChapterIndex = readIndex,
                readCharOffset = readOffset,
                readAt = existing?.readAt ?: 0,
                addedAt = existing?.addedAt ?: now,
                updatedAt = now,
            )
        )
        writeToc(bookId, toc)
        return bookId
    }

    /** 刷新目录（追更） */
    suspend fun refreshToc(book: BookEntity, rule: BookSourceRule): Result<Int> = runCatching {
        val toc = engine.getToc(rule, book.tocUrl ?: book.bookUrl ?: error("缺少目录地址"))
        check(toc.isNotEmpty()) { "目录解析为空" }
        // 追更同样要对齐进度：站点在前面插入公告章/防盗章后目录整体后移，
        // 沿用旧序号会直接跳章（从前只有换源做了对齐，追更没做）
        val (readIndex, readOffset) = alignProgress(book.id, book, toc)
        writeToc(book.id, toc)
        bookDao.update(
            book.copy(
                totalChapters = toc.size,
                latestChapterTitle = toc.lastOrNull()?.title,
                readChapterIndex = readIndex,
                readCharOffset = readOffset,
                updatedAt = System.currentTimeMillis(),
            )
        )
        toc.size
    }

    /** isCached 按章节 URL 判定：目录变动后序号会错位，缓存得跟着章节走 */
    private suspend fun writeToc(bookId: String, toc: List<RemoteChapter>) {
        cache.purgeLegacy(bookId)
        chapterDao.deleteByBook(bookId)
        chapterDao.upsertAll(
            toc.map {
                ChapterEntity(
                    bookId, it.index, it.title,
                    url = it.url,
                    isCached = cache.has(bookId, it.url),
                    variable = it.variable,
                )
            }
        )
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
        val oldTitle = chapterDao.get(bookId, book.readChapterIndex)?.title
            ?: return book.readChapterIndex.coerceIn(0, toc.lastIndex) to 0
        val normalized = normalizeTitle(oldTitle)
        val matched = toc.filter { normalizeTitle(it.title) == normalized }
            .minByOrNull { kotlin.math.abs(it.index - book.readChapterIndex) }
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
    ): Result<Unit> = runCatching {
        val (detail, toc) = fetchDetailAndToc(newRule, newResult.bookUrl)
        check(toc.isNotEmpty()) { "新书源目录为空" }
        // 换源只能沿用章节序号：换了站，同一章的正文分段与字数都不同，字符偏移没有意义
        val (newIndex, _) = alignProgress(book.id, book, toc)

        cache.clear(book.id) // 旧源的正文缓存整本作废
        writeToc(book.id, toc)
        bookDao.update(
            book.copy(
                sourceId = newRule.id,
                bookUrl = newResult.bookUrl,
                tocUrl = detail.tocUrl.ifBlank { newResult.bookUrl },
                totalChapters = toc.size,
                latestChapterTitle = toc.lastOrNull()?.title,
                readChapterIndex = newIndex,
                readCharOffset = 0,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    private fun normalizeTitle(t: String): String =
        t.replace(Regex("[\\s　（）()【】\\[\\]:：、,，.。]"), "")

    private fun netBookId(sourceId: String, bookUrl: String): String {
        val digest = MessageDigest.getInstance("MD5").digest("$sourceId|$bookUrl".toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
