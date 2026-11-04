package com.radium.inkwell.ui.rss

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.radium.inkwell.core.model.ContentElement
import com.radium.inkwell.ui.components.AppIconButton
import com.radium.inkwell.ui.components.AppScaffold
import com.radium.inkwell.ui.components.AppSnackbarHost
import com.radium.inkwell.ui.components.AppTopBar
import com.radium.inkwell.ui.components.CollectMessages
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.ui.components.LoadingState
import com.radium.inkwell.ui.components.MessageBus
import com.radium.inkwell.ui.components.NO_BROWSER_MESSAGE
import com.radium.inkwell.ui.components.PrimaryButton
import com.radium.inkwell.ui.components.SecondaryButton
import com.radium.inkwell.ui.components.horizontalOnly
import com.radium.inkwell.ui.components.openExternalLink
import com.radium.inkwell.ui.components.pageScroll
import com.radium.inkwell.ui.components.rememberAppTopBarScroll
import com.radium.inkwell.ui.components.settingsPageColor
import com.radium.inkwell.ui.components.topBarScroll
import com.radium.inkwell.ui.theme.AppShapes
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Forward
import top.yukonga.miuix.kmp.theme.MiuixTheme
/**
 * 文章阅读。
 *
 * 不走小说的分页阅读器：文章是短的、带图的、结构化的，硬塞进翻页排版里只会难看。
 * 直接滚动着看就好。
 */
