package com.radium.inkwell.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * 带加载态的按钮。
 *
 * 加载指示用 Expressive [AppLoadingIndicator]（钉 [Dimens.buttonSpinner]），叠在文字上
 * 而不是替换文字 —— 按钮宽度也就不会跟着内容一起跳。
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier.defaultMinSize(minHeight = ButtonDefaults.MinHeight),
    ) {
        ButtonContent(text, loading)
    }
}

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier.defaultMinSize(minHeight = ButtonDefaults.MinHeight),
    ) {
        ButtonContent(text, loading)
    }
}

/**
 * 转圈叠在文字上，而不是替换文字 —— 按钮宽度也就不会跟着内容一起跳。
 * 加载中的按钮是禁用的，所以不需要把文字藏起来防误点。
 */
@Composable
private fun ButtonContent(text: String, loading: Boolean) {
    Box(contentAlignment = Alignment.Center) {
        // 文字始终占位，撑住按钮的宽度；加载时让位给转圈
        Text(text, color = if (loading) androidx.compose.ui.graphics.Color.Transparent else LocalContentColor.current)
        if (loading) {
            AppLoadingIndicator(
                color = LocalContentColor.current,
                size = Dimens.buttonSpinner,
            )
        }
    }
}
