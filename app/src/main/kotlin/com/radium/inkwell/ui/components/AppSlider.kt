package com.radium.inkwell.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon

/**
 * 全应用统一的滑块。
 *
 * Expressive 默认轨高被 token 钉死（`Track` 内部会再 `.height(TrackHeight)`，外传
 * `Modifier.height` 盖不掉），竖条拇指还会把行高再抬一截。这里自绘
 * [Dimens.sliderTrack] 连续圆角轨 + [Dimens.sliderThumb] 圆点拇指：仍挂官方 [Slider]
 * （拖动、无障碍、步进都在），只换形。
 *
 * @param activeColor 覆盖 thumb 与已完成轨道色。**只给阅读器浮层用** —— 那里的底色是纸色，
 *   不是 `colorScheme.surface`，用主题 primary 会在深色纸上糊掉。其余地方一律传 null 走主题。
 * @param showStopIndicators 保留参数以兼容旧调用；细轨形态下不再画首尾停点。
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
    @Suppress("UNUSED_PARAMETER")
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
            val color = if (enabled) colors.thumbColor else colors.disabledThumbColor
            Box(
                Modifier
                    .size(Dimens.sliderThumb)
                    .hoverable(interactionSource)
                    .pointerHoverIcon(PointerIcon.Hand),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(Dimens.sliderThumb).background(color, CircleShape))
            }
        },
        track = { state ->
            CompactTrack(
                state = state,
                activeColor = if (enabled) colors.activeTrackColor else colors.disabledActiveTrackColor,
                inactiveColor = if (enabled) {
                    colors.inactiveTrackColor
                } else {
                    colors.disabledInactiveTrackColor
                },
            )
        },
    )
}

@Composable
private fun CompactTrack(
    state: SliderState,
    activeColor: Color,
    inactiveColor: Color,
) {
    val fraction = state.coercedValueAsFraction
    Canvas(Modifier.fillMaxWidth().height(Dimens.sliderTrack)) {
        val radius = CornerRadius(size.height / 2f, size.height / 2f)
        drawRoundRect(color = inactiveColor, cornerRadius = radius)
        val activeWidth = size.width * fraction
        if (activeWidth > 0f) {
            drawRoundRect(
                color = activeColor,
                size = Size(activeWidth, size.height),
                cornerRadius = radius,
            )
        }
    }
}
