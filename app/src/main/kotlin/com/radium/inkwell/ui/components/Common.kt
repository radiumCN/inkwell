package com.radium.inkwell.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.decode.DataSource
import coil3.request.ImageRequest
import coil3.size.Precision
import com.radium.inkwell.core.util.htmlToPlainText
import com.radium.inkwell.ui.theme.AppShapes
import com.radium.inkwell.ui.theme.AppTheme
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 空状态：图标 + 标题 + 提示 + 可选动作，全应用统一形态 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    hint: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    /**
     * 点动作按钮弹出的菜单（[AppDropdownMenu]）：和按钮放进同一个 Box，从按钮上弹出来。
     * 一个按钮背后有几条路时用（空书架的「导入本地书」：选文件 / 选文件夹）
     */
    actionMenu: (@Composable () -> Unit)? = null,
) {
    Box(modifier.fillMaxSize().padding(Dimens.gapXXL), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                icon,
                contentDescription = null,
                Modifier.size(Dimens.iconXL),
                // 纯装饰的大图标：比文字淡得多，只做视觉锚点（dividerLine 那档）
                tint = MiuixTheme.colorScheme.dividerLine,
            )
            Text(
                title,
                Modifier.padding(top = Dimens.gapL),
                style = MiuixTheme.textStyles.headline2,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                textAlign = TextAlign.Center,
            )
            if (hint != null) {
                Text(
                    hint,
                    Modifier.padding(top = Dimens.gapS),
                    style = MiuixTheme.textStyles.footnote1,
                    // 正文性小字用摘要色，不用 outline / dividerLine —— 后者是给线和装饰的，对比度不够读。
                    // 空态提示恰恰是最需要被读清的那句话
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    textAlign = TextAlign.Center,
                )
            }
            if (actionLabel != null && onAction != null) {
                Box(Modifier.padding(top = Dimens.gapS)) {
                    AppTextButton(actionLabel, onClick = onAction)
                    actionMenu?.invoke()
                }
            }
        }
    }
}

/**
 * 加载态。与 [EmptyState]/[ErrorState] 三态对称，全应用统一形态。
 * 内部走 [AppLoadingIndicator]（默认尺寸的 HyperOS 转圈）。
 */
