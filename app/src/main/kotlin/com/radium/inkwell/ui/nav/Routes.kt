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
        get() {
            val base: com.radium.inkwell.ui.rss.RssArticleArgs =
                ROUTE_JSON.decodeFromString(decodeArg(argsArg))
            // 正文（description）不进导航参数，从进程内暂存按链接取回；取不到（进程被杀）就为 null
            return base.copy(
                description = base.description
                    ?: com.radium.inkwell.ui.rss.RssArticleContent.get(base.link),
            )
        }

    companion object {
        fun of(args: com.radium.inkwell.ui.rss.RssArticleArgs): RssArticleRoute {
            // 全文正文放进程内暂存，导航参数只带短字段，避免撑爆 Binder 事务上限
            com.radium.inkwell.ui.rss.RssArticleContent.put(args.link, args.description)
            return RssArticleRoute(
                encodeArg(ROUTE_JSON.encodeToString(args.copy(description = null))),
            )
        }
    }
}

@Serializable
object ReplaceRuleRoute

@Serializable
object SourceManageRoute

/** 书源详情（只读）；书源不在应用内编辑，只能导入 */
@Serializable
data class SourceDetailRoute(val sourceId: String)

@Serializable
object SettingsRoute

@Serializable
object ThemeSettingsRoute

@Serializable
object WebDavSettingsRoute
