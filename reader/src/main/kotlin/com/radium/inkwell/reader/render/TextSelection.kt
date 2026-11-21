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
 * 正文里选中的一段文字：从 [elementIndex] 段的 [start] 到 [endElementIndex] 段的 [end]。
 *
 * 可以跨段，但只在**当前页**之内：选字期间不翻页（见 ReaderScreen），两端都落在本页的段落切片上，
 * 跨页段落只算本页露出来的那几行。从前只许选单个段落，想复制两段话得分两次（被反馈过）。
 *
 * 跨段的选区只能复制：净化 / 替换规则是逐段匹配的（applyPurify 就是逐段跑），带换行的原文
 * 永远匹配不上，所以「删除」「替换」只给 [isSingleParagraph] 的选区。
 */
data class TextSelection(
    /** 起点所在的排版元素；与 PageItem.TextSlice.elementIndex 同义 */
    val elementIndex: Int,
    /** 起点在 [elementIndex] 段内的偏移 */
    val start: Int,
    /** 终点在 [endElementIndex] 段内的偏移（不含） */
    val end: Int,
    /** 选中的字；跨段时各段之间用换行连起来 */
    val text: String,
    /** 终点所在的排版元素；单段选区就是 [elementIndex] */
    val endElementIndex: Int = elementIndex,
) {
    val isSingleParagraph: Boolean get() = endElementIndex == elementIndex
    val isEmpty: Boolean get() = (isSingleParagraph && end <= start) || text.isBlank()

    /** 这一段有没有被选中（哪怕只选了一个字）；画高亮时先问它 */
    fun covers(element: Int): Boolean = element in elementIndex..endElementIndex
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
 * 长按期间拖动会扩展选区：触点所在的词始终在选区里，往下拖过段尾就接着选下一段，往上同理。
 * 手指落在段间空白、图片上时，吸到离它最近的那段。
 */
fun RenderablePage.extendSelection(
    anchor: TextSelection,
    x: Float,
    y: Float,
    layout: LayoutSpec,
): TextSelection {
    val point = pointAt(x, y, layout) ?: return anchor
    // 往回拖就往前扩：锚点词整体始终包含在选区里
    val from = snap(minOf(anchor.startPoint, point), SelectionEnd.START) ?: return anchor
    val to = snap(maxOf(anchor.endPoint, point), SelectionEnd.END) ?: return anchor
    return selectionBetween(from, to) ?: anchor
}

/** 松手后单独移动一个边界，允许缩小默认选区、也能拖到别的段。另一边固定，至少保留一个可见字符。 */
fun RenderablePage.moveSelectionEnd(
    selection: TextSelection,
    edge: SelectionEnd,
    x: Float,
    y: Float,
    layout: LayoutSpec,
): TextSelection {
    val point = pointAt(x, y, layout) ?: return selection
    val target = snap(point, edge) ?: return selection
    return moveEnd(selection, edge, target)
}

/** 按字符移动选区端点，供读屏操作使用。走到本段在本页的头 / 尾就跨到相邻的段，出不了本页。 */
fun RenderablePage.stepSelectionEnd(
    selection: TextSelection,
    edge: SelectionEnd,
    forward: Boolean,
): TextSelection {
    val point = if (edge == SelectionEnd.START) selection.startPoint else selection.endPoint
    val target = step(point, forward) ?: return selection
    return moveEnd(selection, edge, target)
}

private fun RenderablePage.moveEnd(selection: TextSelection, edge: SelectionEnd, target: TextPoint): TextSelection {
    val start = selection.startPoint
    val end = selection.endPoint
    if (start >= end) return selection
    // 另一端往里退一个字就是这一端能到的最远处：选区再小也留一个字
    val (from, to) = if (edge == SelectionEnd.START) {
        minOf(target, step(end, forward = false) ?: return selection) to end
    } else {
        start to maxOf(target, step(start, forward = true) ?: return selection)
    }
    val moved = selectionBetween(from, to) ?: return selection
    return if (moved.text.isBlank()) selection else moved
}

/** 用边界旁的被选字符定位，行末手柄留在本行，避免跳到换行后的下一行。 */
fun RenderablePage.selectionHandlePosition(
    selection: TextSelection,
    edge: SelectionEnd,
    layout: LayoutSpec,
): SelectionHandlePosition? {
    val start = edge == SelectionEnd.START
    val element = if (start) selection.elementIndex else selection.endElementIndex
    val slice = textSlice(element) ?: return null
    val para = measured[element] ?: return null
    val result = para.renderHandle as? TextLayoutResult ?: return null
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

/**
 * 选区的一端：第几段、段内第几个字之前。先比段再比偏移 —— 同一页上段号越大越靠下，
 * 两端谁在前谁在后不用再看坐标。
 */
private data class TextPoint(val element: Int, val offset: Int) : Comparable<TextPoint> {
    override fun compareTo(other: TextPoint): Int =
        if (element != other.element) element.compareTo(other.element) else offset.compareTo(other.offset)
}

private val TextSelection.startPoint get() = TextPoint(elementIndex, start)
private val TextSelection.endPoint get() = TextPoint(endElementIndex, end)

/** 本页上有字的段落切片，从上到下。空标题（没有章节名）占着一个排版元素，但选不到字，不算 */
private fun RenderablePage.textSlices(): List<PageItem.TextSlice> =
    spec.items.filterIsInstance<PageItem.TextSlice>().filter { slice ->
        val para = measured[slice.elementIndex] ?: return@filter false
        para.visibleStart(slice) < para.visibleEnd(slice)
    }

private fun MeasuredParagraph.visibleStart(slice: PageItem.TextSlice): Int = lineStartOffset(slice.startLine)

private fun MeasuredParagraph.visibleEnd(slice: PageItem.TextSlice): Int =
    lineEndOffset(slice.endLine).coerceAtMost(text.length)

/**
 * 两个端点 → 选区：两头的段取半截、中间的段取本页露出来的整段，用换行连起来。
 *
 * 端点正好压在段与段的接缝上时（起点在上一段末尾、终点在下一段开头）挪到有字的那一侧：
 * 否则选区头上挂着一段零个字的「段」，手柄画在空处，复制出来还多一个换行。
 */
private fun RenderablePage.selectionBetween(from: TextPoint, to: TextPoint): TextSelection? {
    val slices = textSlices()
    var a = from
    var b = to
    while (a.element < b.element) {
        val i = slices.indexOfFirst { it.elementIndex == a.element }
        val slice = slices.getOrNull(i) ?: return null
        if (a.offset < measured.getValue(a.element).visibleEnd(slice)) break
        val next = slices.getOrNull(i + 1) ?: return null
        a = TextPoint(next.elementIndex, measured.getValue(next.elementIndex).visibleStart(next))
    }
    while (a.element < b.element) {
        val i = slices.indexOfFirst { it.elementIndex == b.element }
        val slice = slices.getOrNull(i) ?: return null
        if (b.offset > measured.getValue(b.element).visibleStart(slice)) break
        val prev = slices.getOrNull(i - 1) ?: return null
        b = TextPoint(prev.elementIndex, measured.getValue(prev.elementIndex).visibleEnd(prev))
    }
    if (a >= b) return null
    val text = slices.filter { it.elementIndex in a.element..b.element }.joinToString("\n") { slice ->
        val para = measured.getValue(slice.elementIndex)
        val s = if (slice.elementIndex == a.element) a.offset else para.visibleStart(slice)
        val e = if (slice.elementIndex == b.element) b.offset else para.visibleEnd(slice)
        para.text.substring(s, e)
    }
    return TextSelection(a.element, a.offset, b.offset, text, endElementIndex = b.element)
}

/**
 * 端点按字符挪一格。走到本段在本页的头 / 尾就跨进相邻的段，并且连带那段的第一个 / 最后一个字 ——
 * 只跨过去不带字的话，[selectionBetween] 又会把它挪回接缝这一侧，读屏怎么按都原地不动。
 * 本页上没处可挪返回 null。
 */
private fun RenderablePage.step(point: TextPoint, forward: Boolean): TextPoint? {
    val slices = textSlices()
    val i = slices.indexOfFirst { it.elementIndex == point.element }
    val slice = slices.getOrNull(i) ?: return null
    val para = measured.getValue(point.element)
    return if (forward) {
        if (point.offset < para.visibleEnd(slice)) {
            TextPoint(point.element, characterBreaks(para.text).following(point.offset))
        } else {
            val next = slices.getOrNull(i + 1) ?: return null
            val nextPara = measured.getValue(next.elementIndex)
            TextPoint(next.elementIndex, characterBreaks(nextPara.text).following(nextPara.visibleStart(next)))
        }
    } else {
        if (point.offset > para.visibleStart(slice)) {
            TextPoint(point.element, characterBreaks(para.text).preceding(point.offset))
        } else {
            val prev = slices.getOrNull(i - 1) ?: return null
            val prevPara = measured.getValue(prev.elementIndex)
            TextPoint(prev.elementIndex, characterBreaks(prevPara.text).preceding(prevPara.visibleEnd(prev)))
        }
    }
}

/** 端点吸到完整字符的边界上（表情、组合字符不拆开）。起点往前吸、终点往后吸 */
private fun RenderablePage.snap(point: TextPoint, edge: SelectionEnd): TextPoint? {
    val para = measured[point.element] ?: return null
    return point.copy(offset = characterBreaks(para.text).boundaryAt(point.offset, edge))
}

/**
 * 触点 → 本页离它最近的那段上的位置。落在段内照常换算；落在段与段之间、图片上、
 * 正文上下的空白里，就吸到最近那段的头或尾（[offsetOnSlice] 把切片外的 y 夹到首行 / 末行）。
 */
private fun RenderablePage.pointAt(x: Float, y: Float, layout: LayoutSpec): TextPoint? {
    val contentTop = layout.marginTopPx + layout.headerHeightPx
    val slice = textSlices().minByOrNull { s ->
        val top = contentTop + s.yTopInPage
        when {
            y < top -> top - y
            y > top + s.height -> y - top - s.height
            else -> 0f
        }
    } ?: return null
    val para = measured[slice.elementIndex] ?: return null
    return TextPoint(slice.elementIndex, offsetOnSlice(slice, x, y, layout, para))
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
