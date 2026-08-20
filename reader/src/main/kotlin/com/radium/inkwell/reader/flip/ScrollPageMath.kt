package com.radium.inkwell.reader.flip

import com.radium.inkwell.reader.api.FlipDirection
import com.radium.inkwell.reader.paginate.LayoutSpec
import com.radium.inkwell.reader.paginate.PageItem
import com.radium.inkwell.reader.render.RenderablePage

/**
 * 滚动翻页的页游标，算法对齐 Legado [ContentTextView.scroll]：
 * https://github.com/HapeLee/legado-with-MD3
 *
 * [pageOffset] 以内容区顶端为 0。手指下滑为正（露出上一页），上滑为负。
 * 这个符号跟 [androidx.compose.foundation.gestures.scrollable] 默认一致：
 * `reverseDirection = false` 时，正 delta 就是手指沿轴向正方向（竖直＝下滑），
 * 不要再取一次负号，否则手势和阅读方向对反。
 * 越过 `-currentHeight` 就翻到下一页并带回偏移；大于 0 就翻到上一页。
 * 标题/进度跟当前页走，不再从列表可见项反推章号。
 */
data class ScrollPageStep(
    val pageOffset: Float,
    val flip: FlipDirection?,
)

fun applyScrollDrag(
    pageOffset: Float,
    dragDelta: Float,
    currentHeight: Float,
    hasPrev: Boolean,
    hasNext: Boolean,
    viewportHeight: Float,
): ScrollPageStep {
    var offset = pageOffset + dragDelta
    val height = currentHeight.coerceAtLeast(1f)
    if (!hasPrev && offset > 0f) {
        return ScrollPageStep(0f, null)
    }
    if (!hasNext && offset < 0f && offset + height < viewportHeight) {
        return ScrollPageStep(minOf(0f, viewportHeight - height), null)
    }
    if (offset > 0f && hasPrev) {
        return ScrollPageStep(offset, FlipDirection.BACKWARD)
    }
    if (offset < -height && hasNext) {
        return ScrollPageStep(offset, FlipDirection.FORWARD)
    }
    return ScrollPageStep(offset, null)
}

/** 翻页后把偏移折回新的当前页，画面接得上 */
fun carryScrollOffset(
    step: ScrollPageStep,
    currentHeight: Float,
    prevHeight: Float,
): Float {
    val height = currentHeight.coerceAtLeast(1f)
    return when (step.flip) {
        FlipDirection.FORWARD -> step.pageOffset + height
        FlipDirection.BACKWARD -> step.pageOffset - prevHeight.coerceAtLeast(1f)
        null -> step.pageOffset
    }
}

/** 正文带顶边：页眉小标题画在这条线上面 */
fun scrollContentTop(layout: LayoutSpec): Float =
    layout.marginTopPx + layout.headerHeightPx

/** 正文带底边：页脚进度/时间画在这条线下面 */
fun scrollContentBottom(layout: LayoutSpec): Float =
    layout.viewportHeightPx - layout.marginBottomPx - layout.footerHeightPx

/** 本页正文实际高度（最后一项底），不是视口高 —— 按视口叠会在页底留一道空白 */
fun pageContentHeight(page: RenderablePage?): Float {
    val items = page?.spec?.items.orEmpty()
    if (items.isEmpty()) return 0f
    return items.maxOf { item ->
        when (item) {
            is PageItem.TextSlice -> item.yTopInPage + item.height
            is PageItem.ImageBox -> item.top + item.height
        }
    }
}
