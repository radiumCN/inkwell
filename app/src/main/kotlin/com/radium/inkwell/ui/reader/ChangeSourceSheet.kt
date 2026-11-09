package com.radium.inkwell.ui.reader

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.radium.inkwell.core.source.SearchResult
import com.radium.inkwell.core.util.ChapterOrdinal
import com.radium.inkwell.data.repo.TitleMatch
import com.radium.inkwell.ui.components.AppBottomSheet
import com.radium.inkwell.ui.components.AppFilterChip
import com.radium.inkwell.ui.components.AppIconButton
import com.radium.inkwell.ui.components.AppLoadingIndicator
import com.radium.inkwell.ui.components.AppTextButton
import com.radium.inkwell.ui.components.CardViewport
import com.radium.inkwell.ui.components.ContentListColors
import com.radium.inkwell.ui.components.ContentListDefaults
import com.radium.inkwell.ui.components.ContentListItem
import com.radium.inkwell.ui.components.DeterminateProgressBar
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.ui.components.compactWhitespace
import com.radium.inkwell.ui.components.EmptyState
import com.radium.inkwell.ui.components.cardMargin
import com.radium.inkwell.ui.components.groupedCardItem
import com.radium.inkwell.ui.components.pageScroll
import com.radium.inkwell.ui.components.sheetBottomInset
import com.radium.inkwell.ui.theme.AppShapes
import com.radium.inkwell.ui.theme.AppTheme
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 换源面板。
 *
 * 独立于阅读菜单存在 —— 从前它画在 ReaderMenu 里，而正文加载失败时整个翻页容器都不渲染，
 * 菜单根本呼不出来，于是「章节加载失败」这个最需要换源的场景反而换不了源，只能退出去。
 *
 * 从上到下：标题行（「换源」+ ⟳ 重新搜索）→ 当前源一张卡 → 概况行（进度 / 找到几个 +「只看同作者」）→ 候选卡。
 * 被指出「需要调整」时的样子：每行最大的字是同一个书名（三行「校花的贴身高手」），真正要比的书源名是最小那行灰字；
 * 最新章被截成「04-18…」；「匹配作者」一整张开关卡；面板随结果往上长；当前源只在标题下一行小字，没法拿来比。
 */
@Composable
fun ChangeSourceSheet(
    overlay: ReaderOverlayUi,
    currentSourceName: String,
    bookTitle: String,
    bookAuthor: String,
    candidates: List<SearchResult>,
    onApplySource: (SearchResult) -> Unit,
    onToggleCheckAuthor: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    val searching = overlay.searchingSources
    val changing = overlay.changingSource
    // 面板底取浮层色（ReaderThemeScope 里的 background，AppBottomSheet 默认就读它），卡片是卡片色 ——
    // 两者差一档，卡片才浮得起来（AppBottomSheet 经 CardLayer 给到下面的 groupedCardItem）。
    // 候选列表会滚到手势条下面：导航栏高度垫在列表末尾、也加进列表定高，不让面板在外面垫
    AppBottomSheet(
        onDismissRequest = onDismiss,
        title = "换源",
        padNavigationBar = false,
        endAction = {
            // 会话内复用上次结果；书源有增删 / 站点恢复时用这个主动重搜。搜索中、换源中点了没意义，变灰
            AppIconButton(onClick = onRefresh, enabled = !searching && !changing) {
                Icon(MiuixIcons.Refresh, contentDescription = "重新搜索")
            }
        },
    ) {
        val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
        // 候选区定高（不是 heightIn(max)）：结果边搜边出，上限式的高度会让面板随结果一格一格往上长，
        // 手正要点，那一行的位置还在变。按窗口比例取、大屏封顶，和阅读设置面板同一个算法
        val listHeight = (windowHeight * LIST_HEIGHT_FRACTION).coerceAtMost(Dimens.sheetListMaxHeight)
        val bottomInset = sheetBottomInset()
        Column(Modifier.fillMaxWidth()) {
            CurrentSourceCard(currentSourceName, overlay.currentLatestChapter)
            StatusRow(
                overlay = overlay,
                found = candidates.size,
                // 换源中拨开关会把正在换的那一行筛掉（转圈跟着没了）：吞掉
                onToggleCheckAuthor = { if (!changing) onToggleCheckAuthor(it) },
            )
            SearchProgress(overlay)
            Box(Modifier.fillMaxWidth().height(listHeight + bottomInset)) {
                if (candidates.isEmpty() && !searching) {
                    NoCandidates(
                        overlay = overlay,
                        onShowMismatched = { onToggleCheckAuthor(false) },
                        onRefresh = onRefresh,
                        modifier = Modifier.padding(bottom = bottomInset),
                    )
                } else {
                    CandidateList(
                        overlay = overlay,
                        candidates = candidates,
                        bookTitle = bookTitle,
                        bookAuthor = bookAuthor,
                        bottomInset = bottomInset,
                        onApplySource = onApplySource,
                        onShowMismatched = { onToggleCheckAuthor(false) },
                    )
                }
            }
        }
    }
}

