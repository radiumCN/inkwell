package com.radium.inkwell.reader.measure

/**
 * 文本测量抽象。分页算法只依赖本接口，不触碰 Android 类型：
 * Android 实现包装 Compose TextMeasurer，JVM 测试用确定性 Fake。
 */
interface TextMeasureFacade {
    fun measureParagraph(
        text: String,
        style: ResolvedTextStyle,
        widthPx: Int,
    ): MeasuredParagraph
}

interface MeasuredParagraph {
    val lineCount: Int
    fun lineTop(line: Int): Float
    fun lineBottom(line: Int): Float
    /** 该行首字符在段内的偏移 */
    fun lineStartOffset(line: Int): Int
    fun lineEndOffset(line: Int): Int
    /** 渲染句柄：Android 实现持有 TextLayoutResult，Fake 为 null */
    val renderHandle: Any?
}

fun MeasuredParagraph.linesHeight(range: IntRange): Float =
    lineBottom(range.last) - lineTop(range.first)

data class ResolvedTextStyle(
    val fontSizePx: Float,
    val lineHeightPx: Float,
    val fontId: String,
    val firstLineIndentEm: Float,
    val isBold: Boolean = false,
    val justify: Boolean = true,
)
