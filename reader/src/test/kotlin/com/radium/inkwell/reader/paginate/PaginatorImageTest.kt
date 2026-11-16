package com.radium.inkwell.reader.paginate

import com.radium.inkwell.core.model.ChapterContent
import com.radium.inkwell.core.model.ContentElement
import com.radium.inkwell.reader.render.RenderablePage
import com.radium.inkwell.reader.render.imageAt
import com.radium.inkwell.reader.render.loneImageShift
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 正文插图的分页：按原图比例排、放不下挪到下一页、没取到排一条提示，以及图不占字符偏移 */
class PaginatorImageTest {

    // 视口 300x400，无边距；字号 20 → 每行 15 个汉字；行高 40 → 每页 10 行；1dp = 1px
    private fun spec(showImages: Boolean = true, density: Float = 1f) = LayoutSpec(
        viewportWidthPx = 300,
        viewportHeightPx = 400,
        marginLeftPx = 0f, marginTopPx = 0f, marginRightPx = 0f, marginBottomPx = 0f,
        headerHeightPx = 0f, footerHeightPx = 0f,
        fontSizePx = 20f,
        lineHeightPx = 40f,
        paragraphSpacingPx = 0f,
        titleFontScale = 1f,
        firstLineIndentEm = 0f,
        showImages = showImages,
        density = density,
    )

    private val paginator = Paginator(FakeTextMeasureFacade())

    private fun cjk(n: Int) = "字".repeat(n)

    private val pic = ContentElement.Image("https://ex.com/a.jpg")

    private fun paginate(
        content: List<ContentElement>,
        spec: LayoutSpec = spec(),
        info: (ContentElement.Image) -> ImageInfo? = { ImageInfo.Sized(600, 400) },
    ) = paginator.paginate(0, "", ChapterContent(content), spec, images = info).chapter

    @Test
    fun `开关关着不排图、也不问尺寸，偏移和只有字时一样`() {
        var asked = false
        val chapter = paginate(
            listOf(ContentElement.Paragraph(cjk(15)), pic, ContentElement.Paragraph(cjk(15))),
            spec(showImages = false),
        ) { asked = true; ImageInfo.Sized(600, 400) }
        assertFalse(asked)
        val page = chapter.pages.single()
        assertTrue(page.items.all { it is PageItem.TextSlice })
        assertEquals(30, page.endCharOffset)
        assertEquals(30, chapter.totalChars)
    }

    /**
     * 进度按字符偏移记：开关一拨，图后面那些字的偏移不能变；和不收图的旧版本（只有字）也得一样 ——
     * 那时存下的进度升级后还要接着用，偏移往前错一个字就会落到上一页
     */
    @Test
    fun `开没开图、收没收图，字的偏移都一样`() {
        val text = listOf(ContentElement.Paragraph(cjk(15 * 9)), ContentElement.Paragraph(cjk(15 * 12)))
        val withPic = listOf(text[0], pic, text[1])
        val old = paginate(text, spec(showImages = false))
        val off = paginate(withPic, spec(showImages = false))
        val on = paginate(withPic)
        assertEquals(old.totalChars, off.totalChars)
        assertEquals(old.totalChars, on.totalChars)
        assertEquals(old.pages.map { it.startCharOffset }, off.pages.map { it.startCharOffset })
        // 开着图：图挪到第二页页首，第二页仍从第二段的第一个字算起
        assertEquals(135, on.pages[1].startCharOffset)
        assertEquals(old.pages.last().endCharOffset, on.pages.last().endCharOffset)
    }

    @Test
    fun `比正文宽的图按比例缩到正文宽，排在两段之间`() {
        val page = paginate(listOf(ContentElement.Paragraph(cjk(15)), pic, ContentElement.Paragraph(cjk(15))))
            .pages.single()
        val image = page.items[1] as PageItem.ImageBox
        assertEquals(300f, image.width)
        assertEquals(200f, image.height)
        assertEquals(40f, image.top)
        assertEquals(240f, (page.items[2] as PageItem.TextSlice).yTopInPage)
    }

