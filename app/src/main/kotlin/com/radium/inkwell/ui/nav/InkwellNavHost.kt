package com.radium.inkwell.ui.nav

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import com.radium.inkwell.ui.components.Motion
import com.radium.inkwell.ui.components.animationsEnabled
import androidx.navigation.NavDestination.Companion.hasRoute
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
import com.radium.inkwell.ui.sourcedetail.SourceDetailScreen
import com.radium.inkwell.ui.replace.ReplaceRuleScreen
import com.radium.inkwell.ui.rss.RssArticleScreen
import com.radium.inkwell.ui.rss.RssArticlesScreen
import com.radium.inkwell.ui.rss.RssSourceScreen
import com.radium.inkwell.ui.sourcemanage.SourceManageScreen
import com.radium.inkwell.ui.webdav.WebDavSettingsScreen

@Composable
fun InkwellNavHost() {
    val navController = rememberNavController()
    // 统一带 launchSingleTop：快速双击一个入口不会把同一个目的地压两次栈（返回要按两下）
    fun go(route: Any) = navController.navigate(route) { launchSingleTop = true }
    // 从前没配转场，走的是 navigation-compose 的默认淡入淡出（约 700ms，且进出同速）——
    // 与翻页的 180~320ms 完全脱节，而且违反「退场比入场快」。
    // 改成 shared-axis X：前进从右侧滑入，返回向右滑出，方向就说明了"进"和"退"。
    val animate = animationsEnabled()
    NavHost(
        navController = navController,
        startDestination = BookshelfRoute,
        // 整屏转场用「不透明方向滑动」而非交叉淡入。进入的页面若带 alpha 淡入会在过渡中半透明，
        // 跨主题时（亮色书架 → 深色阅读页）底层会透上来，正文里透出书封，观感割裂。
        // 常规页：新页不透明地从右整幅推入，旧页做 1/4 视差左移；全程无 alpha，任何主题组合都干净。
        // 阅读页：单独走垂直轴 —— 上滑推入（“翻开进入阅读”）、返回下滑退出（“合上书”），
        //   表达它是一个沉浸式、自带主题的特殊层级。旧页此时原地不动被盖住（None），不做横向视差，
        //   避免“上来竖的、旁边横的”打架。
        enterTransition = {
            if (!animate) fadeIn(tween(0))
            else if (targetState.destination.hasRoute<ReaderRoute>())
                slideInVertically(Motion.navEnterSpec()) { it } // 阅读页从下方升起，不透明
            else slideInHorizontally(Motion.navEnterSpec()) { it }
        },
        exitTransition = {
            if (!animate) fadeOut(tween(0))
            else if (targetState.destination.hasRoute<ReaderRoute>())
                ExitTransition.None // 旧页原地保持，被升起的阅读页盖住
            else slideOutHorizontally(Motion.navExitSpec()) { -it / 4 }
        },
        popEnterTransition = {
            if (!animate) fadeIn(tween(0))
            else if (initialState.destination.hasRoute<ReaderRoute>())
                EnterTransition.None // 阅读页下滑离开，底下的页原样露出
            else slideInHorizontally(Motion.navEnterSpec()) { -it / 4 }
        },
        popExitTransition = {
            if (!animate) fadeOut(tween(0))
            else if (initialState.destination.hasRoute<ReaderRoute>())
                slideOutVertically(Motion.navExitSpec()) { it } // 阅读页向下滑出，不透明
            else slideOutHorizontally(Motion.navExitSpec()) { it }
        },
    ) {
        composable<BookshelfRoute> {
            BookshelfScreen(
                onOpenBook = { go(ReaderRoute(it)) },
                onOpenDetail = { go(BookDetailRoute(it)) },
                onOpenSearch = { go(SearchRoute()) },
                onOpenExplore = { go(ExploreRoute) },
                onOpenSourceManage = { go(SourceManageRoute) },
                onOpenSettings = { go(SettingsRoute) },
            )
        }
        composable<BookDetailRoute> { entry ->
            val route = entry.toRoute<BookDetailRoute>()
            BookDetailScreen(
                bookId = route.bookId,
                onRead = { go(ReaderRoute(route.bookId)) },
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
                onOpenPreview = { go(BookPreviewRoute.of(it)) },
            )
        }
        composable<ExploreRoute> {
            ExploreScreen(
                onBack = { navController.popBackStack() },
                onOpenSourceManage = { go(SourceManageRoute) },
                onOpenPreview = { go(BookPreviewRoute.of(it)) },
            )
        }
        composable<BookPreviewRoute> { entry ->
            val route = entry.toRoute<BookPreviewRoute>()
            BookPreviewScreen(
                results = route.results,
                onRead = { go(ReaderRoute(it)) },
                onBack = { navController.popBackStack() },
            )
        }
        composable<SourceManageRoute> {
            SourceManageScreen(
                onBack = { navController.popBackStack() },
                onOpen = { go(SourceDetailRoute(it)) },
            )
        }
        composable<SourceDetailRoute> { entry ->
            val route = entry.toRoute<SourceDetailRoute>()
            SourceDetailScreen(
                sourceId = route.sourceId,
                onBack = { navController.popBackStack() },
            )
        }
        composable<SettingsRoute> {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenWebDav = { go(WebDavSettingsRoute) },
                onOpenTheme = { go(ThemeSettingsRoute) },
                onOpenSources = { go(SourceManageRoute) },
                onOpenReplaceRules = { go(ReplaceRuleRoute) },
                onOpenRss = { go(RssSourceRoute) },
                onOpenFeedback = { go(FeedbackRoute) },
            )
        }
        composable<RssSourceRoute> {
            RssSourceScreen(
                onBack = { navController.popBackStack() },
                onOpenSource = { go(RssArticlesRoute(it)) },
            )
        }
        composable<RssArticlesRoute> { entry ->
            val route = entry.toRoute<RssArticlesRoute>()
            RssArticlesScreen(
                sourceId = route.sourceId,
                onBack = { navController.popBackStack() },
                onOpenArticle = { article ->
                    go(
                        RssArticleRoute.of(
                            com.radium.inkwell.ui.rss.RssArticleArgs(
                                sourceId = article.sourceId,
                                title = article.title,
                                link = article.link,
                                description = article.description,
                                pubDate = article.pubDate,
                            )
                        )
                    )
                },
            )
        }
        composable<RssArticleRoute> { entry ->
            val route = entry.toRoute<RssArticleRoute>()
            RssArticleScreen(args = route.args, onBack = { navController.popBackStack() })
        }
        composable<ReplaceRuleRoute> {
            ReplaceRuleScreen(onBack = { navController.popBackStack() })
        }
        composable<ThemeSettingsRoute> {
            ThemeSettingsScreen(onBack = { navController.popBackStack() })
        }
        composable<WebDavSettingsRoute> {
            WebDavSettingsScreen(onBack = { navController.popBackStack() })
        }
        composable<FeedbackRoute> {
            com.radium.inkwell.ui.feedback.FeedbackScreen(onBack = { navController.popBackStack() })
        }
    }
}
