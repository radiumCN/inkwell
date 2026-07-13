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
                       <EncryptedData xmlns="http://www.w3.org/2001/04/xmlenc#"><CipherData/></EncryptedData>
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

            for (i in 1..chapterCount) {
                put(
                    "OEBPS/$chapterDir/ch$i.xhtml",
                    """<?xml version="1.0" encoding="utf-8"?>
                    <html xmlns="http://www.w3.org/1999/xhtml"><head><title>第${i}章</title></head>
                    <body>
                      <h2>第${i}章 起承转合</h2>
                      <p>　　这是第${i}章的第一段，讲述了一些故事。</p>
                      <p>　　这是第${i}章的<b>第二段</b>，有强调文本。</p>
                    </body></html>""",
                )
            }

            val navPoints = (1..chapterCount).joinToString("") { i ->
                """<navPoint id="np$i" playOrder="$i"><navLabel><text>第${i}章 起承转合</text></navLabel>
                   <content src="text/ch$i.xhtml"/></navPoint>"""
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
                put(
                    "OEBPS/nav.xhtml",
                    """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
                    <body><nav epub:type="toc"><ol>$lis</ol></nav></body></html>""",
                )
            }
        }
    }
}