    @Test
    fun `小图按原图一像素 1dp 的大小居中，不放大`() {
        val image = paginate(listOf(pic), spec(density = 2f)) { ImageInfo.Sized(50, 40) }
            .pages.single().items.single() as PageItem.ImageBox
        assertEquals(100f, image.width)
        assertEquals(80f, image.height)
        assertEquals(100f, image.left)
    }

    /** 比一个字还小的（装饰点、防抓取的字图）不排，和开关关着一样 */
    @Test
    fun `比一个字还小的图不排`() {
        val page = paginate(listOf(ContentElement.Paragraph(cjk(15)), pic)) { ImageInfo.Sized(24, 24) }.pages.single()
        assertTrue(page.items.none { it is PageItem.ImageBox })
        assertEquals(15, page.endCharOffset)
    }

    @Test
    fun `比一屏还高的图按一屏封顶、等比缩`() {
        val image = paginate(listOf(pic)) { ImageInfo.Sized(300, 1000) }.pages.single().items.single() as PageItem.ImageBox
        assertEquals(400f, image.height)
        assertEquals(120f, image.width)
    }

    /** 图不跨页：本页剩下的放不下，整张挪到下一页，前一页底下空一截 */
    @Test
    fun `放不下的图整张挪到下一页`() {
        val chapter = paginate(listOf(ContentElement.Paragraph(cjk(15 * 8)), pic, ContentElement.Paragraph(cjk(15))))
        assertEquals(2, chapter.pages.size)
        assertTrue(chapter.pages[0].items.all { it is PageItem.TextSlice })
        val moved = chapter.pages[1].items.first() as PageItem.ImageBox
        assertEquals(0f, moved.top)
        // 页的偏移首尾相接：第二页从图那 1 个偏移开始
        assertEquals(chapter.pages[0].endCharOffset, chapter.pages[1].startCharOffset)
        assertEquals(120, chapter.pages[1].startCharOffset)
    }

    @Test
    fun `没取到的图排一条整宽的提示，字量好了`() {
        val result = paginator.paginate(
            0, "", ChapterContent(listOf(ContentElement.Paragraph(cjk(15)), pic)), spec(density = 2f),
            images = { ImageInfo.Failed },
        )
        val row = result.chapter.pages.single().items[1] as PageItem.ImageBox
        assertTrue(row.failed)
        assertEquals(300f, row.width)
        // 最矮 48dp
        assertEquals(96f, row.height)
        assertEquals(IMAGE_FAILED_LABEL, assertNotNull(result.measured[row.elementIndex]).text)
    }

    // ---- 翻页模式的「整页一张图」居中，与命中测试 ----

    @Test
    fun `页上只有一张图时上下居中，命中测试跟着画的位置走`() {
        val result = paginator.paginate(0, "", ChapterContent(listOf(pic)), spec(), images = { ImageInfo.Sized(600, 400) })
        val page = RenderablePage(result.chapter.pages.single(), result.measured)
        // 框高 200，正文区 400 → 往下挪 100
        assertEquals(100f, page.loneImageShift(spec()))
        assertNotNull(page.imageAt(150f, 150f, spec()))
        assertNull(page.imageAt(150f, 50f, spec()))
    }

    @Test
    fun `图和字在同一页时不居中`() {
        val result = paginator.paginate(
            0, "", ChapterContent(listOf(ContentElement.Paragraph(cjk(15)), pic)), spec(),
            images = { ImageInfo.Sized(600, 400) },
        )
        val page = RenderablePage(result.chapter.pages.single(), result.measured)
        assertEquals(0f, page.loneImageShift(spec()))
        assertNull(page.imageAt(150f, 20f, spec()))
        assertNotNull(page.imageAt(150f, 100f, spec()))
    }
}
