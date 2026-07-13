package com.radium.inkwell.reader.paginate

import com.radium.inkwell.reader.measure.MeasuredParagraph
import com.radium.inkwell.reader.measure.ResolvedTextStyle
import com.radium.inkwell.reader.measure.TextMeasureFacade

/**
 * 确定性等宽测量模型：CJK 宽 = 1em，ASCII = 0.5em，行高 = style.lineHeightPx。
 * 让测试可以手算预期行数/页数。
 */
class FakeTextMeasureFacade : TextMeasureFacade {

    override fun measureParagraph(text: String, style: ResolvedTextStyle, widthPx: Int): MeasuredParagraph {
        val em = style.fontSizePx
        val width = widthPx.toFloat()
        val lines = mutableListOf<IntRange>() // char ranges per line
        var lineStart = 0
        var x = style.firstLineIndentEm * em
        var i = 0
        while (i < text.length) {
            val w = if (text[i].code < 128) em * 0.5f else em
            if (x + w > width && i > lineStart) {
                lines += lineStart until i
                lineStart = i
                x = 0f
            }
            x += w
            i++
        }
        lines += lineStart until text.length
        return FakeMeasuredParagraph(lines, style.lineHeightPx)
    }

    private class FakeMeasuredParagraph(
        private val lines: List<IntRange>,
        private val lineHeight: Float,
    ) : MeasuredParagraph {
        override val lineCount: Int = lines.size
        override fun lineTop(line: Int): Float = line * lineHeight
        override fun lineBottom(line: Int): Float = (line + 1) * lineHeight
        override fun lineStartOffset(line: Int): Int = lines[line].first
        override fun lineEndOffset(line: Int): Int = lines[line].last + 1
        override val renderHandle: Any? = null
    }
}
