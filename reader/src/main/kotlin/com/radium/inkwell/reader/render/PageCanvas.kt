package com.radium.inkwell.reader.render

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.radium.inkwell.reader.api.ReaderTheme
import com.radium.inkwell.reader.measure.MeasuredParagraph
import com.radium.inkwell.reader.paginate.IMAGE_FAILED_LABEL
import com.radium.inkwell.reader.paginate.LayoutSpec
import com.radium.inkwell.reader.paginate.PageItem
import com.radium.inkwell.reader.paginate.PageSpec

/**
 * 水合后的可渲染页：PageSpec + 各元素的测量句柄/图片。
 * [images] 按 elementIndex 放已经解好的图；还没解好的那张画一块淡底占着（框的大小分页时已经定了，图来了不挪字）
 */
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
 *
 * 翻页模式下，一页上只有一张图（整页插画、被挪到下一页的大图）时图在这一页上下居中（[loneImageShift]）；
 * 滚动模式没有「一页」，图直接接在字后面，不走这里。
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
    drawPageItems(page, layout, theme, originY = contentTop + page.loneImageShift(layout), selection = selection)
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
) {
    val text = Color(theme.textColor)
    val title = Color(theme.titleColor)
    val contentLeft = layout.marginLeftPx
    page.spec.items.forEach { item ->
        when (item) {
            is PageItem.TextSlice -> {
                val handle = page.measured[item.elementIndex]?.renderHandle as? TextLayoutResult
                    ?: return@forEach
                // 高亮先画，文字后画 —— 否则半透明的色块会盖在字上
                if (selection != null && selection.elementIndex == item.elementIndex) {
                    drawSelection(
                        handle, item, selection,
                        left = contentLeft, top = originY,
                        color = text.copy(alpha = 0.25f),
                    )
                }
                drawTextSlice(
                    handle, item,
                    left = contentLeft, top = originY,
                    color = if (item.isTitle) title else text,
                )
            }
            is PageItem.ImageBox -> {
                val dst = Rect(Offset(contentLeft + item.left, originY + item.top), Size(item.width, item.height))
                val radius = IMAGE_CORNER_DP.dp.toPx()
                when {
                    item.failed -> drawFailedImageRow(
                        dst, radius, theme,
                        page.measured[item.elementIndex]?.renderHandle as? TextLayoutResult,
                    )
                    else -> {
                        val bmp = page.images[item.elementIndex]
                        val clip = Path().apply { addRoundRect(RoundRect(dst, CornerRadius(radius))) }
                        clipPath(clip) {
                            if (bmp != null) {
                                drawFittedImage(bmp, dst)
                                // 深色纸上压暗一档：夜里一张白底插图比整屏字亮好几倍，晃眼
                                if (theme.isDark) drawRect(Color.Black.copy(alpha = DARK_PAPER_IMAGE_DIM), dst.topLeft, dst.size)
                            } else {
                                // 图还在解：先占一块淡底，框的大小分页时已经定好，图来了字不挪
                                drawRect(text.copy(alpha = PLACEHOLDER_ALPHA), dst.topLeft, dst.size)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 「图片没加载出来 · 点按重试」那一条：淡底圆角条，居中一枚图片小图标 + 一行说明色的字。
 * 字是分页时量好的（measured[elementIndex]），这里只管摆到中间。
 */
private fun DrawScope.drawFailedImageRow(dst: Rect, radius: Float, theme: ReaderTheme, label: TextLayoutResult?) {
    val ink = Color(theme.footerColor)
    drawRoundRect(
        Color(theme.textColor).copy(alpha = PLACEHOLDER_ALPHA),
        topLeft = dst.topLeft, size = dst.size, cornerRadius = CornerRadius(radius),
    )
    val icon = FAILED_ICON_DP.dp.toPx()
    val gap = FAILED_ICON_GAP_DP.dp.toPx()
    val textW = label?.let { l -> (0 until l.lineCount).maxOf { l.getLineRight(it) - l.getLineLeft(it) } } ?: 0f
    val textH = label?.size?.height?.toFloat() ?: 0f
    val total = icon + (if (label != null) gap + textW else 0f)
    val startX = dst.left + (dst.width - total).coerceAtLeast(0f) / 2f
    val centerY = dst.top + dst.height / 2f
    drawPictureIcon(Rect(Offset(startX, centerY - icon / 2f), Size(icon, icon)), ink)
    if (label != null) {
        val left = startX + icon + gap - label.getLineLeft(0)
        translate(left = left, top = centerY - textH / 2f) {
            drawIntoCanvas { canvas -> label.multiParagraph.paint(canvas, color = ink) }
        }
    }
}

/** 线条小图标：一张相框里一道山、一轮太阳（同 HyperOS 的线条图标画风） */
private fun DrawScope.drawPictureIcon(box: Rect, color: Color) {
    val stroke = Stroke(width = box.width / 13f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val inset = box.width * 0.12f
    val frame = Rect(box.left + inset, box.top + inset * 1.4f, box.right - inset, box.bottom - inset * 1.4f)
    drawRoundRect(color, frame.topLeft, frame.size, CornerRadius(box.width * 0.1f), style = stroke)
    val hills = Path().apply {
        moveTo(frame.left, frame.bottom - frame.height * 0.22f)
        lineTo(frame.left + frame.width * 0.3f, frame.top + frame.height * 0.5f)
        lineTo(frame.left + frame.width * 0.55f, frame.bottom - frame.height * 0.3f)
        lineTo(frame.left + frame.width * 0.72f, frame.top + frame.height * 0.62f)
        lineTo(frame.right, frame.bottom - frame.height * 0.18f)
    }
    drawPath(hills, color, style = stroke)
    drawCircle(
        color,
        radius = frame.width * 0.09f,
        center = Offset(frame.left + frame.width * 0.7f, frame.top + frame.height * 0.3f),
        style = stroke,
    )
}

/**
 * 翻页模式下这一页的正文要往下挪多少：页上只有一张图（整页插画，或上一页放不下挪过来的大图）时，
 * 让它在这一页上下居中，而不是顶在页头、底下空一大截。提示条（没取到的图）不算。
 * 长按看大图的命中测试（[imageAt]）用的是同一个值，画在哪就点在哪。
 */
fun RenderablePage.loneImageShift(layout: LayoutSpec): Float {
    val only = spec.items.singleOrNull() as? PageItem.ImageBox ?: return 0f
    if (only.failed) return 0f
    return ((layout.contentHeightPx - only.height) / 2f - only.top).coerceAtLeast(0f)
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
 * 画在一张可变 ARGB 底图上，再拷成 **不可变 ARGB_8888**：纸色页不透明，阴影在
 * CurlRenderer 里另画。从前拷 RGB_565 省一半内存，米色纸少了蓝通道精度，
 * 翻页瞬间整页发褐发紫，松手回到 [PageCanvas] 又正常。
 *
 * 不可变：HWUI 对可变位图每帧重新上传 GPU 纹理，是仿真翻页掉帧的主因。
 * 拷完立刻 recycle 底图，避免峰值双份全屏 ARGB。
 */
fun renderPageBitmap(
    page: RenderablePage?,
    layout: LayoutSpec,
    theme: ReaderTheme,
    density: Density,
): ImageBitmap {
    val width = layout.viewportWidthPx.coerceAtLeast(1)
    val height = layout.viewportHeightPx.coerceAtLeast(1)
    val srgb = android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB)
    val software = android.graphics.Bitmap.createBitmap(
        width, height, android.graphics.Bitmap.Config.ARGB_8888, /* hasAlpha = */ false, srgb,
    )
    CanvasDrawScope().draw(
        density,
        LayoutDirection.Ltr,
        Canvas(software.asImageBitmap()),
        Size(width.toFloat(), height.toFloat()),
    ) {
        drawPage(page, layout, theme)
    }
    val immutable = software.copy(android.graphics.Bitmap.Config.ARGB_8888, /* isMutable = */ false)
    if (immutable != null) {
        software.recycle()
        return immutable.asImageBitmap()
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

/**
 * 本页的文字，给读屏用。按切片的行范围从**同一个**测量结果里截：跨页的段落只念属于本页的那几行，
 * 不会把上一页已经念过的半段再念一遍。分隔符不出字；图念「插图」（有 alt 也不念：书里的 alt 多是文件名），
 * 没取到的图念那一行提示。
 */
fun RenderablePage.accessibleText(): String = buildString {
    for (item in spec.items) {
        val piece = when (item) {
            is PageItem.ImageBox -> if (item.failed) IMAGE_FAILED_LABEL else "插图"
            is PageItem.TextSlice -> {
                val layout = measured[item.elementIndex]?.renderHandle as? TextLayoutResult ?: continue
                val text = layout.layoutInput.text.text
                val start = layout.getLineStart(item.startLine).coerceIn(0, text.length)
                val end = layout.getLineEnd(item.endLine).coerceIn(start, text.length)
                if (start == end) continue
                text.substring(start, end)
            }
        }
        if (isNotEmpty()) append('\n')
        append(piece)
    }
}

/** 正文图的圆角（dp），和订阅文章里的图（App 的 AppShapes.small）一致 */
private const val IMAGE_CORNER_DP = 8

/** 深色纸上给图压的那层黑：亮度约剩八成 */
private const val DARK_PAPER_IMAGE_DIM = 0.2f

/** 图还没解出来时的淡底、提示条的底：正文色叠这么一层，跟着纸走，浅纸深纸都看得出是一块 */
private const val PLACEHOLDER_ALPHA = 0.07f

private const val FAILED_ICON_DP = 18
private const val FAILED_ICON_GAP_DP = 8

