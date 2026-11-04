package com.radium.inkwell.ui.rss

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.RssFeed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radium.inkwell.core.source.rss.RssArticle
import com.radium.inkwell.core.util.htmlToPlainText
import com.radium.inkwell.ui.components.SquircleCorners
import com.radium.inkwell.ui.components.AppIconButton
import com.radium.inkwell.ui.components.AppScaffold
import com.radium.inkwell.ui.components.AppTopBar
import com.radium.inkwell.ui.components.BookCover
import com.radium.inkwell.ui.components.ChipRow
import com.radium.inkwell.ui.components.ContentListDefaults
import com.radium.inkwell.ui.components.ContentListItem
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.ui.components.EmptyState
import com.radium.inkwell.ui.components.ErrorState
import com.radium.inkwell.ui.components.LoadingState
import com.radium.inkwell.ui.components.groupedCardItem
import com.radium.inkwell.ui.components.horizontalOnly
import com.radium.inkwell.ui.components.pageScroll
import com.radium.inkwell.ui.components.rememberAppTopBarScroll
import com.radium.inkwell.ui.components.settingsPageColor
import com.radium.inkwell.ui.components.topBarScroll
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Refresh

@Composable
fun RssArticlesScreen(
    sourceId: String,
    onBack: () -> Unit,
    onOpenArticle: (RssArticle) -> Unit,
    viewModel: RssArticlesViewModel,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val pageColor = settingsPageColor()
    val topBarScroll = rememberAppTopBarScroll()
    AppScaffold(
        modifier = Modifier.topBarScroll(topBarScroll),
        containerColor = pageColor,
        topBar = {
            AppTopBar(
                state.sourceName,
                topBarScroll,
                onBack = onBack,
                containerColor = pageColor,
                actions = {
                    AppIconButton(onClick = viewModel::refresh) {
                        Icon(MiuixIcons.Refresh, contentDescription = "刷新")
                    }
                },
                // 分类 chip 放进顶栏：文章列表从它下面滚过去、一起被毛玻璃盖住。
                // 只有真的分了类才显示 —— 单分类的源不该被一行没得选的 chip 占掉屏幕
                bottomContent = {
                    if (state.sorts.size > 1) {
                        ChipRow(
                            options = state.sorts.map { it.title },
                            selectedIndex = state.currentSort,
                            onSelect = viewModel::selectSort,
                            contentPadding = PaddingValues(horizontal = Dimens.listHorizontal),
                        )
                    }
                },
            )
        },
    ) { padding ->
        when {
            state.loading -> LoadingState(Modifier.padding(padding))
            // 从前这里只甩一行字，连重试都没有 —— 用户唯一能做的是退出去再进来
            state.error != null -> ErrorState(
                message = state.error!!,
                modifier = Modifier.padding(padding),
                onRetry = viewModel::refresh,
            )
            // 抓成功了但一篇都没有（源的列表规则不匹配、或这个分类本来就空）：从前就是一片空白
            state.articles.isEmpty() -> EmptyState(
                icon = Icons.Outlined.RssFeed,
                title = "这里还没有文章",
                hint = "换个分类看看，或稍后刷新",
                actionLabel = "刷新",
                onAction = viewModel::refresh,
                modifier = Modifier.padding(padding),
            )
            // 只让开两侧：顶栏高度垫在列表开头（列表滚到毛玻璃顶栏下面），导航栏高度垫在末尾（见 AppScaffold）
            else -> LazyColumn(
                Modifier.fillMaxSize().padding(padding.horizontalOnly()).pageScroll(),
                contentPadding = PaddingValues(
                    top = padding.calculateTopPadding() + Dimens.gapS,
                    bottom = padding.calculateBottomPadding(),
                ),
                overscrollEffect = null,
            ) {
                // 一张卡装下整个列表（HyperOS 的灰底白卡），行与行之间不留缝
                itemsIndexed(state.articles, key = { _, it -> it.key }) { index, article ->
                    ArticleRow(
                        article,
                        onClick = { onOpenArticle(article) },
                        modifier = Modifier.groupedCardItem(index, state.articles.size),
                    )
                }
            }
        }
    }
}

@Composable
private fun ArticleRow(article: RssArticle, onClick: () -> Unit, modifier: Modifier = Modifier) {
    // 全文型 feed 的 description 就是整篇 HTML：每次重组都过一遍 Jsoup、现编一次正则，滑动就掉帧。
    // 记住它，同 BookListRow 的 caption
    val desc = remember(article.description) {
        article.description?.htmlToPlainText()
            ?.replace(WHITESPACE_RUN, " ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }
    val date = article.pubDate?.takeIf { it.isNotBlank() }
    ContentListItem(
        onClick = onClick,
        modifier = modifier,
        // 底与圆角由外面的卡（groupedCardItem）画，行本身透明、直角
        colors = ContentListDefaults.plainColors(),
        corners = SquircleCorners.Square,
        trailingContent = article.image?.takeIf { it.isNotBlank() }?.let { url ->
            {
                BookCover(
                    title = article.title,
                    coverModel = url,
                    modifier = Modifier.size(
                        width = Dimens.coverThumbWidth,
                        height = Dimens.coverThumbHeight,
                    ),
                    placeholderChars = 2,
                    // 文章标题是这一行的标题，配图不再进读屏
                    contentDescription = null,
                )
            }
        },
        supportingContent = if (desc != null || date != null) {
            {
                Column {
                    if (desc != null) {
                        Text(desc, style = ContentListDefaults.supportingStyle, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (date != null) {
                        Text(date, style = ContentListDefaults.overlineStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        } else {
            null
        },
        content = {
            Text(article.title, style = ContentListDefaults.titleStyle, maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
    )
}

private val WHITESPACE_RUN = Regex("\\s+")
