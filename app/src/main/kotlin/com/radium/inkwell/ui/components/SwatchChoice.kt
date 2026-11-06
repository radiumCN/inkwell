package com.radium.inkwell.ui.components

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 一排里挑一个的块（[Dimens.swatch] 见方）+ 下面一行名字：主题外观的色板、应用图标面板的图形与纸色。
 *
 * 选中的描一圈主色、右下角一枚勾，名字换主色加粗 —— 一排色块只有一个勾不够醒目；没选中的描一圈细分隔线，
 * 浅色的块（净白、米色纸）放在白卡上才看得出边。从前主题色板自己画了一份、图标面板又是另一种（一列带字的行），
 * 两处「从几个里挑一个」长得不像一家。
 *
 * - 外层只定尺寸、托住右下角的勾；可点的块在里层 —— 按压反馈（Miuix 的压暗）要裁成块的形状，
 *   裁切若放在外层，会把伸出块外的勾一起裁掉。下沉（[sinkOnPress]）挂在外层，勾跟着一起沉。
 * - 描边单独叠一层、画在内容**上面**：Miuix 的 `squircleBorder` 画在内容底下（onDrawBehind），
 *   块里铺一张不透明的图标，描边就被整个盖住了。
 * - 整块是一个单选目标，带上名字：名字的 Text 在可点区之外，不这样读屏聚焦到块上什么都念不出。
 *
 * @param cornerRadius null 是圆形色板；给了就是这个半径的 squircle（图标预览：桌面上的图标就是这个形状）
 * @param content 块里画什么（底色、强调色的点、图标的两层），已经按块的形状裁好、内容居中
 */
@Composable
fun SwatchChoice(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    cornerRadius: Dp? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    val strokeWidth = if (selected) Dimens.strokeThick else Dimens.strokeThin
    val strokeColor = if (selected) scheme.primary else scheme.dividerLine
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        val press = remember { MutableInteractionSource() }
        Box(Modifier.size(Dimens.swatch).sinkOnPress(press)) {
            Box(
                Modifier
                    .matchParentSize()
                    .then(if (cornerRadius == null) Modifier.clip(CircleShape) else Modifier.squircleClip(cornerRadius))
                    .selectable(
                        selected = selected,
                        interactionSource = press,
                        indication = LocalIndication.current,
                        role = Role.RadioButton,
                        onClick = onClick,
                    )
                    .semantics { contentDescription = label },
                contentAlignment = Alignment.Center,
                content = content,
            )
            Box(
                Modifier
                    .matchParentSize()
                    .then(
                        if (cornerRadius == null) Modifier.border(strokeWidth, strokeColor, CircleShape)
                        else Modifier.squircleBorder(strokeWidth, strokeColor, cornerRadius),
                    ),
            )
            if (selected) {
                Icon(
                    MiuixIcons.Basic.Check,
                    contentDescription = null,
                    Modifier
                        .align(Alignment.BottomEnd)
                        .size(Dimens.iconSm)
                        .background(scheme.primary, CircleShape)
                        .padding(Dimens.gapXS),
                    tint = scheme.onPrimary,
                )
            }
        }
        Text(
            label,
            Modifier.padding(top = Dimens.gapXS),
            style = MiuixTheme.textStyles.footnote2,
            color = if (selected) scheme.primary else scheme.onSurfaceVariantSummary,
            fontWeight = if (selected) FontWeight.Medium else null,
        )
    }
}
