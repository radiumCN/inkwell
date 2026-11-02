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
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.Font
import com.radium.inkwell.reader.api.ReaderSettings
import java.io.File
import java.util.concurrent.ConcurrentHashMap

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
 * 预设字体 + 用户导入的字体文件（`file:<文件名>`，文件在 [fontsDir] 里）。
 *
 * **文件不在就回落默认字体**，而不是抛：字体 id 跟着 WebDAV 同步，A 机导入的字体在 B 机上根本没有；
 * 用户在别处删了字体文件、设置还没跟上，也是同一种情况。读书的人只会看到换回了默认字体，不会看到崩溃。
 *
 * FontFamily 按 (id, 修改时间) 缓存：同一个字体每次分页都 new 一个 FontFamily，Compose 的字体缓存
 * 就认不出是同一个，每章都要重新从磁盘解析一遍。带上修改时间是为了删掉再导入一个同名的不同字体时，
 * 不拿到旧字体的缓存。
 */
class FileFontRegistry(private val fontsDir: File) : FontRegistry {
    private val cache = ConcurrentHashMap<String, Pair<Long, FontFamily>>()

    override fun resolve(fontId: String): FontFamily {
        if (!ReaderSettings.isFileFont(fontId)) return SystemFontRegistry.resolve(fontId)
        val file = File(fontsDir, fontId.removePrefix(ReaderSettings.FONT_FILE_PREFIX))
        // 文件名来自同步过来的设置，别让 "../" 之类的东西把读取引到字体目录外面去
        if (file.parentFile?.canonicalPath != fontsDir.canonicalPath || !file.isFile) {
            return FontFamily.Default
        }
        val stamp = file.lastModified()
        cache[fontId]?.let { (at, family) -> if (at == stamp) return family }
        val family = FontFamily(Font(file))
        cache[fontId] = stamp to family
        return family
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
    /** 一章最多三种样式；getOrPut 避免每段 new TextStyle（量字热路径上的 pointer chasing） */
    private val styleCache = HashMap<ResolvedTextStyle, TextStyle>(4)

    override fun measureParagraph(text: String, style: ResolvedTextStyle, widthPx: Int): MeasuredParagraph {
        val result = measurer.measure(
            text = AnnotatedString(text),
            style = textStyleOf(style),
            constraints = Constraints(maxWidth = widthPx.coerceAtLeast(1)),
        )
        return ComposeMeasuredParagraph(result)
    }

    private fun textStyleOf(style: ResolvedTextStyle): TextStyle = styleCache.getOrPut(style) {
        val fontSizeSp = with(density) { style.fontSizePx.toSp() }
        val lineHeightSp = with(density) { style.lineHeightPx.toSp() }
        val indentSp = (fontSizeSp.value * style.firstLineIndentEm).sp
        TextStyle(
            fontSize = fontSizeSp,
            lineHeight = lineHeightSp,
            fontFamily = fontRegistry.resolve(style.fontId),
            fontWeight = if (style.isBold) FontWeight.Bold else FontWeight.Normal,
            textIndent = TextIndent(firstLine = indentSp),
            textAlign = if (style.justify) TextAlign.Justify else TextAlign.Start,
            letterSpacing = style.letterSpacingEm.em,
            lineBreak = LineBreak.Paragraph,
            lineHeightStyle = LineHeightStyle(
                alignment = LineHeightStyle.Alignment.Center,
                trim = LineHeightStyle.Trim.None,
            ),
            platformStyle = PlatformTextStyle(includeFontPadding = false),
        )
    }

    private class ComposeMeasuredParagraph(
        private val result: TextLayoutResult,
    ) : MeasuredParagraph {
        // 行高/偏移抽成连续数组：分页装箱只扫这些，不再每次虚调用进 TextLayoutResult 对象图
        private val lineTops: FloatArray
        private val lineBottoms: FloatArray
        private val lineStarts: IntArray
        private val lineEnds: IntArray

        init {
            val n = result.lineCount
            lineTops = FloatArray(n)
            lineBottoms = FloatArray(n)
            lineStarts = IntArray(n)
            lineEnds = IntArray(n)
            for (i in 0 until n) {
                lineTops[i] = result.getLineTop(i)
                lineBottoms[i] = result.getLineBottom(i)
                lineStarts[i] = result.getLineStart(i)
                lineEnds[i] = result.getLineEnd(i)
            }
        }

        override val lineCount: Int get() = lineTops.size
        override fun lineTop(line: Int): Float = lineTops[line]
        override fun lineBottom(line: Int): Float = lineBottoms[line]
        override fun lineStartOffset(line: Int): Int = lineStarts[line]
        override fun lineEndOffset(line: Int): Int = lineEnds[line]
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
