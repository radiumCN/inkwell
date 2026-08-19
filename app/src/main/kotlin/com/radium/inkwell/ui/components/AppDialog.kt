package com.radium.inkwell.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 确认 / 表单弹窗的统一出口。
 *
 * 各页从前直接挂 M3 [androidx.compose.material3.AlertDialog] + 右下角 [androidx.compose.material3.TextButton]：
 * 形状和主题对得上，但按钮落到不带 `shapes` 的文字链，按下去没有 Expressive 形变，
 * 跟页面上的 [PrimaryButton] / [SecondaryButton] 不是一套手感。输入框又各写
 * `OutlinedTextField`，对话框里那条描边框和顶栏/表单行的 tonal 填充框也对不齐。
 *
 * 这里用 [BasicAlertDialog] 自己排：标题 → 正文/内容 → 等宽「次要 | 主要」按钮。
 * 页面不要再手搓居中 [Surface] 冒充弹层，也不要再裸写 AlertDialog。
 *
 * 从 N 项里选一个仍走 [OptionPickerSheet]，不走对话框。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppAlertDialog(
    onDismissRequest: () -> Unit,
    title: String,
    confirmText: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    dismissText: String? = "取消",
    onDismiss: (() -> Unit)? = onDismissRequest,
    confirmEnabled: Boolean = true,
    confirmLoading: Boolean = false,
    dismissEnabled: Boolean = true,
    text: String? = null,
    content: @Composable (ColumnScope.() -> Unit)? = null,
) {
    BasicAlertDialog(onDismissRequest = onDismissRequest, modifier = modifier) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            color = AlertDialogDefaults.containerColor,
            tonalElevation = AlertDialogDefaults.TonalElevation,
        ) {
            Column(Modifier.padding(Dimens.gapXL)) {
                Text(
                    title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (text != null || content != null) {
                    Spacer(Modifier.height(Dimens.gapL))
                    Column(
                        Modifier
                            .heightIn(max = Dimens.dialogBodyMaxHeight)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(Dimens.gapM),
                    ) {
                        if (text != null) {
                            Text(
                                text,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        content?.invoke(this)
                    }
                }
                Spacer(Modifier.height(Dimens.gapXL))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Dimens.gapM),
                ) {
                    if (dismissText != null) {
                        SecondaryButton(
                            text = dismissText,
                            onClick = { (onDismiss ?: onDismissRequest)() },
                            enabled = dismissEnabled && !confirmLoading,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    PrimaryButton(
                        text = confirmText,
                        onClick = onConfirm,
                        enabled = confirmEnabled,
                        loading = confirmLoading,
                        modifier = if (dismissText != null) Modifier.weight(1f) else Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
