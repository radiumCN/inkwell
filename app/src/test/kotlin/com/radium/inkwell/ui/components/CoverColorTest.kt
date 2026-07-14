package com.radium.inkwell.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 默认封面的取色。
 *
 * 两件事必须成立：同一本书永远同一个色（否则每次重进书架，颜色乱跳，反而更难认），
 * 且下标永远合法 —— hashCode 是有符号的，直接 % 会得到负数，那是一个必崩的 IndexOutOfBounds，
 * 而且只在特定书名上崩（测试里不写死这一条，线上就等着某本书让书架整个白屏）。
 */
class CoverColorTest {

    @Test
    fun `同一本书永远是同一个色`() {
        assertEquals(colorIndex("武动乾坤"), colorIndex("武动乾坤"))
    }

    @Test
    fun `hash 为负的书名也能取到合法下标`() {
        // 找几个 hashCode 为负的真实书名，外加边界
        val negatives = listOf("斗破苍穹", "我的贴身校花", "近身狂婿", "女总裁的全能兵王", "傲世九重天")
            .filter { it.hashCode() < 0 }
        assertTrue(negatives.isNotEmpty(), "样本里没有 hash 为负的书名，这条断言就白写了")
        negatives.forEach { assertTrue(colorIndex(it) >= 0, "$it → ${colorIndex(it)}") }
    }

    @Test
    fun `空书名不崩`() {
        assertTrue(colorIndex("") >= 0)
    }
}
