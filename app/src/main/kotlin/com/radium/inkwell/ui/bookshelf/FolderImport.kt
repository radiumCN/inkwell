package com.radium.inkwell.ui.bookshelf

import android.net.Uri
import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.radium.inkwell.data.repo.FolderBook
import com.radium.inkwell.data.repo.LocalFolderScanner
import com.radium.inkwell.ui.components.AppBottomSheet
import com.radium.inkwell.ui.components.AppDropdownMenu
import com.radium.inkwell.ui.components.AppIconButton
import com.radium.inkwell.ui.components.AppMenuItem
import com.radium.inkwell.ui.components.AppTextButton
import com.radium.inkwell.ui.components.CardViewport
import com.radium.inkwell.ui.components.ContentListDefaults
import com.radium.inkwell.ui.components.ContentListItem
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.ui.components.EmptyState
import com.radium.inkwell.ui.components.LoadingState
import com.radium.inkwell.ui.components.PrimaryButton
import com.radium.inkwell.ui.components.SectionHeader
import com.radium.inkwell.ui.components.SettingFootnote
import com.radium.inkwell.ui.components.cardMargin
import com.radium.inkwell.ui.components.groupedCardItem
import com.radium.inkwell.ui.components.pageScroll
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Notes
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 「导入本地书」的两条路：选文件（系统选文件页，可多选）、选文件夹（连同子文件夹找出所有的书，见 [FolderImportSheet]）。
 *
 * 书架顶栏的「＋」、空书架的「导入本地书」、首次打开的欢迎页都弹这一份，和各自的按钮放进同一个 Box、从按钮上弹出来。
 */
@Composable
internal fun ImportBooksMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onPickFiles: () -> Unit,
    onPickFolder: () -> Unit,
) {
    AppDropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        items = listOf(
            AppMenuItem(
                "选择文件",
                onClick = {
                    onDismiss()
                    onPickFiles()
                },
                summary = "txt / EPUB / MOBI，可以多选",
            ),
            AppMenuItem(
                "选择文件夹",
                onClick = {
                    onDismiss()
                    onPickFolder()
                },
                summary = "把文件夹里的书都找出来（含子文件夹）",
            ),
        ),
    )
}

/**
 * 选文件夹导入：找到的书先列出来勾，再一起导（同整理重复的书：一张定高的卡，主按钮写清数量）。
 *
 * - 还在找：转圈 +「已找到 N 本」；一本没有就说清这个文件夹里没有认得的格式；
 * - 概况行「「文件夹」里找到 N 本 · M 本已在书架」+ 全选 / 全不选（管的是没在书架上的那些）；
 * - 按子文件夹分组，小标题是在所选文件夹里的路径；行是文件名，副行格式和大小，已在书架的写在副行最前、默认不勾；
 * - 「导入 N 本」：取好勾选再收面板（面板收起时先回调 [onDismiss] 清掉状态），收起后走和多选文件同一条导入。
 */
@Composable
internal fun FolderImportSheet(
    state: FolderImportState,
    onToggle: (Uri) -> Unit,
    onSelectAll: (Boolean) -> Unit,
    onConfirm: (List<Uri>) -> Unit,
    onDismiss: () -> Unit,
) {
    AppBottomSheet(
        onDismissRequest = onDismiss,
        title = "导入文件夹",
        startAction = {
            AppIconButton(onClick = { dismiss() }) {
                Icon(MiuixIcons.Close, contentDescription = "关闭")
            }
        },
    ) {
        val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
        val listMax = (windowHeight * LIST_HEIGHT_FRACTION).coerceAtMost(Dimens.sheetListMaxHeight)
        when (state) {
            is FolderImportState.Scanning -> LoadingState(
                Modifier.heightIn(max = listMax),
                label = if (state.found == 0) "正在查找…" else "正在查找… 已找到 ${state.found} 本",
            )
            is FolderImportState.Ready -> if (state.books.isEmpty()) {
                EmptyState(
                    icon = MiuixIcons.Notes,
                    title = "没有找到书",
                    hint = "「${state.folderName}」里没有 txt / EPUB / MOBI 文件",
                    modifier = Modifier.heightIn(max = listMax),
                )
            } else {
                FolderBookList(state, listMax, onToggle, onSelectAll, onConfirm = { uris -> dismiss { onConfirm(uris) } })
            }
        }
    }
}

