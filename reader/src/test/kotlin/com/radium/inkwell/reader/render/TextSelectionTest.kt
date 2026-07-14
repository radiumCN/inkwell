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
}
