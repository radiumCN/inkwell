package com.radium.inkwell.reader.measure

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.em
import kotlin.math.abs

internal fun supportsHanJustification(text: String): Boolean =
    text.none { it == '\n' || it == '\r' || it == '\u2028' || it == '\u2029' || it == '\u0085' } &&
        text.codePoints().anyMatch { Character.UnicodeScript.of(it) == Character.UnicodeScript.HAN }

/** 先按正文断行，再用原文字偏移给每行分配字距，供绘制、分页和选字共用。 */
internal fun measureJustifiedHanParagraph(
    measurer: TextMeasurer,
    text: AnnotatedString,
    style: TextStyle,
    width: Int,
    letterSpacingEm: Float,
    density: Density,
): TextLayoutResult {
    val constraints = Constraints(maxWidth = width)
    val startStyle = style.copy(textAlign = TextAlign.Start)
    val plain = measurer.measure(text, startStyle, constraints = constraints)
    if (plain.lineCount <= 1) return plain
    if (plain.getParagraphDirection(0) != ResolvedTextDirection.Ltr) {
        return measurer.measure(text, style, constraints = constraints)
    }

    if ((0 until plain.lineCount - 1).none { width - plain.getLineRight(it) > FIT_TOLERANCE_PX }) return plain
    val fontSizePx = with(density) { style.fontSize.toPx() }
    val lines = List(plain.lineCount) { index ->
        val start = plain.getLineStart(index)
        val end = plain.getLineEnd(index)
        val ranges = if (index < plain.lineCount - 1) {
            hanSpacingRanges(text.text, start, plain.getLineEnd(index, visibleEnd = true))
        } else emptyList()
        HanLine(start, end, ranges, ranges.sumOf { (from, to) -> text.text.codePointCount(from, to) })
    }
    if (lines.none { it.characterCount >= 2 }) return plain

    fun measureLines(spacing: FloatArray): TextLayoutResult {
        val builder = AnnotatedString.Builder(text)
        lines.forEachIndexed { index, line ->
            if (line.start == line.end) return@forEachIndexed
            // 每个 ParagraphStyle 范围独占一行，无需插入换行符或改写正文。
            val indent = if (index == 0) style.textIndent ?: TextIndent.None else TextIndent.None
            builder.addStyle(ParagraphStyle(textIndent = indent, textDirection = TextDirection.Ltr), line.start, line.end)
            if (spacing[index] > 0f) {
                val tracking = SpanStyle(letterSpacing = (letterSpacingEm + spacing[index]).em)
                for ((start, end) in line.ranges) builder.addStyle(tracking, start, end)
            }
        }
        return measurer.measure(builder.toAnnotatedString(), startStyle, softWrap = false, constraints = constraints)
    }

    // 单行测量的字宽可能和原段落中的行不同，用相同分行方式量一次基础宽度。
    val unspaced = measureLines(FloatArray(lines.size))
    if (!unspaced.keepsLineOffsets(plain)) return plain
    val target = width - .5f
    val spacing = FloatArray(lines.size) { index ->
        val count = lines[index].characterCount
        val room = target - unspaced.getLineRight(index)
        if (count < 2 || room <= FIT_TOLERANCE_PX) 0f else {
            (room / (fontSizePx * count)).coerceAtMost(MAX_ADDED_SPACING_EM)
        }
    }
    if (spacing.all { it == 0f }) return plain

    val trial = measureLines(spacing)
    if (!trial.keepsLineOffsets(plain)) return plain
    var needsCorrection = false
    for (index in spacing.indices) {
        if (spacing[index] == 0f) continue
        val right = trial.getLineRight(index)
        val added = right - unspaced.getLineRight(index)
        if (abs(right - target) > FIT_TOLERANCE_PX && added > 0f) {
            val room = target - unspaced.getLineRight(index)
            val corrected = (spacing[index] * room / added).coerceIn(0f, MAX_ADDED_SPACING_EM)
            needsCorrection = needsCorrection || spacing[index] != corrected
            spacing[index] = corrected
        }
    }
    val aligned = if (needsCorrection) measureLines(spacing) else trial
    return if (aligned.keepsLineOffsets(plain) && (0 until aligned.lineCount).all {
        aligned.getLineRight(it) <= maxOf(width + FIT_TOLERANCE_PX, plain.getLineRight(it))
    }) aligned else plain
}

private fun TextLayoutResult.keepsLineOffsets(original: TextLayoutResult): Boolean =
    lineCount == original.lineCount && (0 until lineCount).all {
        getLineStart(it) == original.getLineStart(it) && getLineEnd(it) == original.getLineEnd(it)
    }

private data class HanLine(val start: Int, val end: Int, val ranges: List<Pair<Int, Int>>, val characterCount: Int)

/** 中文和全角标点参与分配，拉丁字母相邻的间距保留。组合标记跟随它前面的字。 */
private fun hanSpacingRanges(text: String, start: Int, end: Int): List<Pair<Int, Int>> = buildList {
    var from = -1
    var index = start
    while (index < end) {
        val code = text.codePointAt(index)
        val type = Character.getType(code)
        val mark = type == Character.NON_SPACING_MARK.toInt() || type == Character.COMBINING_SPACING_MARK.toInt() ||
            type == Character.ENCLOSING_MARK.toInt()
        val spacing = Character.UnicodeScript.of(code) == Character.UnicodeScript.HAN ||
            code in 0x3001..0x303f || code in 0xff01..0xff0f || code in 0xff1a..0xff20 ||
            code in 0xff3b..0xff40 || code in 0xff5b..0xff60 || (mark && from >= 0)
        if (spacing && from < 0) from = index
        if (!spacing && from >= 0) {
            add(from to index)
            from = -1
        }
        index += Character.charCount(code)
    }
    if (from >= 0) add(from to end)
}

private const val FIT_TOLERANCE_PX = 1f
private const val MAX_ADDED_SPACING_EM = 0.25f
