package com.radium.inkwell.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 四个角各自的圆角半径，顺序同 `RoundedCornerShape`（起始上、结束上、结束下、起始下）。
 *
 * 为什么不用 Shape：Miuix 的 squircle（连续曲率圆角，`miuix-squircle`）是一组按 Dp 收半径的修饰符，
 * 不是 Shape —— 它用着色器画遮罩，所以边缘是抗锯齿的、曲线也和 Miuix 自己的卡片、按钮、面板一模一样。
 * 分组卡里的行要「第一行圆上角、最后一行圆下角」，就靠这个按角传半径。
 */
@Immutable
data class SquircleCorners(
    val topStart: Dp,
    val topEnd: Dp,
    val bottomEnd: Dp,
    val bottomStart: Dp,
) {
    /** 四个角全是直角 */
    val isSquare: Boolean
        get() = topStart == 0.dp && topEnd == 0.dp && bottomEnd == 0.dp && bottomStart == 0.dp

    companion object {
        fun all(radius: Dp) = SquircleCorners(radius, radius, radius, radius)

        val Square = all(0.dp)
    }
}

/**
 * 底色 + 把内容裁进 [corners] 的 squircle（Miuix `squircleSurface` 的按角版）。
 *
 * 四个角全直时只画底色、不裁：Miuix 的 squircle 修饰符不管半径是不是 0 都会开一个离屏层，
 * 分组卡中间那些行（一张卡里十几行）没必要每行都付这一层。
 */
@Composable
fun Modifier.squircleSurface(color: Color, corners: SquircleCorners): Modifier =
    if (corners.isSquare) {
        this.background(color)
    } else {
        this.squircleSurface(color, corners.topStart, corners.topEnd, corners.bottomEnd, corners.bottomStart)
    }

/**
 * 带圆角的「面」：Miuix `Surface` 的 squircle 版。提示条、阅读页的小提示、主题预览这类不是卡片、
 * 也不是按钮的面用它；卡片用 Miuix `Card`，按钮用 `AppButtons` 里的封装。
 *
 * 不直接用 Miuix `Surface(shape = …)`：它按 Shape 裁剪，圆角是普通圆弧，而 Miuix 自己的卡片、按钮、
 * 面板全是 squircle —— 两种弯法挨在一起，就是「圆角不统一」。
 *
 * @param borderColor 非 null 时沿 squircle 轮廓描一圈 [Dimens.strokeThin] 的边（主题预览框用）
 */
@Composable
fun AppSurface(
    cornerRadius: Dp,
    modifier: Modifier = Modifier,
    color: Color = MiuixTheme.colorScheme.surfaceContainer,
    contentColor: Color = MiuixTheme.colorScheme.onSurface,
    borderColor: Color? = null,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalContentColor provides contentColor) {
        Box(
            modifier
                // 与 Miuix Surface 一样：读屏把面里的内容当成一组按顺序念
                .semantics(mergeDescendants = false) { isTraversalGroup = true }
                .squircleSurface(color, cornerRadius)
                .then(
                    if (borderColor != null) Modifier.squircleBorder(Dimens.strokeThin, borderColor, cornerRadius)
                    else Modifier,
                ),
            propagateMinConstraints = true,
        ) {
            content()
        }
    }
}
