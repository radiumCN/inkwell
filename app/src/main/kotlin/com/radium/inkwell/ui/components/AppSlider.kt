package com.radium.inkwell.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/**
 * 全应用统一的滑块。
 *
 * Expressive 默认是**竖条 thumb**（Handle 又高又窄）+ thumb 与轨道留缝，看起来进度像
 * 「一段厚胶囊 + 一根竖线」，行高被拇指抬得很高 —— 阅读底栏、亮度、调色这种窄位放不下。
 * 这里统一成**圆点拇指 + 连续轨道**（`thumbTrackGapSize = 0`）：仍走官方 [Slider] /
 * [SliderDefaults]（拖动、无障碍、主题色都在），只是换掉默认形，不再自画一套 SlimSlider。
 *
 * @param activeColor 覆盖 thumb 与已完成轨道色。**只给阅读器浮层用** —— 那里的底色是纸色，
 *   不是 `colorScheme.surface`，用主题 primary 会在深色纸上糊掉。其余地方一律传 null 走主题。
 * @param showStopIndicators 首尾圆点停点。连续拖动（章节进度、亮度、调色）默认关。
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
    showStopIndicators: Boolean = false,
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
    val interactionSource = remember { MutableInteractionSource() }

    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        valueRange = valueRange,
        steps = steps,
        onValueChangeFinished = onValueChangeFinished,
        colors = colors,
        interactionSource = interactionSource,
        thumb = {
            SliderDefaults.Thumb(
                interactionSource = interactionSource,
                colors = colors,
                enabled = enabled,
                thumbSize = CompactThumbSize,
            )
        },
        track = { state ->
            SliderDefaults.Track(
                sliderState = state,
                enabled = enabled,
                colors = colors,
                // 去掉拇指两侧的缝：否则已完成段会缩成厚胶囊，和竖条拇指叠在一起更显高
                thumbTrackGapSize = 0.dp,
                drawStopIndicator = if (showStopIndicators) {
                    {
                        with(SliderDefaults) {
                            drawStopIndicator(
                                offset = it,
                                color = colors.thumbColor,
                                size = TrackStopIndicatorSize,
                            )
                        }
                    }
                } else {
                    null
                },
            )
        },
    )
}

/** 圆点拇指：边长对齐 [Dimens.iconSm]，远矮于 Expressive 默认竖条 Handle */
private val CompactThumbSize = DpSize(Dimens.iconSm, Dimens.iconSm)
