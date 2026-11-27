package com.radium.inkwell.ui.search

import com.radium.inkwell.core.source.BookSourceRule
import com.radium.inkwell.core.source.SearchPage
import com.radium.inkwell.core.source.SearchResult
import com.radium.inkwell.data.repo.TitleMatch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.text.Normalizer
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

    fun record(source: BookSourceRule, items: Collection<SearchResult>): Boolean {
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
    TitleMatch.normalize(author.withoutAuthorPrefix())

private fun String.withoutAuthorPrefix(): String = trim().replaceFirst(AUTHOR_PREFIX, "")

private val SEARCH_SEPARATORS = Regex("[\\s\\p{Z}《》·・･]")

internal fun normalizeSearchMatch(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFKC).replace(SEARCH_SEPARATORS, "").lowercase(Locale.ROOT)

internal enum class SearchMatch {
    EXACT_TITLE, EXACT_AUTHOR, PARTIAL_TITLE, PARTIAL_AUTHOR, NONE,
}

internal fun SearchResult.matchSearch(normalizedKeyword: String): SearchMatch {
    if (normalizedKeyword.isEmpty()) return SearchMatch.NONE
    val normalizedTitle = normalizeSearchMatch(title)
    if (normalizedTitle == normalizedKeyword) return SearchMatch.EXACT_TITLE
    val normalizedAuthor = normalizeSearchMatch(author.orEmpty().withoutAuthorPrefix())
    return when {
        normalizedAuthor == normalizedKeyword -> SearchMatch.EXACT_AUTHOR
        normalizedKeyword in normalizedTitle -> SearchMatch.PARTIAL_TITLE
        normalizedKeyword in normalizedAuthor -> SearchMatch.PARTIAL_AUTHOR
        else -> SearchMatch.NONE
    }
}

/** 保留原词匹配，同时接受该书源地址脚本实际提取的书名或作者。 */
internal fun SearchPage.matchingResults(normalizedKeyword: String): Map<SearchResult, SearchMatch> {
    val rewritten = rewrittenKeyword?.let(::normalizeSearchMatch)?.takeIf { it != normalizedKeyword }.orEmpty()
    return buildMap {
        for (item in items) {
            val match = minOf(item.matchSearch(normalizedKeyword), item.matchSearch(rewritten))
            if (match != SearchMatch.NONE) put(item, match)
        }
    }
}