@Composable
private fun FolderBookList(
    state: FolderImportState.Ready,
    listMax: Dp,
    onToggle: (Uri) -> Unit,
    onSelectAll: (Boolean) -> Unit,
    onConfirm: (List<Uri>) -> Unit,
) {
    val groups = remember(state.books) { state.books.groupBy { it.folder }.toList() }
    val notOnShelf = remember(state.books, state.onShelf) { state.books.count { it.uri !in state.onShelf } }
    val chosen = state.books.filter { it.uri in state.selected }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = cardMargin() + Dimens.listHorizontal, end = cardMargin()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                buildString {
                    append("「${state.folderName}」里找到 ${state.books.size} 本")
                    if (state.onShelf.isNotEmpty()) append(" · ${state.onShelf.size} 本已在书架")
                },
                Modifier.weight(1f),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            if (notOnShelf > 0) {
                val all = state.books.all { it.uri in state.onShelf || it.uri in state.selected }
                AppTextButton(if (all) "全不选" else "全选", onClick = { onSelectAll(!all) })
            }
        }
        // 视口裁成卡的圆上角：一滚，顶上切过的是卡中间的行，不裁的话卡顶是一刀直线（见 CardViewport）
        CardViewport(Modifier.padding(top = Dimens.gapS).heightIn(max = listMax)) {
            LazyColumn(modifier = Modifier.pageScroll(), overscrollEffect = null) {
                groups.forEach { (folder, books) ->
                    item(key = "h:$folder") {
                        // 所选文件夹自己里的书，小标题就是它的名字
                        SectionHeader(if (folder.isEmpty()) state.folderName else "${state.folderName} / $folder")
                    }
                    itemsIndexed(books, key = { _, b -> b.uri.toString() }) { i, book ->
                        FolderBookRow(
                            book = book,
                            onShelf = book.uri in state.onShelf,
                            checked = book.uri in state.selected,
                            onToggle = { onToggle(book.uri) },
                            modifier = Modifier.groupedCardItem(i, books.size),
                        )
                    }
                }
                item(key = "foot") {
                    SettingFootnote(
                        if (state.truncated) {
                            "书太多，只列出了前 ${LocalFolderScanner.MAX_BOOKS} 本。可以分几个子文件夹导入。"
                        } else {
                            "书会复制一份到应用里，原文件不动。同一本已在书架的会自动跳过。"
                        },
                        Modifier.padding(top = Dimens.gapS, bottom = Dimens.gapM),
                    )
                }
            }
        }
        PrimaryButton(
            text = if (chosen.isEmpty()) "导入" else "导入 ${chosen.size} 本",
            onClick = { onConfirm(chosen.map { it.uri }) },
            enabled = chosen.isNotEmpty(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = cardMargin())
                .padding(top = Dimens.gapM, bottom = Dimens.gapL),
        )
    }
}

/** 一本：文件名；副行「已在书架 · EPUB · 1.1 MB」。整行一个复选语义目标，行尾的勾只是展示 */
@Composable
private fun FolderBookRow(
    book: FolderBook,
    onShelf: Boolean,
    checked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    ContentListItem(
        checked = checked,
        onCheckedChange = { onToggle() },
        modifier = modifier,
        // 勾没勾只看行尾的勾选框，行不变色（同多选行）
        colors = ContentListDefaults.plainColors(),
        trailingContent = {
            Checkbox(
                state = ToggleableState(checked),
                onClick = null,
                modifier = Modifier.clearAndSetSemantics {},
            )
        },
        supportingContent = {
            Text(
                buildString {
                    if (onShelf) append("已在书架 · ")
                    append(book.format)
                    if (book.size > 0) append(" · ").append(Formatter.formatShortFileSize(context, book.size))
                },
                style = ContentListDefaults.supportingStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
    ) {
        Text(
            book.name,
            style = ContentListDefaults.titleStyle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 列表最多占屏高的一半（同整理重复的书），再多就在卡里滚 */
private const val LIST_HEIGHT_FRACTION = 0.5f
