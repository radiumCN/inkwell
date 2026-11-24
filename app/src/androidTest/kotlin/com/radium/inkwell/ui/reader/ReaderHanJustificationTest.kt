package com.radium.inkwell.ui.reader

import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.radium.inkwell.reader.measure.ComposeTextMeasureFacade
import com.radium.inkwell.reader.measure.ResolvedTextStyle
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderHanJustificationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val facade = ComposeTextMeasureFacade(createFontFamilyResolver(context), Density(1f))
    private val style = ResolvedTextStyle(32f, 48f, "system", 2f)

    @Test
    fun justifiedHanKeepsOffsetsIndentAndLastLineWhileFillingTheRightMargin() {
        val sample = "晨雾沿着旧码头慢慢散开渡船还停在河心岸边的钟声已经响了三遍船上的旅客起身把行李搬到门边等着舷梯落下才依次走到岸上"
        val glyphWidth = layout("晨", 200, justify = false).getLineRight(0) - 2 * style.fontSizePx
        val width = (glyphWidth * 9.65f).roundToInt()
        val plain = layout(sample, width, justify = false)
        val paragraph = facade.measureParagraph(sample, style, width)
        val justified = paragraph.renderHandle as TextLayoutResult

        assertTrue(plain.lineCount > 2)
        assertEquals(sample, justified.layoutInput.text.text)
        assertEquals(plain.lineCount, justified.lineCount)
        assertEquals(plain.getLineLeft(0), justified.getLineLeft(0), 1f)
        val last = plain.lineCount - 1
        assertEquals(plain.getLineRight(last), justified.getLineRight(last), 1f)
        assertTrue((0 until last).any { width - plain.getLineRight(it) > 8f })
        for (line in 0..last) {
            assertEquals(plain.getLineStart(line), justified.getLineStart(line))
            assertEquals(plain.getLineEnd(line), justified.getLineEnd(line))
            if (line < last) assertEquals("line $line", width.toFloat(), justified.getLineRight(line), 2f)
        }

        val offset = justified.getLineEnd(1) - 2
        val box = justified.getBoundingBox(offset)
        val hitX = box.left + box.width / 4f
        assertEquals(offset, paragraph.offsetForPosition(hitX, box.center.y))
        val selection = justified.getPathForRange(offset, offset + 1).getBounds()
        assertTrue(selection.contains(box.center))
    }

    @Test
    fun mixedChineseAndEnglishDistributeSpaceWithoutSpacingInsideLatinWords() {
        val sample = "旧码头的打印机显示 Offline mode。林舟重新接上电源，屏幕里出现 Ready to print，她把纸摊平等着新的提示。"
        val width = 343
        val plain = layout(sample, width, justify = false)
        val justified = layout(sample, width, justify = true)
        val spans = justified.layoutInput.text.spanStyles
        assertEquals(plain.lineCount, justified.lineCount)
        assertTrue(spans.isNotEmpty())
        for (word in listOf("Offline", "mode", "Ready", "print")) {
            val start = sample.indexOf(word)
            assertTrue(spans.none { it.start < start + word.length && it.end > start })
        }
        val last = plain.lineCount - 1
        assertEquals(plain.getLineRight(last), justified.getLineRight(last), 1f)
    }

    @Test
    fun englishLinesStillJustifyWhenOnlyTheLastLineHasHanCharacters() {
        val sample = "The old captain walked along the quiet harbor ".repeat(4) + "最后"
        val width = 380
        val plain = layout(sample, width, justify = false)
        val justified = layout(sample, width, justify = true)
        val last = plain.lineCount - 1

        assertTrue(last > 1)
        assertTrue(plain.getLineStart(last) <= sample.indexOf('最'))
        assertTrue((0 until last).any { justified.getLineRight(it) > plain.getLineRight(it) + 2f })
        assertEquals(plain.getLineRight(last), justified.getLineRight(last), 1f)
    }

    @Test
    fun separateChineseAndEnglishLinesJustifyInTheSameParagraph() {
        val chinese = "晨雾沿着码头慢慢散开渡船停在河心".repeat(3)
        val english = "The old captain walked along the quiet harbor ".repeat(4)
        val sample = "$chinese $english 最后"
        val englishStart = chinese.length + 1
        val englishEnd = englishStart + english.length
        val fixture = (330..440 step 5).firstNotNullOf { width ->
            val plain = layout(sample, width, justify = false)
            val eligible = (0 until plain.lineCount - 1).filter { width - plain.getLineRight(it) > 5f }
            val hanLine = eligible.firstOrNull {
                plain.getLineStart(it) > 0 && plain.getLineEnd(it, visibleEnd = true) <= chinese.length
            }
            val wordLine = eligible.firstOrNull {
                val start = plain.getLineStart(it)
                val end = plain.getLineEnd(it, visibleEnd = true)
                start >= englishStart && end <= englishEnd && sample.substring(start, end).trim().contains(' ')
            }
            if (hanLine != null && wordLine != null) Triple(width, hanLine, wordLine) else null
        }
        val (width, hanLine, wordLine) = fixture
        val plain = layout(sample, width, justify = false)
        val justified = layout(sample, width, justify = true)

        assertEquals(sample, justified.layoutInput.text.text)
        assertEquals(plain.lineCount, justified.lineCount)
        for (line in 0 until plain.lineCount) {
            assertEquals(plain.getLineStart(line), justified.getLineStart(line))
            assertEquals(plain.getLineEnd(line), justified.getLineEnd(line))
        }
        assertEquals(width.toFloat(), justified.getLineRight(hanLine), 2f)
        assertEquals(width.toFloat(), justified.getLineRight(wordLine), 2f)
        val last = plain.lineCount - 1
        assertEquals(plain.getLineRight(last), justified.getLineRight(last), 1f)
    }

    private fun layout(text: String, width: Int, justify: Boolean): TextLayoutResult =
        facade.measureParagraph(text, style.copy(justify = justify), width).renderHandle as TextLayoutResult
}