/**
 * 当前源单独一张卡、钉在候选上面：淡强调底 + 行尾「当前」（和目录里「在读」那章同一种「这是你现在的」），
 * 写出它更新到哪一章 —— 下面每个候选都拿它比。候选列表里刻意不含当前源，从前只在标题下写一行「当前：xx」，没法对照。
 */
@Composable
private fun CurrentSourceCard(name: String, latestChapter: String?) {
    val scheme = MiuixTheme.colorScheme
    // 整张卡就是「选中」那一档：字也取 onTertiaryContainer（淡强调底上的对比度按它钉过），不用说明灰
    val colors = ContentListColors(
        containerColor = scheme.tertiaryContainer,
        selectedContainerColor = scheme.tertiaryContainer,
        contentColor = scheme.onTertiaryContainer,
        selectedContentColor = scheme.onTertiaryContainer,
        supportingColor = scheme.onTertiaryContainer,
        selectedSupportingColor = scheme.onTertiaryContainer,
    )
    ContentListItem(
        modifier = Modifier.groupedCardItem(0, 1),
        colors = colors,
        supportingContent = {
            Text(
                latestLine(latestChapter),
                style = ContentListDefaults.supportingStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            Text("当前", style = MiuixTheme.textStyles.footnote1, fontWeight = FontWeight.Medium)
        },
    ) {
        Text(
            name.ifBlank { "当前来源" },
            style = ContentListDefaults.titleStyle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 概况一行：搜到哪了、找到几个 + 右边「只看同作者」。
 *
 * 开关从前是一整张「匹配作者」开关卡，占掉一行半，其实很少动；做成 chip 和概况挤一行，拨一下就地重筛、不重新搜。
 * 卡外的字左边与卡里的字对齐（卡离面板边 cardMargin，卡里的行再缩 listHorizontal）。
 */
@Composable
private fun StatusRow(overlay: ReaderOverlayUi, found: Int, onToggleCheckAuthor: (Boolean) -> Unit) {
    val done = overlay.sourcesDone
    val total = overlay.sourcesTotal
    val text = when {
        overlay.searchingSources -> "正在搜索 $done / $total · 找到 $found 个"
        total == 0 -> "没有其他启用的来源"
        done < total -> "上次搜到 $done / $total 就停了 · 找到 $found 个"
        else -> "搜了 $total 个来源 · 找到 $found 个"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = cardMargin() + Dimens.listHorizontal, end = cardMargin())
            .padding(top = Dimens.gapS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            Modifier.weight(1f).padding(end = Dimens.gapS),
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        AppFilterChip(
            selected = overlay.checkAuthor,
            onClick = { onToggleCheckAuthor(!overlay.checkAuthor) },
            label = "只看同作者",
        )
    }
}

/**
 * 搜索进度条。位置一直留着、搜完只是淡掉 —— 收起来的话，下面的候选卡在搜完那一刻整体往上跳一截。
 * 淡入淡出是纯视觉属性，走 effects spec。
 */
@Composable
private fun SearchProgress(overlay: ReaderOverlayUi) {
    val shown = overlay.searchingSources && overlay.sourcesTotal > 0
    val alpha by animateFloatAsState(
        if (shown) 1f else 0f,
        AppTheme.motionScheme.defaultEffectsSpec(),
        label = "sourceSearchProgress",
    )
    val progress = if (overlay.sourcesTotal > 0) overlay.sourcesDone.toFloat() / overlay.sourcesTotal else 0f
    DeterminateProgressBar(
        progress = progress,
        modifier = Modifier
            .padding(horizontal = cardMargin(), vertical = Dimens.gapS)
            .graphicsLayer { this.alpha = alpha },
    )
}

@Composable
private fun CandidateList(
    overlay: ReaderOverlayUi,
    candidates: List<SearchResult>,
    bookTitle: String,
    bookAuthor: String,
    bottomInset: Dp,
    onApplySource: (SearchResult) -> Unit,
    onShowMismatched: () -> Unit,
) {
    val searching = overlay.searchingSources
    // 搜索中卡的末尾多一行「还在找」：它和候选在同一张卡里，算进分组的行数
    val rows = candidates.size + if (searching) 1 else 0
    val hidden = if (overlay.checkAuthor) overlay.authorMismatched else 0
    // 视口裁成卡的圆上角：一滚，顶上切过的是中间的行，不裁的话卡顶是一刀直线（见 CardViewport）。
    // 和概况行之间的缝是上面进度条那一档的留白，本来就在列表外面、不跟着滚
    CardViewport {
        LazyColumn(
            // HyperOS 的回弹 + 到头轻震；主题自带的那份关掉，免得叠成双倍（见 pageScroll）
            modifier = Modifier.pageScroll(),
            contentPadding = PaddingValues(bottom = Dimens.gapXL + bottomInset),
            overscrollEffect = null,
        ) {
            // 排列照返回先后，不在手指底下重排；先搜哪些源（以前有这本书的、校验通过的、快的）由 ViewModel 定
            itemsIndexed(candidates, key = { _, it -> "${it.sourceId}|${it.bookUrl}" }) { i, c ->
                ChangeSourceCandidateRow(
                    candidate = c,
                    bookTitle = bookTitle,
                    bookAuthor = bookAuthor,
                    // 开着「只看同作者」时列出来的都对得上，不必再标
                    markAuthor = !overlay.checkAuthor,
                    currentLatest = overlay.currentLatestChapter,
                    applying = overlay.changingSource && overlay.changingTo == c.sourceId,
                    enabled = !overlay.changingSource,
                    onClick = { onApplySource(c) },
                    modifier = Modifier.groupedCardItem(i, rows),
                )
            }
            if (searching) {
                item(key = "searching") {
                    ContentListItem(
                        modifier = Modifier.groupedCardItem(rows - 1, rows),
                        leadingContent = { AppLoadingIndicator(size = Dimens.buttonSpinner) },
                    ) {
                        Text(
                            "还在找其他来源…",
                            style = ContentListDefaults.supportingStyle,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
            // 作者对不上被筛掉的，说出来、给一个出口：从前它们悄悄消失，用户只看到「没找到」，想不到是开关卡掉的
            if (hidden > 0) {
                item(key = "author-hidden") {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = Dimens.gapS)
                            // 行尾文字按钮自带 gapM 的内边距：扣掉它，按钮的字右边才对得上卡里的字
                            .padding(start = cardMargin() + Dimens.listHorizontal, end = cardMargin() + Dimens.listHorizontal - Dimens.gapM),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "另有 $hidden 个来源找到同名书，作者对不上",
                            Modifier.weight(1f),
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        AppTextButton("显示", onClick = onShowMismatched, enabled = !overlay.changingSource)
                    }
                }
            }
        }
    }
}

/** 一个候选都没有（且没在搜）：说清是哪种「没有」，再给下一步 */
@Composable
private fun NoCandidates(
    overlay: ReaderOverlayUi,
    onShowMismatched: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val done = overlay.sourcesDone
    val total = overlay.sourcesTotal
    val hidden = if (overlay.checkAuthor) overlay.authorMismatched else 0
    when {
        // 作者对不上的放前面：结果其实有，只是被开关挡着 —— 「显示」一下比「重新搜索」近得多
        hidden > 0 -> EmptyState(
            icon = MiuixIcons.Search,
            title = "没有作者一致的来源",
            hint = "另有 $hidden 个来源找到了同名的书，但作者对不上。不少来源的作者字段是空的，或者带了前缀",
            actionLabel = "显示这 $hidden 个",
            onAction = onShowMismatched,
            modifier = modifier,
        )
        // 中途关掉再开：半截且一个都没命中，别说成「都没有」
        total > 0 && done < total -> EmptyState(
            icon = MiuixIcons.Search,
            title = "上次没搜完",
            hint = "查了 $done / $total 个来源，还没找到这本书",
            actionLabel = "重新搜索",
            onAction = onRefresh,
            modifier = modifier,
        )
        total == 0 -> EmptyState(
            icon = MiuixIcons.Search,
            title = "没有其他启用的来源",
            hint = "在来源管理里导入或启用来源后再来换",
            modifier = modifier,
        )
        else -> EmptyState(
            icon = MiuixIcons.Search,
            title = "没有找到这本书",
            hint = "其他 $total 个来源都没有找到这本书",
            actionLabel = "重新搜索",
            onAction = onRefresh,
            modifier = modifier,
        )
    }
}

/**
 * 换源候选行：书源名（主行）→ 最新章（副行）→ 只在对不上时多一行「书名：… · 作者：…」；行尾「多 8 章 / 少 3 章」。
 *
 * 从前是书源名（小灰字）→ 书名（大字）→ 作者 · 最新章：同一本书，每行最大的字都是同一个书名，
 * 要比的书源名反倒最不起眼；作者也是一样的，挤得最新章被截成「04-18…」。
 * 更早主行是 sourceName、副行塞 sourceId（网址），书源没填名称时整行就是裸 URL —— 见 [sourceDisplayName]。
 */
@Composable
private fun ChangeSourceCandidateRow(
    candidate: SearchResult,
    bookTitle: String,
    bookAuthor: String,
    markAuthor: Boolean,
    currentLatest: String?,
    applying: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val gap = remember(candidate.latestChapter, currentLatest) { ChapterOrdinal.gap(candidate.latestChapter, currentLatest) }
    val mismatch = remember(candidate, bookTitle, bookAuthor, markAuthor) {
        mismatchLine(candidate, bookTitle, bookAuthor, markAuthor)
    }
    // 禁用时行里写死的强调色也得跟着淡（槽位里的 LocalContentColor 由行自己淡掉，写死的颜色它管不着）
    val accent = MiuixTheme.colorScheme.primary.let { if (enabled || applying) it else it.copy(alpha = DISABLED_ALPHA) }
    ContentListItem(
        onClick = onClick,
        // 换源中整张列表不可点；正在换的那一行不跟着变灰 —— 一眼看出点的是哪个（再点一下 ViewModel 那道闸会挡掉）
        enabled = enabled || applying,
        modifier = modifier,
        supportingContent = {
            Column {
                Text(
                    if (applying) "正在换到这个源…" else latestLine(candidate.latestChapter),
                    style = ContentListDefaults.supportingStyle,
                    color = if (applying) accent else LocalContentColor.current,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                mismatch?.let {
                    Text(
                        it,
                        style = MiuixTheme.textStyles.footnote1,
                        color = accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        trailingContent = when {
            applying -> {
                { AppLoadingIndicator(size = Dimens.buttonSpinner) }
            }
            gap != null && gap != 0 -> {
                { ChapterGapTag(gap, enabled) }
            }
            else -> null
        },
    ) {
        Text(
            candidate.sourceDisplayName(),
            style = ContentListDefaults.titleStyle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 行尾「多 8 章」（淡强调底的小标签）/「少 3 章」（说明灰字）：和当前源的最新章比。一样多的不标。
 * 换源多半就是为了找更新更快、或者没断更的那个；认不出章号的 [ChapterOrdinal.gap] 给 null，不标、不猜。
 */
@Composable
private fun ChapterGapTag(gap: Int, enabled: Boolean) {
    if (gap > 0) {
        val scheme = MiuixTheme.colorScheme
        val fade = if (enabled) 1f else DISABLED_ALPHA
        Text(
            "多 $gap 章",
            Modifier
                .graphicsLayer { alpha = fade }
                .background(scheme.tertiaryContainer, AppShapes.small)
                .padding(horizontal = Dimens.gapS, vertical = Dimens.gapXS),
            style = MiuixTheme.textStyles.footnote1,
            fontWeight = FontWeight.Medium,
            color = scheme.onTertiaryContainer,
        )
    } else {
        Text("少 ${-gap} 章", style = MiuixTheme.textStyles.footnote1)
    }
}

/**
 * 只在对不上时才写的那一行：书名不完全一样（「精校版」「全本」）、或者关掉「只看同作者」后作者不一样。
 * 一样的不写 —— 从前每行都把书名、作者原样重复一遍，真正不一样的那个反倒淹在里面。
 */
private fun mismatchLine(c: SearchResult, bookTitle: String, bookAuthor: String, markAuthor: Boolean): String? {
    val parts = buildList {
        if (c.title.isNotBlank() && TitleMatch.normalize(c.title) != TitleMatch.normalize(bookTitle)) {
            add("书名：${c.title.compactWhitespace()}")
        }
        val author = c.author?.takeIf { it.isNotBlank() }
        if (markAuthor && author != null && !TitleMatch.authorMatches(author, bookAuthor)) {
            add("作者：${author.compactWhitespace()}")
        }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/** 「最新：第13370章 xxx」；书源没给就明说，别留一行空白 */
private fun latestLine(latest: String?): String =
    latest?.compactWhitespace()?.takeIf { it.isNotEmpty() }?.let { "最新：$it" } ?: "没有最新章节信息"

/** 书源展示名：有名称用名称；否则从 sourceId 里抠主机名，别把整段 URL 甩到主视觉上 */
private fun SearchResult.sourceDisplayName(): String {
    sourceName.takeIf { it.isNotBlank() }?.let { return it }
    val host = sourceId
        .removePrefix("https://")
        .removePrefix("http://")
        .substringBefore('/')
        .substringBefore('#')
        .substringBefore('?')
    return host.ifBlank { sourceId }
}

/** 候选区占窗口高度的比例（大屏再由 [Dimens.sheetListMaxHeight] 封顶）。与阅读设置面板的内容区同一档 */
private const val LIST_HEIGHT_FRACTION = 0.42f

/** 换源中其他行变灰的那一档，与列表行禁用时一致 */
private const val DISABLED_ALPHA = 0.38f
