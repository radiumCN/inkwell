package com.radium.inkwell.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon

/**
 * 全应用统一的滑块。
 *
 * M3 Expressive 默认是 **16dp 粗轨 + 4×44 竖条拇指**（`SliderDefaults.Track` /
 * `Thumb`），那一套官方布局天然垂直居中。阅读底栏要细轨圆点，官方没有对应 token：
 * `Track` 内部会再 `.height(TrackHeight)`，外传高度盖不掉。
 *
 * 这里仍挂官方 [Slider]（拖动、无障碍、步进），只换形。拇指/轨的**布局槽**都做成
 * [Dimens.sliderSlot]（= M3 `TrackHeight`），细线与圆点在槽内居中画 —— 槽高若跟
 * Slider 的 `requiredSizeIn(minHeight = TrackHeight)` 不一致，圆点会系统性偏上/偏下。
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
            // 槽高 = TrackHeight；圆点在槽内居中，与轨线共用同一条中线
            Box(
                Modifier
                    .width(Dimens.sliderThumb)
                    .height(Dimens.sliderSlot)
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
    // 与拇指同槽高；细线按像素中线画，不靠嵌套 Alignment（避免与 Slider 槽位再错一层）
    Canvas(Modifier.fillMaxWidth().height(Dimens.sliderSlot)) {
        val trackHeight = Dimens.sliderTrack.toPx()
        val top = (size.height - trackHeight) / 2f
        val radius = CornerRadius(trackHeight / 2f, trackHeight / 2f)
        val trackSize = Size(size.width, trackHeight)
        drawRoundRect(
            color = inactiveColor,
            topLeft = Offset(0f, top),
            size = trackSize,
            cornerRadius = radius,
        )
        val activeWidth = size.width * fraction
        if (activeWidth > 0f) {
            drawRoundRect(
                color = activeColor,
                topLeft = Offset(0f, top),
                size = Size(activeWidth, trackHeight),
                cornerRadius = radius,
            )
        }
    }
}
