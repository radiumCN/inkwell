package com.radium.inkwell.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import com.radium.inkwell.R
import com.radium.inkwell.ui.components.AppBottomSheet
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.ui.components.FlowBackground
import com.radium.inkwell.ui.components.PrimaryButton
import com.radium.inkwell.ui.components.SettingBlock
import com.radium.inkwell.ui.components.SettingFootnote
import com.radium.inkwell.ui.components.SettingGroup
import com.radium.inkwell.ui.components.SwatchChoice
import com.radium.inkwell.ui.components.cardMargin
import com.radium.inkwell.ui.components.settingsCardColor
import com.radium.inkwell.ui.theme.AppShapes
import com.radium.inkwell.util.AppIcon
import com.radium.inkwell.util.IconPaper
import com.radium.inkwell.util.IconShape
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 选桌面图标：上面一块「桌面上看起来」的预览，下面一张卡里两排 —— 图形（书签 / 翻页 / 叠页 / 段落）和
 * 纸色（暖纸 / 净白 / 青竹 / 墨蓝 / 夜色）分开选，按了底部的「换上这个图标」才真的去换。
 *
 * - **为什么要按钮**：图形和纸色要各点一下，点一下就换一次的话，桌面图标要跟着刷两回，中间那个组合根本不是想要的；
 *   有的启动器每换一次还会把图标从桌面挪回抽屉。先在面板里挑、看着预览定下来，再换一次。
 * - 从前是一列五行（52dp 的图 + 名字 + 一句说明）：挑图标靠看图，字反倒是主角，选中的勾和图隔着一整行字。
 *   现在图形排里每个都按**当前纸色**画，纸色排换了，上面四个跟着换色 —— 两排一起看就是要换上的样子。
 * - 预览按 **squircle 裁切**：最终形状由启动器的蒙版决定，这个 App 的目标桌面是 HyperOS，出来的是圆角方形
 *   （更新弹窗、关于页的应用图标也都裁 squircle）。
 */
@Composable
fun AppIconSheet(
    selected: AppIcon,
    onSelect: (AppIcon) -> Unit,
    onDismiss: () -> Unit,
) {
    // 从桌面上现在这个开始挑；面板关了不留（没按「换上」就是没换）
    var shape by remember { mutableStateOf(selected.shape) }
    var paper by remember { mutableStateOf(selected.paper) }
    val picked = AppIcon.of(shape, paper)
    AppBottomSheet(onDismissRequest = onDismiss, title = "应用图标") {
        Column(Modifier.padding(bottom = Dimens.gapL)) {
            DesktopPreview(picked)
            Text(
                picked.shape.description,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = cardMargin(), vertical = Dimens.gapS),
            )
            SettingGroup {
                SettingBlock {
                    ChoiceCaption("图形")
                    Row(
                        Modifier.fillMaxWidth().selectableGroup(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        IconShape.entries.forEach { s ->
                            SwatchChoice(
                                label = s.label,
                                selected = s == shape,
                                onClick = { shape = s },
                                cornerRadius = AppShapes.radiusMedium,
                            ) {
                                AppIconImage(AppIcon.of(s, paper), Dimens.swatch)
                            }
                        }
                    }
                }
                SettingBlock {
                    ChoiceCaption("纸色")
                    Row(
                        Modifier.fillMaxWidth().selectableGroup(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        IconPaper.entries.forEach { p ->
                            // 和主题外观的色板同一个样子：纸铺满圆，中间一点强调色。两层都取图标自己的资源，不另写颜色
                            SwatchChoice(label = p.label, selected = p == paper, onClick = { paper = p }) {
                                Image(
                                    painterResource(p.paper),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.matchParentSize(),
                                )
                                Image(
                                    painterResource(p.accent),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(Dimens.iconSm).clip(CircleShape),
                                )
                            }
                        }
                    }
                }
            }
            // 换完以后才用得上的话，放在最下面；从前压在标题底下，一打开先读到它
            SettingFootnote("换完后桌面图标几秒才刷新；个别启动器需重启桌面")
            PrimaryButton(
                text = if (picked == selected) "正在使用" else "换上这个图标",
                // 先收起再回调：调用方一回调就把面板摘掉，退场动画会被掐断（见 AppBottomSheet 的 dismiss）
                onClick = { dismiss { onSelect(picked) } },
                enabled = picked != selected,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = cardMargin())
                    .padding(top = Dimens.gapM),
            )
        }
    }
}

/** 卡里一排选项上面的小字（「图形」「纸色」） */
@Composable
private fun ChoiceCaption(text: String) {
    Text(
        text,
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    )
}

/**
 * 「桌面上看起来」：选中的图标摆在 HyperOS 的流动彩色底上（关于页头部那块），带应用名，左右两个空位当邻居。
 * 点下面哪个这里先变 —— 不用回到桌面才知道换上是什么样。整块是装饰，读屏跳过（选项自己念得到名字）。
 */
@Composable
private fun DesktopPreview(icon: AppIcon) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = cardMargin())
            .squircleClip(AppShapes.radiusLarge)
            .clearAndSetSemantics {},
    ) {
        FlowBackground(baseColor = settingsCardColor(), modifier = Modifier.matchParentSize())
        Row(
            Modifier
                .align(Alignment.Center)
                .padding(vertical = Dimens.gapXL),
            horizontalArrangement = Arrangement.spacedBy(Dimens.gapXL),
        ) {
            NeighborSlot()
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // 桌面上的图标大小（更新弹窗头部的应用图标也是这一档）
                AppIconImage(icon, Dimens.dialogHeroIcon, Modifier.squircleClip(AppShapes.radiusLarge))
                Text(
                    stringResource(R.string.app_name),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = Dimens.gapS),
                )
            }
            NeighborSlot()
        }
    }
}

/** 预览里左右的空位：一块半透明的圆角方块，只是告诉你「这是桌面上的一格」 */
@Composable
private fun NeighborSlot() {
    Box(
        Modifier
            .size(Dimens.dialogHeroIcon)
            .squircleSurface(
                MiuixTheme.colorScheme.surfaceContainer.copy(alpha = NEIGHBOR_ALPHA),
                AppShapes.radiusLarge,
            ),
    )
}

private const val NEIGHBOR_ALPHA = 0.45f

/**
 * 应用图标的样子：自适应图标的背景、前景两层（都是 108 × 108 的矢量）叠起来、只露中间 72 —— 和桌面上看到的是同一块。
 * 关于页、更新弹窗、图标面板都用它。
 *
 * 不直接 `painterResource(R.mipmap.…)`：Compose 读不了 adaptive-icon 的 XML，只认矢量和位图；
 * 从前为此另备了一套 432px 的位图做预览，和桌面上的图是两份东西。形状（squircle 裁切）由调用方给。
 */
@Composable
internal fun AppIconImage(icon: AppIcon, size: Dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clipToBounds(), contentAlignment = Alignment.Center) {
        val layer = Modifier.requiredSize(size * ADAPTIVE_LAYER_SCALE)
        Image(painterResource(icon.background), contentDescription = null, modifier = layer)
        Image(painterResource(icon.foreground), contentDescription = null, modifier = layer)
    }
}

/** 自适应图标每层 108，桌面只露中间 72：按 1.5 倍画、居中，四周那圈裁掉 */
private const val ADAPTIVE_LAYER_SCALE = 108f / 72f
