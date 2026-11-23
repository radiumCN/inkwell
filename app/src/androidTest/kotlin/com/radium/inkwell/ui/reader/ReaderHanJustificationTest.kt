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

    private fun layout(text: String, width: Int, justify: Boolean): TextLayoutResult =
        facade.measureParagraph(text, style.copy(justify = justify), width).renderHandle as TextLayoutResult
}
