package com.radium.inkwell.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import com.radium.inkwell.ui.theme.AppShapes
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.foundation.clickable
import top.yukonga.miuix.kmp.squircle.squircleClip

/**
 * 主按钮：Miuix 实心强调色按钮（`buttonColorsPrimary`），带加载态。
 *
 * 加载指示用 [AppLoadingIndicator]（钉 [Dimens.buttonSpinner]），叠在文字上而不是替换文字 ——
 * 按钮宽度也就不会跟着内容一起跳。
 *
 * 按压反馈是 Miuix 主题提供的 `LocalIndication`（HyperOS 那种按下压暗），不用自己传。
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
        minHeight = Dimens.buttonMinHeight,
        colors = ButtonDefaults.buttonColorsPrimary(),
        modifier = modifier,
    ) {
        ButtonContent(text, loading)
    }
}

/**
 * 次按钮：Miuix 默认的浅灰实心按钮（`buttonColors`，底是 `secondaryVariant`）。
 *
 * HyperOS 没有描边按钮 —— 对话框里「取消 | 确定」就是一灰一彩两块实心。M3 时代这里是 OutlinedButton，
 * 换过来之后一排按钮里不会再夹一圈描边。
 */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        minHeight = Dimens.buttonMinHeight,
        colors = ButtonDefaults.buttonColors(),
        modifier = modifier,
    ) {
        ButtonContent(text, loading)
    }
}

/**
 * 卡片里的小号实心按钮（阅读设置的字号加减）。底色是 `surfaceContainerHighest` ——
 * 和同一张卡里 [SegmentedChoice] 的未选中段同一档，不是卡片上再浮一块默认按钮灰。
 *
 * 高度与圆角也对齐 [SegmentedChoice] 的槽（48dp、[AppShapes.radiusMedium]）：它俩在阅读设置里上下挨着，
 * Miuix 按钮默认的 16dp 圆角配 12dp 的槽，同一张卡里两种弯法，装上就被指出「圆角不统一」。
 */
@Composable
fun TonalButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val scheme = MiuixTheme.colorScheme
    Button(
        onClick = onClick,
        enabled = enabled,
        cornerRadius = AppShapes.radiusMedium,
        minHeight = Dimens.touchTarget,
        // 上下不留内边距、高度交给 minHeight：默认的 13dp 上下边距加上 17sp 的行高会撑到 49~50dp，比槽高出一线
        insideMargin = PaddingValues(horizontal = Dimens.gapL),
        colors = ButtonDefaults.buttonColors(
            color = scheme.surfaceContainerHighest,
            contentColor = scheme.onSurfaceContainerHighest,
        ),
        modifier = modifier,
    ) {
        ButtonContent(text, loading = false)
    }
}

/**
 * 图标按钮。包一层的理由有两个：
 *
 * - **触控下限**：Miuix `IconButton` 默认 40×40dp，低于 48dp（[Dimens.touchTarget]）。这里把最小尺寸抬上去，
 *   按压的圆底也跟着大一圈 —— 可点区与看得见的反馈是同一块，不会出现「点在圆外也触发」。
 * - **禁用态**：Miuix 的图标按钮禁用时只是不响应，图标颜色不变，看不出它点不了。这里把内容色压到 38%。
 *
 * @param contentColor 图标色。默认沿用外层（顶栏上是 onSurface）；阅读器浮层、删除这类要换色的地方传
 */
@Composable
fun AppIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentColor: Color = Color.Unspecified,
    backgroundColor: Color = Color.Unspecified,
    content: @Composable () -> Unit,
) {
    val base = if (contentColor.isSpecified()) contentColor else LocalContentColor.current
    IconButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        backgroundColor = backgroundColor,
        minWidth = Dimens.touchTarget,
        minHeight = Dimens.touchTarget,
    ) {
        CompositionLocalProvider(
            LocalContentColor provides if (enabled) base else base.copy(alpha = base.alpha * DISABLED_ALPHA),
            content = content,
        )
    }
}

/**
 * 顶栏返回键。
 *
 * 二十来个页面从前一字不差地重复「IconButton + ArrowBack + contentDescription 返回」，
 * 抄漏一处就少一个读屏名字。收成一个组件后，无障碍名称和 RTL 镜像只有一个地方能写错。
 * 图标用 Miuix 的 [MiuixIcons.Back]（HyperOS 那条细线箭头），它不会自动镜像，RTL 下这里自己翻。
 */
@Composable
fun BackButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    AppIconButton(onClick = onClick, modifier = modifier) {
        Icon(
            MiuixIcons.Back,
            contentDescription = "返回",
            modifier = if (rtl) Modifier.graphicsLayer { scaleX = -1f } else Modifier,
        )
    }
}

/**
 * 行内文字按钮（展开/收起、撤销、停止校验、书籍行尾的「加入」）：强调色文字、无底色，按下时压一层 Miuix 反馈。
 *
 * Miuix 的 `TextButton` 只收一个字符串、而且是**有底色**的按钮，不是这种文字链；这里自己排。
 * 按压压暗的那一块裁成 [AppShapes.radiusMedium] 的 squircle（和 Miuix 按钮同一种弯法）。
 * 不要拿它冒充 [PrimaryButton] / [SecondaryButton] —— 那是实心主操作。
 *
 * 可点高度保底 [Dimens.touchTarget]（文字本身只有一行高），左右留 [Dimens.gapM]。
 * 读屏角色要自己补（clickable 的 role）：不补的话读屏念不出「按钮」，听着不像能点的东西。
 *
 * @param contentColor 文字色。默认强调色；危险操作（停止、删除）传 `error`
 */
@Composable
fun AppTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = Dimens.gapM),
    contentColor: Color = MiuixTheme.colorScheme.primary,
    content: @Composable RowScope.() -> Unit,
) {
    val tint = if (enabled) contentColor else contentColor.copy(alpha = contentColor.alpha * DISABLED_ALPHA)
    CompositionLocalProvider(LocalContentColor provides tint) {
        Box(
            modifier
                .defaultMinSize(minHeight = Dimens.touchTarget)
                .squircleClip(AppShapes.radiusMedium)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .padding(contentPadding),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, content = content)
        }
    }
}

/**
 * [AppTextButton] 的纯文字版：字是 body2 + Medium，颜色跟按钮的内容色走。绝大多数文字按钮就是这样一行字 ——
 * 从前十几处各自手写同一行 `Text(…, body2, Medium)`，书源管理页还另起了个私有帮手，改一处字重就得改十几处。
 * 字号要不一样的（欢迎页那行小字链接、简介的「展开」）仍用带 content 的那个。
 */
@Composable
fun AppTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = Dimens.gapM),
    contentColor: Color = MiuixTheme.colorScheme.primary,
) {
    AppTextButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        contentPadding = contentPadding,
        contentColor = contentColor,
    ) {
        Text(text, style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.Medium)
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
        Text(
            text,
            style = MiuixTheme.textStyles.button,
            color = if (loading) Color.Transparent else LocalContentColor.current,
            maxLines = 1,
        )
        if (loading) {
            AppLoadingIndicator(
                color = LocalContentColor.current,
                size = Dimens.buttonSpinner,
            )
        }
    }
}

/** 禁用态内容的不透明度（与 Material / HyperOS 一致的 38%） */
private const val DISABLED_ALPHA = 0.38f

private fun Color.isSpecified(): Boolean = this != Color.Unspecified
