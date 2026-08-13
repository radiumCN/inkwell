package com.radium.inkwell.reader.paginate

import com.radium.inkwell.core.model.ChapterContent
import com.radium.inkwell.core.model.ContentElement
import com.radium.inkwell.reader.measure.MeasuredParagraph
import com.radium.inkwell.reader.measure.ResolvedTextStyle
import com.radium.inkwell.reader.measure.TextMeasureFacade

/**
 * 分页器：把章节段落流按视口贪心装箱成页。纯逻辑，只依赖 TextMeasureFacade。
 *
 * 排版元素表 = [标题虚拟元素] + content.elements，PageItem.elementIndex 指向该表。
 * 规则：行是最小切割单位；图片不跨页；标题 keep-with-next（其后放不下 2 行正文则推下页）。
 */
class Paginator(private val measurer: TextMeasureFacade) {

    /** 分页产物附带各元素的测量结果，供渲染层水合复用（保证测量渲染同源） */
    class Result(
        val chapter: PaginatedChapter,
        val measured: Map<Int, MeasuredParagraph>,
        /**
         * false = 还在往后排，[chapter.pages] 只是已经封好的前缀。
         * 翻页时若已在最后一页且 !complete，不要跳下一章 —— 下一页马上就来。
         */
        val complete: Boolean = true,
    )

    /**
     * @param notifyAfterChar 调用方想尽快看到的章内偏移（进书进度）。覆盖到该偏移的页
     *   一旦封好就 [onPartial] 一次，让 UI 先出字；余下页继续排完再返回完整结果。
     *   `Int.MAX_VALUE`（跳到章末）不中途回调，避免先闪第一页再跳最后一页。
     */
    fun paginate(
        chapterIndex: Int,
        title: String,
        content: ChapterContent,
        spec: LayoutSpec,
        notifyAfterChar: Int = 0,
        onPartial: ((Result) -> Unit)? = null,
    ): Result {
        val elements = buildList {
            add(TitleElement(title))
            addAll(content.elements)
        }
        val measured = HashMap<Int, MeasuredParagraph>()
        val pages = mutableListOf<PageSpec>()
        var cursor = PageCursor(spec)
        var charOffset = 0
        // 一章只有三种样式：循环里复用，别每段 new 一个 data class 去砸分配器
        val bodyStyle = spec.bodyStyle()
        val titleStyle = spec.titleStyle()
        val headingStyle = spec.headingStyle()
        var partialEmitted = false
        val partialCb = onPartial.takeIf { notifyAfterChar != Int.MAX_VALUE }

        fun snapshot(complete: Boolean) = Result(
            PaginatedChapter(chapterIndex, title, pages.toList(), charOffset),
            // 完整结果不再拷测量表：paginate 返回后不会再往里写。中途回调必须拷一份，
            // 否则 Default 线程继续 put 时，主线程若正在遍历会 ConcurrentModification。
            if (complete) measured else HashMap(measured),
            complete,
        )

        fun seal() {
            if (!cursor.isEmpty) {
                pages += cursor.seal(chapterIndex, pages.size)
                cursor = PageCursor(spec)
                // 目标偏移已经落在已封页里 → 先让 UI 出这一屏，余下继续排
                if (partialCb != null && !partialEmitted && notifyAfterChar < pages.last().endCharOffset) {
                    partialEmitted = true
                    partialCb(snapshot(complete = false))
                }
            }
        }

        elements.forEachIndexed { idx, element ->
            when (element) {
                is TitleElement -> {
                    if (element.text.isNotBlank()) {
                        val mp = measurer.measureParagraph(element.text, titleStyle, spec.contentWidthPx)
                        measured[idx] = mp
                        cursor.addGap(spec.titleTopSpacingPx)
                        placeParagraph(idx, element.text.length, mp, spec, cursor, charOffset,
                            isTitle = true, onSeal = ::seal, currentCursor = { cursor })
                        cursor.addGap(spec.paragraphSpacingPx * 1.5f)
                    }
                    charOffset += element.text.length
                }
                is ContentElement.Paragraph -> {
                    if (element.text.isNotBlank()) {
                        val mp = measurer.measureParagraph(element.text, bodyStyle, spec.contentWidthPx)
                        measured[idx] = mp
                        placeParagraph(idx, element.text.length, mp, spec, cursor, charOffset,
                            isTitle = false, onSeal = ::seal, currentCursor = { cursor })
                        cursor.addGap(spec.paragraphSpacingPx)
                    }
                    charOffset += element.text.length
                }
                is ContentElement.Heading -> {
                    if (element.text.isNotBlank()) {
                        val mp = measurer.measureParagraph(element.text, headingStyle, spec.contentWidthPx)
                        measured[idx] = mp
                        placeParagraph(idx, element.text.length, mp, spec, cursor, charOffset,
                            isTitle = true, onSeal = ::seal, currentCursor = { cursor })
                        cursor.addGap(spec.paragraphSpacingPx)
                    }
                    charOffset += element.text.length
                }
                is ContentElement.Image -> {
                    placeImage(idx, element, spec, cursor, charOffset, onSeal = ::seal, currentCursor = { cursor })
                    cursor.addGap(spec.paragraphSpacingPx)
                    charOffset += 1
                }
                ContentElement.Divider -> {
                    cursor.addGap(spec.lineHeightPx)
                    charOffset += 1
                }
            }
        }
        seal()
        if (pages.isEmpty()) {
            // 空章节兜底：产出一个空页，游标语义不塌
            pages += PageSpec(chapterIndex, 0, emptyList(), 0, 0)
        }
        return snapshot(complete = true)
    }

