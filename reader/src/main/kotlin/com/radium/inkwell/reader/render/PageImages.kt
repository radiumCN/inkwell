package com.radium.inkwell.reader.render

import com.radium.inkwell.reader.paginate.LayoutSpec
import com.radium.inkwell.reader.paginate.PageItem

/**
 * 触点落在页上哪张图上（长按看大图、点提示条重试），没落在图上返回 null。
 *
 * [originY] 是这一页正文的顶边：翻页模式默认就是正文区顶 + [loneImageShift]（和 [drawPage] 画的位置一致）；
 * 滚动模式三页叠着、跟着手指挪，由调用方按当时的位移传进来。
 */
fun RenderablePage.imageAt(
    x: Float,
    y: Float,
    layout: LayoutSpec,
    originY: Float = layout.marginTopPx + layout.headerHeightPx + loneImageShift(layout),
): PageItem.ImageBox? {
    val localX = x - layout.marginLeftPx
    val localY = y - originY
    return spec.items.firstOrNull { item ->
        item is PageItem.ImageBox &&
            localX >= item.left && localX <= item.left + item.width &&
            localY >= item.top && localY <= item.top + item.height
    } as PageItem.ImageBox?
}
