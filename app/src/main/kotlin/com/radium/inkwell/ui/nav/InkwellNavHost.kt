package com.radium.inkwell.ui.nav

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.radium.inkwell.ui.bookshelf.BookshelfScreen
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.ui.components.Motion
import com.radium.inkwell.ui.components.animationsEnabled
import com.radium.inkwell.ui.detail.BookDetailScreen
import com.radium.inkwell.ui.explore.ExploreScreen
import com.radium.inkwell.ui.preview.BookPreviewScreen
import com.radium.inkwell.ui.reader.ReaderScreen
import com.radium.inkwell.ui.replace.ReplaceRuleScreen
import com.radium.inkwell.ui.rss.RssArticleScreen
import com.radium.inkwell.ui.rss.RssArticlesScreen
import com.radium.inkwell.ui.rss.RssSourceScreen
import com.radium.inkwell.ui.search.SearchScreen
import com.radium.inkwell.ui.settings.AboutSettingsScreen
import com.radium.inkwell.ui.settings.AppearanceSettingsScreen
import com.radium.inkwell.ui.settings.ReadingSettingsScreen
import com.radium.inkwell.ui.settings.SettingsScreen
import com.radium.inkwell.ui.settings.ThemeSettingsScreen
import com.radium.inkwell.ui.settings.UpdateSettingsScreen
import com.radium.inkwell.ui.sourcedetail.SourceDetailScreen
import com.radium.inkwell.ui.sourcemanage.SourceManageScreen
import com.radium.inkwell.ui.webdav.WebDavSettingsScreen

