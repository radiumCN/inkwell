package com.radium.inkwell.core.util

import com.radium.inkwell.core.model.ContentElement
import com.radium.inkwell.core.model.ContentElement.Heading
import com.radium.inkwell.core.model.ContentElement.Paragraph
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * 章首重复标题。去掉的只能是「标题」：误删一段正文比留一行重复的标题糟得多，
 * 所以下面一半的用例在钉「别动」。
 */
class RepeatedTitleTest {

    private val body = Paragraph("暮色四合，山门外的钟声一下一下地传过来。")

    private fun strip(vararg els: ContentElement, title: String = "第一章 明道宫", book: String = "") =
        RepeatedTitle.strip(els.toList(), title, book)

    @Test
    fun `EPUB 的标题行和章节名一样就去掉`() {
        assertEquals(listOf(body), strip(Heading(2, "第一章 明道宫"), body))
    }

    @Test
    fun `正文第一行就是章节名也去掉`() {
        assertEquals(listOf(body), strip(Paragraph("第一章 明道宫"), body))
    }

    @Test
    fun `空格、标点、全半角不同也算同一行`() {
        assertEquals(listOf(body), strip(Paragraph("第一章　明道宫。"), body))
        assertEquals(listOf(body), strip(Paragraph("【第１章】明道宫"), body, title = "第1章 明道宫"))
        assertEquals(listOf(body), strip(Heading(1, "Chapter ONE"), body, title = "chapter one"))
    }

    @Test
    fun `拆成两行的标题一起去掉`() {
        assertEquals(listOf(body), strip(Heading(2, "第一章"), Heading(3, "明道宫"), body))
        assertEquals(listOf(body), strip(Paragraph("第一章"), Paragraph("明道宫"), body))
        assertEquals(listOf(body), strip(Paragraph("第一章"), Heading(3, "明道宫"), body))
    }

    @Test
    fun `前面带着书名的标题行也去掉`() {
        assertEquals(listOf(body), strip(Paragraph("《雾港》 第一章 明道宫"), body, book = "雾港"))
    }

    @Test
    fun `只写了半截的标题行去掉`() {
        assertEquals(listOf(body), strip(Heading(2, "第一章"), body))
        // 目录里的章节名带着卷名，正文里的标题行没有
        assertEquals(listOf(body), strip(Heading(2, "第一章 明道宫"), body, title = "第一卷 第一章 明道宫"))
    }

    @Test
    fun `标题写了两遍就两遍都去掉`() {
        assertEquals(listOf(body), strip(Heading(1, "第一章 明道宫"), Paragraph("第一章 明道宫"), body))
    }

    @Test
    fun `正文碰巧以章节名里的字开头，不动`() {
        val els = listOf(Paragraph("明道宫里一片寂静，只有风声。"), body)
        assertSame(els, RepeatedTitle.strip(els, "第一章 明道宫"))
    }

    @Test
    fun `普通段落只是半截标题，不动`() {
        // 段落不像标题行那样「被包含就算」：一段「第一章」后面跟的是正文，拼不成整个章节名
        val els = listOf(Paragraph("第一章"), body)
        assertSame(els, RepeatedTitle.strip(els, "第一章 明道宫"))
    }

    @Test
    fun `和章节名对不上的标题行，不动`() {
        val els = listOf(Heading(2, "第一章"), body)
        assertSame(els, RepeatedTitle.strip(els, "楔子"))
        // 单个字也被章节名包含，但那不是标题
        val single = listOf(Heading(2, "一"), body)
        assertSame(single, RepeatedTitle.strip(single, "第一章 明道宫"))
    }

    @Test
    fun `只看开头，正文后面再出现章节名不动`() {
        val els = listOf(body, Paragraph("第一章 明道宫"))
        assertSame(els, RepeatedTitle.strip(els, "第一章 明道宫"))
    }

    @Test
    fun `分隔线、图片挡在前面就不往下找`() {
        val els = listOf(ContentElement.Divider, Paragraph("第一章 明道宫"), body)
        assertSame(els, RepeatedTitle.strip(els, "第一章 明道宫"))
    }

    @Test
    fun `整章只有一行标题（卷首页），去掉后是空的`() {
        assertEquals(emptyList<ContentElement>(), strip(Heading(1, "第一卷 风起"), title = "第一卷 风起"))
    }

    @Test
    fun `章节名是空的或全是标点，不动`() {
        val els = listOf(Paragraph("……"), body)
        assertSame(els, RepeatedTitle.strip(els, ""))
        assertSame(els, RepeatedTitle.strip(els, "……"))
    }
}
