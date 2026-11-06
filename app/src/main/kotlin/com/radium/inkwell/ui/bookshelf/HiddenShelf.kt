package com.radium.inkwell.ui.bookshelf

import com.radium.inkwell.core.source.SearchResult
import com.radium.inkwell.data.db.entity.BookEntity
import com.radium.inkwell.data.prefs.AppPrefs
import com.radium.inkwell.data.repo.BookRepository
import com.radium.inkwell.data.repo.bookKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * 搜索 / 发现 / 预览认「已在书架」用的索引：按 (书名, 作者) 认同一本书，也按 (书源, 地址) 认**就是这一条**。
 *
 * 光按书名作者不够：书架上那本的书名作者是入库时定的。从前入库取详情页的写法（现在以列表为准，
 * 见 bookNameAndAuthor），详情页作者取出空串、或写成「某某 著」的书，列表上那一行永远对不上 ——
 * 点完「加入」提示「已加入书架」，按钮照旧是「加入」（被指出过）。(书源, 地址) 就是入库主键的来源
 * （netBookId），从哪一行加的，那一行一定认得出来；老库里已经存歪了的书也一样。
 */
data class ShelfIndex(
    private val keys: Set<Pair<String, String>> = emptySet(),
    /** 网络书的 (sourceId, bookUrl)；本地书没有，不进来 */
    private val urls: Set<Pair<String, String>> = emptySet(),
) {
    fun contains(title: String, author: String?, sourceId: String, bookUrl: String): Boolean =
        bookKey(title, author) in keys || (sourceId to bookUrl) in urls

    operator fun contains(result: SearchResult): Boolean =
        contains(result.title, result.author, result.sourceId, result.bookUrl)
}

/**
 * 搜索 / 发现 / 预览用来认「已在书架」的索引（见 [ShelfIndex]）。
 *
 * 隐藏区上了锁（[AppPrefs.hiddenRequireAuth]）时，**隐藏的书一律不算在架**。从前这几处直接用
 * [BookRepository.shelfKeys]，它连隐藏的书一起算：搜一下书名，结果行上就亮着「已加入」、预览页写着
 * 「已在书架」—— 锁着的隐藏区就这么被一次搜索绕开了，等于替旁人确认「这本书藏在这儿」。
 *
 * 没上锁时照旧算在架：那种隐藏只是把书从列表里收起来，不防人，搜出来显示「已加入」才不让人困惑。
 */
internal fun searchableShelf(
    books: List<BookEntity>,
    hiddenLocked: Boolean,
): ShelfIndex {
    val visible = books.filter { !(hiddenLocked && it.hidden) }
    return ShelfIndex(
        keys = visible.mapTo(HashSet()) { bookKey(it.title, it.author) },
        urls = visible.mapNotNullTo(HashSet()) { b ->
            val sourceId = b.sourceId ?: return@mapNotNullTo null
            val bookUrl = b.bookUrl ?: return@mapNotNullTo null
            sourceId to bookUrl
        },
    )
}

/** [searchableShelf] 的实时版：书架变动、或隐藏区锁的开关一拨，标记都跟着变 */
internal fun searchableShelf(
    bookRepo: BookRepository,
    appPrefs: AppPrefs,
): Flow<ShelfIndex> =
    combine(bookRepo.books, appPrefs.hiddenRequireAuth) { books, locked ->
        searchableShelf(books, locked)
    }

/**
 * 这个搜索词会不会把上锁隐藏区里的书露出来 —— 会的就不进搜索历史，已在历史里的也不显示。
 *
 * 和 [searchableShelf] 同一个理由：搜索页不经验证就能打开，历史里摆着「藏起来的那本书」的书名，
 * 等于把锁着的隐藏区替旁人念了一遍。所以按**包含**而不是相等来判：搜「斗破」找「斗破苍穹」，
 * 或者整个书名带上作者一起输，都一样暴露了。作者也算 —— 搜作者名同样指向那本书。
 * 宁可多拦：拦错了只是少记一条历史，放过了就是隐私泄露。
 *
 * 没上锁时一律不拦，理由同 [searchableShelf]。
 */
internal fun revealsHiddenBook(
    keyword: String,
    books: List<BookEntity>,
    hiddenLocked: Boolean,
): Boolean {
    if (!hiddenLocked) return false
    val k = keyword.trim().lowercase()
    if (k.isEmpty()) return false
    fun overlaps(field: String): Boolean {
        val f = field.trim().lowercase()
        // 空字段一定得先排除：任何字符串都 contains("")，一本没填作者的隐藏书会把所有词都拦下
        return f.isNotEmpty() && (f.contains(k) || k.contains(f))
    }
    return books.any { it.hidden && (overlaps(it.title) || overlaps(it.author)) }
}
