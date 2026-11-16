package com.radium.inkwell.ui.components

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 调色滑块：轨道画成渐变（拖到哪儿就是轨道上那儿的颜色），拇指是白圈里填当前色。阅读设置「自定义纸张」用。
 *
 * 为什么不用 [AppSlider]：它是「已填充 / 未填充」两段纯色，适合亮度、字号这种有多少之分的量；颜色没有多少，
 * 三条都填强调色的话看不出往哪拖会变成什么色（被指出过：自定义纸色那三条分不清谁是谁）。
 * Miuix 0.9.4 自带渐变轨的 `HsvHueSlider` 一族，但它们收不了取值范围（纸色浓度只给 0~0.25）、
 * 也没有松手回调（我们松手才写盘），所以照它的样子自己画。
 *
 * - 高度同 [AppSlider]（Miuix 的 28dp 胶囊），和面板里别的滑块一个粗细。
 * - 拇指圆心从左端半个轨高走到右端半个轨高，渐变的两端也画在这两点上：拇指底下的颜色就是它代表的颜色。
 * - 白圈 + 一圈淡影：轨道从近白到近黑都有，白圈在浅的那头靠影子勾边、在深的那头靠白圈本身。Miuix 滑块的拇指也是白的。
 * - 横着拖才接手（竖着划仍是面板滚动），点一下直接跳到那儿。拖动中每一下都回调 [onValueChange]，
 *   抬手（或点一下）回调一次 [onValueChangeFinished]。
 * - 读屏：[label] 是名字，上下滑调值（setProgress）。
 *
 * @param trackColors 轨道从左到右的颜色，均匀分布；只给一个就是纯色轨
 * @param thumbColor 拇指里填的颜色：当前值对应的那个颜色
 */
@Composable
fun AppColorSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    trackColors: List<Color>,
    thumbColor: Color,
    label: String,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val outline = MiuixTheme.colorScheme.dividerLine
    val onChange by rememberUpdatedState(onValueChange)
    val onFinished by rememberUpdatedState(onValueChangeFinished)
    val range by rememberUpdatedState(valueRange)
    var width by remember { mutableIntStateOf(0) }
    var height by remember { mutableIntStateOf(0) }
    // 拖动中手指的横坐标：draggable 只给增量，自己累加
    var dragX by remember { mutableFloatStateOf(0f) }

    fun emitAt(x: Float) {
        val radius = height / 2f
        val span = width - 2 * radius
        val fraction = if (span <= 0f) 0f else ((x - radius) / span).coerceIn(0f, 1f)
        onChange(range.start + fraction * (range.endInclusive - range.start))
    }

    val dragState = rememberDraggableState { delta ->
        dragX += delta
        emitAt(dragX)
    }
    val span = valueRange.endInclusive - valueRange.start
    val fraction = if (span <= 0f) 0f else ((value - valueRange.start) / span).coerceIn(0f, 1f)

    Spacer(
        modifier
            .fillMaxWidth()
            .height(SliderDefaults.MinHeight)
            .onSizeChanged {
                width = it.width
                height = it.height
            }
            .semantics {
                contentDescription = label
                progressBarRangeInfo = ProgressBarRangeInfo(value.coerceIn(valueRange), valueRange)
                setProgress { target ->
                    val next = target.coerceIn(valueRange)
                    if (next == value) return@setProgress false
                    onValueChange(next)
                    onValueChangeFinished?.invoke()
                    true
                }
            }
            // 点一下跳过去。拖起来时 draggable 先（Main 阶段里层先看到）把事件消费掉，这里的点按就作废
            .pointerInput(Unit) {
                detectTapGestures { pos ->
                    emitAt(pos.x)
                    onFinished?.invoke()
                }
            }
            .draggable(
                state = dragState,
                orientation = Orientation.Horizontal,
                onDragStarted = { pos ->
                    dragX = pos.x
                    emitAt(pos.x)
                },
                onDragStopped = { onFinished?.invoke() },
            )
            .drawBehind {
                val radius = size.height / 2f
                val corner = CornerRadius(radius)
                val brush = if (trackColors.size == 1) SolidColor(trackColors[0])
                else Brush.horizontalGradient(trackColors, startX = radius, endX = size.width - radius)
                drawRoundRect(brush, cornerRadius = corner)
                // 一道细边：轨道和卡片同色的那一头（浅纸上的「明度」右端）不至于化进卡里
                val hairline = Dimens.strokeThin.toPx()
                drawRoundRect(
                    outline,
                    topLeft = Offset(hairline / 2, hairline / 2),
                    size = size.copy(width = size.width - hairline, height = size.height - hairline),
                    cornerRadius = CornerRadius(radius - hairline / 2),
                    style = Stroke(hairline),
                )

                val center = Offset(radius + fraction * (size.width - 2 * radius), radius)
                val thumb = radius - Dimens.gapXS.toPx()
                drawCircle(THUMB_SHADOW, radius = thumb + hairline, center = center + Offset(0f, hairline / 2))
                drawCircle(THUMB_RING, radius = thumb, center = center)
                drawCircle(thumbColor, radius = thumb - Dimens.strokeThick.toPx(), center = center)
            }
    )
}

/** 拇指外圈。和 Miuix 滑块的拇指一样是白的：轨道什么颜色都有，白圈 + 影子在哪一段上都看得出来 */
private val THUMB_RING = Color.White

/** 拇指下面那圈淡影，给近白的轨道段勾出拇指的边 */
private val THUMB_SHADOW = Color.Black.copy(alpha = 0.22f)
