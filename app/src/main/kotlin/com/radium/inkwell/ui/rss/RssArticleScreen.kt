package com.radium.inkwell.ui.rss

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.size.Precision
import com.radium.inkwell.core.model.ContentElement
import com.radium.inkwell.ui.components.AppIconButton
import com.radium.inkwell.ui.components.AppLoadingIndicator
import com.radium.inkwell.ui.components.AppScaffold
import com.radium.inkwell.ui.components.AppSnackbarHost
import com.radium.inkwell.ui.components.AppToolbar
import com.radium.inkwell.ui.components.BackButton
import com.radium.inkwell.ui.components.CollectMessages
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.ui.components.LoadingState
import com.radium.inkwell.ui.components.MessageBus
import com.radium.inkwell.ui.components.NO_BROWSER_MESSAGE
import com.radium.inkwell.ui.components.PrimaryButton
import com.radium.inkwell.ui.components.SecondaryButton
import com.radium.inkwell.ui.components.ToolbarTitle
import com.radium.inkwell.ui.components.horizontalOnly
import com.radium.inkwell.ui.components.rememberExternalLinkOpener
import com.radium.inkwell.ui.components.pageScroll
import com.radium.inkwell.ui.components.rememberToolbarTitleReveal
import com.radium.inkwell.ui.components.settingsPageColor
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
 *
 * feed 只给了摘要的（少数派这类）：摘要先亮着，文末一行「正在取全文…」，原网页整理出全文就整个换掉，
 * 日期下面注明「正文取自原网页」。整理不出来的，原网页在应用内打开（[onOpenWebPage]，阅读的做法）——
 * 从前只有「在浏览器中打开原文」，看全文就得离开应用。
 */
