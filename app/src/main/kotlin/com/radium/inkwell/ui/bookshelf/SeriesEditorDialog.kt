package com.radium.inkwell.ui.bookshelf

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.radium.inkwell.ui.components.AppAlertDialog
import com.radium.inkwell.ui.components.AppTextButton
import com.radium.inkwell.ui.components.CompactTextField
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal data class SeriesEditorVolume(val id: String, val title: String)

@Composable
internal fun SeriesEditorDialog(
    initialTitle: String,
    initialVolumes: List<SeriesEditorVolume>,
    creating: Boolean,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, List<String>) -> Unit,
) {
    var title by remember(initialTitle) { mutableStateOf(initialTitle) }
    var order by remember(initialVolumes) { mutableStateOf(initialVolumes) }
    fun move(from: Int, to: Int) {
        order = order.toMutableList().apply { add(to, removeAt(from)) }
    }
    AppAlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = if (creating) "新建合订本" else "管理分卷",
        confirmText = if (creating) "创建" else "保存",
        confirmEnabled = title.isNotBlank() && order.size >= 2,
        confirmLoading = saving,
        dismissEnabled = !saving,
        onConfirm = { onSave(title.trim(), order.map { it.id }) },
    ) {
        CompactTextField(title, { title = it.take(120) }, "合订本名称", readOnly = saving)
        order.forEachIndexed { index, volume ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${index + 1}. ${volume.title}",
                    Modifier.weight(1f),
                    style = MiuixTheme.textStyles.body2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                AppTextButton("上移", onClick = { move(index, index - 1) }, enabled = index > 0 && !saving)
                AppTextButton("下移", onClick = { move(index, index + 1) }, enabled = index < order.lastIndex && !saving)
                if (!creating && order.size > 2) {
                    AppTextButton("移出", onClick = { order = order.filterNot { it.id == volume.id } }, enabled = !saving)
                }
            }
        }
    }
}
