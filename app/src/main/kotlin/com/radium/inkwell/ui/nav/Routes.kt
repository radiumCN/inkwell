package com.radium.inkwell.ui.nav

import com.radium.inkwell.core.source.SearchResult
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
object BookshelfRoute

@Serializable
data class BookDetailRoute(val bookId: String)

/**
 * 网络书籍预览（未入库）：看简介/目录，再决定加书架或直接读。
 *
 * 整条搜索结果都带过去：不少 JSON API 书源的「详情页」其实是目录接口，解析不出书名/作者/封面
 * （书名搜索时就给过了），得靠它回落。内容含 `/ ? &`，作为 path 参数会被切断，故 Base64 传递。
 */
@Serializable
data class BookPreviewRoute(val resultsArg: String) {
    /** 同一本书在各个书源下的搜索结果；预览页靠它换源 */
    val results: List<SearchResult> get() = ROUTE_JSON.decodeFromString(decodeArg(resultsArg))

    companion object {
        fun of(results: List<SearchResult>) =
            BookPreviewRoute(encodeArg(ROUTE_JSON.encodeToString(results)))
    }
}

private val ROUTE_JSON = Json { ignoreUnknownKeys = true }

private fun encodeArg(s: String): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray(Charsets.UTF_8))

private fun decodeArg(s: String): String =
    String(Base64.getUrlDecoder().decode(s), Charsets.UTF_8)

@Serializable
data class ReaderRoute(val bookId: String)

@Serializable
data class SearchRoute(val initialQuery: String? = null)

@Serializable
object ExploreRoute

@Serializable
object RssSourceRoute

@Serializable
data class RssArticlesRoute(val sourceId: String)

/**
 * 文章阅读。整条文章随导航带过去 —— 为了看一篇文章把整个列表再抓一遍毫无道理，
 * 而且不少源的摘要（description）本来就是全文，重抓反而丢了它。
 * 内容含 `/ ? &`，作为 path 参数会被切断，故 Base64 传递。
 */
@Serializable
data class RssArticleRoute(val argsArg: String) {
    val args: com.radium.inkwell.ui.rss.RssArticleArgs
        get() = ROUTE_JSON.decodeFromString(decodeArg(argsArg))

    companion object {
        fun of(args: com.radium.inkwell.ui.rss.RssArticleArgs) =
            RssArticleRoute(encodeArg(ROUTE_JSON.encodeToString(args)))
    }
}

@Serializable
object ReplaceRuleRoute

@Serializable
object SourceManageRoute

@Serializable
data class SourceEditRoute(val sourceId: String? = null)

@Serializable
object SettingsRoute

@Serializable
object ThemeSettingsRoute

@Serializable
object WebDavSettingsRoute