@Composable
fun RssArticleScreen(
    args: RssArticleArgs,
    onBack: () -> Unit,
    onOpenWebPage: (url: String, title: String) -> Unit,
    viewModel: RssArticleViewModel,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val messages = remember { MessageBus() }
    val snackbar = remember { SnackbarHostState() }
    CollectMessages(messages, snackbar)

    // 从前拉不起浏览器时静默吞掉：按钮点了没反应，用户只会以为没点中。
    // 原文链接来自订阅源，先把地址亮出来问一句再跳（见 rememberExternalLinkOpener）
    val links = rememberExternalLinkOpener { messages.emit(NO_BROWSER_MESSAGE) }
    fun openInBrowser() = links.open(state.link)
    fun openWebPage() = onOpenWebPage(state.link, state.title)

    val pageColor = settingsPageColor()
    // 窄栏，不用大标题栏：这一页的标题就是文章标题。从前停在顶部时是大标题「文章」压在文章标题上面 ——
    // 两行大字，第一行没有信息量，左边还和下面的标题错开 6dp（Miuix 大标题缩进 26dp、正文 20dp）。
    // 栏里的标题等页面上的文章标题滚到栏底下才淡出来（见 ToolbarTitleReveal）
    val listState = rememberLazyListState()
    val reveal = rememberToolbarTitleReveal(listState)
    AppScaffold(
        containerColor = pageColor,
        snackbarHost = { AppSnackbarHost(snackbar) },
        topBar = {
            AppToolbar(
                modifier = reveal.bar,
                navigationIcon = { BackButton(onClick = onBack) },
                containerColor = pageColor,
                actions = {
                    // 脚本地址、data: 地址浏览器打不开，不给
                    if (state.hasWebPage) {
                        AppIconButton(onClick = ::openInBrowser) {
                            Icon(
                                MiuixIcons.Forward,
                                contentDescription = "浏览器打开原文",
                            )
                        }
                    }
                },
            ) {
                ToolbarTitle(state.title, Modifier.graphicsLayer { alpha = reveal.titleAlpha })
            }
        },
    ) { padding ->
        if (state.loading) {
            LoadingState(Modifier.padding(padding))
            return@AppScaffold
        }

        // 懒加载：长文、多图的文章从前一次把全部段落和图片组合完才出第一屏。
        // 列表也让「上面的图加载出来、高度变了」不再把正在读的那段往下推 —— 位置锚在可见的那一项上
        val imageSizes = remember { mutableStateMapOf<String, Size>() }
        // 正文的行高：Miuix 的字体样式不设行高，按字体默认的行距排，长篇中文挤成一片。只在文章正文里放宽，不动全局
        val paragraphStyle = MiuixTheme.textStyles.body1.copy(lineHeight = ARTICLE_LINE_HEIGHT)
        LazyColumn(
            Modifier
                .fillMaxSize()
                // 只让开两侧：顶栏高度垫在列表开头（正文滚到毛玻璃顶栏下面），导航栏高度垫在末尾（见 AppScaffold）
                .padding(padding.horizontalOnly())
                .pageScroll(),
            state = listState,
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
                        Modifier.padding(top = Dimens.gapS).then(reveal.title),
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
                    // 说清这不是 feed 里的内容：是去原网页整理出来的，个别网站可能挑错了块（文末有「查看原网页」）
                    state.fullTextHost?.let { host ->
                        Text(
                            "正文取自原网页 · $host",
                            Modifier.padding(top = Dimens.gapS),
                            style = MiuixTheme.textStyles.footnote1,
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
                    // 小标题比正文大一档（title4 = 18sp）：从前用 headline2，在 Miuix 里和正文 body1 一样是 16sp，
                    // 只差一个字重，看不出是标题。上面留得比下面多，让它贴着自己领起的那一段
                    is ContentElement.Heading -> Text(
                        el.text,
                        Modifier.padding(top = Dimens.gapL, bottom = Dimens.gapXS),
                        style = MiuixTheme.textStyles.title4,
                        fontWeight = FontWeight.Medium,
                    )
                    is ContentElement.Paragraph -> Text(
                        el.text,
                        Modifier.padding(vertical = Dimens.gapS),
                        style = paragraphStyle,
                    )
                    is ContentElement.Image -> ArticleImage(el, imageSizes)
                    ContentElement.Divider -> Spacer(Modifier.height(Dimens.gapM))
                }
            }

            item(key = "footer", contentType = "footer") {
                Column {
                    val error = state.error
                    val failure = state.fullTextFailure
                    when {
                        // 抓取失败一律说出来、并给重试：从前错误只在「有原文链接」时才显示，
                        // 订阅源不存在、或没有链接时抓失败，页面就只剩一个标题
                        error != null -> {
                            Text(
                                "正文没能取到：$error",
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.error,
                            )
                            Spacer(Modifier.height(Dimens.gapM))
                            SecondaryButton(text = "重试", onClick = viewModel::load, modifier = Modifier.fillMaxWidth())
                        }
                        // 摘要已经亮着，只在文末转一行，不整页转圈
                        state.fetchingFullText -> Row(
                            Modifier.padding(top = Dimens.gapS),
                            horizontalArrangement = Arrangement.spacedBy(Dimens.gapS),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AppLoadingIndicator(size = Dimens.buttonSpinner)
                            Text(
                                "正在取全文…",
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                        // 整理不出来不是错误（要跑脚本才出内容的网页多的是）：说一句，原网页在下面
                        failure != null -> Text(
                            if (state.hasWebPage) "$failure，可以在应用内打开原网页看。" else "$failure。",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        // 解析不出正文不是错误：很多源本来就只给标题+链接
                        state.elements.isEmpty() -> Text(
                            "这篇文章没有可解析的正文（订阅源只给了标题和链接）",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }

                    // 原网页永远给出口，在应用内打开。这里看不到正文时它是主按钮；正文已经在这儿了
                    // （feed 给的全文、从原网页整理出来的全文）降成灰色的次要按钮 —— 个别网站可能挑错了块，留条退路。
                    // 外部浏览器只留在顶栏，先把地址亮出来问一句。
                    if (state.hasWebPage) {
                        Spacer(Modifier.height(Dimens.gapXL))
                        if (state.webPageIsPrimary) {
                            PrimaryButton(text = "在应用内打开原网页", onClick = ::openWebPage, modifier = Modifier.fillMaxWidth())
                        } else {
                            SecondaryButton(text = "查看原网页", onClick = ::openWebPage, modifier = Modifier.fillMaxWidth())
                        }
                    }
                    Spacer(Modifier.height(Dimens.touchTarget))
                }
            }
        }
    }
}

/**
 * 正文里的一张图。没加载出来之前先按 4:3 占位、铺满宽度；加载出来按原图的宽高显示：
 * **原图一像素算 1dp，比正文宽才缩到正文宽**（浏览器的 `max-width: 100%` 同一个意思）。
 * 配图本来就比手机屏宽，照旧铺满；徽章、按钮图这类几十像素的小图按原大小，不再放大成整屏宽的一团糊
 * （爱范儿那个 🔗 表情图就是这么被反馈上来的；表情图本身在解析时已经换回文字，这里兜别的小图）。
 *
 * 要拿到原图尺寸，解码用 [Precision.INEXACT]：只缩不放。EXACT 会把 72px 的图按布局放大解码，
 * 量出来的就不是原图了。大图照旧按布局缩着解，不会把几 MB 的原图整个解进内存。
 *
 * 尺寸记进 [sizes]（页面级），这一项滑出视口再滑回来时直接按真实大小排，不再先占位再跳一下。
 * 加载失败就不占地方 —— 留一块永远不会有图的灰框只会让人以为还在加载。
 */
@Composable
private fun ArticleImage(el: ContentElement.Image, sizes: MutableMap<String, Size>) {
    val size = sizes[el.resourceId]
    if (size == FAILED_IMAGE) return
    val context = LocalPlatformContext.current
    val request = remember(el.resourceId, context) {
        ImageRequest.Builder(context)
            .data(el.resourceId)
            .precision(Precision.INEXACT)
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = el.alt,
        contentScale = ContentScale.FillWidth,
        onSuccess = { s ->
            val intrinsic = s.painter.intrinsicSize
            if (intrinsic.isSpecified && intrinsic.width > 0f && intrinsic.height > 0f) {
                sizes[el.resourceId] = intrinsic
            }
        },
        onError = { sizes[el.resourceId] = FAILED_IMAGE },
        modifier = Modifier
            .padding(vertical = Dimens.gapS)
            // 先收窄到原图宽、再铺满：宽 = min(原图宽, 正文宽)
            .then(if (size != null) Modifier.widthIn(max = size.width.dp) else Modifier)
            .fillMaxWidth()
            .aspectRatio(size?.let { it.width / it.height } ?: PLACEHOLDER_RATIO)
            .clip(AppShapes.small)
            .then(
                if (size == null) Modifier.background(MiuixTheme.colorScheme.surfaceContainerHigh)
                else Modifier
            ),
    )
}

private const val PLACEHOLDER_RATIO = 4f / 3f

/** 文章正文的行高（字号的倍数）：中文长文常用 1.5~1.8，取中 */
private val ARTICLE_LINE_HEIGHT = 1.6.em

/** [ArticleImage] 的尺寸表里「这张加载失败」的标记 */
private val FAILED_IMAGE = Size(-1f, -1f)
