package com.radium.inkwell.ui.rss

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.radium.inkwell.core.model.ContentElement
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.ui.components.PrimaryButton
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * 文章阅读。
 *
 * 不走小说的分页阅读器：文章是短的、带图的、结构化的，硬塞进翻页排版里只会难看。
 * 直接滚动着看就好。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RssArticleScreen(
    args: RssArticleArgs,
    onBack: () -> Unit,
    viewModel: RssArticleViewModel = koinViewModel { parametersOf(args) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    fun openInBrowser() {
        if (state.link.isBlank()) return
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(state.link)))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("文章", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (state.link.isNotBlank()) {
                        IconButton(onClick = ::openInBrowser) {
                            Icon(
                                Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = "浏览器打开原文",
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (state.loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.rowHorizontal),
        ) {
            Text(
                state.title,
                Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.headlineSmall,
            )
            state.pubDate?.let {
                Text(
                    it,
                    Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            Spacer(Modifier.height(16.dp))

            state.elements.forEach { el ->
                when (el) {
                    is ContentElement.Heading -> Text(
                        el.text,
                        Modifier.padding(vertical = 8.dp),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    is ContentElement.Paragraph -> Text(
                        el.text,
                        Modifier.padding(vertical = 6.dp),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    is ContentElement.Image -> AsyncImage(
                        model = el.resourceId,
                        contentDescription = el.alt,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    )
                    ContentElement.Divider -> Spacer(Modifier.height(12.dp))
                }
            }

            if (state.needsBrowser) {
                // 解析不出正文不是错误：很多源本来就只给标题+链接
                Text(
                    state.error ?: "这篇文章没有可解析的正文（订阅源只给了标题和链接）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 「看原文」永远给出口，而不是只在正文解析失败时才给。
            // 拿真实 feed 试过：BBC、少数派、HN 的摘要就是**一句话**，不是全文 ——
            // 正文"解析成功"了，用户却只看到孤零零一行，然后无处可去。
            if (state.link.isNotBlank()) {
                Spacer(Modifier.height(24.dp))
                PrimaryButton(
                    text = "在浏览器中打开原文",
                    onClick = ::openInBrowser,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(48.dp))
        }
    }
}
