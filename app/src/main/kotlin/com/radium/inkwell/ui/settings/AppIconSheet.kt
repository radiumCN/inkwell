package com.radium.inkwell.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.radium.inkwell.ui.components.ContentListDefaults
import com.radium.inkwell.ui.components.ContentListItem
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.util.AppIcon
import androidx.compose.ui.text.style.TextAlign
import com.radium.inkwell.ui.components.AppBottomSheet
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 选桌面图标。
 *
 * 预览必须是**圆形裁切**的：桌面上大多数启动器就是这么裁的，方形预览会让人以为选中之后
 * 得到的是一张方图，结果装上一看边角全没了。这里的圆等于所见即所得。
 */
@Composable
fun AppIconSheet(
    selected: AppIcon,
    onSelect: (AppIcon) -> Unit,
    onDismiss: () -> Unit,
) {
    // 标题走 Miuix 面板自带的居中标题行；图标一共几个，面板按内容高度展开，不用分段
    AppBottomSheet(onDismissRequest = onDismiss, title = "应用图标") {
        Column(Modifier.padding(bottom = Dimens.gapXL)) {
            Text(
                "换完之后桌面上的图标可能要等几秒才刷新；个别启动器得重启桌面才认。",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                // 跟着上面居中的面板标题走，不然一行标题居中、一行说明靠左，读起来像两块
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = Dimens.screenPadding,
                        end = Dimens.screenPadding,
                        bottom = Dimens.gapM,
                    ),
            )

            Column(
                verticalArrangement = Arrangement.spacedBy(ContentListDefaults.ListSpacing),
            ) {
                AppIcon.entries.forEach { icon ->
                    IconRow(
                        icon = icon,
                        selected = icon == selected,
                        // 先收起再回调：调用方一回调就把面板摘掉，退场动画会被掐断（同 OptionPickerSheet）
                        onClick = { dismiss { onSelect(icon) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun IconRow(icon: AppIcon, selected: Boolean, onClick: () -> Unit) {
    val accent = MiuixTheme.colorScheme.primary
    ContentListItem(
        selected = selected,
        onClick = onClick,
        modifier = ContentListDefaults.rowChrome(),
        leadingContent = {
            Box(
                Modifier
                    .size(Dimens.swatch)
                    .clip(CircleShape)
                    // 选中的那个描一圈主色。只靠"某一项颜色深一点"是分不出来的
                    .then(
                        if (selected) {
                            Modifier.border(Dimens.strokeThick, accent, CircleShape)
                        } else {
                            Modifier.border(
                                Dimens.strokeThin,
                                MiuixTheme.colorScheme.dividerLine,
                                CircleShape,
                            )
                        },
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
        },
        trailingContent = if (selected) {
            {
                Icon(
                    MiuixIcons.Basic.Check,
                    contentDescription = "已选中",
                    Modifier.size(Dimens.iconMd),
                )
            }
        } else {
            null
        },
        supportingContent = {
            Text(icon.description, style = ContentListDefaults.supportingStyle)
        },
        contentPadding = ContentListDefaults.ComfortablePadding,
        content = {
            Text(icon.label, style = ContentListDefaults.titleStyle)
        },
    )
}
