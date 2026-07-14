package com.radium.inkwell.reader.render

import com.radium.inkwell.reader.paginate.LayoutSpec
import com.radium.inkwell.reader.paginate.PageItem

/**
 * 正文里选中的一段文字。
 *
 * 只允许选**单个段落**内的连续区间：正文是自绘 Canvas，跨段落选择要自己维护段间的
 * 锚点与方向，代价远大于收益 —— 净化规则本来也是逐段匹配的（applyPurify 就是逐段跑），
 * 一条跨段的规则永远匹配不上。
 */
data class TextSelection(
    /** 指向排版元素列表；与 PageItem.TextSlice.elementIndex 同义 */
    val elementIndex: Int,
    val start: Int,
    val end: Int,
    val text: String,
) {
    val isEmpty: Boolean get() = end <= start || text.isBlank()
}

/**
 * 触点 → 选中的词。自绘正文没有系统级的文字选择，命中测试只能自己做：
 * 先按 y 找到触点落在哪个段落切片上，再换算成段内坐标，交给测量层定位字符。
 */
fun RenderablePage.selectWordAt(x: Float, y: Float, layout: LayoutSpec): TextSelection? {
    val (slice, offset) = hitTest(x, y, layout) ?: return null
    val para = measured[slice.elementIndex] ?: return null
    val word = para.wordBoundary(offset)
    if (word.isEmpty()) return null
    return TextSelection(
        elementIndex = slice.elementIndex,
        start = word.first,
        end = word.last + 1,
        text = para.text.substring(word.first, (word.last + 1).coerceAtMost(para.text.length)),
    )
}

/**
 * 拖动扩展选区。锚点固定在长按选中的那个词，只在同一段落内延伸 ——
 * 手指移到别的段落上时保持原样，而不是跳段（跳段会让选区突然面目全非）。
 */
fun RenderablePage.extendSelection(
    anchor: TextSelection,
    x: Float,
    y: Float,
    layout: LayoutSpec,
): TextSelection {
    val para = measured[anchor.elementIndex] ?: return anchor
    val slice = spec.items
        .filterIsInstance<PageItem.TextSlice>()
        .firstOrNull { it.elementIndex == anchor.elementIndex }
        ?: return anchor

    val local = toParagraphLocal(slice, x, y, layout, para)
    val offset = para.offsetForPosition(local.first, local.second)
        .coerceIn(0, para.text.length)

    // 往回拖就往前扩：锚点词整体始终包含在选区里
    val start = minOf(anchor.start, offset)
    val end = maxOf(anchor.end, offset)
    return anchor.copy(
        start = start,
        end = end,
        text = para.text.substring(start, end.coerceAtMost(para.text.length)),
    )
}

/**
 * 触点 → (排版元素下标, 段内字符偏移)。命中不到正文时返回 null。
 *
 * 单独暴露出来是为了能直接测坐标换算本身 —— 只测 [selectWordAt] 的话，
 * 中文整段没有词边界，分词器会把整段吞掉，选区永远从 0 起，跨页偏移错没错根本看不出来。
 */
fun RenderablePage.offsetAt(x: Float, y: Float, layout: LayoutSpec): Pair<Int, Int>? {
    val (slice, offset) = hitTest(x, y, layout) ?: return null
    return slice.elementIndex to offset
}

private fun RenderablePage.hitTest(
    x: Float,
    y: Float,
    layout: LayoutSpec,
): Pair<PageItem.TextSlice, Int>? {
    val contentTop = layout.marginTopPx + layout.headerHeightPx
    val slice = spec.items
        .filterIsInstance<PageItem.TextSlice>()
        .firstOrNull { s ->
            val top = contentTop + s.yTopInPage
            y >= top && y <= top + s.height
        }
        ?: return null
    val para = measured[slice.elementIndex] ?: return null
    val local = toParagraphLocal(slice, x, y, layout, para)
    return slice to para.offsetForPosition(local.first, local.second)
}

/**
 * 页面坐标 → 段内坐标。跨页段落只有一半的行画在本页上（drawTextSlice 用 translate
 * 把这半段挪到位），命中测试必须做同一个换算，否则在下半页选到的是上半页的字。
 */
private fun toParagraphLocal(
    slice: PageItem.TextSlice,
    x: Float,
    y: Float,
    layout: LayoutSpec,
    para: com.radium.inkwell.reader.measure.MeasuredParagraph,
): Pair<Float, Float> {
    val contentLeft = layout.marginLeftPx
    val contentTop = layout.marginTopPx + layout.headerHeightPx
    val sliceTopInParagraph = para.lineTop(slice.lineRange.first)
    return (x - contentLeft) to (y - contentTop - slice.yTopInPage + sliceTopInParagraph)
}
