package com.radium.inkwell.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 带加载态的按钮。
 *
 * 从前每处都是 `if (busy) CircularProgressIndicator(...) else Text(...)` 手写。
 * M3 的 CircularProgressIndicator 默认直径 40dp，比文字高得多 —— 一点下去按钮就被内容
 * 撑大一圈，看着像「点击动画把按钮放大了」。其实没有任何动画，是布局跳变。
 *
 * 这里把按钮高度钉死在 M3 的标准值，转圈只占 18dp，颜色跟随按钮的 contentColor。
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
            CircularProgressIndicator(
                Modifier.size(Dimens.buttonSpinner),
                strokeWidth = 2.dp,
                color = LocalContentColor.current,
            )
        }
    }
}
