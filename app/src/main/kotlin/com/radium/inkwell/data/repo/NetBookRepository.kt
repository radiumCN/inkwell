package com.radium.inkwell.data.repo

import com.radium.inkwell.core.source.BookSourceEngine
import com.radium.inkwell.core.source.BookSourceRule
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

    /** 搜索结果 → 详情 + 目录 → 入库，返回 bookId */
    suspend fun addToShelf(result: SearchResult, rule: BookSourceRule): Result<String> = runCatching {
        val detail = engine.getDetail(rule, result.bookUrl)
        val tocUrl = detail.tocUrl.ifBlank { result.bookUrl }
        val toc = engine.getToc(rule, tocUrl)
        check(toc.isNotEmpty()) { "目录解析为空" }

        val bookId = netBookId(rule.id, result.bookUrl)
        val now = System.currentTimeMillis()
        bookDao.upsert(
            BookEntity(
                id = bookId,
                type = BookType.NET,
                title = detail.title.ifBlank { result.title },
                author = detail.author ?: result.author ?: "",
                coverPath = detail.coverUrl ?: result.coverUrl,
                intro = detail.intro ?: result.intro,
                sourceId = rule.id,
                bookUrl = result.bookUrl,
                tocUrl = tocUrl,
                latestChapterTitle = toc.lastOrNull()?.title,
                totalChapters = toc.size,
                addedAt = now,
                updatedAt = now,
            )
        )
        chapterDao.deleteByBook(bookId)
        chapterDao.upsertAll(toc.map { ChapterEntity(bookId, it.index, it.title, url = it.url) })
        bookId
    }

    /** 刷新目录（追更） */
    suspend fun refreshToc(book: BookEntity, rule: BookSourceRule): Result<Int> = runCatching {
        val toc = engine.getToc(rule, book.tocUrl ?: book.bookUrl ?: error("缺少目录地址"))
        check(toc.isNotEmpty()) { "目录解析为空" }
        val cached = chapterDao.getByBook(book.id).filter { it.isCached }.map { it.index }.toSet()
        chapterDao.deleteByBook(book.id)
        chapterDao.upsertAll(
            toc.map { ChapterEntity(book.id, it.index, it.title, url = it.url, isCached = it.index in cached) }
        )
        bookDao.update(
            book.copy(
                totalChapters = toc.size,
                latestChapterTitle = toc.lastOrNull()?.title,
                updatedAt = System.currentTimeMillis(),
            )
        )
        toc.size
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
        val detail = engine.getDetail(newRule, newResult.bookUrl)
        val tocUrl = detail.tocUrl.ifBlank { newResult.bookUrl }
        val toc = engine.getToc(newRule, tocUrl)
        check(toc.isNotEmpty()) { "新书源目录为空" }

        val oldChapters = chapterDao.getByBook(book.id)
        val currentTitle = oldChapters.getOrNull(book.readChapterIndex)?.title
        val newIndex = alignChapter(currentTitle, book.readChapterIndex, toc.map { it.title })

        cache.clear(book.id)
        chapterDao.deleteByBook(book.id)
        chapterDao.upsertAll(toc.map { ChapterEntity(book.id, it.index, it.title, url = it.url) })
        bookDao.update(
            book.copy(
                sourceId = newRule.id,
                bookUrl = newResult.bookUrl,
                tocUrl = tocUrl,
                totalChapters = toc.size,
                latestChapterTitle = toc.lastOrNull()?.title,
                readChapterIndex = newIndex,
                readCharOffset = 0,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    /** 章节对齐：先精确标题匹配（旧索引附近优先），再退化为按索引夹取 */
    private fun alignChapter(currentTitle: String?, oldIndex: Int, newTitles: List<String>): Int {
        if (currentTitle == null || newTitles.isEmpty()) return oldIndex.coerceIn(0, (newTitles.size - 1).coerceAtLeast(0))
        val normalized = normalizeTitle(currentTitle)
        val exact = newTitles.withIndex()
            .filter { normalizeTitle(it.value) == normalized }
            .minByOrNull { kotlin.math.abs(it.index - oldIndex) }
        if (exact != null) return exact.index
        return oldIndex.coerceIn(0, newTitles.size - 1)
    }

    private fun normalizeTitle(t: String): String =
        t.replace(Regex("[\\s　（）()【】\\[\\]:：、,，.。]"), "")

    private fun netBookId(sourceId: String, bookUrl: String): String {
        val digest = MessageDigest.getInstance("MD5").digest("$sourceId|$bookUrl".toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
