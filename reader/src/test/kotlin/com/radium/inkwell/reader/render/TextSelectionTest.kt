package com.radium.inkwell.reader.render

import com.radium.inkwell.core.model.ChapterContent
import com.radium.inkwell.core.model.ContentElement
import com.radium.inkwell.reader.paginate.FakeTextMeasureFacade
import com.radium.inkwell.reader.paginate.LayoutSpec
import com.radium.inkwell.reader.paginate.PageItem
import com.radium.inkwell.reader.paginate.Paginator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 长按选字的命中测试。自绘正文没有系统级的文字选择，坐标 → 字符的换算全是自己算的，
 * 尤其跨页段落那段偏移最容易错（在下半页选到上半页的字），必须钉住。
 */
class TextSelectionTest {

    // 视口 300x400，无边距；字号 20 → 每行 15 个汉字；行高 40 → 每页 10 行
    private fun spec(viewportH: Int = 400) = LayoutSpec(
        viewportWidthPx = 300,
        viewportHeightPx = viewportH,
        marginLeftPx = 0f, marginTopPx = 0f, marginRightPx = 0f, marginBottomPx = 0f,
        headerHeightPx = 0f, footerHeightPx = 0f,
        fontSizePx = 20f,
        lineHeightPx = 40f,
        paragraphSpacingPx = 0f,
        titleFontScale = 1f,
        firstLineIndentEm = 0f,
    )

    private val paginator = Paginator(FakeTextMeasureFacade())

    private fun page(text: String, pageIndex: Int = 0, viewportH: Int = 400): RenderablePage {
        val result = paginator.paginate(
            0, "", ChapterContent(listOf(ContentElement.Paragraph(text))), spec(viewportH),
        )
        return RenderablePage(
            spec = result.chapter.pages[pageIndex],
            measured = result.measured,
        )
    }

    @Test
    fun `长按空白处选不中任何东西`() {
        // 只有一行文字，往下 300px 是空白
        assertNull(page("abc def").selectWordAt(10f, 300f, spec()))
    }

    @Test
    fun `长按选中触点所在的词`() {
        // "hello world" —— 第 0 行，字宽 20：x=30 落在第 1 个字符上（hello 里）
        // 排版元素 0 是 Paginator 塞的章节标题，正文段落从 1 起
        val sel = assertNotNull(page("hello world").selectWordAt(30f, 10f, spec()))
        assertEquals("hello", sel.text)
        assertEquals(1, sel.elementIndex)
    }

    @Test
    fun `触点在第二个词上就选第二个词`() {
        // ASCII 半宽 = 10px；偏移 7 落在 "world" 里 → x = 75
        val sel = assertNotNull(page("hello world").selectWordAt(75f, 10f, spec()))
        assertEquals("world", sel.text)
    }

    /**
     * 跨页段落只有一半的行画在本页上，drawTextSlice 用 translate 把这半段挪到位。
     * 命中测试必须做同一个换算 —— 否则在第二页的第一行长按，选到的是段落真正的第一行。
     */
    @Test
    fun `跨页段落的第二页命中的是本页的行，不是段首`() {
        // CJK 每字 1em=20px，视口宽 300 → 每行 15 字；行高 40、视口高 400 → 每页 10 行。
        // 300 字 = 20 行，必然跨到第 2 页。
        val text = "字".repeat(300)
        val p = page(text, pageIndex = 1)

        val slice = p.spec.items.filterIsInstance<PageItem.TextSlice>().single()
        assertTrue(slice.lineRange.first >= 10, "第二页应从第 11 行起，实际 ${slice.lineRange}")

        val para = assertNotNull(p.measured[slice.elementIndex])
        val expected = para.lineStartOffset(slice.lineRange.first)

        // 在第二页最顶上长按：命中的字符应是本页首行的首字（第 150 个字），而不是整段的第 0 个。
        // 这里直接看原始偏移 —— 若看 selectWordAt 的结果，整段汉字没有词边界、
        // 分词器会把 300 字整个吞成一个"词"，选区永远从 0 起，跨页偏移错没错根本看不出来。
        val (element, offset) = assertNotNull(p.offsetAt(0f, 1f, spec()))
        assertEquals(slice.elementIndex, element)
        assertEquals(
            expected, offset,
            "在第二页顶部长按，命中的却是段落第 $offset 个字（应为 $expected）—— 坐标换算漏了跨页偏移",
        )
    }

