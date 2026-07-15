package com.radium.inkwell.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * 错误态。与 [EmptyState] 对称。
 *
 * 抽出来是因为每个页面各搓各的，而且**搓漏了**：RSS 文章列表加载失败时只甩一行字，
 * 连重试按钮都没有 —— 用户唯一能做的是退出去再进来。
 * 错误态的第一要务是给出口，不是把错误信息念一遍。
 */
@Composable
fun ErrorState(
    message: String,
    modifier: Modifier = Modifier,
    title: String = "加载失败",
    onRetry: (() -> Unit)? = null,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
    tertiaryLabel: String? = null,
    onTertiary: (() -> Unit)? = null,
) {
    Box(modifier.fillMaxSize().padding(Dimens.gapXXL), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.ErrorOutline,
                contentDescription = null,
                Modifier.size(Dimens.iconXL),
                tint = MaterialTheme.colorScheme.outlineVariant,
            )
            Text(
                title,
                Modifier.padding(top = Dimens.gapL),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Text(
                message,
                Modifier.padding(top = Dimens.gapS),
                style = MaterialTheme.typography.bodySmall,
                // 正文性文字用 onSurfaceVariant（对比度达标）而非 outline（浅色下仅约 3.9:1）
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            if (onRetry != null || onSecondary != null) {
                Spacer(Modifier.height(Dimens.gapXL))
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.gapM)) {
                    if (onRetry != null) {
                        SecondaryButton(text = "重试", onClick = onRetry)
                    }
                    if (secondaryLabel != null && onSecondary != null) {
                        PrimaryButton(text = secondaryLabel, onClick = onSecondary)
                    }
                }
            }
            if (tertiaryLabel != null && onTertiary != null) {
                Spacer(Modifier.height(Dimens.gapS))
                TextButton(onClick = onTertiary) { Text(tertiaryLabel) }
            }
        }
    }
}
