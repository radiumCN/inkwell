package com.radium.inkwell.ui.nav

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.radium.inkwell.ui.components.LocalNavAnimatedScope
import com.radium.inkwell.ui.components.LocalSharedTransitionScope
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

/**
 * 开书容器变换要跨「书架 → 阅读页」两个路由，共享作用域必须比 NavHost 更外层，
 * 所以这里只负责包一层 [SharedTransitionLayout] 并把作用域下发，导航图本身在 [InkwellNavGraph]。
 * 拆成两个函数纯粹是为了不把整张导航图往里缩进一级。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun InkwellNavHost() {
    SharedTransitionLayout {
        CompositionLocalProvider(LocalSharedTransitionScope provides this) {
            InkwellNavGraph()
        }
    }
}

@Composable
private fun InkwellNavGraph() {
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
        // 阅读页分两种情况：
        //   a) 书架 → 阅读页：走「开书」容器变换（见 Modifier.bookOpenContainer）—— 被点的那本书封面
        //      就地放大成整页阅读器，返回收回原格子。此时整页转场必须让位（None），否则「整页在上抬」
        //      和「封面在放大」两套位移会打架。
        //   b) 详情页 / 搜索预览 → 阅读页：没有可放大的源封面，退回「小幅上抬」——从屏高 1/8 处快速
        //      抬入，用 M3「强调减速」曲线收尾（见 Motion.readerEnterSpec），落定像被接住。
        //   为什么不整屏上滑：那条整幅位移恰好和进书首帧的排版+GC 抢同一段时间（见
        //   ReaderViewModel.PREFETCH_LEAD_IN_MS 的让位注释），动画和首帧互相拖累；小幅+短时长把预算让给首帧。
        //   为什么整页不淡入/不缩放：过渡中带 alpha 或缩放<1 会从边缘/半透明透出底层书架的书封，跨主题割裂。
        //   （容器变换内部的淡入是另一回事：它只发生在正在放大的容器里，封面化成书页正是要的效果。）
        enterTransition = {
            if (!animate) fadeIn(tween(0))
            else if (targetState.destination.hasRoute<ReaderRoute>())
                // 从书架来 → 让位给开书变换；从别处来 → 小幅上抬兜底
                if (initialState.destination.hasRoute<BookshelfRoute>()) EnterTransition.None
                else slideInVertically(Motion.readerEnterSpec()) { it / 8 }
            else slideInHorizontally(Motion.navEnterSpec()) { it }
        },
        exitTransition = {
            if (!animate) fadeOut(tween(0))
            else if (targetState.destination.hasRoute<ReaderRoute>())
                ExitTransition.None // 旧页原地保持：书架要留在原处，封面才有地方"飞出去"
            else slideOutHorizontally(Motion.navExitSpec()) { -it / 4 }
        },
        popEnterTransition = {
            if (!animate) fadeIn(tween(0))
            else if (initialState.destination.hasRoute<ReaderRoute>())
                EnterTransition.None // 书架原样露出，等封面收回它的格子
            else slideInHorizontally(Motion.navEnterSpec()) { -it / 4 }
        },
        popExitTransition = {
            if (!animate) fadeOut(tween(0))
            else if (initialState.destination.hasRoute<ReaderRoute>())
                // 回书架 → 让位给收回封面的变换；回别处 → 小幅下滑，与入场对称
                if (targetState.destination.hasRoute<BookshelfRoute>()) ExitTransition.None
                else slideOutVertically(Motion.navExitSpec()) { it / 8 }
            else slideOutHorizontally(Motion.navExitSpec()) { it }
        },
    ) {
        composable<BookshelfRoute> {
            // 只有书架和阅读页参与开书变换，所以只给这两个路由下发入退场作用域
            CompositionLocalProvider(LocalNavAnimatedScope provides this) {
                BookshelfScreen(
                    onOpenBook = { go(ReaderRoute(it)) },
                    onOpenDetail = { go(BookDetailRoute(it)) },
                    onOpenSearch = { go(SearchRoute()) },
                    onOpenExplore = { go(ExploreRoute) },
                    onOpenSourceManage = { go(SourceManageRoute) },
                    onOpenSettings = { go(SettingsRoute) },
                )
            }
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
            CompositionLocalProvider(LocalNavAnimatedScope provides this) {
                ReaderScreen(bookId = route.bookId, onExit = { navController.popBackStack() })
            }
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
