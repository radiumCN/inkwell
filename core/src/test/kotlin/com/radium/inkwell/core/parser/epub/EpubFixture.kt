package com.radium.inkwell.core.parser.epub

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 代码即时生成最小合法 EPUB 样本，可精确构造边界 case */
object EpubFixture {

    fun minimalEpub(
        file: File,
        title: String = "测试之书",
        author: String = "测试作者",
        chapterCount: Int = 3,
        withNcx: Boolean = true,
        withNav: Boolean = false,
        hrefCaseMismatch: Boolean = false,
        withEncryption: Boolean = false,
        /** encryption.xml 里写的算法；null = 不写 EncryptionMethod（老样本） */
        encryptionAlgorithm: String? = null,
        /** NCX 的 content src 带 `#锚点`（calibre / Sigil 常见写法） */
        ncxAnchors: Boolean = false,
        /** nav.xhtml 里目录前面先放 landmarks 与 page-list 两个 nav（文档顺序在前） */
        navExtrasFirst: Boolean = false,
        /** 第一章夹一张 img 插图、一张 svg 包着的整页插画（EPUB 的标准写法），图放在 OEBPS/images/ 下 */
        withImages: Boolean = false,
    ) {
        ZipOutputStream(file.outputStream()).use { zip ->
            fun put(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }

            put("mimetype", "application/epub+zip")
            put(
                "META-INF/container.xml",
                """<?xml version="1.0"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
                </container>""",
            )
            if (withEncryption) {
                put(
                    "META-INF/encryption.xml",
                    """<encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                       <EncryptedData xmlns="http://www.w3.org/2001/04/xmlenc#">${
                           encryptionAlgorithm?.let { """<EncryptionMethod Algorithm="$it"/>""" }.orEmpty()
                       }<CipherData><CipherReference URI="OEBPS/fonts/a.ttf"/></CipherData></EncryptedData>
                       </encryption>""",
                )
            }

            val chapterDir = if (hrefCaseMismatch) "Text" else "text"
            val manifest = StringBuilder()
            val spine = StringBuilder()
            for (i in 1..chapterCount) {
                // OPF 里引用小写路径，zip entry 实际首字母大写 → 大小写兜底测试
                manifest.append("""<item id="ch$i" href="text/ch$i.xhtml" media-type="application/xhtml+xml"/>""")
                spine.append("""<itemref idref="ch$i"/>""")
            }
            manifest.append("""<item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>""")
            if (withNav) {
                manifest.append("""<item id="nav" href="nav.xhtml" properties="nav" media-type="application/xhtml+xml"/>""")
            }

            put(
                "OEBPS/content.opf",
                """<?xml version="1.0"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>$title</dc:title>
                    <dc:creator>$author</dc:creator>
                    <dc:language>zh</dc:language>
                    <dc:identifier id="id">urn:uuid:test-0001</dc:identifier>
                  </metadata>
                  <manifest>$manifest</manifest>
                  <spine toc="ncx">$spine</spine>
                </package>""",
            )

            if (withImages) {
                put("OEBPS/images/a.png", "PNG-A")
                put("OEBPS/images/b.jpg", "JPG-B")
            }
            for (i in 1..chapterCount) {
                val pictures = if (withImages && i == 1) {
                    """<p><img src="../images/a.png" alt="插图"/></p>
                      <div><svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" viewBox="0 0 600 800">
                        <image width="600" height="800" xlink:href="../images/b.jpg"/></svg></div>"""
                } else {
                    ""
                }
                put(
                    "OEBPS/$chapterDir/ch$i.xhtml",
                    """<?xml version="1.0" encoding="utf-8"?>
                    <html xmlns="http://www.w3.org/1999/xhtml"><head><title>第${i}章</title></head>
                    <body>
                      <h2>第${i}章 起承转合</h2>
                      <p>　　这是第${i}章的第一段，讲述了一些故事。</p>
                      $pictures
                      <p>　　这是第${i}章的<b>第二段</b>，有强调文本。</p>
                    </body></html>""",
                )
            }

            val navPoints = (1..chapterCount).joinToString("") { i ->
                """<navPoint id="np$i" playOrder="$i"><navLabel><text>第${i}章 起承转合</text></navLabel>
                   <content src="text/ch$i.xhtml${if (ncxAnchors) "#c$i" else ""}"/></navPoint>"""
            }
            put(
                "OEBPS/toc.ncx",
                """<?xml version="1.0"?>
                <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
                  <head/><docTitle><text>$title</text></docTitle>
                  <navMap>$navPoints</navMap>
                </ncx>""",
            )

            if (withNav) {
                val lis = (1..chapterCount).joinToString("") { i ->
                    """<li><a href="text/ch$i.xhtml">Nav第${i}章</a></li>"""
                }
                val extras = if (!navExtrasFirst) "" else
                    """<nav epub:type="landmarks"><ol><li><a epub:type="bodymatter" href="text/ch1.xhtml">正文开始</a></li></ol></nav>
                    <nav epub:type="page-list"><ol><li><a href="text/ch2.xhtml">12</a></li></ol></nav>"""
                put(
                    "OEBPS/nav.xhtml",
                    """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
                    <body>$extras<nav epub:type="toc"><ol>$lis</ol></nav></body></html>""",
                )
            }
        }
    }
}
