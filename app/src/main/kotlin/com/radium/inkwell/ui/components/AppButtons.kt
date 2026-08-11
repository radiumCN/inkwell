package com.radium.inkwell.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * 带加载态的按钮。
 *
 * 加载指示用 Expressive [AppLoadingIndicator]（钉 [Dimens.buttonSpinner]），叠在文字上
 * 而不是替换文字 —— 按钮宽度也就不会跟着内容一起跳。
 *
 * 显式传 `shapes = ButtonDefaults.shapes()`：这是 Expressive 的**按压形变**重载（按下时圆角
 * 收成 `shapes.small`，松手弹回）。不传就落到不带 `shapes` 的旧重载 —— 编译通过、颜色形状也对，
 * 只是按下去那一下没有形变，跟 `ButtonGroup` 里的按钮不是一套手感。形变时长走主题的
 * `MotionScheme`，系统关动画时随 `InstantMotionScheme` 一起静止。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
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
        shapes = ButtonDefaults.shapes(),
        enabled = enabled && !loading,
        modifier = modifier.defaultMinSize(minHeight = Dimens.buttonMinHeight),
    ) {
        ButtonContent(text, loading)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    OutlinedButton(
        onClick = onClick,
        shapes = ButtonDefaults.shapes(),
        enabled = enabled && !loading,
        modifier = modifier.defaultMinSize(minHeight = Dimens.buttonMinHeight),
    ) {
        ButtonContent(text, loading)
    }
}

/**
 * 图标按钮。和 [PrimaryButton] 同理，为的是**按压形变**那个 `shapes` 重载。
 *
 * 封成组件而不是各页自己传 `shapes`：顶栏里的图标按钮有五十来处，靠自觉传参数必然漏，
 * 漏掉的那几个按下去不形变，同一条顶栏上手感就分了两派。
 *
 * 形参照着 M3 [IconButton] 抄，迁移只是改个名字；[colors] 留出来是给阅读器浮层那类
 * 需要换内容色的地方。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: IconButtonColors = IconButtonDefaults.iconButtonColors(),
    content: @Composable () -> Unit,
) {
    IconButton(
        onClick = onClick,
        shapes = IconButtonDefaults.shapes(),
        modifier = modifier,
        enabled = enabled,
        colors = colors,
        content = content,
    )
}

/**
 * 顶栏返回键。
 *
 * 二十来个页面从前一字不差地重复「IconButton + AutoMirrored ArrowBack + contentDescription 返回」，
 * 抄漏一处就少一个读屏名字。收成一个组件后，无障碍名称和 RTL 镜像只有一个地方能写错。
 */
@Composable
fun BackButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    AppIconButton(onClick = onClick, modifier = modifier) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
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
        Text(text, color = if (loading) androidx.compose.ui.graphics.Color.Transparent else LocalContentColor.current)
        if (loading) {
            AppLoadingIndicator(
                color = LocalContentColor.current,
                size = Dimens.buttonSpinner,
            )
        }
    }
}
