package com.radium.inkwell.reader.render

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import com.radium.inkwell.reader.api.ReaderTheme
import com.radium.inkwell.reader.measure.MeasuredParagraph
import com.radium.inkwell.reader.paginate.LayoutSpec
import com.radium.inkwell.reader.paginate.PageItem

/**
 * 滚动模式下排好版的一章：整章连续排成一列元素，而不是切成一屏一屏的页。
 *
 * 复用分页器来做测量 —— 给它一个"高得放得下整章"的视口，它就只会产出一页，
 * 那一页的 items 正好是带 y 偏移的全部元素。这样字体、行距、首行缩进、两端对齐
 * 全都与翻页模式走同一套排版逻辑，不会出现"换个翻页方式字就长得不一样了"。
 */
class ScrollChapter(
    val chapterIndex: Int,
    val title: String,
    val items: List<PageItem>,
    val measured: Map<Int, MeasuredParagraph>,
    val images: Map<Int, ImageBitmap> = emptyMap(),
    /** elementIndex → 该元素起始处的章内字符偏移。阅读进度靠它换算 */
    val charOffsets: Map<Int, Int> = emptyMap(),
) {
    /**
     * 每个元素在列表里占的高度：取到**下一个元素的顶**，而不是元素自身的高度 ——
     * 段间距是这么被算进去的。最后一个元素没有"下一个"，用自身高度。
     */
    fun slotHeight(index: Int): Float {
        val cur = items[index]
        val top = cur.yTop()
        val next = items.getOrNull(index + 1) ?: return cur.height()
        return (next.yTop() - top).coerceAtLeast(cur.height())
    }
}

private fun PageItem.yTop(): Float = when (this) {
    is PageItem.TextSlice -> yTopInPage
    is PageItem.ImageBox -> top
}

private fun PageItem.height(): Float = when (this) {
    is PageItem.TextSlice -> height
    is PageItem.ImageBox -> height
}

/** 滚动模式里画一个元素。左右留白与翻页模式一致，上下留白交给列表 */
@Composable
fun ScrollItemView(
    chapter: ScrollChapter,
    index: Int,
    layout: LayoutSpec,
    theme: ReaderTheme,
    modifier: Modifier = Modifier,
) {
    val item = chapter.items[index]
    val heightDp = with(LocalDensity.current) { chapter.slotHeight(index).toDp() }
    Spacer(
        modifier
            .fillMaxWidth()
            .height(heightDp)
            .drawBehind { drawScrollItem(chapter, item, layout, theme) }
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawScrollItem(
    chapter: ScrollChapter,
    item: PageItem,
    layout: LayoutSpec,
    theme: ReaderTheme,
) {
    when (item) {
        is PageItem.TextSlice -> {
            val handle = chapter.measured[item.elementIndex]?.renderHandle as? TextLayoutResult
                ?: return
            // 元素被单独画在自己的槽里，所以纵向原点就是它自己的第一行
            val sliceTop = handle.getLineTop(item.lineRange.first)
            translate(left = layout.marginLeftPx, top = -sliceTop) {
                clipRect(
                    top = sliceTop,
                    bottom = handle.getLineBottom(item.lineRange.last),
                ) {
                    drawIntoCanvas { canvas ->
                        handle.multiParagraph.paint(
                            canvas,
                            color = Color(if (item.isTitle) theme.titleColor else theme.textColor),
                        )
                    }
                }
            }
        }
        is PageItem.ImageBox -> {
            val bmp = chapter.images[item.elementIndex]
            val dst = Rect(
                Offset(layout.marginLeftPx + item.left, 0f),
                Size(item.width, item.height),
            )
            if (bmp != null) {
                drawFittedImage(bmp, dst)
            } else {
                drawRect(
                    Color(theme.footerColor).copy(alpha = 0.15f),
                    topLeft = dst.topLeft,
                    size = dst.size,
                )
            }
        }
    }
}
