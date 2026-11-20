package com.radium.inkwell.reader.render

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.ResolvedTextDirection
import com.radium.inkwell.reader.measure.MeasuredParagraph
import com.radium.inkwell.reader.paginate.LayoutSpec
import com.radium.inkwell.reader.paginate.PageItem
import java.text.BreakIterator
import java.util.Locale

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

enum class SelectionEnd { START, END }

data class SelectionHandlePosition(val anchor: Offset, val lineCenterY: Float)

/**
 * 触点 → 选中的词。自绘正文没有系统级的文字选择，命中测试只能自己做：
 * 先按 y 找到触点落在哪个段落切片上，再换算成段内坐标，交给测量层定位字符。
 */
fun RenderablePage.selectWordAt(x: Float, y: Float, layout: LayoutSpec): TextSelection? {
    val (slice, offset) = hitTest(x, y, layout) ?: return null
    val para = measured[slice.elementIndex] ?: return null
    val visibleStart = para.lineStartOffset(slice.startLine)
    val visibleEnd = para.lineEndOffset(slice.endLine).coerceAtMost(para.text.length)
    if (visibleStart >= visibleEnd) return null
    val word = para.wordBoundary(offset.coerceIn(visibleStart, visibleEnd - 1))
    if (word.isEmpty()) return null
    val start = maxOf(word.first, visibleStart)
    val end = minOf(word.last + 1, visibleEnd)
    if (start >= end) return null
    val breaks = characterBreaks(para.text)
    val from = breaks.boundaryAt(start, SelectionEnd.START)
    val to = breaks.boundaryAt(end, SelectionEnd.END)
    return TextSelection(slice.elementIndex, from, to, para.text.substring(from, to))
}

/**
 * 长按期间拖动会扩展选区，固定触点所在的词，并限制在本页的同一段落内。
 */
fun RenderablePage.extendSelection(
    anchor: TextSelection,
    x: Float,
    y: Float,
    layout: LayoutSpec,
): TextSelection {
    val para = measured[anchor.elementIndex] ?: return anchor
    val slice = textSlice(anchor.elementIndex) ?: return anchor
    val offset = offsetOnSlice(slice, x, y, layout, para)
    val breaks = characterBreaks(para.text)
    // 往回拖就往前扩：锚点词整体始终包含在选区里
    val start = breaks.boundaryAt(minOf(anchor.start, offset), SelectionEnd.START)
    val end = breaks.boundaryAt(maxOf(anchor.end, offset), SelectionEnd.END)
    return anchor.copy(start = start, end = end, text = para.text.substring(start, end))
}

/** 松手后单独移动一个边界，允许缩小默认选区。另一边固定，至少保留一个可见字符。 */
fun RenderablePage.moveSelectionEnd(
    selection: TextSelection,
    edge: SelectionEnd,
    x: Float,
    y: Float,
    layout: LayoutSpec,
): TextSelection {
    val para = measured[selection.elementIndex] ?: return selection
    val slice = textSlice(selection.elementIndex) ?: return selection
    if (selection.start !in 0 until selection.end || selection.end > para.text.length) return selection
    val breaks = characterBreaks(para.text)
    val target = breaks.boundaryAt(offsetOnSlice(slice, x, y, layout, para), edge)
    val start = if (edge == SelectionEnd.START) minOf(target, breaks.preceding(selection.end)) else selection.start
    val end = if (edge == SelectionEnd.END) maxOf(target, breaks.following(selection.start)) else selection.end
    val text = para.text.substring(start, end)
    return if (text.isBlank()) selection else selection.copy(start = start, end = end, text = text)
}

/** 用边界旁的被选字符定位，行末手柄留在本行，避免跳到换行后的下一行。 */
fun RenderablePage.selectionHandlePosition(
    selection: TextSelection,
    edge: SelectionEnd,
    layout: LayoutSpec,
): SelectionHandlePosition? {
    val slice = textSlice(selection.elementIndex) ?: return null
    val para = measured[selection.elementIndex] ?: return null
    val result = para.renderHandle as? TextLayoutResult ?: return null
    val start = edge == SelectionEnd.START
    val adjacent = if (start) selection.start else selection.end - 1
    if (adjacent !in para.text.indices) return null
    val line = result.getLineForOffset(adjacent)
    if (line !in slice.lineRange) return null
    val rtl = result.getBidiRunDirection(adjacent) == ResolvedTextDirection.Rtl
    val boundary = if (start) selection.start else selection.end
    val clusterStart = if (start) adjacent else characterBreaks(para.text).preceding(boundary)
    val box = result.getBoundingBox(clusterStart)
    val x = if (start != rtl) box.left else box.right
    val top = layout.marginTopPx + layout.headerHeightPx + slice.yTopInPage - para.lineTop(slice.startLine)
    val bottom = para.lineBottom(line)
    return SelectionHandlePosition(
        anchor = Offset(layout.marginLeftPx + x, top + bottom),
        lineCenterY = top + (para.lineTop(line) + bottom) / 2f,
    )
}

private fun RenderablePage.textSlice(elementIndex: Int): PageItem.TextSlice? =
    spec.items.firstOrNull { it is PageItem.TextSlice && it.elementIndex == elementIndex } as? PageItem.TextSlice

private fun offsetOnSlice(
    slice: PageItem.TextSlice,
    x: Float,
    y: Float,
    layout: LayoutSpec,
    para: MeasuredParagraph,
): Int {
    val (localX, localY) = toParagraphLocal(slice, x, y, layout, para)
    val top = para.lineTop(slice.startLine)
    val bottom = para.lineBottom(slice.endLine)
    if (localY < top) return para.lineStartOffset(slice.startLine)
    if (localY >= bottom) return para.lineEndOffset(slice.endLine)
    return para.offsetForPosition(localX, localY)
        .coerceIn(para.lineStartOffset(slice.startLine), para.lineEndOffset(slice.endLine))
}

private fun characterBreaks(text: String): BreakIterator =
    BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(text) }

private fun BreakIterator.boundaryAt(offset: Int, edge: SelectionEnd): Int = when {
    isBoundary(offset) -> offset
    edge == SelectionEnd.START -> preceding(offset)
    else -> following(offset)
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
    val slice = spec.items.firstOrNull { item ->
        item is PageItem.TextSlice && run {
            val s = item
            val top = contentTop + s.yTopInPage
            y >= top && y <= top + s.height
        }
    } as? PageItem.TextSlice ?: return null
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
    val sliceTopInParagraph = para.lineTop(slice.startLine)
    return (x - contentLeft) to (y - contentTop - slice.yTopInPage + sliceTopInParagraph)
}
