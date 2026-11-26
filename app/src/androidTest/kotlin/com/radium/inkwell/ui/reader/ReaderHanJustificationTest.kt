package com.radium.inkwell.ui.reader

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.radium.inkwell.core.model.ChapterContent
import com.radium.inkwell.core.model.ContentElement
import com.radium.inkwell.reader.api.ReaderSettings
import com.radium.inkwell.reader.api.ReaderTheme
import com.radium.inkwell.reader.measure.ComposeTextMeasureFacade
import com.radium.inkwell.reader.measure.ResolvedTextStyle
import com.radium.inkwell.reader.paginate.LayoutSpec
import com.radium.inkwell.reader.paginate.PageItem
import com.radium.inkwell.reader.paginate.PageSpec
import com.radium.inkwell.reader.paginate.Paginator
import com.radium.inkwell.reader.render.RenderablePage
import com.radium.inkwell.reader.render.SelectionEnd
import com.radium.inkwell.reader.render.TextSelection
import com.radium.inkwell.reader.render.drawPage
import com.radium.inkwell.reader.render.drawPageItems
import com.radium.inkwell.reader.render.selectionHandlePosition
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
    fun highlightReachesSelectionHandleWhenSelectionEndsAtWrappedHanLine() {
        assertWrappedHanSelectionHighlight(firstVisibleLine = 0, selectedLine = 0)
    }

    @Test
    fun highlightStaysOnSelectedHanLineAfterWrap() {
        assertWrappedHanSelectionHighlight(firstVisibleLine = 0, selectedLine = 1)
        assertWrappedHanSelectionHighlight(firstVisibleLine = 1, selectedLine = 1)
    }

    @Test
    fun paginatedSelectionFollowsReflowedReaderSettings() {
        val sample = "晨雾沿着旧码头慢慢散开，渡船停在河心，岸边的灯还亮着。".repeat(72)
        val chapter = ChapterContent(listOf(ContentElement.Paragraph("港湾"), ContentElement.Paragraph(sample)))
        val presets = listOf(
            ReaderSettings(),
            ReaderSettings(
                fontSizeSp = ReaderSettings.FONT_SIZE_MIN, lineSpacingMult = 1.2f,
                firstLineIndentEm = 0f, paragraphSpacingEm = 0f,
                marginHorizontalDp = 8f, marginVerticalDp = 0f, showHeader = false, showFooter = false,
                fontId = ReaderSettings.FONT_SERIF,
            ),
            ReaderSettings(
                fontSizeSp = ReaderSettings.FONT_SIZE_MAX, lineSpacingMult = 2.2f,
                letterSpacingEm = 0.15f, paragraphSpacingEm = 1f,
                marginHorizontalDp = 48f, marginVerticalDp = 48f, bodyFontWeight = ReaderSettings.FONT_WEIGHT_BOLD,
                fontId = ReaderSettings.FONT_SANS,
            ),
        )
        val paginator = Paginator(facade)
        for (settings in presets) {
            for (viewport in listOf(IntSize(360, 640), IntSize(640, 360))) {
                for (justify in listOf(true, false)) {
                    val layout = buildLayoutSpec(
                        viewport, settings.copy(justify = justify), Density(1f),
                        cutout = IntInsets(0, 0, 0, 0), infoLineHeight = 18.sp, speechInsetPx = 0f,
                    )
                    val result = paginator.paginate(0, "渡口", chapter, layout)
                    val pageSpec = result.chapter.pages.first { page ->
                        page.items.filterIsInstance<PageItem.TextSlice>().any { it.startLine > 0 }
                    }
                    val slice = pageSpec.items.filterIsInstance<PageItem.TextSlice>().first { it.startLine > 0 }
                    val page = RenderablePage(pageSpec, result.measured)
                    val paragraph = result.measured.getValue(slice.elementIndex)
                    val textLayout = paragraph.renderHandle as TextLayoutResult
                    val line = slice.startLine
                    val start = textLayout.getLineStart(line) + 1
                    val end = start + 2
                    assertTrue(end < textLayout.getLineEnd(line, visibleEnd = true))
                    val selected = TextSelection(slice.elementIndex, start, end, paragraph.text.substring(start, end))
                    val startHandle = checkNotNull(page.selectionHandlePosition(selected, SelectionEnd.START, layout))
                    val endHandle = checkNotNull(page.selectionHandlePosition(selected, SelectionEnd.END, layout))
                    val lineHeight = paragraph.lineBottom(line) - paragraph.lineTop(line)
                    val bottom = layout.marginTopPx + layout.headerHeightPx + slice.yTopInPage + lineHeight
                    val description = "viewport=$viewport, font=${settings.fontSizeSp}, justify=$justify"
                    assertEquals(description, bottom, startHandle.anchor.y, 0.1f)
                    assertEquals(description, bottom, endHandle.anchor.y, 0.1f)
                    val x = ((startHandle.anchor.x + endHandle.anchor.x) / 2).roundToInt()
                    val y = bottom.roundToInt() - 3
                    val plain = renderPage(layout) { drawPage(page, layout, settings.theme) }
                    val highlighted = renderPage(layout) { drawPage(page, layout, settings.theme, selected) }
                    try {
                        assertTrue(description, plain.getPixel(x, y) != highlighted.getPixel(x, y))
                        assertEquals(description, plain.getPixel(x, y + 6), highlighted.getPixel(x, y + 6))
                    } finally {
                        plain.recycle()
                        highlighted.recycle()
                    }
                }
            }
        }
    }

    private fun assertWrappedHanSelectionHighlight(firstVisibleLine: Int, selectedLine: Int) {
        val sample = "晨雾沿着旧码头慢慢散开渡船还停在河心岸边的钟声已经响了三遍"
        val glyphWidth = layout("晨", 200, justify = false).getLineRight(0) - 2 * style.fontSizePx
        val width = (glyphWidth * 9.65f).roundToInt()
        val paragraph = facade.measureParagraph(sample, style, width)
        val textLayout = paragraph.renderHandle as TextLayoutResult
        val lastLine = textLayout.lineCount - 1
        assertTrue(lastLine > selectedLine)
        val end = textLayout.getLineEnd(selectedLine)
        val start = end - 2
        val selection = TextSelection(0, start, end, sample.substring(start, end))
        val sliceTop = textLayout.getLineTop(firstVisibleLine)
        val height = textLayout.getLineBottom(lastLine) - sliceTop
        val layout = LayoutSpec(
            viewportWidthPx = width + 16, viewportHeightPx = height.roundToInt() + 8,
            marginLeftPx = 8f, marginTopPx = 0f, marginRightPx = 8f, marginBottomPx = 0f,
            headerHeightPx = 0f, footerHeightPx = 0f, fontSizePx = style.fontSizePx,
            lineHeightPx = style.lineHeightPx, paragraphSpacingPx = 0f,
        )
        val slice = PageItem.TextSlice(0, firstVisibleLine, lastLine, 0f, height, false)
        val page = RenderablePage(PageSpec(0, 0, listOf(slice), 0, sample.length), mapOf(0 to paragraph))
        val handle = checkNotNull(page.selectionHandlePosition(selection, SelectionEnd.END, layout))
        val sampleX = handle.anchor.x.roundToInt()
        val sampleY = handle.anchor.y.roundToInt() - 3
        val plain = renderPage(layout) { drawPageItems(page, layout, ReaderTheme.PAPER, originY = 0f) }
        val highlighted = renderPage(layout) {
            drawPageItems(page, layout, ReaderTheme.PAPER, originY = 0f, selection = selection)
        }
        try {
            assertTrue(
                "选区高亮应当贴在第 ${selectedLine + 1} 行的手柄上方",
                highlighted.getPixel(sampleX - 2, sampleY) != plain.getPixel(sampleX - 2, sampleY),
            )
            assertEquals(plain.getPixel(sampleX + 2, sampleY), highlighted.getPixel(sampleX + 2, sampleY))
            if (selectedLine > firstVisibleLine) {
                val precedingY = (textLayout.getLineBottom(selectedLine - 1) - sliceTop).roundToInt() - 3
                assertEquals(plain.getPixel(sampleX - 2, precedingY), highlighted.getPixel(sampleX - 2, precedingY))
            }
        } finally {
            plain.recycle()
            highlighted.recycle()
        }
    }

    private fun renderPage(layout: LayoutSpec, draw: DrawScope.() -> Unit): Bitmap {
        val bitmap = Bitmap.createBitmap(layout.viewportWidthPx, layout.viewportHeightPx, Bitmap.Config.ARGB_8888)
        CanvasDrawScope().draw(
            Density(1f), LayoutDirection.Ltr, Canvas(bitmap.asImageBitmap()),
            Size(bitmap.width.toFloat(), bitmap.height.toFloat()),
            block = draw,
        )
        return bitmap
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