@Composable
fun LoadingState(
    modifier: Modifier = Modifier,
    label: String? = null,
) {
    Box(modifier.fillMaxSize().padding(Dimens.gapXXL), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            AppLoadingIndicator()
            if (label != null) {
                Text(
                    label,
                    Modifier.padding(top = Dimens.gapM),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * 不确定进度指示：Miuix [InfiniteProgressIndicator]（HyperOS 那种细环 + 绕圈的小点）。
 *
 * 尺寸从按钮里的 18dp 到整页的 32dp 都是同一个组件 —— M3 Expressive 时代大号是形变多边形、
 * 小号得退回圆环（多边形强缩会炸约束），这里没有那道分叉了。线宽随尺寸：小于 24dp 用细线，否则粗一档，
 * 否则 18dp 的转圈里那根 2dp 的环看着发粗。
 */
@Composable
fun AppLoadingIndicator(
    modifier: Modifier = Modifier,
    color: Color = MiuixTheme.colorScheme.primary,
    size: Dp? = null,
) {
    val actual = size ?: Dimens.loadingIndicator
    InfiniteProgressIndicator(
        modifier = modifier,
        color = color,
        size = actual,
        strokeWidth = if (actual < Dimens.iconMd) {
            ProgressIndicatorDefaults.DefaultInfiniteProgressIndicatorStrokeWidth
        } else {
            Dimens.strokeThick + Dimens.strokeThin
        },
    )
}

/**
 * 确定进度条（搜索 / 书源校验 / 更新下载 / 离线缓存）：Miuix [LinearProgressIndicator] 的 6dp 胶囊条。
 *
 * [progress] 收的是值，不是 M3 时代那种 lambda：Miuix 的条本来就要值，lambda 只是装作把读数推迟到绘制阶段，
 * 其实还是在组合里读 —— 调用方以为进度跳动不触发重组，实际照样重组，不如签名照实写。
 */
@Composable
fun DeterminateProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
) {
    LinearProgressIndicator(
        progress = progress.coerceIn(0f, 1f),
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * 读数式的进度条：书读到哪了。书架封面底边、「继续阅读」卡片里用。
 *
 * 和 [DeterminateProgressBar] 分开：那条表达「有活正在跑」（搜索、校验、下载），这条是静止的读数 ——
 * 书架上一排封面要的是细细一条线，不是一排 6dp 的任务进度条。
 *
 * [progress] 落在 0..1；读屏按进度条报百分比（这里手动挂 progressBarRangeInfo，Miuix 的条不带）。
 */
@Composable
fun ReadingProgressMeter(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color = MiuixTheme.colorScheme.primary,
    trackColor: Color = MiuixTheme.colorScheme.surfaceContainerHighest,
) {
    val value = progress.coerceIn(0f, 1f)
    LinearProgressIndicator(
        progress = value,
        modifier = modifier
            .fillMaxWidth()
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(value, 0f..1f) },
        colors = ProgressIndicatorDefaults.progressIndicatorColors(
            foregroundColor = color,
            backgroundColor = trackColor,
        ),
        height = Dimens.progressMeter,
    )
}

/**
 * 书封缩略图。
 *
 * 默认封面**永远垫在底层**，图片加载成功就把它盖住。从前只有 `coverModel == null` 才画占位，
 * 于是「有封面地址、但抓不下来」这种最常见的情况（书源的图床挂了、防盗链、超时）
 * 落进 AsyncImage 的失败分支后就是一整块空白灰方 —— 连书名都没有，用户根本认不出是哪本书。
 * 垫在底层就不必去猜 Coil 的加载状态：成功即遮住，失败即透出。
 *
 * 新解出来的图淡入盖上（effects 令牌，系统「移除动画」时直接出），而不是一帧之内从默认封面跳成图；
 * 内存缓存命中的（滑回来的那些）不淡，直接出。不用 Coil 自带的 crossfade：它自己计时，
 * 不理系统的动画开关，而且一成功就该撤掉的默认封面会在淡入期间先消失，露出底下的灰方。
 *
 * @param contentDescription 读屏念的名字。书名就印在旁边（列表行标题、书架卡片下的书名、详情页抬头）
 *   时传 null —— 封面整块不进读屏，否则同一个书名连念两遍（默认封面上那行字还会再念一遍截断的）。
 *   默认仍是书名，给「只有一张封面、旁边没字」的地方用（进书 splash）。
 * @param retryReferer 取不到时带上这个 Referer 再取一次（防盗链的图床，见 [referer]）。订阅文章的配图给文章网站的来源；
 *   书封不给 —— 书源的图床另有书源自己的请求头那一套
 */
@Composable
fun BookCover(
    title: String,
    coverModel: Any?,
    modifier: Modifier = Modifier,
    placeholderChars: Int = 4,
    contentDescription: String? = title,
    retryReferer: String? = null,
) {
    Box(
        (if (contentDescription == null) modifier.clearAndSetSemantics {} else modifier)
            .clip(AppShapes.small)
            .background(MiuixTheme.colorScheme.surfaceContainerHigh),
    ) {
        var loaded by remember(coverModel) { mutableStateOf(false) }
        // 这次成功是不是内存缓存命中：命中就不淡入
        var instant by remember(coverModel) { mutableStateOf(false) }
        val fade = remember(coverModel) { Animatable(0f) }
        val fadeSpec = AppTheme.motionScheme.defaultEffectsSpec<Float>()
        LaunchedEffect(fade, loaded, instant) {
            when {
                !loaded -> fade.snapTo(0f)
                instant -> fade.snapTo(1f)
                else -> fade.animateTo(1f, fadeSpec)
            }
        }
        // 图完全不透明了才撤默认封面；derivedStateOf 让淡入的每一帧只走图层、不重组
        val covered by remember(coverModel) {
            derivedStateOf { loaded && (instant || fade.value >= 1f) }
        }
        if (!covered) DefaultCover(title, placeholderChars)
        if (coverModel != null) {
            val context = LocalPlatformContext.current
            // 先不带 Referer；取不到、又给了 retryReferer 的，带上它再来一次
            var withReferer by remember(coverModel) { mutableStateOf(false) }
            val request = remember(coverModel, context, withReferer) {
                ImageRequest.Builder(context)
                    .data(coverModel)
                    .referer(if (withReferer) retryReferer else null)
                    // 不设 size：AsyncImage 按布局约束定解码尺寸 —— 列表封面 48×64dp，不会把 1000px 原图解进内存。
                    // 从前为了拿约束在外面套了 BoxWithConstraints，每个列表项都多一次子组合，书架一滑就是一屏。
                    // 各调用点的尺寸都是有界的（定宽高，或网格格子里 fillMaxSize）；无界时 Coil 会按原图解，新调用点别给无界约束。
                    .precision(Precision.INEXACT)
                    .build()
            }
            AsyncImage(
                model = request,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = if (instant) 1f else fade.value },
                onSuccess = { s ->
                    instant = s.result.dataSource == DataSource.MEMORY_CACHE ||
                        s.result.dataSource == DataSource.MEMORY
                    loaded = true
                },
                onError = {
                    loaded = false
                    if (!withReferer && retryReferer != null) withReferer = true
                },
            )
        }
    }
}

/**
 * 没有封面时长这样：一块带书脊的素封面，印着书名。
 *
 * 底色由**书名决定**（同一本书永远同一个色），而不是随机或一律灰 ——
 * 一屏灰方块里找书，等于逐个读字；有了颜色，位置和色块本身就成了记忆线索。
 * 色板全部取自 App 的墨/纸语汇，且都足够深，白字压得住（不必逐个算对比度）。
 */
@Composable
private fun DefaultCover(title: String, maxChars: Int) {
    val base = remember(title) { COVER_PALETTE[colorIndex(title)] }
    Box(Modifier.fillMaxSize().background(base)) {
        // 书脊：左侧一道压深的窄条。没有它，纯色块看着像色卡而不像书
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(0.07f)
                .background(Color.Black.copy(alpha = 0.16f))
        )
        Text(
            title.take(maxChars),
            Modifier
                .align(Alignment.Center)
                // 左边多让一格给书脊，字才看着落在「封面」正中而不是压着书脊
                .padding(start = Dimens.gapM, end = Dimens.gapXS),
            style = MiuixTheme.textStyles.subtitle,
            color = Color.White.copy(alpha = 0.92f),
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 墨/纸语汇里的深色调；都够深，白字直接压得住 */
private val COVER_PALETTE = listOf(
    Color(0xFF92400E), // 赭石（品牌主色）
    Color(0xFF2B3A55), // 墨蓝
    Color(0xFF3E6B4F), // 竹绿
    Color(0xFF7C4A21), // 牛皮
    Color(0xFF5B4B7A), // 紫檀
    Color(0xFF6B3A3A), // 绛
    Color(0xFF44615E), // 苍
    Color(0xFF7A5C2E), // 秋香
)

/** 按书名取色。hashCode 在 JVM 上是规范定义的，同一本书跨设备、跨启动都是同一个色 */
internal fun colorIndex(title: String): Int {
    val h = title.hashCode()
    // Int.MIN_VALUE 取绝对值还是它自己（负数），直接 % 会得到负下标
    return ((h % COVER_PALETTE.size) + COVER_PALETTE.size) % COVER_PALETTE.size
}

/**
 * 书籍列表行：封面 + 标题/副标题/来源 + 尾部动作，搜索与发现共用；onClick 非空时整行可点。
 *
 * 装进卡片用（每行 [groupedCardItem]）：行本身透明，底色与圆角由卡画。
 */
@Composable
fun BookListRow(
    title: String,
    subtitle: String?,
    caption: String?,
    coverModel: Any?,
    trailingLabel: String,
    trailingLoading: Boolean,
    onTrailing: () -> Unit,
    /** false 时尾部按钮置灰不可点（如"已加入"）—— 书已在架就不该再让人点"加入" */
    trailingEnabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    colors: ContentListColors = ContentListDefaults.plainColors(),
) {
    val overline: (@Composable () -> Unit)? = subtitle?.takeIf { it.isNotBlank() }?.let {
        {
            Text(it, style = ContentListDefaults.overlineStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    val plainCaption = remember(caption) {
        caption?.htmlToPlainText()
            ?.replace('\n', ' ')
            ?.replace(Regex(" {2,}"), " ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }
    val supporting: (@Composable () -> Unit)? = plainCaption?.let {
        {
            // 书源名/最新章节是要读的信息：走槽位给的摘要色，不另取更淡的装饰色
            Text(it, style = ContentListDefaults.supportingStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    val cover: @Composable () -> Unit = {
        BookCover(
            title = title,
            coverModel = coverModel,
            modifier = Modifier.size(
                width = Dimens.coverThumbWidth,
                height = Dimens.coverThumbHeight,
            ),
            placeholderChars = 2,
            // 书名就是这一行的标题，读屏不必再念一遍封面
            contentDescription = null,
        )
    }
    val trailing: @Composable () -> Unit = {
        // 转圈叠在按钮上，而不是替换它 —— 24dp 的转圈换掉 48dp 的按钮，整行高度会跳一下
        Box(contentAlignment = Alignment.Center) {
            AppTextButton(
                onClick = onTrailing,
                enabled = trailingEnabled && !trailingLoading,
            ) {
                Text(
                    trailingLabel,
                    style = MiuixTheme.textStyles.body2,
                    fontWeight = FontWeight.Medium,
                    color = if (trailingLoading) Color.Transparent else LocalContentColor.current,
                )
            }
            if (trailingLoading) {
                AppLoadingIndicator(
                    color = MiuixTheme.colorScheme.primary,
                    size = Dimens.buttonSpinner,
                )
            }
        }
    }
    val titleContent: @Composable () -> Unit = {
        Text(title, style = ContentListDefaults.titleStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (onClick != null) {
        ContentListItem(
            onClick = onClick,
            modifier = modifier,
            leadingContent = cover,
            trailingContent = trailing,
            overlineContent = overline,
            supportingContent = supporting,
            // 48×64 书封比三行字还高：它撑开行高，上下只留 8dp
            contentPadding = ContentListDefaults.MediaPadding,
            colors = colors,
            content = titleContent,
        )
    } else {
        ContentListItem(
            modifier = modifier,
            leadingContent = cover,
            trailingContent = trailing,
            overlineContent = overline,
            supportingContent = supporting,
            contentPadding = ContentListDefaults.MediaPadding,
            colors = colors,
            content = titleContent,
        )
    }
}

/**
 * 书籍简介：默认收起到 [INTRO_COLLAPSED_LINES] 行，**放不下才**给「展开 / 收起」。
 * 详情页与预览页共用。从前两页各手写一份，按钮常驻 —— 简介本来就不到四行时，点了什么都不变。
 */
@Composable
fun BookIntro(intro: String, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable(intro) { mutableStateOf(false) }
    // 收起状态下排版有没有被截：展开后就看不出来了，所以只在收起时更新
    var overflows by remember(intro) { mutableStateOf(false) }
    Column(modifier) {
        Text("简介", style = MiuixTheme.textStyles.headline2, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(Dimens.gapXS))
        Text(
            intro,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceSecondary,
            maxLines = if (expanded) Int.MAX_VALUE else INTRO_COLLAPSED_LINES,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow },
        )
        if (expanded || overflows) {
            AppTextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "收起" else "展开", style = MiuixTheme.textStyles.body2)
            }
        }
    }
}

private const val INTRO_COLLAPSED_LINES = 4
