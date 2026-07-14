package com.radium.inkwell.reader.render

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.radium.inkwell.reader.api.ReaderTheme
import com.radium.inkwell.reader.measure.MeasuredParagraph
import com.radium.inkwell.reader.paginate.LayoutSpec
import com.radium.inkwell.reader.paginate.PageItem
import com.radium.inkwell.reader.paginate.PageSpec

/** 水合后的可渲染页：PageSpec + 各元素的测量句柄/图片 */
class RenderablePage(
    val spec: PageSpec,
    val measured: Map<Int, MeasuredParagraph>,
    val images: Map<Int, ImageBitmap> = emptyMap(),
    val header: String = "",
    val footer: String = "",
)

/**
 * 自绘一页的内容。跨页段落的行子集用 translate + clipRect 露出属于本页的行，
 * 画的是分页时测量的同一个 TextLayoutResult，像素级一致。
 */
fun DrawScope.drawPage(
    page: RenderablePage?,
    layout: LayoutSpec,
    theme: ReaderTheme,
    selection: TextSelection? = null,
) {
    drawRect(Color(theme.background))
    if (page == null) return
    val contentLeft = layout.marginLeftPx
    val contentTop = layout.marginTopPx + layout.headerHeightPx
    page.spec.items.forEach { item ->
        when (item) {
            is PageItem.TextSlice -> {
                val handle = page.measured[item.elementIndex]?.renderHandle as? TextLayoutResult
                    ?: return@forEach
                // 高亮先画，文字后画 —— 否则半透明的色块会盖在字上
                if (selection != null && selection.elementIndex == item.elementIndex) {
                    drawSelection(
                        handle, item, selection,
                        left = contentLeft, top = contentTop,
                        color = Color(theme.textColor).copy(alpha = 0.25f),
                    )
                }
                drawTextSlice(
                    handle, item,
                    left = contentLeft, top = contentTop,
                    color = Color(if (item.isTitle) theme.titleColor else theme.textColor),
                )
            }
            is PageItem.ImageBox -> {
                val bmp = page.images[item.elementIndex]
                val dst = Rect(
                    Offset(contentLeft + item.left, contentTop + item.top),
                    Size(item.width, item.height),
                )
                if (bmp != null) {
                    drawFittedImage(bmp, dst)
                } else {
                    drawRect(
                        Color(theme.footerColor).copy(alpha = 0.15f),
                        topLeft = dst.topLeft, size = dst.size,
                    )
                }
            }
        }
    }
}

@Composable
fun PageCanvas(
    page: RenderablePage?,
    layout: LayoutSpec,
    theme: ReaderTheme,
    modifier: Modifier = Modifier,
    selection: TextSelection? = null,
) {
    Spacer(modifier.fillMaxSize().drawBehind { drawPage(page, layout, theme, selection) })
}

/**
 * 把一页渲染为位图（仿真卷页需要对整页做几何变形）。
 * 返回不可变位图：HWUI 对可变位图每帧重新上传 GPU 纹理（全屏 ~10MB），
 * 是仿真翻页掉帧的主因；不可变位图纹理只上传一次。
 */
fun renderPageBitmap(
    page: RenderablePage?,
    layout: LayoutSpec,
    theme: ReaderTheme,
    density: Density,
): ImageBitmap {
    val width = layout.viewportWidthPx.coerceAtLeast(1)
    val height = layout.viewportHeightPx.coerceAtLeast(1)
    val bitmap = ImageBitmap(width, height)
    CanvasDrawScope().draw(
        density,
        LayoutDirection.Ltr,
        Canvas(bitmap),
        Size(width.toFloat(), height.toFloat()),
    ) {
        drawPage(page, layout, theme)
    }
    return bitmap.asAndroidBitmap()
        .copy(android.graphics.Bitmap.Config.ARGB_8888, /* isMutable = */ false)
        .asImageBitmap()
}

/**
 * 选区高亮。用与 drawTextSlice 完全相同的 translate + clipRect ——
 * 差一点点，色块就会飘到字的上方或糊到相邻页去。
 */
private fun DrawScope.drawSelection(
    layoutResult: TextLayoutResult,
    slice: PageItem.TextSlice,
    selection: TextSelection,
    left: Float,
    top: Float,
    color: Color,
) {
    val len = layoutResult.layoutInput.text.length
    val start = selection.start.coerceIn(0, len)
    val end = selection.end.coerceIn(start, len)
    if (end <= start) return
    val sliceTopInParagraph = layoutResult.getLineTop(slice.lineRange.first)
    translate(left = left, top = top + slice.yTopInPage - sliceTopInParagraph) {
        clipRect(
            top = sliceTopInParagraph,
            bottom = layoutResult.getLineBottom(slice.lineRange.last),
        ) {
            drawPath(layoutResult.getPathForRange(start, end), color)
        }
    }
}

private fun DrawScope.drawTextSlice(
    layoutResult: TextLayoutResult,
    slice: PageItem.TextSlice,
    left: Float,
    top: Float,
    color: Color,
) {
    val sliceTopInParagraph = layoutResult.getLineTop(slice.lineRange.first)
    translate(left = left, top = top + slice.yTopInPage - sliceTopInParagraph) {
        clipRect(
            top = sliceTopInParagraph,
            bottom = layoutResult.getLineBottom(slice.lineRange.last),
        ) {
            drawIntoCanvas { canvas ->
                layoutResult.multiParagraph.paint(canvas, color = color)
            }
        }
    }
}

private fun DrawScope.drawFittedImage(bitmap: ImageBitmap, dst: Rect) {
    val scale = minOf(dst.width / bitmap.width, dst.height / bitmap.height)
    val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
    val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
    val left = (dst.left + (dst.width - w) / 2f).toInt()
    val top = (dst.top + (dst.height - h) / 2f).toInt()
    drawImage(
        image = bitmap,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(bitmap.width, bitmap.height),
        dstOffset = IntOffset(left, top),
        dstSize = IntSize(w, h),
    )
}