    private inline fun placeParagraph(
        elementIndex: Int,
        textLength: Int,
        mp: MeasuredParagraph,
        spec: LayoutSpec,
        startCursor: PageCursor,
        elementCharBase: Int,
        isTitle: Boolean,
        onSeal: () -> Unit,
        currentCursor: () -> PageCursor,
    ) {
        var line = 0
        var cursor = startCursor
        while (line < mp.lineCount) {
            val fit = cursor.fitLines(mp, fromLine = line)
            // 标题 keep-with-next：标题起步但本页连 1 行都放不下 → 推下页
            val effectiveFit = if (isTitle && line == 0 && fit < minOf(mp.lineCount, 1)) 0 else fit
            if (effectiveFit == 0) {
                if (cursor.isEmpty) {
                    // 单行都放不下的极端小视口：强放 1 行避免死循环
                    cursor.addTextSlice(elementIndex, line..line, mp, isTitle, elementCharBase)
                    line += 1
                    if (line < mp.lineCount) onSeal()
                    cursor = currentCursor()
                    continue
                }
                onSeal()
                cursor = currentCursor()
                continue
            }
            cursor.addTextSlice(elementIndex, line until line + effectiveFit, mp, isTitle, elementCharBase)
            line += effectiveFit
            if (line < mp.lineCount) {
                onSeal()
                cursor = currentCursor()
            }
        }
        // 记录段落尾字符（供 endCharOffset 精确）
        currentCursor().noteCharEnd(elementCharBase + textLength)
    }

    private inline fun placeImage(
        elementIndex: Int,
        element: ContentElement.Image,
        spec: LayoutSpec,
        startCursor: PageCursor,
        elementCharBase: Int,
        onSeal: () -> Unit,
        currentCursor: () -> PageCursor,
    ) {
        var cursor = startCursor
        val maxW = spec.contentWidthPx.toFloat()
        val maxH = spec.contentHeightPx
        // 无固有尺寸信息时按 4:3 占位；渲染层拿到真实图后等比适配该框
        val boxW = maxW
        val boxH = (maxW * 0.75f).coerceAtMost(maxH)
        if (!cursor.fits(boxH) && !cursor.isEmpty) {
            onSeal()
            cursor = currentCursor()
        }
        cursor.addImage(elementIndex, element.resourceId, boxW, boxH.coerceAtMost(maxH), elementCharBase)
        cursor.noteCharEnd(elementCharBase + 1)
    }

    internal class TitleElement(val text: String)

    private fun LayoutSpec.bodyStyle() = ResolvedTextStyle(
        fontSizePx = fontSizePx,
        lineHeightPx = lineHeightPx,
        fontId = fontId,
        firstLineIndentEm = firstLineIndentEm,
        justify = justify,
    )

    private fun LayoutSpec.titleStyle() = ResolvedTextStyle(
        fontSizePx = fontSizePx * titleFontScale,
        lineHeightPx = lineHeightPx * titleFontScale,
        fontId = fontId,
        firstLineIndentEm = 0f,
        isBold = true,
        justify = false,
    )

    private fun LayoutSpec.headingStyle() = ResolvedTextStyle(
        fontSizePx = fontSizePx * 1.15f,
        lineHeightPx = lineHeightPx * 1.15f,
        fontId = fontId,
        firstLineIndentEm = 0f,
        isBold = true,
        justify = false,
    )
}

/** 当前正在装配的页 */
internal class PageCursor(private val spec: LayoutSpec) {
    private val items = mutableListOf<PageItem>()
    private var usedHeight = 0f
    private var startChar = -1
    private var endChar = 0

    val isEmpty: Boolean get() = items.isEmpty()

    private val remaining: Float get() = spec.contentHeightPx - usedHeight

    fun fits(height: Float): Boolean = height <= remaining + EPSILON

    /** 从 fromLine 起最多能放下多少行 */
    fun fitLines(mp: MeasuredParagraph, fromLine: Int): Int {
        var count = 0
        val base = mp.lineTop(fromLine)
        while (fromLine + count < mp.lineCount) {
            val h = mp.lineBottom(fromLine + count) - base
            if (h > remaining + EPSILON) break
            count++
        }
        return count
    }

    fun addTextSlice(elementIndex: Int, lineRange: IntRange, mp: MeasuredParagraph, isTitle: Boolean, elementCharBase: Int) {
        val startLine = lineRange.first
        val endLine = lineRange.last
        val height = mp.lineBottom(endLine) - mp.lineTop(startLine)
        items += PageItem.TextSlice(elementIndex, startLine, endLine, usedHeight, height, isTitle)
        usedHeight += height
        val sliceStart = elementCharBase + mp.lineStartOffset(startLine)
        val sliceEnd = elementCharBase + mp.lineEndOffset(endLine)
        if (startChar < 0) startChar = sliceStart
        endChar = maxOf(endChar, sliceEnd)
    }

    fun addImage(elementIndex: Int, resourceId: String, width: Float, height: Float, elementCharBase: Int) {
        val left = (spec.contentWidthPx - width) / 2f
        items += PageItem.ImageBox(elementIndex, resourceId, left, usedHeight, width, height)
        usedHeight += height
        if (startChar < 0) startChar = elementCharBase
        endChar = maxOf(endChar, elementCharBase + 1)
    }

    fun addGap(gap: Float) {
        if (items.isNotEmpty()) usedHeight += gap
    }

    fun noteCharEnd(offset: Int) {
        if (items.isNotEmpty()) endChar = maxOf(endChar, offset)
    }

    fun seal(chapterIndex: Int, pageIndex: Int): PageSpec =
        PageSpec(chapterIndex, pageIndex, items.toList(), startChar.coerceAtLeast(0), endChar)

    private companion object {
        const val EPSILON = 0.5f
    }
}
