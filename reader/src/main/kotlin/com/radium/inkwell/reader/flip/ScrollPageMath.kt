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
 *
 * 一次手势吃掉的位移：快滑/惯性一次能甩过好几页，但画面上只有 prev/cur/next。
 * [ScrollConsume.drawOffset] 必须停在这三页能铺住的范围内，多出来的进 leftover，
 * 等翻页后的新窗口再吃。否则当前页被推走、下一页还没画，就会留下大块空白。
 */
data class ScrollConsume(
    val drawOffset: Float,
    val flip: FlipDirection?,
    val leftover: Float,
)

fun consumeScroll(
    pageOffset: Float,
    dragDelta: Float,
    currentHeight: Float,
    prevHeight: Float,
    nextHeight: Float,
    hasPrev: Boolean,
    hasNext: Boolean,
    viewportHeight: Float,
): ScrollConsume {
    val curH = currentHeight.coerceAtLeast(1f)
    val offset = pageOffset + dragDelta
    if (offset > 0f) {
        if (!hasPrev) {
            return ScrollConsume(0f, null, 0f)
        }
        val leftover = if (prevHeight > 0f) offset - prevHeight.coerceAtLeast(1f) else 0f
        return ScrollConsume(0f, FlipDirection.BACKWARD, leftover)
    }
    // 下一页还没画出来：不能把当前页推走露出空洞。长页仍可滑到页底对齐视口。
    if (nextHeight <= 0f) {
        val fillClamp = minOf(0f, viewportHeight - curH)
        if (offset < fillClamp) {
            return ScrollConsume(
                drawOffset = fillClamp,
                flip = if (hasNext) FlipDirection.FORWARD else null,
                leftover = if (hasNext) offset - fillClamp else 0f,
            )
        }
        return ScrollConsume(offset, null, 0f)
    }
    if (offset < -curH) {
        return ScrollConsume(-curH, FlipDirection.FORWARD, offset + curH)
    }
    return ScrollConsume(offset, null, 0f)
}

/** 正文带顶边：页眉小标题画在这条线上面 */
fun scrollContentTop(layout: LayoutSpec): Float =
    layout.marginTopPx + layout.headerHeightPx

/** 正文带底边：页脚进度/时间画在这条线下面 */
fun scrollContentBottom(layout: LayoutSpec): Float =
    layout.viewportHeightPx - layout.marginBottomPx - layout.footerHeightPx

/** 本页正文实际高度（最后一项底），不是视口高 —— 按视口叠会在页底留一道空白 */
fun pageContentHeight(page: RenderablePage?): Float = scrollPageHeight(page)

/**
 * 滚动叠页高度。没加载出来的图不占位：阅读页现在不会把图填进 [RenderablePage.images]，
 * 分页却按整屏宽的 4:3 留了一大块，叠下一页时就会在正文底下空出半屏。
 * 上滑把后面的字拖上来，下滑又藏回去 —— 就是这块空白在进进出出。
 */
fun scrollPageHeight(page: RenderablePage?): Float {
    if (page == null) return 0f
    val items = page.spec.items
    if (items.isEmpty()) return 0f
    var bottom = 0f
    var collapse = 0f
    for (item in items) {
        when (item) {
            is PageItem.TextSlice -> {
                bottom = maxOf(bottom, item.yTopInPage + item.height - collapse)
            }
            is PageItem.ImageBox -> {
                if (page.images[item.elementIndex] != null) {
                    bottom = maxOf(bottom, item.top + item.height - collapse)
                } else {
                    collapse += item.height
                }
            }
        }
    }
    return bottom
}
