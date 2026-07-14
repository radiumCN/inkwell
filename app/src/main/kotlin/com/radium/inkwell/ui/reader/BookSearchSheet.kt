package com.radium.inkwell.ui.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.ui.components.SearchField

/**
 * 全书搜索。
 *
 * 结果**边搜边出**：几千章的书要逐章抓，全扫一遍好几分钟；而用户想找的那句话
 * 八成就在前几章 —— 干等一个进度条走完毫无道理。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookSearchSheet(
    state: ReaderUiState,
    onSearch: (String) -> Unit,
    onCancel: () -> Unit,
    onSelect: (ChapterHit) -> Unit,
    onDismiss: () -> Unit,
) {
    val hits = state.searchResults ?: return
    var keyword by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Dimens.rowHorizontal),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SearchField(
                    value = keyword,
                    onValueChange = { keyword = it },
                    placeholder = "在本书中搜索",
                    onSearch = { onSearch(keyword) },
                    modifier = Modifier.weight(1f),
                )
                if (state.searching) {
                    TextButton(onClick = onCancel) { Text("停止") }
                } else {
                    TextButton(onClick = { onSearch(keyword) }) { Text("搜索") }
                }
            }

            if (state.searching) {
                val total = state.chapterCount.coerceAtLeast(1)
                Column(Modifier.padding(horizontal = Dimens.rowHorizontal, vertical = 4.dp)) {
                    Text(
                        "已扫 ${state.searchProgress}/$total 章 · 命中 ${hits.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LinearProgressIndicator(
                        progress = { state.searchProgress.toFloat() / total },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                }
            }

            if (hits.isEmpty() && !state.searching && keyword.isNotBlank()) {
                Text(
                    "没有找到「$keyword」",
                    Modifier.padding(Dimens.rowHorizontal),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.padding(top = 4.dp))
            LazyColumn(Modifier.heightIn(max = Dimens.sheetListMaxHeight)) {
                items(hits, key = { "${it.chapterIndex}:${it.charOffset}" }) { hit ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(hit) }
                            .padding(horizontal = Dimens.rowHorizontal, vertical = 10.dp),
                    ) {
                        Text(
                            hit.chapterTitle,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            hit.excerpt,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
