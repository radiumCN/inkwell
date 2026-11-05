package com.radium.inkwell.core.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 换源面板行尾的「多 8 章 / 少 3 章」靠它。标错比不标糟：用户会照着它挑源，
 * 所以认不准的一律 null —— 下面一半的用例是在钉「别瞎认」。
 */
class ChapterOrdinalTest {

    @Test
    fun `阿拉伯数字与中文数字都认`() {
        assertEquals(13370, ChapterOrdinal.of("第13370章 不速之客"))
        assertEquals(1200, ChapterOrdinal.of("第一千二百章 灯市"))
        assertEquals(105, ChapterOrdinal.of("第一百零五回"))
        assertEquals(12, ChapterOrdinal.of("第１２话"))
    }

    @Test
    fun `序号可以在中间`() {
        assertEquals(13370, ChapterOrdinal.of("04-18 第13370章 不速之客"))
        assertEquals(88, ChapterOrdinal.of("VIP 第88章"))
    }

    @Test
    fun `卷号不当章号`() {
        assertEquals(12, ChapterOrdinal.of("第五卷 第12章 夜航"))
        assertNull(ChapterOrdinal.of("第五卷 风起青萍"))
    }

    @Test
    fun `没有第X章就不认`() {
        // 最新章字段里只有日期、只有章名、开头一串数字 —— 都不猜
        assertNull(ChapterOrdinal.of("04-18"))
        assertNull(ChapterOrdinal.of("013 回声"))
        assertNull(ChapterOrdinal.of("大结局"))
        assertNull(ChapterOrdinal.of("第0章 上架感言"))
        assertNull(ChapterOrdinal.of(""))
        assertNull(ChapterOrdinal.of(null))
    }

    @Test
    fun `差几章`() {
        assertEquals(8, ChapterOrdinal.gap("第13370章 不速之客", "第13362章 灯下黑"))
        assertEquals(-3, ChapterOrdinal.gap("第13359章 夜航", "第13362章 灯下黑"))
        assertEquals(0, ChapterOrdinal.gap("第13362章", "第13362章 灯下黑"))
        assertEquals(-20, ChapterOrdinal.gap("第30章", "第50章"))
    }

    @Test
    fun `编号对不上的不比`() {
        // 按卷重新编号的站：12 对 13362 不是「少 13350 章」
        assertNull(ChapterOrdinal.gap("第五卷 第12章", "第13362章"))
        assertNull(ChapterOrdinal.gap("第13370章", "04-18"))
        assertNull(ChapterOrdinal.gap(null, "第13362章"))
    }
}
