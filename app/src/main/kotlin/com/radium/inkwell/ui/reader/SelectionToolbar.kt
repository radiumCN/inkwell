package com.radium.inkwell.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.radium.inkwell.ui.components.AppIconButton
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.ui.components.PrimaryButton

/**
 * 选中文字后的操作条。
 *
 * 动作行用 Expressive [HorizontalFloatingToolbar]（底部居中的浮动药丸）；
 * 替换表单仍用贴底 [Surface]（表单宽度与输入需要全宽，不适合塞进药丸）。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
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

    if (replacing) {
        Surface(
            modifier.fillMaxWidth().navigationBarsPadding(),
            color = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = MaterialTheme.shapes.large,
        ) {
            Column(Modifier.padding(horizontal = Dimens.listHorizontal, vertical = Dimens.listVertical)) {
                Text(
                    "「$selectedText」",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
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
                    TextButton(onClick = {
                        replacing = false
                        replacement = ""
                    }) { Text("取消") }
                    PrimaryButton(
                        text = "保存规则",
                        onClick = {
                            onReplace(replacement)
                            replacing = false
                            replacement = ""
                        },
                    )
                }
            }
        }
    } else {
        Column(
            modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = Dimens.listHorizontal, vertical = Dimens.listVertical),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "「$selectedText」",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .padding(bottom = Dimens.gapS)
                    .fillMaxWidth(),
            )
            HorizontalFloatingToolbar(
                expanded = true,
                colors = FloatingToolbarDefaults.standardFloatingToolbarColors(),
            ) {
                AppIconButton(onClick = onCopy) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "复制")
                }
                // 最常用的动作：把这句话从本书里删掉
                AppIconButton(onClick = onPurify) {
                    Icon(Icons.Default.CleaningServices, contentDescription = "净化")
                }
                AppIconButton(onClick = { replacing = true }) {
                    Icon(Icons.Default.SwapHoriz, contentDescription = "替换")
                }
                AppIconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "取消")
                }
            }
        }
    }
}
