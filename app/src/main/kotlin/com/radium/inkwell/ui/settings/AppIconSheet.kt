package com.radium.inkwell.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import com.radium.inkwell.ui.components.ContentListDefaults
import com.radium.inkwell.ui.components.ContentListItem
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.ui.components.SettingGroup
import com.radium.inkwell.ui.components.cardMargin
import com.radium.inkwell.ui.theme.AppShapes
import com.radium.inkwell.util.AppIcon
import androidx.compose.ui.text.style.TextAlign
import com.radium.inkwell.ui.components.AppBottomSheet
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 选桌面图标。
 *
 * 预览按 **squircle 裁切**：图标是方图，最终形状由启动器的蒙版决定。这个 App 的目标桌面是
 * HyperOS，出来的图标是圆角方形（更新弹窗、关于页的应用图标也都裁 squircle），从前的圆形
 * 预览反而不是所见即所得。
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
                // 两件事都说完但压到一行：从前那句 33 个字折成两行，第二行只剩几个字
                "换完后桌面图标几秒才刷新；个别启动器需重启桌面",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                // 跟着上面居中的面板标题走，不然一行标题居中、一行说明靠左，读起来像两块
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = cardMargin())
                    .padding(bottom = Dimens.gapS),
            )

            // 几个图标拼成一张卡（面板里的列表也装进卡，见 AppBottomSheet）；从前一个图标一张小卡，
            // 卡和面板同一个白，平时看不见，选中的那张淡强调底像一块孤零零的圆角色块
            SettingGroup {
                AppIcon.entries.forEach { icon ->
                    IconRow(
                        icon = icon,
                        selected = icon == selected,
                        // 先收起再回调：调用方一回调就把面板摘掉，退场动画会被掐断（见 AppBottomSheet 的 dismiss）
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
        leadingContent = {
            Box(
                Modifier
                    .size(Dimens.swatch)
                    // squircle 裁切 + 同曲线描边，见 [AppIconSheet] 的 KDoc
                    .squircleClip(AppShapes.radiusMedium)
                    // 选中的那个描一圈主色。只靠"某一项颜色深一点"是分不出来的
                    .then(
                        if (selected) {
                            Modifier.squircleBorder(Dimens.strokeThick, accent, AppShapes.radiusMedium)
                        } else {
                            Modifier.squircleBorder(
                                Dimens.strokeThin,
                                MiuixTheme.colorScheme.dividerLine,
                                AppShapes.radiusMedium,
                            )
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(icon.preview),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    // 裁切挂在上面的 Box：方图裁成 HyperOS 蒙版形状
                    modifier = Modifier.size(Dimens.swatch),
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
        // 52dp 的图标预览比两行字高：它撑开行高，上下只留 8dp
        contentPadding = ContentListDefaults.MediaPadding,
        content = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(icon.label, style = ContentListDefaults.titleStyle)
                if (icon == AppIcon.DEFAULT) {
                    // 「默认」跟在标题后做成标签（同书源管理「仅发现」的标签法），中性灰而不是淡强调底：
                    // 行选中时底色就是淡强调，标签再淡强调就糊在行里了
                    Text(
                        "默认",
                        style = MiuixTheme.textStyles.footnote2,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onSecondaryVariant,
                        modifier = Modifier
                            .padding(start = Dimens.gapS)
                            .background(MiuixTheme.colorScheme.secondaryVariant, AppShapes.extraSmall)
                            // 只留左右：上下贴着 footnote2 的行高，标签和标题行一样矮
                            .padding(horizontal = Dimens.gapS),
                    )
                }
            }
        },
    )
}
