package com.radium.inkwell.ui.bookshelf

import com.radium.inkwell.ui.components.Dimens
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 书架网格「每行本数」（ShelfGrid.kt）：手机上排几列、宽屏多排几列、捏合换哪一档。
 * 手势本身要真机试，这里钉住换算不被改歪 —— 尤其是「默认档和加设置之前一模一样」。
 */
class ShelfGridTest {

    /** 网格左右各垫 12dp、格间 12dp（BookshelfScreen 的 contentPadding / spacedBy） */
    private val spacing = 12f

    private fun count(columns: Int, contentWidth: Float) =
        shelfColumnCount(columns, contentWidth, spacing, shelfMinCell(columns).value)

    @Test
    fun `手机竖屏上就是选的本数`() {
        // 360dp 到 440dp 宽的手机，减去左右 24dp 的留白
        for (width in 336..416) {
            for (columns in ShelfColumns.OPTIONS) {
                assertEquals(columns, count(columns, width.toFloat()), "内容宽 ${width}dp、每行 $columns 本")
            }
        }
    }

    @Test
    fun `默认 3 本这档和从前的 Adaptive 96dp 一模一样`() {
        // 从前是 GridCells.Adaptive(96dp)：放得下几格就几格。窄到连 3 格都放不下的分屏小窗不算（那里新的会托到 3）
        for (width in 312..2400) {
            val adaptive = ((width + spacing) / (Dimens.bookshelfGridMin.value + spacing)).toInt()
            assertEquals(adaptive, count(ShelfColumns.DEFAULT, width.toFloat()), "内容宽 ${width}dp")
        }
    }

    @Test
    fun `宽屏多排几本，档位越密排得越多`() {
        // 800dp 宽的平板
        assertEquals(7, count(3, 776f))
        assertEquals(8, count(4, 776f))
        assertEquals(10, count(5, 776f))
        for (width in 336..2400) {
            val w = width.toFloat()
            assertTrue(count(3, w) <= count(4, w) && count(4, w) <= count(5, w), "内容宽 ${width}dp")
        }
    }

    @Test
    fun `张开少一本、捏拢多一本，没到一档的比例不动`() {
        assertEquals(3, pinchColumns(4, PINCH_STEP))
        assertEquals(5, pinchColumns(4, 1f / PINCH_STEP))
        assertEquals(4, pinchColumns(4, 1.1f))
        assertEquals(4, pinchColumns(4, 0.9f))
    }

    @Test
    fun `捏到头不再换`() {
        assertEquals(3, pinchColumns(3, 2f))
        assertEquals(5, pinchColumns(5, 0.3f))
    }

    @Test
    fun `不认识的本数回默认`() {
        assertEquals(3, ShelfColumns.sanitize(null))
        assertEquals(3, ShelfColumns.sanitize(7))
        assertEquals(4, ShelfColumns.sanitize(4))
    }

    @Test
    fun `每行 5 本时不印最新章`() {
        assertTrue(ShelfColumns.showsLatestChapter(3))
        assertTrue(ShelfColumns.showsLatestChapter(4))
        assertEquals(false, ShelfColumns.showsLatestChapter(5))
    }
}
