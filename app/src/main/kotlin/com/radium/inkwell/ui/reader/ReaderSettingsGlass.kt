package com.radium.inkwell.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.radium.inkwell.ui.components.squircleShape
import com.radium.inkwell.ui.theme.AppShapes
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.ProgressiveBlur
import top.yukonga.miuix.kmp.blur.progressiveTextureBlur
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.squircle.addSquircleRect
import top.yukonga.miuix.kmp.squircle.squircleClip

@Composable
internal fun Modifier.readerSettingsSurface(backdrop: LayerBackdrop?, color: Color, radius: Dp): Modifier {
    val bandPx = with(LocalDensity.current) { SettingsBandHeight.toPx() }
    var heightPx by remember { mutableIntStateOf(0) }
    val glass = backdrop != null && heightPx > 0 && color.alpha > 0f
    val surface = this.onSizeChanged { heightPx = it.height }
        .squircleClip(topStart = radius, topEnd = radius, bottomStart = 0.dp, bottomEnd = 0.dp)
    val blur = if (glass) {
        Modifier.progressiveTextureBlur(
            backdrop = backdrop,
            shape = RectangleShape,
            blurRadius = SettingsBlurRadius * color.alpha,
            gradient = ProgressiveBlur.Top.copy(endFraction = (bandPx / heightPx).coerceAtMost(1f)),
        )
    } else {
        Modifier
    }
    return surface.then(blur).drawWithCache {
        // 只有上沿透出毛玻璃。到渐隐带底部恢复面板底色，设置分组仍落在实色上。
        val bandHeight = bandPx.coerceAtMost(size.height).coerceAtLeast(1f)
        val fill = Brush.verticalGradient(
            listOf(if (glass) color.copy(alpha = color.alpha * SheetGlassTint) else color, color),
            endY = bandHeight,
        )
        val highlight = glassHighlight(color)
        val rim = Brush.verticalGradient(
            listOf(highlight.copy(alpha = highlight.alpha * color.alpha), Color.Transparent),
            endY = bandHeight,
        )
        val outline = Path().apply { addSquircleRect(size.width, size.height, radius.toPx()) }
        onDrawWithContent {
            drawRect(fill)
            drawContent()
            drawPath(outline, rim, style = Stroke(2.dp.toPx()))
        }
    }
}

@Composable
internal fun ReaderSettingsSlider(
    backdrop: LayerBackdrop?,
    color: Color,
    sheetShown: Float,
    active: Boolean,
    content: @Composable () -> Unit,
) {
    Box(Modifier.alpha(if (active) 1f else sheetShown)) {
        val glassAlpha = 1f - sheetShown
        if (active && glassAlpha > 0f) {
            val radius = AppShapes.radiusLarge
            val shape = remember(radius) { squircleShape(radius) }
            val material = if (backdrop == null) {
                Modifier.background(color, shape)
            } else {
                Modifier.textureBlur(
                    backdrop = backdrop,
                    shape = shape,
                    blurRadius = SettingsBlurRadius,
                    colors = BlurDefaults.blurColors(
                        blendColors = listOf(BlendColorEntry(color = color.copy(alpha = SliderGlassTint))),
                    ),
                )
            }
            Box(
                Modifier.matchParentSize().alpha(glassAlpha).then(material).drawWithCache {
                    val outline = Path().apply { addSquircleRect(size.width, size.height, radius.toPx()) }
                    val rim = Brush.verticalGradient(
                        listOf(glassHighlight(color), Color.Transparent),
                        endY = size.height.coerceAtLeast(1f),
                    )
                    onDrawBehind { drawPath(outline, rim, style = Stroke(1.dp.toPx())) }
                },
            )
        }
        // 滑杆本体始终留在原位，拖动中继续接收同一手势。
        content()
    }
}

private fun glassHighlight(color: Color): Color =
    Color.White.copy(alpha = if (color.luminance() < 0.5f) 0.24f else 0.78f)

private val SettingsBandHeight = 36.dp
private const val SettingsBlurRadius = 16f
private const val SheetGlassTint = 0.78f
private const val SliderGlassTint = 0.92f
