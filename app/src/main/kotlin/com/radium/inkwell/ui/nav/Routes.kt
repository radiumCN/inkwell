package com.radium.inkwell.ui.nav

import kotlinx.serialization.Serializable

@Serializable
object BookshelfRoute

@Serializable
data class BookDetailRoute(val bookId: String)

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
