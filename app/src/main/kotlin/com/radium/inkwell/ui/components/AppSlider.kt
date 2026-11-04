package com.radium.inkwell.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 全应用统一的滑块：Miuix [Slider]（HyperOS 亮度条那种胶囊粗轨，拖动时带触感反馈）。
 *
 * 封一层是为了两件 Miuix 默认值不管的事：
 * - **读屏名字**（[label]）：不给的话读屏只念一个百分比，不知道这条在调什么；旁边印着的标签 Text
 *   在滑块节点之外，念不到它身上。
 * - **阅读器浮层换色**（[activeColor] / [inactiveColor]）：那里的底是纸色，不是主题的卡片色，
 *   主题 primary 在深色纸上会糊掉。其余地方一律不传，走主题。
 *
 * @param height 轨道高度。默认 Miuix 的 28dp；夹在两行字之间的（阅读底栏进度）传 [Dimens.sliderCompact]
 */
@Composable
fun AppSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    /** 松手时回调（主题设置靠它在拖完之后才落库，而不是每一帧都写） */
    onValueChangeFinished: (() -> Unit)? = null,
    activeColor: Color? = null,
    inactiveColor: Color? = null,
    label: String? = null,
    height: Dp = SliderDefaults.MinHeight,
) {
    val scheme = MiuixTheme.colorScheme
    val colors = if (activeColor == null && inactiveColor == null) {
        SliderDefaults.sliderColors()
    } else {
        SliderDefaults.sliderColors(
            foregroundColor = activeColor ?: scheme.primary,
            backgroundColor = inactiveColor ?: scheme.sliderBackground,
        )
    }
    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = if (label != null) modifier.semantics { contentDescription = label } else modifier,
        enabled = enabled,
        valueRange = valueRange,
        steps = steps,
        onValueChangeFinished = onValueChangeFinished,
        height = height,
        colors = colors,
    )
}
