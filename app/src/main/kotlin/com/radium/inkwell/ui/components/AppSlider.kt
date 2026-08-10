package com.radium.inkwell.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * 全应用统一的滑块。
 *
 * **用 M3 默认形态**：Expressive 的 Slider 是厚轨 + 竖条 thumb + 首尾停点，thumb 在拖动时
 * 还会变窄。从前这里是个 `SlimSlider`，自己画了 14dp 圆点 + 4dp 细轨去压高度 —— 结果是
 * 一处「看着更清爽」换来两处代价：拖动反馈（thumb 变窄、轨道让位）整个丢了，而且换主题时
 * 它不跟着走，因为轨道颜色是自己 `background()` 上去的。厚一点是 Expressive 有意为之
 * （触控目标和视觉重量对齐），别再为了省几个 dp 把它画回去。
 *
 * 抽成组件是因为有三处在用（阅读菜单的亮度与章节进度、自定义纸色的四条 HSV、书源校验超时），
 * 各写各的必然长得不一样。
 *
 * @param activeColor 覆盖 thumb 与已完成轨道色。**只给阅读器浮层用** —— 那里的底色是纸色，
 *   不是 `colorScheme.surface`，用主题 primary 会在深色纸上糊掉。其余地方一律传 null 走主题。
 * @param showStopIndicators 首尾圆点停点。章节进度这类连续拖动关掉它 —— 窄栏里停点会像多长了
 *   一个拇指，和竖条 thumb 抢视觉。
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
    showStopIndicators: Boolean = true,
) {
    val colors = if (activeColor == null && inactiveColor == null) {
        SliderDefaults.colors()
    } else {
        val active = activeColor ?: MaterialTheme.colorScheme.primary
        val inactive = inactiveColor ?: MaterialTheme.colorScheme.surfaceContainerHighest
        SliderDefaults.colors(
            thumbColor = active,
            activeTrackColor = active,
            inactiveTrackColor = inactive,
        )
    }

    if (showStopIndicators) {
        Slider(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier,
            enabled = enabled,
            valueRange = valueRange,
            steps = steps,
            onValueChangeFinished = onValueChangeFinished,
            colors = colors,
        )
    } else {
        Slider(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier,
            enabled = enabled,
            valueRange = valueRange,
            steps = steps,
            onValueChangeFinished = onValueChangeFinished,
            colors = colors,
            track = { state ->
                SliderDefaults.Track(
                    sliderState = state,
                    enabled = enabled,
                    colors = colors,
                    drawStopIndicator = null,
                )
            },
        )
    }
}
