package com.radium.inkwell.ui.rss

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radium.inkwell.core.source.rss.RssArticle
import com.radium.inkwell.ui.components.BookCover
import com.radium.inkwell.ui.components.ContentListDefaults
import com.radium.inkwell.ui.components.ContentListItem
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.ui.components.ErrorState
import com.radium.inkwell.ui.components.LoadingState
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RssArticlesScreen(
    sourceId: String,
    onBack: () -> Unit,
    onOpenArticle: (RssArticle) -> Unit,
    viewModel: RssArticlesViewModel,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(state.sourceName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // 只有真的分了类才显示 —— 单分类的源不该被一行没得选的 chip 占掉屏幕
            if (state.sorts.size > 1) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = Dimens.listHorizontal),
                    horizontalArrangement = Arrangement.spacedBy(Dimens.gapS),
                ) {
                    state.sorts.forEachIndexed { i, sort ->
                        FilterChip(
                            selected = state.currentSort == i,
                            onClick = { viewModel.selectSort(i) },
                            label = { Text(sort.title) },
                        )
                    }
                }
            }

            when {
                state.loading -> LoadingState()
                // 从前这里只甩一行字，连重试都没有 —— 用户唯一能做的是退出去再进来
                state.error != null -> ErrorState(
                    message = state.error!!,
                    onRetry = viewModel::refresh,
                )
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = ContentListDefaults.listContentPadding(),
                    verticalArrangement = Arrangement.spacedBy(ContentListDefaults.ListSpacing),
                ) {
                    items(state.articles, key = { it.key }) { article ->
                        ArticleRow(article, onClick = { onOpenArticle(article) })
                    }
                }
            }
        }
    }
}

@Composable
private fun ArticleRow(article: RssArticle, onClick: () -> Unit) {
    val desc = article.description?.stripHtml()?.takeIf { it.isNotBlank() }
    val date = article.pubDate?.takeIf { it.isNotBlank() }
    ContentListItem(
        onClick = onClick,
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
                )
            }
        },
        supportingContent = if (desc != null || date != null) {
            {
                Column {
                    if (desc != null) {
                        Text(desc, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (date != null) {
                        Text(date, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        } else {
            null
        },
        content = {
            Text(article.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
    )
}

private fun String.stripHtml(): String =
    replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim()
