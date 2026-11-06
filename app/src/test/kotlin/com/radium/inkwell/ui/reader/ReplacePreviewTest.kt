package com.radium.inkwell.ui.reader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 选字替换弹窗的「本章 N 处」和预览。数错了，用户就照着一个错的数去决定删不删 ——
 * 尤其是「本章 1 处」其实是二十处的时候。
 */
class ReplacePreviewTest {

    @Test
    fun `逐段数、不重叠，和 String replace 换掉的处数一致`() {
        val paras = listOf("", "哈哈哈哈", "哈", "没有")
        val p = replacePreview(paras, "哈哈")!!
        // 「哈哈哈哈」里不重叠是 2 处（不是 3 处）；单独一个「哈」不算
        assertEquals(2, p.count)
        assertEquals("XX", "哈哈哈哈".replace("哈哈", "X"))
    }

    @Test
    fun `跨段拼起来才有的不算`() {
        // 规则逐段跑，「上段末尾 + 下段开头」永远换不到
        assertNull(replacePreview(listOf("", "他们的周", "身仿佛"), "周身"))
    }

    @Test
    fun `优先展示选中的那一处`() {
        val paras = listOf("", "广告甲", "正文广告乙广告丙")
        // 选的是第 2 段偏移 5 的那个「广告」（丙前面那个）
        val p = replacePreview(paras, "广告", anchor = 2 to 5)!!
        assertEquals(3, p.count)
        assertEquals("正文广告乙", p.before)
        assertEquals("丙", p.after)
    }

    @Test
    fun `原文改过后选区那段没有了就退回第一处`() {
        val paras = listOf("", "第一处广告在这", "选区在这一段")
        val p = replacePreview(paras, "广告", anchor = 2 to 0)!!
        assertEquals(1, p.count)
        assertEquals("第一处", p.before)
        assertEquals("在这", p.after)
    }

    @Test
    fun `前后文截短时带省略号`() {
        val para = "一二三四五六七八九十" + "目标" + "甲乙丙丁戊己庚辛壬癸"
        val p = replacePreview(listOf("", para), "目标", context = 3)!!
        assertEquals("…八九十", p.before)
        assertEquals("甲乙丙…", p.after)
    }

    @Test
    fun `空原文和没命中都是 null`() {
        assertNull(replacePreview(listOf("", "正文"), ""))
        assertNull(replacePreview(listOf("", "正文"), "广告"))
    }

    @Test
    fun `一个字或者命中太多算有风险`() {
        assertTrue(isRiskyReplace("他", 1))
        assertTrue(isRiskyReplace("他们", 9))
        assertFalse(isRiskyReplace("他们", 3))
        assertFalse(isRiskyReplace("仿佛是有着肉眼难辨的细微光芒出现", 1))
    }
}
