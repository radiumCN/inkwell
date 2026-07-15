package com.radium.inkwell.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.util.AppIcon

/**
 * 选桌面图标。
 *
 * 预览必须是**圆形裁切**的：桌面上大多数启动器就是这么裁的，方形预览会让人以为选中之后
 * 得到的是一张方图，结果装上一看边角全没了。这里的圆等于所见即所得。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppIconSheet(
    selected: AppIcon,
    onSelect: (AppIcon) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.padding(bottom = Dimens.gapXL)) {
            Text(
                "应用图标",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(
                    start = Dimens.screenPadding,
                    end = Dimens.screenPadding,
                    bottom = Dimens.gapXS,
                ),
            )
            Text(
                "换完之后桌面上的图标可能要等几秒才刷新；个别启动器得重启桌面才认。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(
                    start = Dimens.screenPadding,
                    end = Dimens.screenPadding,
                    bottom = Dimens.gapM,
                ),
            )

            AppIcon.entries.forEach { icon ->
                IconRow(
                    icon = icon,
                    selected = icon == selected,
                    onClick = { onSelect(icon) },
                )
            }
        }
    }
}

@Composable
private fun IconRow(icon: AppIcon, selected: Boolean, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Dimens.screenPadding, vertical = Dimens.gapM),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.gapL),
    ) {
        Box(
            Modifier
                .size(Dimens.swatch)
                .clip(CircleShape)
                // 选中的那个描一圈主色。只靠"某一项颜色深一点"是分不出来的
                .then(
                    if (selected) {
                        Modifier.border(2.dp, accent, CircleShape)
                    } else {
                        Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                    }
                ),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(icon.preview),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(Dimens.swatch)
                    .clip(CircleShape),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(icon.label, style = MaterialTheme.typography.bodyLarge)
            Text(
                icon.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (selected) {
            // 选中态与 OptionPicker / 主题色板一致：主色勾选图标，不再是自成一套的描边小圆点
            Icon(
                Icons.Default.Check,
                contentDescription = "已选中",
                Modifier.size(Dimens.iconMd),
                tint = accent,
            )
        }
    }
}
