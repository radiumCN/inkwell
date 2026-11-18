package com.radium.inkwell.ui.search

import com.radium.inkwell.core.source.BookSourceRule
import com.radium.inkwell.core.source.SearchPage
import com.radium.inkwell.core.source.SearchResult
import com.radium.inkwell.data.repo.TitleMatch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Locale

/** 与全局书籍去重分开：一个源仍可翻到新页，即使这一页的书其他源都已经返回过。 */
internal class SearchPageHistory {
    private data class PageItem(val url: String, val book: Pair<String, String>)
    private val pagesBySource = mutableMapOf<String, MutableSet<Set<PageItem>>>()

    fun canContinue(sourceId: String, page: SearchPage?): Boolean {
        if (page == null || !page.hasMore || page.items.isEmpty()) return false
        val signature = page.items.mapTo(mutableSetOf()) { PageItem(it.bookUrl.trim(), it.searchKey) }
        return pagesBySource.getOrPut(sourceId) { mutableSetOf() }.add(signature)
    }

    fun clear() = pagesBySource.clear()
}

/** 只给可确认是同一本书的精确命中累计站点；同一站点导入多个规则只算一个。 */
internal class ExactBookMatches(private val keyword: String) {
    private val hostsByAuthor = mutableMapOf<String, MutableSet<String>>()

    fun record(source: BookSourceRule, items: List<SearchResult>): Boolean {
        val host = source.baseUrl.toHttpUrlOrNull()?.host?.removePrefix("www.") ?: return false
        return items.any { item ->
            if (!SearchScheduling.isExactTitle(item.title, keyword)) return@any false
            val author = normalizeAuthor(item.author.orEmpty()).lowercase(Locale.ROOT)
            if (author.isEmpty()) return@any false
            val hosts = hostsByAuthor.getOrPut(author) { mutableSetOf() }
            hosts += host
            hosts.size >= SearchScheduling.EARLY_STOP_SOURCES
        }
    }
}

private val AUTHOR_PREFIX = Regex("^作者\\s*[:：]\\s*")

internal fun normalizeAuthor(author: String): String =
    TitleMatch.normalize(author.trim().replaceFirst(AUTHOR_PREFIX, ""))
