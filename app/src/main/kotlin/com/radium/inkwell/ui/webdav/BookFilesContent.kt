package com.radium.inkwell.ui.webdav

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.state.ToggleableState
import com.radium.inkwell.data.repo.BookFileBackupItem
import com.radium.inkwell.data.repo.LocalBookBackupRepository
import com.radium.inkwell.ui.components.AppLoadingIndicator
import com.radium.inkwell.ui.components.AppTextButton
import com.radium.inkwell.ui.components.ContentListDefaults
import com.radium.inkwell.ui.components.ContentListItem
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.ui.components.PrimaryButton
import com.radium.inkwell.ui.components.SectionHeader
import com.radium.inkwell.ui.components.SegmentedChoice
import com.radium.inkwell.ui.components.SettingBlock
import com.radium.inkwell.ui.components.SettingFootnote
import com.radium.inkwell.ui.components.SettingGroup
import com.radium.inkwell.ui.components.cardMargin
import com.radium.inkwell.ui.components.groupedCardItem
import com.radium.inkwell.ui.components.horizontalOnly
import com.radium.inkwell.ui.components.pageScroll
import java.util.Locale
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun BookFilesContent(page: BookFilesUiState, viewModel: WebDavViewModel, padding: PaddingValues) {
    val selected = page.items.filter { it.key in page.selected }
    val done = page.results.count { it.value == null }
    val failed = page.results.size - done
    val enabled = !page.loading && !page.transferring
    val action = if (page.restore) "恢复" else "备份"
    val selectable = page.items.filter { it.key !in page.results || page.results[it.key] != null }
    val duplicateVersions = page.items.groupingBy { it.bookId }.eachCount()
    val unambiguous = selectable.filter { duplicateVersions[it.bookId] == 1 }

    // 只让开两侧：顶栏高度垫在列表开头、内容滚到毛玻璃底下；导航栏高度给底部的操作卡（没有操作卡时垫在列表末尾）。
    // 从前整块 padding(padding) 抬上去，列表在顶栏下面、手势条上面各截断一刀
    Column(Modifier.fillMaxSize().padding(padding.horizontalOnly())) {
        val hasActions = page.items.isNotEmpty()
        // 切「备份 / 恢复」回到顶上。不用 key() 重建整个列表：分段控件也在列表里，重建了它的滑块就没有动画
        val listState = remember(page.restore) { LazyListState() }
        LazyColumn(
            Modifier.weight(1f).pageScroll(),
            state = listState,
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + Dimens.gapS,
                bottom = if (hasActions) Dimens.gapS else padding.calculateBottomPadding(),
            ),
            overscrollEffect = null,
        ) {
            item(key = "mode") {
                SettingGroup {
                    SettingBlock {
                        SegmentedChoice(
                            options = listOf("备份到云端", "恢复到本机"),
                            selectedIndex = if (page.restore) 1 else 0,
                            onSelect = { if (!page.transferring && (it == 1) != page.restore) viewModel.loadBookFiles(it == 1) },
                        )
                    }
                }
            }
            item(key = "head") {
                SettingFootnote(
                    if (page.restore) "恢复后可继续原有阅读进度。换机后请先同步书架。"
                    else "备份目录：${LocalBookBackupRepository.DIRECTORY}/",
                )
                if (page.restore) {
                    AppTextButton(
                        "同步书架",
                        onClick = { viewModel.loadBookFiles(restore = true, syncShelf = true) },
                        modifier = Modifier.padding(horizontal = cardMargin()),
                        enabled = enabled,
                    )
                    if (duplicateVersions.values.any { it > 1 }) {
                        SettingFootnote("同一本书有多个备份时，请手动选择一个版本。")
                    }
                }
                page.error?.let { error ->
                    SettingFootnote(error, color = MiuixTheme.colorScheme.error)
                    if (page.items.isEmpty()) {
                        AppTextButton(
                            "重试",
                            onClick = { viewModel.loadBookFiles(page.restore) },
                            modifier = Modifier.padding(horizontal = cardMargin()),
                            enabled = enabled,
                        )
                    }
                }
                if (page.loading) {
                    Row(Modifier.fillMaxWidth().padding(Dimens.gapXL), horizontalArrangement = Arrangement.Center) {
                        AppLoadingIndicator()
                    }
                } else if (page.items.isEmpty() && page.error == null) {
                    SettingFootnote(if (page.restore) "没有可恢复的书籍" else "书架上没有可备份的本地书籍")
                } else if (page.items.isNotEmpty()) {
                    SectionHeader(
                        "选择要${action}的书籍",
                        trailing = if (unambiguous.isEmpty()) null else {
                            {
                                val allSelected = unambiguous.all { it.key in page.selected }
                                AppTextButton(
                                    if (allSelected) "取消全选" else "全选",
                                    onClick = viewModel::selectAllBookFiles,
                                    enabled = enabled,
                                )
                            }
                        },
                    )
                }
            }
            // 一张卡装下整个列表（HyperOS 的灰底白卡），不是一本一张小卡
            itemsIndexed(page.items, key = { _, it -> it.key }) { index, item ->
                val completed = item.key in page.results && page.results[item.key] == null
                BookFileRow(
                    modifier = Modifier.groupedCardItem(index, page.items.size),
                    item = item,
                    selected = item.key in page.selected,
                    enabled = enabled && !completed,
                    status = if (completed) "已$action" else page.results[item.key],
                    failed = page.results[item.key] != null,
                    showVersion = (duplicateVersions[item.bookId] ?: 0) > 1,
                    onToggle = { viewModel.selectBookFile(item) },
                )
            }
        }
        if (hasActions) {
            SettingGroup(Modifier.padding(bottom = padding.calculateBottomPadding())) {
                SettingBlock {
                    if (done > 0 || failed > 0) {
                        Text(
                            "已${action} $done 本" + if (failed > 0) " · 失败 $failed 本" else "",
                            style = MiuixTheme.textStyles.footnote1,
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Dimens.gapS),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val size = if (page.restore || selected.isEmpty()) ""
                        else " · ${formatWebDavBytes(selected.sumOf { it.size })}"
                        Text(
                            if (page.transferring) "正在$action…" else "已选 ${selected.size} 本$size",
                            modifier = Modifier.weight(1f),
                            style = MiuixTheme.textStyles.footnote1,
                        )
                        if (page.transferring) {
                            AppLoadingIndicator()
                            AppTextButton("取消", onClick = viewModel::cancelBookFileTransfer)
                        } else {
                            PrimaryButton(
                                text = if (page.restore) "恢复所选书籍" else "开始备份",
                                onClick = viewModel::transferBookFiles,
                                enabled = enabled && selected.isNotEmpty(),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BookFileRow(
    modifier: Modifier,
    item: BookFileBackupItem,
    selected: Boolean,
    enabled: Boolean,
    status: String?,
    failed: Boolean,
    showVersion: Boolean,
    onToggle: () -> Unit,
) {
    ContentListItem(
        modifier = modifier,
        checked = selected,
        onCheckedChange = { onToggle() },
        enabled = enabled,
        colors = ContentListDefaults.plainColors(),
        trailingContent = {
            Checkbox(
                state = ToggleableState(selected),
                onClick = null,
                modifier = Modifier.clearAndSetSemantics {},
            )
        },
        supportingContent = {
            val version = if (showVersion) " · ${item.remoteName.orEmpty().substringBeforeLast('.').takeLast(8)}" else ""
            Text(
                "${item.extension.uppercase(Locale.ROOT)} · ${formatWebDavBytes(item.size)}$version",
                style = ContentListDefaults.supportingStyle,
            )
            if (status != null) {
                Text(
                    status,
                    style = ContentListDefaults.supportingStyle,
                    color = if (failed) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.primary,
                )
            }
        },
    ) {
        Text(item.title, style = ContentListDefaults.titleStyle)
    }
}

internal fun formatWebDavBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var amount = bytes / 1024.0
    var unit = 0
    while (amount >= 1024 && unit < units.lastIndex) {
        amount /= 1024
        unit++
    }
    return String.format(Locale.ROOT, if (amount >= 10) "%.0f %s" else "%.1f %s", amount, units[unit])
}