    @Test
    fun `拖动扩展选区，锚点词始终包含在内`() {
        val p = page("hello world again", viewportH = 400)
        val anchor = assertNotNull(p.selectWordAt(30f, 10f, spec())) // "hello"
        assertEquals("hello", anchor.text)

        // 往右拖到 "world" 里（ASCII 半宽）
        val extended = p.extendSelection(anchor, 75f, 10f, spec())
        assertTrue(extended.text.startsWith("hello"), "锚点词被拖丢了: ${extended.text}")
        assertTrue(extended.text.length > anchor.text.length)
    }

    @Test
    fun `往回拖也从锚点词往前扩，而不是把选区翻过来`() {
        val p = page("hello world again")
        val anchor = assertNotNull(p.selectWordAt(75f, 10f, spec())) // "world"
        val extended = p.extendSelection(anchor, 0f, 10f, spec())     // 拖回段首
        assertEquals(0, extended.start)
        assertTrue(extended.text.endsWith("world"), "选区应从段首延伸到锚点词末尾: ${extended.text}")
    }

    @Test
    fun `分别向内拖两个端点可把整句缩到一个词`() {
        val p = page("前文，词汇仍在。")
        val initial = assertNotNull(p.selectWordAt(70f, 10f, spec()))
        assertEquals("前文，词汇仍在。", initial.text)
        val startMoved = p.moveSelectionEnd(initial, SelectionEnd.START, 60f, 10f, spec())
        assertEquals("词汇仍在。", startMoved.text)
        val word = p.moveSelectionEnd(startMoved, SelectionEnd.END, 100f, 10f, spec())
        assertEquals("词汇", word.text)
        assertEquals(3, word.start)
        assertEquals(5, word.end)
    }

    @Test
    fun `手柄经过表情时保留完整字符并且不能把选区拖空`() {
        val p = page("甲😀乙")
        val initial = assertNotNull(p.selectWordAt(30f, 10f, spec()))
        val startMoved = p.moveSelectionEnd(initial, SelectionEnd.START, 40f, 10f, spec())
        val emoji = p.moveSelectionEnd(startMoved, SelectionEnd.END, 0f, 10f, spec())
        assertEquals("😀", emoji.text)
        assertEquals(emoji, p.moveSelectionEnd(emoji, SelectionEnd.START, 200f, 10f, spec()))
    }

    @Test
    fun `读屏可独立调整端点并保留完整字符`() {
        val p = page("甲😀乙")
        val all = TextSelection(1, 0, 4, "甲😀乙")
        val startMoved = p.stepSelectionEnd(all, SelectionEnd.START, forward = true)
        val emoji = p.stepSelectionEnd(startMoved, SelectionEnd.END, forward = false)

        assertEquals("😀乙", startMoved.text)
        assertEquals(TextSelection(1, 1, 3, "😀"), emoji)
        assertEquals(emoji, p.stepSelectionEnd(emoji, SelectionEnd.START, forward = true))
        assertEquals(emoji, p.stepSelectionEnd(emoji, SelectionEnd.END, forward = false))
        assertEquals(startMoved, p.stepSelectionEnd(emoji, SelectionEnd.END, forward = true))
    }

    @Test
    fun `跨页长段落的选区限制在可见页内`() {
        val p = page("字".repeat(300), pageIndex = 1)
        val selected = assertNotNull(p.selectWordAt(20f, 5f, spec()))
        assertEquals(150, selected.start)
        assertEquals(300, selected.end)
        assertEquals(selected, p.moveSelectionEnd(selected, SelectionEnd.START, 0f, -100f, spec()))
        assertEquals(selected, p.stepSelectionEnd(selected, SelectionEnd.START, forward = false))
        assertEquals(selected, p.stepSelectionEnd(selected, SelectionEnd.END, forward = true))
    }

