package com.radium.inkwell.ui.nav

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.radium.inkwell.ui.bookshelf.BookshelfScreen
import com.radium.inkwell.ui.detail.BookDetailScreen
import com.radium.inkwell.ui.explore.ExploreScreen
import com.radium.inkwell.ui.preview.BookPreviewScreen
import com.radium.inkwell.ui.reader.ReaderScreen
import com.radium.inkwell.ui.search.SearchScreen
import com.radium.inkwell.ui.settings.SettingsScreen
import com.radium.inkwell.ui.settings.ThemeSettingsScreen
import com.radium.inkwell.ui.sourceedit.SourceEditScreen
import com.radium.inkwell.ui.sourcemanage.SourceManageScreen
import com.radium.inkwell.ui.webdav.WebDavSettingsScreen

@Composable
fun InkwellNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = BookshelfRoute) {
        composable<BookshelfRoute> {
            BookshelfScreen(
                onOpenBook = { navController.navigate(ReaderRoute(it)) },
                onOpenDetail = { navController.navigate(BookDetailRoute(it)) },
                onOpenSearch = { navController.navigate(SearchRoute()) },
                onOpenExplore = { navController.navigate(ExploreRoute) },
                onOpenSourceManage = { navController.navigate(SourceManageRoute) },
                onOpenSettings = { navController.navigate(SettingsRoute) },
            )
        }
        composable<BookDetailRoute> { entry ->
            val route = entry.toRoute<BookDetailRoute>()
            BookDetailScreen(
                bookId = route.bookId,
                onRead = { navController.navigate(ReaderRoute(route.bookId)) },
                onBack = { navController.popBackStack() },
            )
        }
        composable<ReaderRoute> { entry ->
            val route = entry.toRoute<ReaderRoute>()
            ReaderScreen(bookId = route.bookId, onExit = { navController.popBackStack() })
        }
        composable<SearchRoute> {
            SearchScreen(
                onBack = { navController.popBackStack() },
                onOpenPreview = { navController.navigate(BookPreviewRoute.of(it)) },
            )
        }
        composable<ExploreRoute> {
            ExploreScreen(
                onBack = { navController.popBackStack() },
                onOpenSourceManage = { navController.navigate(SourceManageRoute) },
                onOpenPreview = { navController.navigate(BookPreviewRoute.of(it)) },
            )
        }
        composable<BookPreviewRoute> { entry ->
            val route = entry.toRoute<BookPreviewRoute>()
            BookPreviewScreen(
                result = route.result,
                onRead = { navController.navigate(ReaderRoute(it)) },
                onBack = { navController.popBackStack() },
            )
        }
        composable<SourceManageRoute> {
            SourceManageScreen(
                onBack = { navController.popBackStack() },
                onEdit = { navController.navigate(SourceEditRoute(it)) },
            )
        }
        composable<SourceEditRoute> { entry ->
            val route = entry.toRoute<SourceEditRoute>()
            SourceEditScreen(
                sourceId = route.sourceId,
                onBack = { navController.popBackStack() },
            )
        }
        composable<SettingsRoute> {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenWebDav = { navController.navigate(WebDavSettingsRoute) },
                onOpenTheme = { navController.navigate(ThemeSettingsRoute) },
            )
        }
        composable<ThemeSettingsRoute> {
            ThemeSettingsScreen(onBack = { navController.popBackStack() })
        }
        composable<WebDavSettingsRoute> {
            WebDavSettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
