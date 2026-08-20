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
    val bg = Color(theme.background)
    drawRect(bg)
    if (page == null) return
    val contentTop = layout.marginTopPx + layout.headerHeightPx
    drawPageItems(page, layout, theme, originY = contentTop, selection = selection)
}

/**
 * 只画正文项。[originY] 是本页内容区顶边，滚动模式用它把下一页接在当前页正文底下，
 * 而不是再叠一整屏（页底空白会裂开）。
 */
fun DrawScope.drawPageItems(
    page: RenderablePage,
    layout: LayoutSpec,
    theme: ReaderTheme,
    originY: Float,
    selection: TextSelection? = null,
    /** 滚动叠页：没图的占位不画，后面的字上移，避免半屏空白 */
    collapseMissingImages: Boolean = false,
) {
    val text = Color(theme.textColor)
    val title = Color(theme.titleColor)
    val footer = Color(theme.footerColor)
    val contentLeft = layout.marginLeftPx
    var collapse = 0f
    page.spec.items.forEach { item ->
        when (item) {
            is PageItem.TextSlice -> {
                val handle = page.measured[item.elementIndex]?.renderHandle as? TextLayoutResult
                    ?: return@forEach
                val top = originY - collapse
                // 高亮先画，文字后画 —— 否则半透明的色块会盖在字上
                if (selection != null && selection.elementIndex == item.elementIndex) {
                    drawSelection(
                        handle, item, selection,
                        left = contentLeft, top = top,
                        color = text.copy(alpha = 0.25f),
                    )
                }
                drawTextSlice(
                    handle, item,
                    left = contentLeft, top = top,
                    color = if (item.isTitle) title else text,
                )
            }
            is PageItem.ImageBox -> {
                val bmp = page.images[item.elementIndex]
                if (collapseMissingImages && bmp == null) {
                    collapse += item.height
                    return@forEach
                }
                val dst = Rect(
                    Offset(contentLeft + item.left, originY + item.top - collapse),
                    Size(item.width, item.height),
                )
                if (bmp != null) {
                    drawFittedImage(bmp, dst)
                } else {
                    drawRect(
                        footer.copy(alpha = 0.15f),
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
 *
 * 画在一张可变 ARGB 底图上，再拷成 **RGB_565 不可变**图：纸色页不透明，阴影在
 * CurlRenderer 里另画，不需要页图 alpha。565 是 ARGB 的一半，也更吃 CPU/GPU 缓存。
 * 拷完立刻 recycle 底图，避免峰值双份全屏 ARGB（1080×2400 一张约 10MB）。
 *
 * 不可变：HWUI 对可变位图每帧重新上传 GPU 纹理，是仿真翻页掉帧的主因。
 */
fun renderPageBitmap(
    page: RenderablePage?,
    layout: LayoutSpec,
    theme: ReaderTheme,
    density: Density,
): ImageBitmap {
    val width = layout.viewportWidthPx.coerceAtLeast(1)
    val height = layout.viewportHeightPx.coerceAtLeast(1)
    val software = android.graphics.Bitmap.createBitmap(
        width, height, android.graphics.Bitmap.Config.ARGB_8888,
    )
    CanvasDrawScope().draw(
        density,
        LayoutDirection.Ltr,
        Canvas(software.asImageBitmap()),
        Size(width.toFloat(), height.toFloat()),
    ) {
        drawPage(page, layout, theme)
    }
    val compact = software.copy(android.graphics.Bitmap.Config.RGB_565, /* isMutable = */ false)
    if (compact != null) {
        software.recycle()
        return compact.asImageBitmap()
    }
    return software.asImageBitmap()
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
    val sliceTopInParagraph = layoutResult.getLineTop(slice.startLine)
    translate(left = left, top = top + slice.yTopInPage - sliceTopInParagraph) {
        clipRect(
            top = sliceTopInParagraph,
            bottom = layoutResult.getLineBottom(slice.endLine),
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
    val sliceTopInParagraph = layoutResult.getLineTop(slice.startLine)
    val lastLine = layoutResult.lineCount - 1
    val fullParagraph = slice.startLine == 0 && slice.endLine >= lastLine
    translate(left = left, top = top + slice.yTopInPage - sliceTopInParagraph) {
        // 整段落都在本页：clip 是空操作，paint 也不必被裁掉几百行
        if (fullParagraph) {
            drawIntoCanvas { canvas ->
                layoutResult.multiParagraph.paint(canvas, color = color)
            }
        } else {
            clipRect(
                top = sliceTopInParagraph,
                bottom = layoutResult.getLineBottom(slice.endLine),
            ) {
                drawIntoCanvas { canvas ->
                    layoutResult.multiParagraph.paint(canvas, color = color)
                }
            }
        }
    }
}

internal fun DrawScope.drawFittedImage(bitmap: ImageBitmap, dst: Rect) {
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
