package com.radium.inkwell.ui.nav

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalWindowInfo
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
    // 进书放大动画的原点：点哪本书，阅读页就从哪儿长出来。
    // 由调用点直接给出（书架量到封面位置，别处给 Center），不在转场里反推 —— 转场只管播。
    // 已知的将就：书架按「最近阅读」排序，返回时这本书多半已挪去第一格，退场仍缩向进书时的
    // 旧格子。可接受的原因是退场只从 1.0 缩到 0.85 —— origin 只是「往哪边收」的方向暗示，
    // 不是精确落进某个格子，偏一格肉眼几乎无感；不为此建一套「回查当前位置」的机器。
    val openOrigin = remember { mutableStateOf(TransformOrigin.Center) }
    fun openBook(bookId: String, origin: TransformOrigin) {
        // 阅读页已在栈顶时整个忽略：入场动画那 200ms 里四周还露着书架，能点中第二本。
        // 放行的话 launchSingleTop 会吞掉导航（页面还是第一本），origin 却已被改写 ——
        // 看着 A 进来、返回却缩向 B。origin 与导航必须同生共死。
        if (navController.currentDestination?.hasRoute<ReaderRoute>() == true) return
        openOrigin.value = origin
        go(ReaderRoute(bookId))
    }
    // 旋屏/折叠后窗口尺寸一变，记下的「窗口比例坐标」就对不上原书位置（书架也已回流）——
    // 退场会缩向一个不相干的地方。尺寸变了就退化为中心缩回：宁可平淡，不可指错。
    val containerSize = LocalWindowInfo.current.containerSize
    LaunchedEffect(containerSize) { openOrigin.value = TransformOrigin.Center }
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
        // 阅读页走的是 M3 motion 的 **shared axis Z**（纵深轴）—— 没有共享容器时表达「父 → 子钻入」
        // 的标准模式：进入的页面缩放放大，离开的页面原地留在下面。相对 M3 原样有三处**有意偏离**，
        // 每一处都在解决一个具体问题，别"顺手改回规范"：
        //   1) 缩放原点在**被点的那本书**上，而非 M3 的屏幕中心 —— 中心展开就丢了「从这本书长出来」。
        //   2) **只缩放、不叠 alpha**，而 M3 的 shared axis 带淡入 —— 带 alpha 会让过渡中的正文半透明，
        //      跨主题时（亮色书架 → 深色阅读页）底层书封透进正文里。这是项目里踩过的坑。
        //   3) 时长 200ms，短于 M3 全屏转场建议的 300ms+ —— 为的是不跟进书首帧排版抢时间
        //      （见 ReaderViewModel.PREFETCH_LEAD_IN_MS）。
        // 起始缩放 0.85 则是**符合**规范的：M3 shared axis Z 的入场就是从 0.8 放大到 1.0。
        // （注意别拿 fade through 的 92% 当参照 —— 那个模式用于互不相关的目的地，比如切 tab。）
        //
        // 至于 M3 对「列表项 → 详情」的首选 container transform：试过四版，全部失败。
        // 书封是 3:4 缩略图，撑到全屏必糊（默认封面的书名会成满屏巨字）；而且共享元素的 overlay
        // 图层会吃掉元素自身的 3D 变换。历史见 0.1.6-beta.3~7 的提交。
        //
        // 具体到画面：**从被点那本书的位置放大展开**，像桌面点图标、窗口从图标处长出来。
        //   长大的是「阅读页本身」，不是书封被撑大 —— 这是它和之前那版容器变换的根本区别：
        //   那版把 3:4 的封面缩略图 scaleToBounds 到全屏，默认封面的书名被放成满屏巨字，越清晰越难看。
        //   这里书封原地不动（旧页 None），只有阅读页在放大，不存在任何被拉伸的素材。
        //   原点由书架传上来（Modifier.onGloballyPositioned 量到的封面中心换算成屏幕比例）；
        //   详情页 / 搜索预览没有源位置，退回 TransformOrigin.Center，从中间展开。
        //
        //   只缩放、**不叠 alpha**：带 alpha 的话过渡中正文半透明，跨主题时（亮色书架 → 深色阅读页）
        //   底层书封会透进正文里。纯缩放时阅读页始终不透明，四周露出的书架是"窗口正在长大"的
        //   应有观感，不是穿帮。
        enterTransition = {
            if (!animate) fadeIn(tween(0))
            else if (targetState.destination.hasRoute<ReaderRoute>())
                scaleIn(
                    Motion.readerEnterSpec(),
                    initialScale = Motion.READER_OPEN_SCALE,
                    transformOrigin = openOrigin.value,
                )
            else slideInHorizontally(Motion.navEnterSpec()) { it }
        },
        exitTransition = {
            if (!animate) fadeOut(tween(0))
            else if (targetState.destination.hasRoute<ReaderRoute>())
                ExitTransition.None // 书架原地保持，被长大的阅读页盖住
            else slideOutHorizontally(Motion.navExitSpec()) { -it / 4 }
        },
        popEnterTransition = {
            if (!animate) fadeIn(tween(0))
            else if (initialState.destination.hasRoute<ReaderRoute>())
                EnterTransition.None // 阅读页缩回去，底下的书架原样露出
            else slideInHorizontally(Motion.navEnterSpec()) { -it / 4 }
        },
        popExitTransition = {
            if (!animate) fadeOut(tween(0))
            else if (initialState.destination.hasRoute<ReaderRoute>())
                // 返回：缩回它当初长出来的那个位置。用 readerExitSpec 而不是通用的 navExitSpec ——
                // 退场要比入场快、曲线要和入场配对，理由见 Motion.readerExitSpec。
                scaleOut(
                    Motion.readerExitSpec(),
                    targetScale = Motion.READER_OPEN_SCALE,
                    transformOrigin = openOrigin.value,
                )
            else slideOutHorizontally(Motion.navExitSpec()) { it }
        },
    ) {
        composable<BookshelfRoute> {
            BookshelfScreen(
                onOpenBook = { id, origin -> openBook(id, origin) },
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
                // 详情页没有源位置，从屏幕中间展开
                onRead = { openBook(route.bookId, TransformOrigin.Center) },
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
                // 搜索预览同理，从中间展开
                onRead = { openBook(it, TransformOrigin.Center) },
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
