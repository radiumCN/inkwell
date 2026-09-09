package com.radium.inkwell.reader.paginate

import com.radium.inkwell.core.model.ChapterContent
import com.radium.inkwell.core.model.ContentElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PaginatorTest {

    // 视口 300x400，无边距；字号 20 → 每行 15 个汉字；行高 40 → 每页 10 行
    private fun spec(
        viewportH: Int = 400,
        paragraphSpacing: Float = 0f,
    ) = LayoutSpec(
        viewportWidthPx = 300,
        viewportHeightPx = viewportH,
        marginLeftPx = 0f, marginTopPx = 0f, marginRightPx = 0f, marginBottomPx = 0f,
        headerHeightPx = 0f, footerHeightPx = 0f,
        fontSizePx = 20f,
        lineHeightPx = 40f,
        paragraphSpacingPx = paragraphSpacing,
        titleFontScale = 1f,
        firstLineIndentEm = 0f,
    )

    private val paginator = Paginator(FakeTextMeasureFacade())

    private fun cjk(n: Int) = "字".repeat(n)

    @Test
    fun `single short paragraph fits one page`() {
        val content = ChapterContent(listOf(ContentElement.Paragraph(cjk(30))))
        val result = paginator.paginate(0, "", content, spec())
        assertEquals(1, result.chapter.pages.size)
        // 30 字 / 每行 15 字 = 2 行
        val slice = result.chapter.pages[0].items.single() as PageItem.TextSlice
        assertEquals(0..1, slice.lineRange)
    }

    @Test
    fun `long paragraph splits across pages at line boundary`() {
        // 300 字 = 20 行 → 页容量 10 行 → 2 页
        val content = ChapterContent(listOf(ContentElement.Paragraph(cjk(300))))
        val result = paginator.paginate(0, "", content, spec())
        assertEquals(2, result.chapter.pages.size)
        val p0 = result.chapter.pages[0].items.single() as PageItem.TextSlice
        val p1 = result.chapter.pages[1].items.single() as PageItem.TextSlice
        assertEquals(0..9, p0.lineRange)
        assertEquals(10..19, p1.lineRange)
        // 字符偏移连续无缝
        assertEquals(result.chapter.pages[0].endCharOffset, result.chapter.pages[1].startCharOffset)
    }

    @Test
    fun `title occupies first page with body`() {
        val content = ChapterContent(listOf(ContentElement.Paragraph(cjk(15))))
        val result = paginator.paginate(0, "第一章 测试", content, spec())
        val items = result.chapter.pages[0].items
        assertEquals(2, items.size)
        assertTrue((items[0] as PageItem.TextSlice).isTitle)
        assertTrue(!(items[1] as PageItem.TextSlice).isTitle)
    }

    @Test
    fun `heading keeps with next - pushed when fewer than two body lines fit below it`() {
        // 8 行正文占 320px，剩 80px：小标题 1 行（行高 40*1.15=46）放得下，但它下面只剩 34px，
        // 连一行正文都塞不进 → 标题整体推到第二页，与正文同页
        val content = ChapterContent(
            listOf(
                ContentElement.Paragraph(cjk(120)), // 8 行
                ContentElement.Heading(level = 2, text = "第二节"),
                ContentElement.Paragraph(cjk(30)), // 2 行
            )
        )
        val result = paginator.paginate(0, "", content, spec())
        assertEquals(2, result.chapter.pages.size)
        val p1 = result.chapter.pages[1].items
        assertTrue((p1[0] as PageItem.TextSlice).isTitle, "标题应开在第二页页首")
        assertTrue(!(p1[1] as PageItem.TextSlice).isTitle, "正文紧随其后")
        assertEquals(0f, (p1[0] as PageItem.TextSlice).yTopInPage)
    }

    @Test
    fun `heading stays when two body lines fit below it`() {
        // 5 行正文占 200px，剩 200px：标题 46 + 正文 2 行 80 = 126 放得下 → 不推
        val content = ChapterContent(
            listOf(
                ContentElement.Paragraph(cjk(75)), // 5 行
                ContentElement.Heading(level = 2, text = "第二节"),
                ContentElement.Paragraph(cjk(30)),
            )
        )
        val result = paginator.paginate(0, "", content, spec())
        assertEquals(1, result.chapter.pages.size)
    }

    @Test
    fun `image never splits - pushed to next page`() {
        // 9 行文本占 360px，剩 40px；图片占位高 225px 放不下 → 推第二页
        val content = ChapterContent(
            listOf(
                ContentElement.Paragraph(cjk(135)), // 9 行
                ContentElement.Image("img:1"),
            )
        )
        val result = paginator.paginate(0, "", content, spec())
        assertEquals(2, result.chapter.pages.size)
        assertTrue(result.chapter.pages[1].items.single() is PageItem.ImageBox)
    }

    @Test
    fun `pageIndexFor locates by char offset after repagination`() {
        val content = ChapterContent(listOf(ContentElement.Paragraph(cjk(300))))
        val small = paginator.paginate(0, "", content, spec()).chapter          // 2 页
        val big = paginator.paginate(0, "", content, spec(viewportH = 200)).chapter // 5 行/页 → 4 页
        val offset = small.pages[1].startCharOffset // 第 150 字
        val pageInBig = big.pageIndexFor(offset)
        assertTrue(big.pages[pageInBig].startCharOffset <= offset)
        assertTrue(offset < big.pages[pageInBig].endCharOffset)
    }

    @Test
    fun `empty chapter yields single blank page`() {
        val result = paginator.paginate(3, "", ChapterContent(emptyList()), spec())
        assertEquals(1, result.chapter.pages.size)
        assertEquals(3, result.chapter.pages[0].chapterIndex)
    }

    @Test
    fun `paragraph spacing not applied at page top`() {
        // 每段 1 行 + 段距 20px：页容量 400px → floor((400+20)/60) = 7 段/页
        val paras = List(10) { ContentElement.Paragraph(cjk(15)) }
        val result = paginator.paginate(0, "", ChapterContent(paras), spec(paragraphSpacing = 20f))
        val firstPageFirst = result.chapter.pages[1].items.first() as PageItem.TextSlice
        assertEquals(0f, firstPageFirst.yTopInPage)
    }

    @Test
    fun `长章先回调覆盖目标偏移的页，再返回完整结果`() {
        // 300 字 = 20 行 → 2 页；notifyAfterChar=0 应在第一页封好时就回调
        val content = ChapterContent(listOf(ContentElement.Paragraph(cjk(300))))
        var partial: Paginator.Result? = null
        val result = paginator.paginate(
            0, "", content, spec(),
            notifyAfterChar = 0,
            onPartial = { if (partial == null) partial = it },
        )
        val first = partial
        assertTrue(first != null, "应先出第一页")
        checkNotNull(first)
        assertEquals(false, first.complete)
        assertEquals(1, first.chapter.pages.size)
        assertEquals(true, result.complete)
        assertEquals(2, result.chapter.pages.size)
    }
}
