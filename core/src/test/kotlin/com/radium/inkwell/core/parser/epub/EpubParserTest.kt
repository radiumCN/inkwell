package com.radium.inkwell.core.parser.epub

import com.radium.inkwell.core.model.BookParseException
import com.radium.inkwell.core.model.ContentElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import java.io.File

class EpubParserTest {

    private fun temp(): File =
        kotlin.io.path.createTempFile(suffix = ".epub").toFile().also { it.deleteOnExit() }

    @Test
    fun `parses metadata spine and ncx toc`() {
        val f = temp()
        EpubFixture.minimalEpub(f, chapterCount = 3)
        EpubParser().open(f).use { book ->
            assertEquals("测试之书", book.metadata.title)
            assertEquals("测试作者", book.metadata.author)
            assertEquals(3, book.chapters.size)
            assertEquals(3, book.toc.size)
            assertEquals("第2章 起承转合", book.toc[1].title)
            assertEquals(1, book.toc[1].chapterIndex)
        }
    }

    @Test
    fun `chapter content converts to paragraph flow`() {
        val f = temp()
        EpubFixture.minimalEpub(f)
        EpubParser().open(f).use { book ->
            val elements = book.loadChapter(0).elements
            val heading = elements.filterIsInstance<ContentElement.Heading>()
            val paras = elements.filterIsInstance<ContentElement.Paragraph>()
            assertEquals(1, heading.size)
            assertEquals(2, paras.size)
            assertTrue(paras[0].text.startsWith("这是第1章的第一段"))
            // 全角缩进被剥离，交给排版层
            assertTrue(!paras[0].text.startsWith("　"))
        }
    }

    @Test
    fun `epub3 nav takes priority over ncx`() {
        val f = temp()
        EpubFixture.minimalEpub(f, withNav = true)
        EpubParser().open(f).use { book ->
            assertEquals("Nav第1章", book.toc[0].title)
        }
    }

    /** landmarks / page-list 排在目录前面时，取的仍是 epub:type="toc" 那个 nav，而不是文档里第一个 */
    @Test
    fun `nav toc is picked by type, not document order`() {
        val f = temp()
        EpubFixture.minimalEpub(f, withNav = true, navExtrasFirst = true)
        EpubParser().open(f).use { book ->
            assertEquals(listOf("Nav第1章", "Nav第2章", "Nav第3章"), book.toc.map { it.title })
        }
    }

    @Test
    fun `href case mismatch is tolerated`() {
        val f = temp()
        EpubFixture.minimalEpub(f, hrefCaseMismatch = true)
        EpubParser().open(f).use { book ->
            val paras = book.loadChapter(0).elements.filterIsInstance<ContentElement.Paragraph>()
            assertTrue(paras.isNotEmpty())
        }
    }

    @Test
    fun `drm epub is rejected`() {
        val f = temp()
        EpubFixture.minimalEpub(f, withEncryption = true)
        assertFailsWith<BookParseException.DrmProtected> { EpubParser().open(f) }
    }

    @Test
    fun `真加密算法照样拒绝`() {
        val f = temp()
        EpubFixture.minimalEpub(
            f, withEncryption = true,
            encryptionAlgorithm = "http://www.w3.org/2001/04/xmlenc#aes128-cbc",
        )
        assertFailsWith<BookParseException.DrmProtected> { EpubParser().open(f) }
    }

    /** 字体混淆只动嵌入字体、正文是明文 —— 出版社书、Sigil 导出常带，不能当 DRM 拒掉 */
    @Test
    fun `只做了字体混淆的书能打开`() {
        for (algorithm in listOf("http://www.idpf.org/2008/embedding", "http://ns.adobe.com/pdf/enc#RC")) {
            val f = temp()
            EpubFixture.minimalEpub(f, withEncryption = true, encryptionAlgorithm = algorithm)
            EpubParser().open(f).use { book ->
                assertEquals(3, book.chapters.size)
            }
        }
    }

    /** 目录项全带锚点时，章节标题仍取目录标题，而不是从书名一路沿用下去 */
    @Test
    fun `目录项带锚点时章节标题仍取目录`() {
        val f = temp()
        EpubFixture.minimalEpub(f, ncxAnchors = true)
        EpubParser().open(f).use { book ->
            assertEquals(
                listOf("第1章 起承转合", "第2章 起承转合", "第3章 起承转合"),
                book.chapters.map { it.title },
            )
        }
    }

    @Test
    fun `sniff rejects non zip`() {
        val f = temp()
        f.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        assertTrue(!EpubParser().sniff(f))
    }

    /** href 是 URI：`+` 是字面加号（空格才写 %20）。按表单解码会把它解成空格，章节就找不到了 */
    @Test
    fun `href 里的加号不被解成空格`() {
        assertEquals("OEBPS/Text/chapter+1.xhtml", resolvePath("OEBPS/Text", "chapter+1.xhtml"))
        assertEquals("OEBPS/Text/a b.xhtml", resolvePath("OEBPS/Text", "a%20b.xhtml"))
    }

    /**
     * 插图照收（阅读页显示不显示是阅读设置的事）：地址按本章文件换成包内路径，loadResource 认得它。
     * svg 包着的整页插画（xlink:href）也收；图夹在两段之间，顺序不乱
     */
    @Test
    fun `插图按包内路径收进正文，读得出字节`() {
        val f = temp()
        EpubFixture.minimalEpub(f, withImages = true)
        EpubParser().open(f).use { book ->
            val elements = book.loadChapter(0).elements
            val images = elements.filterIsInstance<ContentElement.Image>()
            assertEquals(
                listOf(ContentElement.Image("OEBPS/images/a.png", "插图"), ContentElement.Image("OEBPS/images/b.jpg")),
                images,
            )
            val first = elements.indexOfFirst { it is ContentElement.Image }
            assertTrue(elements[first - 1] is ContentElement.Paragraph)
            assertTrue(elements.last() is ContentElement.Paragraph)
            assertEquals("PNG-A", String(book.loadResource(images[0].resourceId)!!.data))
            assertEquals("JPG-B", String(book.loadResource(images[1].resourceId)!!.data))
        }
    }
}
