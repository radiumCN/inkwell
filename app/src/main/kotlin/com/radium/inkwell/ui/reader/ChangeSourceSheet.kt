package com.radium.inkwell.ui.reader

import com.radium.inkwell.ui.components.sheetBottomInset
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.radium.inkwell.ui.components.AppBottomSheet
import com.radium.inkwell.ui.components.AppTextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.radium.inkwell.core.source.SearchResult
import com.radium.inkwell.ui.components.AppLoadingIndicator
import com.radium.inkwell.ui.components.ContentListDefaults
import com.radium.inkwell.ui.components.ContentListItem
import com.radium.inkwell.ui.components.DeterminateProgressBar
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.ui.components.SwitchRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 换源面板。
 *
 * 独立于阅读菜单存在 —— 从前它画在 ReaderMenu 里，而正文加载失败时整个翻页容器都不渲染，
 * 菜单根本呼不出来，于是「章节加载失败」这个最需要换源的场景反而换不了源，只能退出去。
 */
@Composable
fun ChangeSourceSheet(
    overlay: ReaderOverlayUi,
    currentSourceName: String,
    candidates: List<SearchResult>,
    onApplySource: (SearchResult) -> Unit,
    onToggleCheckAuthor: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
        // 面板底取浮层色（ReaderThemeScope 里的 background，AppBottomSheet 默认就读它），候选行是卡片色 ——
        // 两者差一档，卡片才浮得起来；同色时候选行会糊进底色，看起来像没有卡片。
        // 标题行带进度分数与「重新搜索」，自己排，不用面板自带的居中标题。
        // 候选列表会滚到手势条下面：导航栏高度垫在各状态末尾，不让面板在外面垫
        AppBottomSheet(
            onDismissRequest = onDismiss,
            padNavigationBar = false,
        ) {
        // 底部留白与导航栏高度垫在各状态的末尾（从前垫在这一层上，列表在手势条上方就截断了）
        val bottomInset = Dimens.gapXL + sheetBottomInset()
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.screenPadding, vertical = Dimens.gapS),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("换源", style = MiuixTheme.textStyles.headline2, fontWeight = FontWeight.Medium)
                    // 告诉用户现在读的是哪个源 —— 换源列表里刻意不含当前源，不标出来就无从对比
                    currentSourceName.takeIf { it.isNotBlank() }?.let {
                        Text(
                            "当前：$it",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                // 边搜边出，让用户看得见还在搜、搜了多少，而不是干等一个转圈
                if (overlay.searchingSources) {
                    Text(
                        "${overlay.sourcesDone}/${overlay.sourcesTotal}",
                        style = MiuixTheme.textStyles.body2,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                } else if (!overlay.changingSource) {
                    // 会话内复用上次结果；书源有增删/站点恢复时用这个主动重搜
                    AppTextButton("重新搜索", onClick = onRefresh)
                }
            }
            // 搜索进度拉成整行进度条：右上角只留分数，比「搜索中 98/380」一坨小字好扫
            if (overlay.searchingSources && overlay.sourcesTotal > 0) {
                DeterminateProgressBar(
                    progress = (overlay.sourcesDone.toFloat() / overlay.sourcesTotal).coerceIn(0f, 1f),
                    modifier = Modifier.padding(
                        horizontal = Dimens.screenPadding,
                        vertical = Dimens.gapXS,
                    ),
                )
            }
            // 作者匹配开关：书源返回的作者字段太脏，卡死了就一个源都换不到；
            // 拨一下就地重筛已搜到的结果，不重新发请求。走共享 SwitchRow，与设置页一套行式
            SwitchRow(
                title = "匹配作者",
                subtitle = if (overlay.checkAuthor) "只显示同一作者的书" else "只认书名，不看作者",
                checked = overlay.checkAuthor,
                onCheckedChange = onToggleCheckAuthor,
            )
            when {
                overlay.changingSource || (overlay.searchingSources && candidates.isEmpty()) -> Box(
                    Modifier.fillMaxWidth().padding(Dimens.gapXL).padding(bottom = bottomInset),
                    contentAlignment = Alignment.Center,
                ) { AppLoadingIndicator() }
                candidates.isEmpty() -> Text(
                    when {
                        // 中途关掉再开：半截且一个都没命中，别说成「都没有」
                        overlay.sourcesTotal > 0 && overlay.sourcesDone < overlay.sourcesTotal ->
                            "上次搜索未完成（已查 ${overlay.sourcesDone}/${overlay.sourcesTotal}）。" +
                                "可点右上角「重新搜索」继续找。"
                        overlay.checkAuthor ->
                            "其他 ${overlay.sourcesTotal} 个书源都没有找到这本书。" +
                                "可以关掉上面的「匹配作者」再看看 —— 不少书源的作者字段是空的或带前缀。"
                        else ->
                            "其他 ${overlay.sourcesTotal} 个书源都没有找到这本书"
                    },
                    Modifier
                        .padding(horizontal = Dimens.screenPadding, vertical = Dimens.gapL)
                        .padding(bottom = bottomInset),
                    style = MiuixTheme.textStyles.body2,
                )
                else -> LazyColumn(
                    Modifier.heightIn(max = Dimens.sheetListMaxHeight + bottomInset),
                    contentPadding = ContentListDefaults.listContentPadding(bottom = bottomInset),
                    verticalArrangement = Arrangement.spacedBy(ContentListDefaults.ListSpacing),
                ) {
                    if (overlay.sourcesTotal > 0 && overlay.sourcesDone < overlay.sourcesTotal &&
                        !overlay.searchingSources
                    ) {
                        item(key = "incomplete-hint") {
                            Text(
                                "上次搜索未完成（已查 ${overlay.sourcesDone}/${overlay.sourcesTotal}），以下为当时结果。可点右上角「重新搜索」。",
                                Modifier.padding(vertical = Dimens.gapS),
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                    items(candidates, key = { "${it.sourceId}|${it.bookUrl}" }) { c ->
                        ChangeSourceCandidateRow(
                            candidate = c,
                            onClick = { onApplySource(c) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 换源候选行：书源名（overline）→ 书名（主行）→ 作者/最新章（副行）。
 *
 * 从前主行是 sourceName、副行塞 sourceId（网址），书源没填名称时整行就是裸 URL，
 * 和截图里那条 `http://m.x2552.com` 一样 —— 认不出换的是哪本书、哪个站。
 */
@Composable
private fun ChangeSourceCandidateRow(
    candidate: SearchResult,
    onClick: () -> Unit,
) {
    val sourceLabel = candidate.sourceDisplayName()
    val supporting = buildList {
        candidate.author?.takeIf { it.isNotBlank() }?.let(::add)
        candidate.latestChapter?.takeIf { it.isNotBlank() }?.let(::add)
    }.joinToString("  ·  ")

    ContentListItem(
        onClick = onClick,
        overlineContent = {
            Text(
                sourceLabel,
                style = ContentListDefaults.overlineStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = if (supporting.isNotEmpty()) {
            {
                Text(
                    supporting,
                    style = ContentListDefaults.supportingStyle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else {
            null
        },
        content = {
            Text(
                candidate.title.ifBlank { "未命名" },
                style = ContentListDefaults.titleStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}

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
