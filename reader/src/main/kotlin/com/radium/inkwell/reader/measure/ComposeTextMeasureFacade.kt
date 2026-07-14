package com.radium.inkwell.reader.measure

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp

/** fontId → FontFamily 的解析 */
interface FontRegistry {
    fun resolve(fontId: String): FontFamily
}

/** 系统预设字体：默认/衬线/无衬线/等宽 */
object SystemFontRegistry : FontRegistry {
    override fun resolve(fontId: String): FontFamily = when (fontId) {
        "serif" -> FontFamily.Serif
        "sans" -> FontFamily.SansSerif
        "mono" -> FontFamily.Monospace
        else -> FontFamily.Default
    }
}

/**
 * 用 Compose TextMeasurer 实现的测量层。手动构造（非 rememberTextMeasurer），
 * 可在 Dispatchers.Default 上调用；同一实例不做跨线程并发复用。
 */
class ComposeTextMeasureFacade(
    private val fontFamilyResolver: FontFamily.Resolver,
    private val density: Density,
    private val fontRegistry: FontRegistry = SystemFontRegistry,
) : TextMeasureFacade {

    private val measurer = TextMeasurer(fontFamilyResolver, density, LayoutDirection.Ltr, cacheSize = 0)

    override fun measureParagraph(text: String, style: ResolvedTextStyle, widthPx: Int): MeasuredParagraph {
        val fontSizeSp = with(density) { style.fontSizePx.toSp() }
        val lineHeightSp = with(density) { style.lineHeightPx.toSp() }
        val indentSp = (fontSizeSp.value * style.firstLineIndentEm).sp
        val result = measurer.measure(
            text = AnnotatedString(text),
            style = TextStyle(
                fontSize = fontSizeSp,
                lineHeight = lineHeightSp,
                fontFamily = fontRegistry.resolve(style.fontId),
                fontWeight = if (style.isBold) FontWeight.Bold else FontWeight.Normal,
                textIndent = TextIndent(firstLine = indentSp),
                textAlign = if (style.justify) TextAlign.Justify else TextAlign.Start,
                lineBreak = LineBreak.Paragraph,
                lineHeightStyle = LineHeightStyle(
                    alignment = LineHeightStyle.Alignment.Center,
                    trim = LineHeightStyle.Trim.None,
                ),
                platformStyle = PlatformTextStyle(includeFontPadding = false),
            ),
            constraints = Constraints(maxWidth = widthPx.coerceAtLeast(1)),
        )
        return ComposeMeasuredParagraph(result)
    }

    private class ComposeMeasuredParagraph(
        private val result: TextLayoutResult,
    ) : MeasuredParagraph {
        override val lineCount: Int get() = result.lineCount
        override fun lineTop(line: Int): Float = result.getLineTop(line)
        override fun lineBottom(line: Int): Float = result.getLineBottom(line)
        override fun lineStartOffset(line: Int): Int = result.getLineStart(line)
        override fun lineEndOffset(line: Int): Int = result.getLineEnd(line)
        override val text: String get() = result.layoutInput.text.text

        override fun offsetForPosition(x: Float, y: Float): Int =
            result.getOffsetForPosition(Offset(x, y))

        override fun wordBoundary(offset: Int): IntRange {
            val len = text.length
            if (len == 0) return IntRange.EMPTY
            val safe = offset.coerceIn(0, len - 1)
            val range = result.getWordBoundary(safe)
            // 中文常常整段没有词边界，退化成"整个偏移点"——此时至少给一个字，
            // 否则长按下去什么都没选中，用户以为功能坏了
            return if (range.end > range.start) {
                range.start until range.end
            } else {
                safe until (safe + 1).coerceAtMost(len)
            }
        }

        override val renderHandle: Any get() = result
    }
}