    // ---- 跨段：两段各占一行。"hello world" 是元素 1（y 0..40），"second para" 是元素 2（y 40..80）----

    private fun twoParagraphs(): RenderablePage {
        val result = paginator.paginate(
            0, "",
            ChapterContent(listOf(ContentElement.Paragraph("hello world"), ContentElement.Paragraph("second para"))),
            spec(),
        )
        return RenderablePage(spec = result.chapter.pages[0], measured = result.measured)
    }

    @Test
    fun `往下拖过段尾接着选下一段，复制出来两段之间是换行`() {
        val p = twoParagraphs()
        val anchor = assertNotNull(p.selectWordAt(30f, 10f, spec())) // "hello"
        // 拖到第二段 "sec|ond" 的 o 上（ASCII 半宽 10px，x=35 → 偏移 3）
        val sel = p.extendSelection(anchor, 35f, 50f, spec())
        assertEquals("hello world\nsec", sel.text)
        assertEquals(TextSelection(1, 0, 3, "hello world\nsec", endElementIndex = 2), sel)
        assertTrue(!sel.isSingleParagraph)
    }

    @Test
    fun `往上拖跨段时锚点词仍在选区末尾`() {
        val p = twoParagraphs()
        val anchor = assertNotNull(p.selectWordAt(75f, 50f, spec())) // 第二段的 "para"
        assertEquals("para", anchor.text)
        val sel = p.extendSelection(anchor, 65f, 10f, spec()) // 第一段 "world" 的 w
        assertEquals(TextSelection(1, 6, 11, "world\nsecond para", endElementIndex = 2), sel)
    }

    @Test
    fun `手指拖到正文下面的空白处，吸到最后一段的段尾`() {
        val p = twoParagraphs()
        val anchor = assertNotNull(p.selectWordAt(30f, 10f, spec()))
        assertEquals("hello world\nsecond para", p.extendSelection(anchor, 10f, 300f, spec()).text)
    }

    @Test
    fun `终点手柄拖回第一段就恢复成单段，压在下一段开头也不多挂一个空段`() {
        val p = twoParagraphs()
        val cross = TextSelection(1, 0, 3, "hello world\nsec", endElementIndex = 2)
        val single = TextSelection(1, 0, 11, "hello world")
        // 拖到第一段行尾之后
        assertEquals(single, p.moveSelectionEnd(cross, SelectionEnd.END, 115f, 10f, spec()))
        // 拖到第二段最开头：零个字的第二段不算进选区，复制出来没有尾巴上的换行
        val atSeam = p.moveSelectionEnd(cross, SelectionEnd.END, 0f, 50f, spec())
        assertEquals(single, atSeam)
        assertTrue(atSeam.isSingleParagraph)
    }

    @Test
    fun `起点手柄拖进下一段，选区收窄到那一段`() {
        val p = twoParagraphs()
        val cross = TextSelection(1, 6, 6, "world\nsecond", endElementIndex = 2)
        assertEquals(
            TextSelection(2, 3, 6, "ond"),
            p.moveSelectionEnd(cross, SelectionEnd.START, 35f, 50f, spec()),
        )
    }

    @Test
    fun `读屏逐字移动端点可以跨段，退回来还是原来的选区`() {
        val p = twoParagraphs()
        val world = TextSelection(1, 6, 11, "world")
        // 终点在第一段末尾往后：跨进第二段并带上它的第一个字
        val crossEnd = p.stepSelectionEnd(world, SelectionEnd.END, forward = true)
        assertEquals(TextSelection(1, 6, 1, "world\ns", endElementIndex = 2), crossEnd)
        assertEquals(world, p.stepSelectionEnd(crossEnd, SelectionEnd.END, forward = false))

        val second = TextSelection(2, 0, 6, "second")
        // 起点在第二段开头往前：跨回第一段并带上它的最后一个字
        val crossStart = p.stepSelectionEnd(second, SelectionEnd.START, forward = false)
        assertEquals(TextSelection(1, 10, 6, "d\nsecond", endElementIndex = 2), crossStart)
        assertEquals(second, p.stepSelectionEnd(crossStart, SelectionEnd.START, forward = true))
    }
}
