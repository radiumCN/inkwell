package com.radium.inkwell.reader.paginate

/** 排版指纹：任一字段变化都必须重新分页 */
data class LayoutSpec(
    val viewportWidthPx: Int,
    val viewportHeightPx: Int,
    val marginLeftPx: Float,
    val marginTopPx: Float,
    val marginRightPx: Float,
    val marginBottomPx: Float,
    val headerHeightPx: Float,
    val footerHeightPx: Float,
    val fontSizePx: Float,
    val lineHeightPx: Float,
    val paragraphSpacingPx: Float,
    val titleFontScale: Float = 1.4f,
    val titleTopSpacingPx: Float = 0f,
    val firstLineIndentEm: Float = 2f,
    val fontId: String = "system",
    val justify: Boolean = true,
    val letterSpacingEm: Float = 0f,
    /**
     * 正文里的图排不排（阅读设置「正文显示图片」，默认关）。放进排版指纹：开关一拨，分页缓存整体作废、
     * 按同一个字符偏移重排 —— 图在字符流里恒占 1 个偏移（排不排都占），进度不会因为开关挪位置。
     */
    val showImages: Boolean = false,
    /** 1dp 合多少 px。图按「原图一像素 = 1dp」排（见 [fitImage]），和订阅文章同一条规则 */
    val density: Float = 1f,
) {
    val contentWidthPx: Int get() = (viewportWidthPx - marginLeftPx - marginRightPx).toInt()
    val contentHeightPx: Float get() =
        viewportHeightPx - marginTopPx - marginBottomPx - headerHeightPx - footerHeightPx
}

/** 轻量分页结果：纯数据，可缓存 */
data class PageSpec(
    val chapterIndex: Int,
    val pageIndexInChapter: Int,
    val items: List<PageItem>,
    /** 本页起始/结束字符在全章字符流中的偏移（end 为开区间） */
    val startCharOffset: Int,
    val endCharOffset: Int,
    /**
     * 本页最后一项之后、封页前已经垫上的留白（段距、分隔线的空行）。
     *
     * 翻页模式用不着它 —— 下一页从页顶重新起排。滚动模式却是把页首尾相接的：不补回来的话，
     * 恰好在段尾断页的地方，上一段的末行和下一段的首行就贴在一起，比页内的段距窄一截，
     * 读着像两段并成了一段。章末一页恒为 0：章与章之间有下一章标题的上间距隔着，不再叠一层段距。
     */
    val trailingGapPx: Float = 0f,
)

sealed interface PageItem {
    /** 一个段落的行片段（可能是整段，也可能是跨页段的一半） */
    data class TextSlice(
        /** 指向排版元素列表（含虚拟标题元素，0 = 章节标题） */
        val elementIndex: Int,
        /** 含端点。两个 Int 而不是 IntRange，避免热路径上每个切片再挂一个堆对象 */
        val startLine: Int,
        val endLine: Int,
        val yTopInPage: Float,
        val height: Float,
        val isTitle: Boolean,
    ) : PageItem {
        val lineRange: IntRange get() = startLine..endLine
    }

    /**
     * 一张图占的框，宽高已按原图比例算好（见 [fitImage]），渲染层把图画满这个框。
     * [failed]：图没取到，这里排的是一条「图片没加载出来 · 点按重试」（字的测量结果在 measured[elementIndex]），
     * 不是一个永远等不来图的空框 —— 空框只会让人以为还在加载。
     */
    data class ImageBox(
        val elementIndex: Int,
        val resourceId: String,
        val left: Float,
        val top: Float,
        val width: Float,
        val height: Float,
        val failed: Boolean = false,
    ) : PageItem
}

data class PaginatedChapter(
    val chapterIndex: Int,
    val title: String,
    val pages: List<PageSpec>,
    val totalChars: Int,
) {
    /** 按章内字符偏移二分定位页 */
    fun pageIndexFor(charOffset: Int): Int {
        if (pages.isEmpty()) return 0
        val clamped = charOffset.coerceIn(0, (totalChars - 1).coerceAtLeast(0))
        var lo = 0
        var hi = pages.lastIndex
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (pages[mid].startCharOffset <= clamped) lo = mid else hi = mid - 1
        }
        return lo
    }
}
