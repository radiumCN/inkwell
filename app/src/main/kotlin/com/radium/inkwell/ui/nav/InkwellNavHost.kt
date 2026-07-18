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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
    // 这次进书是不是「从书架点封面」进来的 —— 决定走开书容器变换还是小幅上抬兜底。
    //
    // 从前是在转场里用 hasRoute<BookshelfRoute>() 嗅探"上一个目的地是谁"，但那个判据没如预期命中
    // （0.1.6-beta.3 的开书动画不播，一半就栽在这），而且它推断的本来就是调用点**已经确知**的事。
    // 与其在转场里反推，不如让真正知道答案的入口直接说：书架点书置 true，详情页/搜索预览置 false。
    val openViaCover = remember { mutableStateOf(false) }
    fun openBook(bookId: String, viaCover: Boolean) {
        openViaCover.value = viaCover
        go(ReaderRoute(bookId))
    }
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
        //      就地放大成整页阅读器，返回收回原格子。此时整页自己不能再做位移（否则「整页在上抬」和
        //      「封面在放大」两套位移打架），但**也不能写 None** —— 零时长会把共享元素直接掐死。
        //      要用 Motion.holdEnter/holdExit 这种"空转"转场：视觉上什么都不做，只占住时间窗。
        //      （0.1.6-beta.3 的开书动画完全不播，就是栽在这里写了 None。）
        //   b) 详情页 / 搜索预览 → 阅读页：没有可放大的源封面，退回「小幅上抬」——从屏高 1/8 处快速
        //      抬入，用 M3「强调减速」曲线收尾（见 Motion.readerEnterSpec），落定像被接住。
        //   为什么不整屏上滑：那条整幅位移恰好和进书首帧的排版+GC 抢同一段时间（见
        //   ReaderViewModel.PREFETCH_LEAD_IN_MS 的让位注释），动画和首帧互相拖累；小幅+短时长把预算让给首帧。
        //   为什么整页不淡入/不缩放：过渡中带 alpha 或缩放<1 会从边缘/半透明透出底层书架的书封，跨主题割裂。
        //   （容器变换内部的淡入是另一回事：它只发生在正在放大的容器里，封面化成书页正是要的效果。）
        enterTransition = {
            if (!animate) fadeIn(tween(0))
            else if (targetState.destination.hasRoute<ReaderRoute>())
                // 走开书变换时用「空转」占住时间窗，**不能用 None**（零时长会把变换掐死，见 Motion.holdEnter）
                if (openViaCover.value) Motion.holdEnter()
                else slideInVertically(Motion.readerEnterSpec()) { it / 8 }
            else slideInHorizontally(Motion.navEnterSpec()) { it }
        },
        exitTransition = {
            if (!animate) fadeOut(tween(0))
            else if (targetState.destination.hasRoute<ReaderRoute>())
                // 书架必须在整段变换里一直活着且不透明：它既是封面"飞出去"的源，
                // 也是变换容器之外露出的背景。用 None 它当帧就被回收，源就没了。
                if (openViaCover.value) Motion.holdExit() else ExitTransition.None
            else slideOutHorizontally(Motion.navExitSpec()) { -it / 4 }
        },
        popEnterTransition = {
            if (!animate) fadeIn(tween(0))
            else if (initialState.destination.hasRoute<ReaderRoute>())
                // 书架原样露出，等封面收回它的格子 —— 同样要占住窗口
                if (openViaCover.value) Motion.holdEnter() else EnterTransition.None
            else slideInHorizontally(Motion.navEnterSpec()) { -it / 4 }
        },
        popExitTransition = {
            if (!animate) fadeOut(tween(0))
            else if (initialState.destination.hasRoute<ReaderRoute>())
                if (openViaCover.value) Motion.holdExit()
                else slideOutVertically(Motion.navExitSpec()) { it / 8 }
            else slideOutHorizontally(Motion.navExitSpec()) { it }
        },
    ) {
        composable<BookshelfRoute> {
            // 只有书架和阅读页参与开书变换，所以只给这两个路由下发入退场作用域
            CompositionLocalProvider(LocalNavAnimatedScope provides this) {
                BookshelfScreen(
                    // 书架点书 —— 唯一有源封面可放大的入口，走开书变换
                    onOpenBook = { openBook(it, viaCover = true) },
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
                // 详情页没有可放大的源封面，退回小幅上抬
                onRead = { openBook(route.bookId, viaCover = false) },
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
                // 搜索预览同理，没有源封面
                onRead = { openBook(it, viaCover = false) },
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
