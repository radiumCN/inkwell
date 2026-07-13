package com.radium.inkwell.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

/** 空状态：图标 + 标题 + 提示 + 可选动作，全应用统一形态 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    hint: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                icon,
                contentDescription = null,
                Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.outlineVariant,
            )
            Text(
                title,
                Modifier.padding(top = 16.dp),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (hint != null) {
                Text(
                    hint,
                    Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    textAlign = TextAlign.Center,
                )
            }
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction, Modifier.padding(top = 8.dp)) {
                    Text(actionLabel)
                }
            }
        }
    }
}

/** 书封缩略图：有封面显示图片，无封面用书名首字占位 */
@Composable
fun BookCover(
    title: String,
    coverModel: Any?,
    modifier: Modifier = Modifier,
    placeholderChars: Int = 4,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 1.dp,
    ) {
        if (coverModel != null) {
            AsyncImage(
                model = coverModel,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(6.dp)) {
                Text(
                    title.take(placeholderChars),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** 书籍列表行：封面 + 标题/副标题/来源 + 尾部动作，搜索与发现共用 */
@Composable
fun BookListRow(
    title: String,
    subtitle: String?,
    caption: String?,
    coverModel: Any?,
    trailingLabel: String,
    trailingLoading: Boolean,
    onTrailing: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BookCover(
            title = title,
            coverModel = coverModel,
            modifier = Modifier.size(width = 48.dp, height = 64.dp),
            placeholderChars = 2,
        )
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!caption.isNullOrBlank()) {
                Text(
                    caption,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailingLoading) {
            CircularProgressIndicator(Modifier.size(24.dp))
        } else {
            TextButton(onClick = onTrailing) { Text(trailingLabel) }
        }
    }
}
