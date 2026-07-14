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
data class BookPreviewRoute(val resultArg: String) {
    val result: SearchResult get() = ROUTE_JSON.decodeFromString(decodeArg(resultArg))

    companion object {
        fun of(result: SearchResult) = BookPreviewRoute(encodeArg(ROUTE_JSON.encodeToString(result)))
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
object SourceManageRoute

@Serializable
data class SourceEditRoute(val sourceId: String? = null)

@Serializable
object SettingsRoute

@Serializable
object ThemeSettingsRoute

@Serializable
object WebDavSettingsRoute
