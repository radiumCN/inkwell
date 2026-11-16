package com.radium.inkwell.ui.bookshelf

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.state.ToggleableState
import com.radium.inkwell.data.db.entity.BookEntity
import com.radium.inkwell.data.db.entity.BookType
import com.radium.inkwell.ui.components.AppAlertDialog
import com.radium.inkwell.ui.components.ContentListDefaults
import com.radium.inkwell.ui.components.ContentListItem
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Text

/** 单本与多选共用：默认仅移出；本地文件删除必须再确认一次。 */
@Composable
internal fun RemoveFromShelfDialog(
    books: List<BookEntity>,
    onDismiss: () -> Unit,
    onRemove: (clearFiles: Boolean) -> Unit,
) {
    if (books.isEmpty()) return
    val ids = books.map { it.id }
    var clearFiles by remember(ids) { mutableStateOf(false) }
    var confirmDeletion by remember(ids) { mutableStateOf(false) }
    val hasLocal = books.any { it.type != BookType.NET }
    val hasNet = books.any { it.type == BookType.NET }
    val target = if (books.size == 1) "《${books.first().title}》" else "选中的 ${books.size} 本书"
    if (confirmDeletion) {
        AppAlertDialog(
            onDismissRequest = onDismiss,
            title = "删除本地文件？",
            text = "将移出$target，并删除应用内的本地书籍文件${if (hasNet) "、清除网络章节缓存" else ""}。文件删除后无法恢复，阅读记录会保留。",
            confirmText = "删除文件并移出",
            closeOnConfirm = true,
            onConfirm = { onRemove(true) },
        )
    } else {
        AppAlertDialog(
            onDismissRequest = onDismiss,
            title = "移出书架",
            text = "将$target 移出书架？\n" + if (hasLocal) {
                "本地文件和阅读记录会保留，可随时重新导入。"
            } else {
                "阅读记录会保留，重新加入后可继续阅读。移出后不再追更。"
            },
            confirmText = "移出",
            closeOnConfirm = true,
            onConfirm = {
                if (clearFiles && hasLocal) confirmDeletion = true else onRemove(clearFiles)
            },
        ) {
            ContentListItem(
                checked = clearFiles,
                onCheckedChange = { clearFiles = it },
                colors = ContentListDefaults.plainColors(),
                leadingContent = {
                    Checkbox(
                        state = ToggleableState(clearFiles),
                        onClick = null,
                        modifier = Modifier.clearAndSetSemantics {},
                    )
                },
            ) {
                Text(when {
                    hasLocal && hasNet -> "同时删除本地文件并清除章节缓存"
                    hasLocal -> "同时删除本地文件"
                    else -> "同时清除已缓存章节"
                })
            }
        }
    }
}
