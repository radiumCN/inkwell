package com.radium.inkwell.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 细滑块。
 *
 * M3 新版 Slider 的默认 thumb 是一根**竖条**，又高又抢眼 —— 阅读菜单里它把整条章节进度
 * 撑得比一行按钮还厚，而那本该是最不该占地方的一条。这里换成一个小圆点 + 4dp 细轨。
 *
 * 抽成共享组件是因为阅读菜单里有两条滑块（亮度、章节进度），书源校验里还有一条超时滑块 ——
 * 各写各的必然长得不一样。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SlimSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    activeColor: Color? = null,
    inactiveColor: Color? = null,
) {
    val active = activeColor ?: MaterialTheme.colorScheme.primary
    val inactive = inactiveColor ?: MaterialTheme.colorScheme.outlineVariant

    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        valueRange = valueRange,
        steps = steps,
        thumb = {
            Box(
                Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(if (enabled) active else inactive)
            )
        },
        track = { state ->
            val span = (state.valueRange.endInclusive - state.valueRange.start).takeIf { it > 0f } ?: 1f
            val fraction = ((state.value - state.valueRange.start) / span).coerceIn(0f, 1f)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(inactive),
                contentAlignment = Alignment.CenterStart,
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(if (enabled) active else inactive)
                )
            }
        },
    )
}
