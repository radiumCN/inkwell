package com.radium.inkwell.data.repo

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 换源的判定标准。
 *
 * 这套规则松一点紧一点，直接决定「其他 165 个书源都没找到这本小说」还是「换到了另一本书」——
 * 之前就因为作者用相等匹配而不是包含匹配，把绝大多数源判死过。手动换源和自动换源共用它，
 * 所以它错一次，两条路一起错。
 */
class TitleMatchTest {

    @Test
    fun `书名号与空白不该影响判定`() {
        assertTrue(TitleMatch.matches("《武动乾坤》", "武动乾坤"))
        assertTrue(TitleMatch.matches("武动乾坤", "《武动乾坤》"))
        assertTrue(TitleMatch.matches(" 武动乾坤 ", "武动乾坤"))
    }

    @Test
    fun `带后缀的同名书认得出来`() {
        assertTrue(TitleMatch.matches("武动乾坤（精校版）", "武动乾坤"))
        assertTrue(TitleMatch.matches("武动乾坤", "武动乾坤（精校版）"))
    }

    @Test
    fun `不同的书不该混为一谈`() {
        assertFalse(TitleMatch.matches("斗破苍穹", "武动乾坤"))
        assertFalse(TitleMatch.matches("", "武动乾坤"))
    }

    /** 单字书名被任意长书名包含，会把整个书源库都判成命中 */
    @Test
    fun `单字不足以构成包含匹配`() {
        assertFalse(TitleMatch.matches("斗", "斗破苍穹"))
    }

    @Test
    fun `作者用包含匹配，而不是相等`() {
        // 书源常带前缀，一律要求相等会把绝大多数源判死 —— 这正是从前的 bug
        assertTrue(TitleMatch.authorMatches("作者：天蚕土豆", "天蚕土豆"))
        assertTrue(TitleMatch.authorMatches("天蚕土豆", "天蚕土豆"))
    }

    @Test
    fun `任一边为空就不拿作者卡人`() {
        assertTrue(TitleMatch.authorMatches(null, "天蚕土豆"))
        assertTrue(TitleMatch.authorMatches("", "天蚕土豆"))
        assertTrue(TitleMatch.authorMatches("天蚕土豆", ""))
    }

    @Test
    fun `作者对不上就是对不上`() {
        assertFalse(TitleMatch.authorMatches("唐家三少", "天蚕土豆"))
    }
}
