package com.radium.inkwell.ui.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.radium.inkwell.core.source.SearchResult
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.ui.components.SwitchRow

/**
 * 换源面板。
 *
 * 独立于阅读菜单存在 —— 从前它画在 ReaderMenu 里，而正文加载失败时整个翻页容器都不渲染，
 * 菜单根本呼不出来，于是「章节加载失败」这个最需要换源的场景反而换不了源，只能退出去。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangeSourceSheet(
    state: ReaderUiState,
    candidates: List<SearchResult>,
    onApplySource: (SearchResult) -> Unit,
    onToggleCheckAuthor: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
        ModalBottomSheet(onDismissRequest = onDismiss) {
            Column(Modifier.fillMaxWidth().padding(bottom = Dimens.gapXL)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = Dimens.screenPadding, vertical = Dimens.gapS),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("换源", style = MaterialTheme.typography.titleMedium)
                        // 告诉用户现在读的是哪个源 —— 换源列表里刻意不含当前源，不标出来就无从对比
                        state.currentSourceName.takeIf { it.isNotBlank() }?.let {
                            Text(
                                "当前：$it",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    // 边搜边出，让用户看得见还在搜、搜了多少，而不是干等一个转圈
                    if (state.searchingSources) {
                        Text(
                            "搜索中 ${state.sourcesDone}/${state.sourcesTotal}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // 作者匹配开关：书源返回的作者字段太脏，卡死了就一个源都换不到；
                // 拨一下就地重筛已搜到的结果，不重新发请求。走共享 SwitchRow，与设置页一套行式
                SwitchRow(
                    title = "匹配作者",
                    subtitle = if (state.checkAuthor) "只显示同一作者的书" else "只认书名，不看作者",
                    checked = state.checkAuthor,
                    onCheckedChange = onToggleCheckAuthor,
                )
                when {
                    state.changingSource || (state.searchingSources && candidates.isEmpty()) -> Box(
                        Modifier.fillMaxWidth().padding(Dimens.gapXL),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }
                    candidates.isEmpty() -> Text(
                        if (state.checkAuthor) {
                            "其他 ${state.sourcesTotal} 个书源都没有找到这本书。" +
                                "可以关掉上面的「匹配作者」再看看 —— 不少书源的作者字段是空的或带前缀。"
                        } else {
                            "其他 ${state.sourcesTotal} 个书源都没有找到这本书"
                        },
                        Modifier.padding(horizontal = Dimens.screenPadding, vertical = Dimens.gapL),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    else -> LazyColumn(Modifier.heightIn(max = Dimens.sheetListMaxHeight)) {
                        items(candidates, key = { "${it.sourceId}|${it.bookUrl}" }) { c ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onApplySource(c) }
                                    .padding(horizontal = Dimens.rowHorizontal, vertical = Dimens.rowVertical),
                            ) {
                                // 书源名称打头。从前这行首位是 sourceId（其实是书源网址），
                                // 满屏 m.22biqu.net / cread.com# 谁也认不出哪个是哪个源
                                Text(
                                    c.sourceName.ifBlank { c.sourceId },
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    buildString {
                                        append(c.sourceId)
                                        c.latestChapter?.takeIf { it.isNotBlank() }?.let { append("  ·  $it") }
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
}
