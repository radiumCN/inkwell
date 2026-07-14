package com.radium.inkwell.core.text

import com.radium.inkwell.core.model.ContentElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChineseConverterTest {

    @Test
    fun `繁转简`() {
        assertEquals("斗破苍穹", ChineseConverter.toSimplified("鬥破蒼穹"))
        assertEquals("这些人都很势利", ChineseConverter.toSimplified("這些人都很勢利"))
    }

    @Test
    fun `简转繁`() {
        assertTrue(ChineseConverter.toTraditional("斗破苍穹").contains("蒼穹"))
    }

    @Test
    fun `已经是简体的原样放行`() {
        assertEquals("少年缓缓抬起头来", ChineseConverter.toSimplified("少年缓缓抬起头来"))
    }

    /** 图片和分隔符不该被当成文本转换掉 */
    @Test
    fun `只转文本元素，图片与分隔符原样保留`() {
        val elements = listOf(
            ContentElement.Heading(1, "第一章 隕落的天才"),
            ContentElement.Paragraph("蕭炎"),
            ContentElement.Image("https://x/1.jpg"),
            ContentElement.Divider,
        )
        val out = ChineseConverter.convert(elements, ChineseConverter::toSimplified)

        assertEquals("第一章 陨落的天才", (out[0] as ContentElement.Heading).text)
        assertEquals("萧炎", (out[1] as ContentElement.Paragraph).text)
        assertEquals(elements[2], out[2])
        assertEquals(ContentElement.Divider, out[3])
    }
}