/**
 * Navigation 3 + Material 3 Adaptive 入口。
 *
 * - 返回栈自持（[rememberNavBackStack]），前进/返回见 [InkwellNavigator]
 * - 宽屏：书架|详情、设置|二级 用 [ListDetailSceneStrategy]（[NavPaneGroup.sceneKey] 区分）；阅读器全屏
 * - 转场：常规 shared-axis X；进阅读 shared-axis Z（从书封原点缩放、不叠 alpha）
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun InkwellNavHost() {
    val backStack = rememberNavBackStack(BookshelfRoute)
    val nav = remember(backStack) { InkwellNavigator(backStack) }

    val containerSize = LocalWindowInfo.current.containerSize
    LaunchedEffect(containerSize) { nav.openOrigin.value = TransformOrigin.Center }

    val animate = animationsEnabled()
    val windowAdaptiveInfo = currentWindowAdaptiveInfoV2()
    val directive = remember(windowAdaptiveInfo) {
        calculatePaneScaffoldDirective(windowAdaptiveInfo)
            .copy(horizontalPartitionSpacerSize = 0.dp)
    }
    val listDetailStrategy = rememberListDetailSceneStrategy<NavKey>(directive = directive)
    val dualPane = directive.maxHorizontalPartitions > 1

    val defaultPush = remember(animate) {
        if (!animate) {
            ContentTransform(fadeIn(tween(0)), fadeOut(tween(0)))
        } else {
            slideInHorizontally(Motion.navEnterSpec()) { it } togetherWith
                slideOutHorizontally(Motion.navExitSpec()) { -it / 4 }
        }
    }
    val defaultPop = remember(animate) {
        if (!animate) {
            ContentTransform(fadeIn(tween(0)), fadeOut(tween(0)))
        } else {
            slideInHorizontally(Motion.navEnterSpec()) { -it / 4 } togetherWith
                slideOutHorizontally(Motion.navExitSpec()) { it }
        }
    }

    val readerMeta = remember(animate, nav) {
        NavDisplay.transitionSpec {
            if (!animate) {
                ContentTransform(fadeIn(tween(0)), fadeOut(tween(0)))
            } else {
                scaleIn(
                    Motion.readerEnterSpec(),
                    initialScale = Motion.READER_OPEN_SCALE,
                    transformOrigin = nav.openOrigin.value,
                ) togetherWith ExitTransition.None
            }
        } + NavDisplay.popTransitionSpec {
            if (!animate) {
                ContentTransform(fadeIn(tween(0)), fadeOut(tween(0)))
            } else {
                EnterTransition.None togetherWith scaleOut(
                    Motion.readerExitSpec(),
                    targetScale = Motion.READER_OPEN_SCALE,
                    transformOrigin = nav.openOrigin.value,
                )
            }
        }
    }

    NavDisplay(
        backStack = backStack,
        onBack = { nav.back() },
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        ),
        sceneStrategies = listOf(listDetailStrategy),
        transitionSpec = { defaultPush },
        popTransitionSpec = { defaultPop },
        predictivePopTransitionSpec = { _ -> defaultPop },
        entryProvider = entryProvider {
            entry<BookshelfRoute>(
                metadata = ListDetailSceneStrategy.listPane(
                    sceneKey = NavPaneGroup.BOOKSHELF,
                    detailPlaceholder = { PanePlaceholder("选择一本书查看详情") },
                ),
            ) {
                BookshelfScreen(
                    onOpenBook = { id, origin -> nav.openBook(id, origin) },
                    onOpenDetail = { nav.go(BookDetailRoute(it)) },
                    onOpenSearch = { nav.go(SearchRoute()) },
                    onOpenExplore = { nav.go(ExploreRoute) },
                    onOpenSettings = { nav.go(SettingsRoute) },
                )
            }
            entry<BookDetailRoute>(
                metadata = ListDetailSceneStrategy.detailPane(sceneKey = NavPaneGroup.BOOKSHELF),
            ) { route ->
                BookDetailScreen(
                    bookId = route.bookId,
                    showBack = !dualPane,
                    onRead = { nav.openBook(route.bookId, TransformOrigin.Center) },
                    onBack = { nav.back() },
                )
            }
            entry<ReaderRoute>(metadata = readerMeta) { route ->
                ReaderScreen(bookId = route.bookId, onExit = { nav.back() })
            }
            entry<SearchRoute> {
                SearchScreen(
                    onBack = { nav.back() },
                    onOpenPreview = { nav.go(BookPreviewRoute.of(it)) },
                )
            }
            entry<ExploreRoute> {
                ExploreScreen(
                    onBack = { nav.back() },
                    onOpenSourceManage = { nav.go(SourceManageRoute) },
                    onOpenPreview = { nav.go(BookPreviewRoute.of(it)) },
                )
            }
            entry<BookPreviewRoute> { route ->
                BookPreviewScreen(
                    results = route.results,
                    onRead = { nav.openBook(it, TransformOrigin.Center) },
                    onBack = { nav.back() },
                )
            }
            entry<SourceManageRoute>(
                metadata = ListDetailSceneStrategy.detailPane(sceneKey = NavPaneGroup.SETTINGS),
            ) {
                SourceManageScreen(
                    onBack = { nav.back() },
                    onOpen = { nav.go(SourceDetailRoute(it)) },
                )
            }
            entry<SourceDetailRoute>(
                metadata = ListDetailSceneStrategy.detailPane(sceneKey = NavPaneGroup.SETTINGS),
            ) { route ->
                SourceDetailScreen(
                    sourceId = route.sourceId,
                    onBack = { nav.back() },
                )
            }
            entry<SettingsRoute>(
                metadata = ListDetailSceneStrategy.listPane(
                    sceneKey = NavPaneGroup.SETTINGS,
                    detailPlaceholder = { PanePlaceholder("选择一项设置") },
                ),
            ) {
                SettingsScreen(
                    onBack = { nav.back() },
                    onOpenAppearance = { nav.go(AppearanceSettingsRoute) },
                    onOpenReading = { nav.go(ReadingSettingsRoute) },
                    onOpenRss = { nav.go(RssSourceRoute) },
                    onOpenWebDav = { nav.go(WebDavSettingsRoute) },
                    onOpenUpdate = { nav.go(UpdateSettingsRoute) },
                    onOpenAbout = { nav.go(AboutSettingsRoute) },
                )
            }
            entry<AppearanceSettingsRoute>(
                metadata = ListDetailSceneStrategy.detailPane(sceneKey = NavPaneGroup.SETTINGS),
            ) {
                AppearanceSettingsScreen(
                    onBack = { nav.back() },
                    onOpenTheme = { nav.go(ThemeSettingsRoute) },
                )
            }
            entry<ReadingSettingsRoute>(
                metadata = ListDetailSceneStrategy.detailPane(sceneKey = NavPaneGroup.SETTINGS),
            ) {
                ReadingSettingsScreen(
                    onBack = { nav.back() },
                    onOpenSources = { nav.go(SourceManageRoute) },
                    onOpenReplaceRules = { nav.go(ReplaceRuleRoute) },
                )
            }
            entry<UpdateSettingsRoute>(
                metadata = ListDetailSceneStrategy.detailPane(sceneKey = NavPaneGroup.SETTINGS),
            ) {
                UpdateSettingsScreen(onBack = { nav.back() })
            }
            entry<AboutSettingsRoute>(
                metadata = ListDetailSceneStrategy.detailPane(sceneKey = NavPaneGroup.SETTINGS),
            ) {
                AboutSettingsScreen(
                    onBack = { nav.back() },
                    onOpenFeedback = { nav.go(FeedbackRoute) },
                    onOpenDisclaimer = { nav.go(DisclaimerRoute) },
                )
            }
            entry<RssSourceRoute>(
                metadata = ListDetailSceneStrategy.detailPane(sceneKey = NavPaneGroup.SETTINGS),
            ) {
                RssSourceScreen(
                    onBack = { nav.back() },
                    onOpenSource = { nav.go(RssArticlesRoute(it)) },
                )
            }
            entry<RssArticlesRoute>(
                metadata = ListDetailSceneStrategy.detailPane(sceneKey = NavPaneGroup.SETTINGS),
            ) { route ->
                RssArticlesScreen(
                    sourceId = route.sourceId,
                    onBack = { nav.back() },
                    onOpenArticle = { article ->
                        nav.go(
                            RssArticleRoute.of(
                                com.radium.inkwell.ui.rss.RssArticleArgs(
                                    sourceId = article.sourceId,
                                    title = article.title,
                                    link = article.link,
                                    description = article.description,
                                    pubDate = article.pubDate,
                                ),
                            ),
                        )
                    },
                )
            }
            entry<RssArticleRoute>(
                metadata = ListDetailSceneStrategy.detailPane(sceneKey = NavPaneGroup.SETTINGS),
            ) { route ->
                RssArticleScreen(args = route.args, onBack = { nav.back() })
            }
            entry<ReplaceRuleRoute>(
                metadata = ListDetailSceneStrategy.detailPane(sceneKey = NavPaneGroup.SETTINGS),
            ) {
                ReplaceRuleScreen(onBack = { nav.back() })
            }
            entry<ThemeSettingsRoute>(
                metadata = ListDetailSceneStrategy.detailPane(sceneKey = NavPaneGroup.SETTINGS),
            ) {
                ThemeSettingsScreen(onBack = { nav.back() })
            }
            entry<WebDavSettingsRoute>(
                metadata = ListDetailSceneStrategy.detailPane(sceneKey = NavPaneGroup.SETTINGS),
            ) {
                WebDavSettingsScreen(onBack = { nav.back() })
            }
            entry<FeedbackRoute>(
                metadata = ListDetailSceneStrategy.detailPane(sceneKey = NavPaneGroup.SETTINGS),
            ) {
                com.radium.inkwell.ui.feedback.FeedbackScreen(onBack = { nav.back() })
            }
            entry<DisclaimerRoute>(
                metadata = ListDetailSceneStrategy.detailPane(sceneKey = NavPaneGroup.SETTINGS),
            ) {
                com.radium.inkwell.ui.legal.DisclaimerScreen(onBack = { nav.back() })
            }
        },
    )
}

@Composable
private fun PanePlaceholder(text: String) {
    Box(
        Modifier.fillMaxSize().padding(Dimens.screenPadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
