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
        return FakeMeasuredParagraph(text, lines, style.lineHeightPx, style.fontSizePx)
    }

    private class FakeMeasuredParagraph(
        override val text: String,
        private val lines: List<IntRange>,
        private val lineHeight: Float,
        private val em: Float,
    ) : MeasuredParagraph {
        override val lineCount: Int = lines.size
        override fun lineTop(line: Int): Float = line * lineHeight
        override fun lineBottom(line: Int): Float = (line + 1) * lineHeight
        override fun lineStartOffset(line: Int): Int = lines[line].first
        override fun lineEndOffset(line: Int): Int = lines[line].last + 1

        /**
         * 行号 = y / 行高；列则沿行累加字宽找 —— 必须和 measureParagraph 用同一个宽度模型
         * （CJK 1em、ASCII 0.5em），否则这个假测量层自己就前后矛盾，测出来的结论是假的。
         */
        override fun offsetForPosition(x: Float, y: Float): Int {
            if (lines.isEmpty()) return 0
            val line = (y / lineHeight).toInt().coerceIn(0, lines.size - 1)
            val range = lines[line]
            var acc = 0f
            var i = range.first
            while (i <= range.last) {
                val w = if (text[i].code < 128) em * 0.5f else em
                if (acc + w > x) return i
                acc += w
                i++
            }
            return range.last + 1
        }

        /** 假分词：按空白切；没有空白（中文）时给单字 */
        override fun wordBoundary(offset: Int): IntRange {
            if (text.isEmpty()) return IntRange.EMPTY
            val i = offset.coerceIn(0, text.length - 1)
            if (text[i].isWhitespace()) return i until i + 1
            var start = i
            var end = i + 1
            while (start > 0 && !text[start - 1].isWhitespace()) start--
            while (end < text.length && !text[end].isWhitespace()) end++
            return start until end
        }

        override val renderHandle: Any? = null
    }
}
