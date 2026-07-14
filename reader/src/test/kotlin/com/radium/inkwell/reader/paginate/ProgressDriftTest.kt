package com.radium.inkwell.reader.paginate

import com.radium.inkwell.core.model.ChapterContent
import com.radium.inkwell.core.model.ContentElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 进度回退的机制。
 *
 * 阅读器把「读到哪」存成章内字符偏移，恢复时按偏移反查页码。同一套分页下这是无损的
 * （见断言一）。但视口一变（重进阅读页时系统栏隐藏前后要测两次），分页就是另一套 ——
 * 这时若把 position **吸附到所在页的起始偏移**，偏移会往前退；再用这个退化后的偏移
 * 去新分页里定位，就退了一页。用户看到的正是「返回再进来，退回上一页」。
 *
 * 修法是定位时保留原始偏移（ReaderViewModel.showPage 的 keepOffset）。这个测试钉住
 * 「为什么必须保留」—— 谁哪天把 keepOffset 当成多余的优化掉，这里会先叫。
 */
class ProgressDriftTest {

    private fun spec(viewportH: Int) = LayoutSpec(
        viewportWidthPx = 300, viewportHeightPx = viewportH,
        marginLeftPx = 0f, marginTopPx = 0f, marginRightPx = 0f, marginBottomPx = 0f,
        headerHeightPx = 0f, footerHeightPx = 0f,
        fontSizePx = 20f, lineHeightPx = 40f, paragraphSpacingPx = 10f,
        titleFontScale = 1f, firstLineIndentEm = 0f,
    )

    private val content = ChapterContent((1..8).map { ContentElement.Paragraph("字".repeat(120)) })

    private fun chapter(viewportH: Int) = Paginator(FakeTextMeasureFacade())
        .paginate(0, "第一章 陨落的天才", content, spec(viewportH)).chapter

    @Test
    fun `同一套分页下，存进去哪一页就读回哪一页`() {
        val ch = chapter(400)
        ch.pages.forEachIndexed { i, p ->
            assertEquals(i, ch.pageIndexFor(p.startCharOffset))
        }
    }

    @Test
    fun `换了视口再吸附到页首，进度会往前退 —— 所以定位时必须保留原始偏移`() {
        val tall = chapter(440)  // 系统栏隐藏后的视口
        val short = chapter(400) // 系统栏还在时的那一帧

        val target = tall.pages[2].startCharOffset // 用户停在第 3 页

        // 拿保存的偏移去「矮视口」那套分页里定位，落在某一页里 —— 这一步是对的
        val iShort = short.pageIndexFor(target)
        assertTrue(target >= short.pages[iShort].startCharOffset)

        // 但若就此把 position 吸附成该页的起始偏移，偏移就退了
        val snapped = short.pages[iShort].startCharOffset
        assertTrue(snapped < target, "本例中吸附确实会让偏移往前退，否则这个测试没意义")

        // 再拿退化后的偏移回到「高视口」那套分页 —— 退到了上一页
        assertEquals(
            1, tall.pageIndexFor(snapped),
            "吸附后重新定位应当退回第 2 页（这正是那个 bug）",
        )
        // 而保留原始偏移则稳稳停在第 3 页
        assertEquals(2, tall.pageIndexFor(target))
    }
}