@Composable
fun RssArticleScreen(
    args: RssArticleArgs,
    onBack: () -> Unit,
    viewModel: RssArticleViewModel,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val messages = remember { MessageBus() }
    val snackbar = remember { SnackbarHostState() }
    CollectMessages(messages, snackbar)

    fun openInBrowser() {
        if (state.link.isBlank()) return
        // 从前拉不起浏览器时静默吞掉：按钮点了没反应，用户只会以为没点中
        if (!context.openExternalLink(state.link)) messages.emit(NO_BROWSER_MESSAGE)
    }

    val pageColor = settingsPageColor()
    val topBarScroll = rememberAppTopBarScroll()
    AppScaffold(
        modifier = Modifier.topBarScroll(topBarScroll),
        containerColor = pageColor,
        snackbarHost = { AppSnackbarHost(snackbar) },
        topBar = {
            AppTopBar("文章", topBarScroll, onBack = onBack, containerColor = pageColor, actions = {
                if (state.link.isNotBlank()) {
                    AppIconButton(onClick = ::openInBrowser) {
                        Icon(
                            MiuixIcons.Forward,
                            contentDescription = "浏览器打开原文",
                        )
                    }
                }
            })
        },
    ) { padding ->
        if (state.loading) {
            LoadingState(Modifier.padding(padding))
            return@AppScaffold
        }

        // 懒加载：长文、多图的文章从前一次把全部段落和图片组合完才出第一屏。
        // 列表也让「上面的图加载出来、高度变了」不再把正在读的那段往下推 —— 位置锚在可见的那一项上
        val imageRatios = remember { mutableStateMapOf<String, Float>() }
        LazyColumn(
            Modifier
                .fillMaxSize()
                // 只让开两侧：顶栏高度垫在列表开头（正文滚到毛玻璃顶栏下面），导航栏高度垫在末尾（见 AppScaffold）
                .padding(padding.horizontalOnly())
                .pageScroll(),
            contentPadding = PaddingValues(
                start = Dimens.screenPadding,
                end = Dimens.screenPadding,
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding(),
            ),
            overscrollEffect = null,
        ) {
            item(key = "title", contentType = "title") {
                Column {
                    Text(
                        state.title,
                        Modifier.padding(top = Dimens.gapS),
                        style = MiuixTheme.textStyles.title2,
                    )
                    state.pubDate?.let {
                        Text(
                            it,
                            Modifier.padding(top = Dimens.gapXS),
                            style = MiuixTheme.textStyles.footnote1,
                            // 正文性小字走摘要色；outline / dividerLine 是给线的，浅色下读不清
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    Spacer(Modifier.height(Dimens.gapL))
                }
            }

            // 正文元素按位置当 key：抓一次就定了，不会增删重排
            itemsIndexed(
                state.elements,
                key = { i, _ -> "el$i" },
                contentType = { _, el -> el::class },
            ) { _, el ->
                when (el) {
                    is ContentElement.Heading -> Text(
                        el.text,
                        Modifier.padding(vertical = Dimens.gapS),
                        style = MiuixTheme.textStyles.headline2,
                        fontWeight = FontWeight.Medium,
                    )
                    is ContentElement.Paragraph -> Text(
                        el.text,
                        Modifier.padding(vertical = Dimens.gapS),
                        style = MiuixTheme.textStyles.body1,
                    )
                    is ContentElement.Image -> ArticleImage(el, imageRatios)
                    ContentElement.Divider -> Spacer(Modifier.height(Dimens.gapM))
                }
            }

            item(key = "footer", contentType = "footer") {
                Column {
                    // 抓取失败一律说出来、并给重试：从前错误只在「有原文链接」时才显示，
                    // 订阅源不存在、或没有链接时抓失败，页面就只剩一个标题
                    val error = state.error
                    if (error != null) {
                        Text(
                            "正文没能取到：$error",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(Dimens.gapM))
                        SecondaryButton(text = "重试", onClick = viewModel::load, modifier = Modifier.fillMaxWidth())
                    } else if (state.needsBrowser) {
                        // 解析不出正文不是错误：很多源本来就只给标题+链接
                        Text(
                            "这篇文章没有可解析的正文（订阅源只给了标题和链接）",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }

                    // 「看原文」永远给出口，而不是只在正文解析失败时才给。
                    // 拿真实 feed 试过：BBC、少数派、HN 的摘要就是**一句话**，不是全文 ——
                    // 正文"解析成功"了，用户却只看到孤零零一行，然后无处可去。
                    if (state.link.isNotBlank()) {
                        Spacer(Modifier.height(Dimens.gapXL))
                        PrimaryButton(
                            text = "在浏览器中打开原文",
                            onClick = ::openInBrowser,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Spacer(Modifier.height(Dimens.touchTarget))
                }
            }
        }
    }
}

/**
 * 正文里的一张图。没加载出来之前先按 4:3 占位，加载出来按真实宽高比铺满宽度；
 * 比例记进 [ratios]（页面级），这一项滑出视口再滑回来时直接按真实比例排，不再先占位再跳一下。
 * 加载失败就不占地方 —— 留一块永远不会有图的灰框只会让人以为还在加载。
 */
@Composable
private fun ArticleImage(el: ContentElement.Image, ratios: MutableMap<String, Float>) {
    val ratio = ratios[el.resourceId]
    if (ratio == FAILED_IMAGE) return
    AsyncImage(
        model = el.resourceId,
        contentDescription = el.alt,
        contentScale = ContentScale.FillWidth,
        onSuccess = { s ->
            val size = s.painter.intrinsicSize
            if (size.isSpecified && size.width > 0f && size.height > 0f) {
                ratios[el.resourceId] = size.width / size.height
            }
        },
        onError = { ratios[el.resourceId] = FAILED_IMAGE },
        modifier = Modifier
            .padding(vertical = Dimens.gapS)
            .fillMaxWidth()
            .aspectRatio(ratio ?: PLACEHOLDER_RATIO)
            .clip(AppShapes.small)
            .then(
                if (ratio == null) Modifier.background(MiuixTheme.colorScheme.surfaceContainerHigh)
                else Modifier
            ),
    )
}

private const val PLACEHOLDER_RATIO = 4f / 3f

/** [ArticleImage] 的比例表里「这张加载失败」的标记 */
private const val FAILED_IMAGE = -1f
