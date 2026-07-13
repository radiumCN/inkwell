package com.radium.inkwell.ui.nav

import java.util.Base64
import kotlinx.serialization.Serializable

@Serializable
object BookshelfRoute

@Serializable
data class BookDetailRoute(val bookId: String)

/**
 * 网络书籍预览（未入库）：看简介/目录，再决定加书架或直接读。
 * sourceId 与 bookUrl 都可能含 `/ ? &`，作为 path 参数会被切断，故用 URL-safe Base64 传递。
 */
@Serializable
data class BookPreviewRoute(val sourceIdArg: String, val bookUrlArg: String) {
    val sourceId: String get() = decodeArg(sourceIdArg)
    val bookUrl: String get() = decodeArg(bookUrlArg)

    companion object {
        fun of(sourceId: String, bookUrl: String) =
            BookPreviewRoute(encodeArg(sourceId), encodeArg(bookUrl))
    }
}

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
