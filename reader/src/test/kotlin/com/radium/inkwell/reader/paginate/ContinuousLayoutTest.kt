package com.radium.inkwell.reader.paginate

import com.radium.inkwell.core.model.ChapterContent
import com.radium.inkwell.core.model.ContentElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 滚动模式的地基：给分页器一个"高得放得下整章"的视口，它必须只产出**一页**，
 * 且那一页带着全部元素的 y 偏移。这个前提一旦不成立（比如分页器改成按段落硬切页），
 * 滚动模式会静默地只显示第一屏 —— 所以钉住它。
 */
class ContinuousLayoutTest {

    private fun spec(viewportH: Int) = LayoutSpec(
        viewportWidthPx = 300,
        viewportHeightPx = viewportH,
        marginLeftPx = 0f, marginTopPx = 0f, marginRightPx = 0f, marginBottomPx = 0f,
        headerHeightPx = 0f, footerHeightPx = 0f,
        fontSizePx = 20f,
        lineHeightPx = 40f,
        paragraphSpacingPx = 10f,
        titleFontScale = 1f,
        firstLineIndentEm = 0f,
    )

    private val paginator = Paginator(FakeTextMeasureFacade())

    @Test
    fun `无限高视口把整章排成一页`() {
        val content = ChapterContent(
            (1..12).map { ContentElement.Paragraph("字".repeat(60)) }, // 每段 4 行
        )
        // 常规视口：必然多页
        val paged = paginator.paginate(0, "第一章", content, spec(400))
        assertTrue(paged.chapter.pages.size > 1, "常规视口应该分成多页")

        // 滚动模式的视口
        val tall = paginator.paginate(0, "第一章", content, spec(2_000_000))
        assertEquals(1, tall.chapter.pages.size, "无限高视口应该只有一页")

        // 标题 + 12 段 = 13 个元素，一个不能少
        val items = tall.chapter.pages[0].items.filterIsInstance<PageItem.TextSlice>()
        assertEquals(13, items.size)
        assertEquals((0..12).toList(), items.map { it.elementIndex })
    }

    @Test
    fun `元素的 y 偏移单调递增，且段间距被算进去`() {
        val content = ChapterContent(listOf(
            ContentElement.Paragraph("字".repeat(15)), // 1 行 = 40px
            ContentElement.Paragraph("字".repeat(15)),
        ))
        val page = paginator.paginate(0, "", content, spec(2_000_000)).chapter.pages[0]
        val items = page.items.filterIsInstance<PageItem.TextSlice>()

        assertEquals(2, items.size, "标题为空时不占元素")
        val gap = items[1].yTopInPage - (items[0].yTopInPage + items[0].height)
        assertEquals(10f, gap, "两段之间应该正好是一个段间距")
    }
}
