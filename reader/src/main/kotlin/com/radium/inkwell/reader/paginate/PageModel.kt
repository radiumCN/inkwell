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
    /** 顶部小标题（页眉章节名）离屏幕上边的距离 = 挖孔 + 用户设的头部留白。只给页眉定位用 */
    val headerTopPaddingPx: Float = 0f,
    val firstLineIndentEm: Float = 2f,
    val fontId: String = "system",
    val justify: Boolean = true,
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
)

sealed interface PageItem {
    /** 一个段落的行片段（可能是整段，也可能是跨页段的一半） */
    data class TextSlice(
        /** 指向排版元素列表（含虚拟标题元素，0 = 章节标题） */
        val elementIndex: Int,
        val lineRange: IntRange,
        val yTopInPage: Float,
        val height: Float,
        val isTitle: Boolean,
    ) : PageItem

    data class ImageBox(
        val elementIndex: Int,
        val resourceId: String,
        val left: Float,
        val top: Float,
        val width: Float,
        val height: Float,
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
