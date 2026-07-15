package com.radium.inkwell.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.ui.components.PrimaryButton

/**
 * 选中文字后的操作条。贴在屏幕底部而不是浮在选区旁边 ——
 * 浮动气泡要算避让（选区在顶部时往下、在底部时往上，还得避开挖孔），
 * 而这里真正要给的就三个动作，底部固定位置反而更好点。
 */
@Composable
fun SelectionToolbar(
    selectedText: String,
    onCopy: () -> Unit,
    onPurify: () -> Unit,
    onReplace: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var replacing by remember { mutableStateOf(false) }
    var replacement by remember { mutableStateOf("") }

    Surface(
        modifier.fillMaxWidth(),
        // 渲染在 ReaderThemeScope 内，直接吃语义色。靠顶部一道发丝线与正文分层，不投影 ——
        // 同色纸上的投影会糊成一道脏灰线（与阅读菜单顶/底栏同一处理）。
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(Modifier.padding(horizontal = Dimens.listHorizontal, vertical = Dimens.listVertical)) {
            Text(
                "「$selectedText」",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (replacing) {
                OutlinedTextField(
                    value = replacement,
                    onValueChange = { replacement = it },
                    label = { Text("替换成") },
                    placeholder = { Text("留空即删除") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = Dimens.gapS),
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = Dimens.gapS),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = { replacing = false }) { Text("取消") }
                    PrimaryButton(
                        text = "保存规则",
                        onClick = { onReplace(replacement) },
                    )
                }
            } else {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    TextButton(onClick = onCopy) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null)
                        Text(" 复制")
                    }
                    // 最常用的动作：把这句话从本书里删掉
                    TextButton(onClick = onPurify) {
                        Icon(Icons.Default.CleaningServices, contentDescription = null)
                        Text(" 净化")
                    }
                    TextButton(onClick = { replacing = true }) {
                        Icon(Icons.Default.SwapHoriz, contentDescription = null)
                        Text(" 替换")
                    }
                    TextButton(onClick = onDismiss) { Text("取消") }
                }
            }
        }
        }
    }
}
