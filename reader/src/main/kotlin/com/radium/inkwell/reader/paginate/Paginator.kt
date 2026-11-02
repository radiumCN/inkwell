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
     * @param isAbandoned 每排一段问一次：这次排版是不是已经作废了（视口 / 字号换了、调用方被取消）。
     *   是就立刻收手，返回 `complete = false` 的半截结果 —— 调用方得自己丢掉它，别当缓存。
     *   排版是纯 CPU 循环、没有挂起点，协程取消对它无效：不问这一句的话，连点几下 A+，
     *   每一档都要把整章排完、还一路占着测量锁，新字号排在它们后面干等。
     */
    fun paginate(
        chapterIndex: Int,
        title: String,
        content: ChapterContent,
        spec: LayoutSpec,
        notifyAfterChar: Int = 0,
        onPartial: ((Result) -> Unit)? = null,
        isAbandoned: () -> Boolean = { false },
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
            // 中途结果的总字数按「已封的页覆盖到哪」算。charOffset 是**正在排的那一段**的起点：
            // 目标偏移落在一个跨页的长段里（大字号、文学类 EPUB 很常见）时，pageIndexFor 会把目标
            // 夹到这段开头之前 —— 先出的是前一页、还顺手把进度挪了过去，排完再跳回来。
            PaginatedChapter(
                chapterIndex, title, pages.toList(),
                if (complete) charOffset else maxOf(charOffset, pages.lastOrNull()?.endCharOffset ?: 0),
            ),
            // 完整结果不再拷测量表：paginate 返回后不会再往里写。中途回调必须拷一份，
            // 否则 Default 线程继续 put 时，主线程若正在遍历会 ConcurrentModification。
            if (complete) measured else HashMap(measured),
            complete,
        )

        fun sealPage(endOfChapter: Boolean) {
            if (!cursor.isEmpty) {
                pages += cursor.seal(chapterIndex, pages.size, keepTrailingGap = !endOfChapter)
                cursor = PageCursor(spec)
                // 目标偏移已经落在已封页里 → 先让 UI 出这一屏，余下继续排
                if (partialCb != null && !partialEmitted && notifyAfterChar < pages.last().endCharOffset) {
                    partialEmitted = true
                    partialCb(snapshot(complete = false))
                }
            }
        }

        /** 章内断页：页尾垫上的段距要记着，滚动模式拼页时补回来（见 [PageSpec.trailingGapPx]） */
        fun seal() = sealPage(endOfChapter = false)

        elements.forEachIndexed { idx, element ->
            if (isAbandoned()) return snapshot(complete = false)
            when (element) {
                is TitleElement -> {
                    if (element.text.isNotBlank()) {
                        val mp = measurer.measureParagraph(element.text, titleStyle, spec.contentWidthPx)
                        measured[idx] = mp
                        cursor.addLeadingSpace(spec.titleTopSpacingPx)
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
        sealPage(endOfChapter = true)
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
            // 标题 keep-with-next：标题起步、本页已有内容、而「整个标题 + 段距 + 两行正文」
            // 放不下 → 整体推下页，别让标题孤悬页底、正文全在下一页。
            // 从前这里写的是 `fit < minOf(mp.lineCount, 1)`，与 `fit == 0` 等价 —— 判定形同虚设，
            // 文件头 KDoc 承诺的规则实际从未生效过。页面本来就空时不推（再推还是空页，死循环）。
            if (isTitle && line == 0 && !cursor.isEmpty && !cursor.fits(keepWithNextHeight(mp, spec))) {
                onSeal()
                cursor = currentCursor()
                continue
            }
            val effectiveFit = cursor.fitLines(mp, fromLine = line)
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

    /** 标题要带着走的最小高度：标题全部行 + 一个段距 + [KEEP_WITH_NEXT_LINES] 行正文 */
    private fun keepWithNextHeight(title: MeasuredParagraph, spec: LayoutSpec): Float =
        (title.lineBottom(title.lineCount - 1) - title.lineTop(0)) +
            spec.paragraphSpacingPx +
            KEEP_WITH_NEXT_LINES * spec.lineHeightPx

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

    private companion object {
        const val KEEP_WITH_NEXT_LINES = 2
    }

    // 字距三种样式一起带：标题按正文的字距走，否则正文调松以后标题显得挤成一团
    private fun LayoutSpec.bodyStyle() = ResolvedTextStyle(
        fontSizePx = fontSizePx,
        lineHeightPx = lineHeightPx,
        fontId = fontId,
        firstLineIndentEm = firstLineIndentEm,
        justify = justify,
        letterSpacingEm = letterSpacingEm,
    )

    private fun LayoutSpec.titleStyle() = ResolvedTextStyle(
        fontSizePx = fontSizePx * titleFontScale,
        lineHeightPx = lineHeightPx * titleFontScale,
        fontId = fontId,
        firstLineIndentEm = 0f,
        isBold = true,
        justify = false,
        letterSpacingEm = letterSpacingEm,
    )

    private fun LayoutSpec.headingStyle() = ResolvedTextStyle(
        fontSizePx = fontSizePx * 1.15f,
        lineHeightPx = lineHeightPx * 1.15f,
        fontId = fontId,
        firstLineIndentEm = 0f,
        isBold = true,
        justify = false,
        letterSpacingEm = letterSpacingEm,
    )
}

/** 当前正在装配的页 */
internal class PageCursor(private val spec: LayoutSpec) {
    private val items = mutableListOf<PageItem>()
    private var usedHeight = 0f
    /** 最后一项的底边。usedHeight 比它多出来的那截，就是页尾垫上的段距 */
    private var contentBottom = 0f
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
        contentBottom = usedHeight
        val sliceStart = elementCharBase + mp.lineStartOffset(startLine)
        val sliceEnd = elementCharBase + mp.lineEndOffset(endLine)
        if (startChar < 0) startChar = sliceStart
        endChar = maxOf(endChar, sliceEnd)
    }

    fun addImage(elementIndex: Int, resourceId: String, width: Float, height: Float, elementCharBase: Int) {
        val left = (spec.contentWidthPx - width) / 2f
        items += PageItem.ImageBox(elementIndex, resourceId, left, usedHeight, width, height)
        usedHeight += height
        contentBottom = usedHeight
        if (startChar < 0) startChar = elementCharBase
        endChar = maxOf(endChar, elementCharBase + 1)
    }

    fun addGap(gap: Float) {
        if (items.isNotEmpty()) usedHeight += gap
    }

    /**
     * 页首留白：与 [addGap] 相反，空页也照加。章节标题永远是章首页的第一个元素，
     * 从前用 addGap 加标题上间距，判空直接跳过 ——「标题上间距」那档设置调到哪都一样。
     * 不进 [items]，所以 [isEmpty] 仍为 true：keep-with-next 不会因为这段空白把标题推下页。
     */
    fun addLeadingSpace(space: Float) {
        usedHeight += space
    }

    fun noteCharEnd(offset: Int) {
        if (items.isNotEmpty()) endChar = maxOf(endChar, offset)
    }

    /** [keepTrailingGap] 章末一页传 false：见 [PageSpec.trailingGapPx] */
    fun seal(chapterIndex: Int, pageIndex: Int, keepTrailingGap: Boolean = true): PageSpec =
        PageSpec(
            chapterIndex, pageIndex, items.toList(), startChar.coerceAtLeast(0), endChar,
            trailingGapPx = if (keepTrailingGap) (usedHeight - contentBottom).coerceAtLeast(0f) else 0f,
        )

    private companion object {
        const val EPSILON = 0.5f
    }
}
