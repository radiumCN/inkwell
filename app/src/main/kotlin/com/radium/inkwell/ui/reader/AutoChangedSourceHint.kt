package com.radium.inkwell.ui.reader

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.radium.inkwell.ui.components.AppSurface
import com.radium.inkwell.ui.components.AppTextButton
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.ui.components.blockTouchesBelow
import com.radium.inkwell.ui.components.smallGlassHighlight
import com.radium.inkwell.ui.components.squircleShape
import com.radium.inkwell.ui.theme.AppShapes
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.VerticalDivider
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Replace
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 自动换源之后浮在纸面底部的提示胶囊：换源图标 +「已换到书源「xx」」｜ 撤销。须在 [ReaderThemeScope] 里用。
 *
 * 从前是顶部一块实色的 12dp 圆角条，三处不对：阅读页里浮起来的东西（选字药丸、菜单栏）都是玻璃，只有它是贴上去的色块；
 * 离屏幕顶边只留 4dp、不让挖孔，摄像头正落在条子中间，还压住页眉和章首的大标题 —— 自动换源多半就停在章首
 * （读不出来的正是这一章）；说明字 13sp，「撤销」「知道了」两颗按钮 14sp 加粗，比消息本身还抢眼。
 *
 * 现在和 App 里的提示胶囊（AppSnackbar）、发现页的书源胶囊同一个样子：随文案收窄的玻璃胶囊（和选字药丸同一种玻璃，
 * 不投影），调用方把它放在页脚那行上面 —— 页码、进度、时间照样露着，「撤销」在拇指区。
 * 只留「撤销」一个动作：「知道了」和到点自己收起是一回事，去掉后强调色只剩真正要紧的那一个。
 *
 * 书源名太长时只截书源名（「」里面截），后引号和「撤销」始终露全。
 */
@Composable
internal fun AutoChangedSourceHint(
    sourceName: String,
    onUndo: () -> Unit,
    /** 录着翻页区的图层（见 ReaderScreen）；null 时退回实色底 */
    pageBackdrop: LayerBackdrop?,
    modifier: Modifier = Modifier,
) {
    val scheme = MiuixTheme.colorScheme
    val overlayColor = readerOverlayColor()
    val ink = scheme.onSurface
    val glass = pageBackdrop != null
    // 「撤销」按玻璃最坏的底推（底下透上来的是正文）：纸上刚好够线的褐，压到深一档的玻璃上就不够了
    val action = remember(scheme.primary, overlayColor, ink, glass) {
        if (glass) readerGlassAction(scheme.primary, overlayColor, ink) else scheme.primary
    }
    val shape = remember { squircleShape(AppShapes.radiusExtraLarge) }
    AppSurface(
        // 56dp 高的胶囊，28dp 的半径就是两头全圆
        cornerRadius = AppShapes.radiusExtraLarge,
        modifier = modifier
            .readerGlass(pageBackdrop, shape, overlayColor, smallGlassHighlight(dark = overlayColor.luminance() < 0.5f))
            // 点在字上别漏给纸面（翻页、唤出菜单）
            .blockTouchesBelow(),
        color = if (glass) Color.Transparent else overlayColor,
    ) {
        Row(
            Modifier.padding(
                start = Dimens.gapL,
                end = Dimens.gapXS,
                top = Dimens.gapXS,
                bottom = Dimens.gapXS,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                MiuixIcons.Replace,
                contentDescription = null,
                tint = ink,
                modifier = Modifier.size(Dimens.iconSm),
            )
            Spacer(Modifier.width(Dimens.gapS))
            // 三段字合成一个读屏节点；liveRegion：胶囊一出来 TalkBack 就念，看不见的人也知道书源被换了
            Row(
                Modifier
                    .weight(1f, fill = false)
                    .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("已换到来源「", style = MiuixTheme.textStyles.body2, color = ink, maxLines = 1)
                Text(
                    sourceName,
                    style = MiuixTheme.textStyles.body2,
                    color = ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text("」", style = MiuixTheme.textStyles.body2, color = ink, maxLines = 1)
            }
            VerticalDivider(
                Modifier
                    .padding(start = Dimens.gapM)
                    .height(Dimens.iconSm),
            )
            AppTextButton("撤销", onClick = onUndo, contentColor = action)
        }
    }
}
