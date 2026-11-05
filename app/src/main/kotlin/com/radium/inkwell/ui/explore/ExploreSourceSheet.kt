package com.radium.inkwell.ui.explore

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.radium.inkwell.ui.components.AppBottomSheet
import com.radium.inkwell.ui.components.AppTextButton
import com.radium.inkwell.ui.components.ContentListDefaults
import com.radium.inkwell.ui.components.ContentListItem
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.ui.components.SectionHeader
import com.radium.inkwell.ui.components.SearchField
import com.radium.inkwell.ui.components.cardMargin
import com.radium.inkwell.ui.components.groupedCardItem
import com.radium.inkwell.ui.components.pageScroll
import com.radium.inkwell.ui.components.sheetBottomInset
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 发现页的书源选择器（底部面板）：搜索 +「最近」置顶 + 全部书源，点行即换。
 *
 * 从顶栏标题的下拉菜单搬到底部 —— 书源数量没有上限（导入书源包的用户动辄几十上百个可发现源），
 * 下拉菜单平铺长列表既没法搜、又从屏幕左上展开够不着。面板形态与阅读器「换源」一致，
 * 入口则是书架隐藏区那种底部玻璃胶囊（[ExploreScreen] 的 SourceCapsule）：开面板与点选项都在拇指区。
 *
 * 分区与搜索态：
 * - 无搜索词：「最近」置顶（最近用过的源，新→旧，含当前）+「全部书源」（其余）。都按内容列表规范进卡片，
 *   当前源走单选行的选中态（淡强调底 + 行尾勾）。
 * - 有搜索词：平铺过滤结果（名称或网址命中），分组标题消失 —— 搜索时人要的是「找到那个」，不是浏览结构。
 * - 搜不到给「清除搜索」出口，别把人关在一个空面板里。
 */
@Composable
fun ExploreSourceSheet(
    sources: List<ExploreSource>,
    recentIds: List<String>,
    currentId: String?,
    onPick: (index: Int) -> Unit,
    onOpenManage: () -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    AppBottomSheet(
        onDismissRequest = onDismiss,
        title = "切换书源",
        // 列表会滚到手势条下面：导航栏高度垫进列表末尾，别让面板在外面垫（见 AppBottomSheet）
        padNavigationBar = false,
        endAction = {
            // 「我要的源不在列表里」的出路：缺源/导入/启停都去书源管理。收起面板再跳，别掐断退场动画
            AppTextButton(text = "管理", onClick = { dismiss { onOpenManage() } })
        },
    ) {
        val bottomInset = sheetBottomInset()
        val trimmed = query.trim()
        Column(Modifier.fillMaxWidth()) {
            SearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = "搜索书源名称或网址",
                modifier = Modifier
                    .padding(horizontal = cardMargin())
                    .padding(vertical = Dimens.gapS),
            )
            // 「最近」按 recentIds 的顺序取（新→旧）；「全部」不含它们，免得同名列两遍
            val recent = remember(sources, recentIds) {
                recentIds.mapNotNull { id -> sources.indexOfFirst { it.id == id }.takeIf { it >= 0 } }
            }
            val rest = remember(sources, recent) {
                sources.filterIndexed { i, _ -> i !in recent.toSet() }
            }
            LazyColumn(
                modifier = Modifier.fillMaxWidth().pageScroll(),
                contentPadding = PaddingValues(bottom = Dimens.gapXL + bottomInset),
                overscrollEffect = null,
            ) {
                if (trimmed.isNotEmpty()) {
                    val hits = sources.filter {
                        it.name.contains(trimmed, ignoreCase = true) || it.id.contains(trimmed, ignoreCase = true)
                    }
                    if (hits.isEmpty()) {
                        item(key = "no-match") {
                            Column(
                                Modifier.fillMaxWidth().padding(Dimens.gapXL),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    "没有匹配「$trimmed」的书源",
                                    style = MiuixTheme.textStyles.body2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                                AppTextButton("清除搜索", onClick = { query = "" })
                            }
                        }
                    } else {
                        itemsIndexed(hits, key = { _, s -> s.id }) { i, s ->
                            SourceRow(
                                source = s,
                                selected = s.id == currentId,
                                onClick = { dismiss { onPick(sources.indexOfFirst { it.id == s.id }) } },
                                modifier = Modifier.groupedCardItem(i, hits.size),
                            )
                        }
                    }
                } else {
                    if (recent.isNotEmpty()) {
                        item(key = "recent-header") { SectionHeader("最近") }
                        itemsIndexed(
                            recent.map { sources[it] },
                            key = { _, s -> "recent|${s.id}" },
                        ) { i, s ->
                            SourceRow(
                                source = s,
                                selected = s.id == currentId,
                                onClick = { dismiss { onPick(recent[i]) } },
                                modifier = Modifier.groupedCardItem(i, recent.size),
                            )
                        }
                    }
                    if (rest.isNotEmpty()) {
                        item(key = "all-header") { SectionHeader("全部书源") }
                        itemsIndexed(rest, key = { _, s -> s.id }) { i, s ->
                            val index = sources.indexOfFirst { it.id == s.id }
                            SourceRow(
                                source = s,
                                selected = s.id == currentId,
                                onClick = { dismiss { onPick(index) } },
                                modifier = Modifier.groupedCardItem(i, rest.size),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 书源行：名称 + 「N 个分类」副行；当前源走单选行的选中态（读屏念「已选中」），行尾打勾 */
@Composable
private fun SourceRow(
    source: ExploreSource,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ContentListItem(
        selected = selected,
        onClick = onClick,
        modifier = modifier,
        supportingContent = {
            Text(
                "${source.categories.size} 个分类",
                style = ContentListDefaults.supportingStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = if (selected) {
            {
                Icon(
                    MiuixIcons.Basic.Check,
                    contentDescription = null,
                    modifier = Modifier.size(Dimens.iconSm),
                )
            }
        } else {
            null
        },
    ) {
        Text(
            source.name,
            style = ContentListDefaults.titleStyle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
