package com.radium.inkwell.core.parser.mobi

import java.io.File
import java.nio.ByteBuffer

/** 手工构造最小 MOBI7 文件（PDB + record0 + 未压缩正文），用于确定性单测 */
object MobiFixture {

    fun mobi7(
        file: File,
        chapterCount: Int = 6,
        compression: Int = 1,
        encryption: Int = 0,
    ) {
        val html = buildHtml(chapterCount)
        val text = html.toByteArray(Charsets.UTF_8)
        val textRecords = text.toList().chunked(4096).map { it.toByteArray() }
        val record0 = buildRecord0(
            textLength = text.size,
            recordCount = textRecords.size,
            compression = compression,
            encryption = encryption,
        )
        writePdb(file, listOf(record0) + textRecords)
    }

    /** 内联 TOC + mbp:pagebreak 的典型 MOBI7 正文；filepos 用 10 位定宽两遍回填 */
    private fun buildHtml(chapterCount: Int): String {
        val sb = StringBuilder()
        var bytePos = 0
        fun append(s: String) {
            sb.append(s)
            bytePos += s.toByteArray(Charsets.UTF_8).size
        }

        val tocRefPlaceholder = "0000000000"
        append("<html><head><guide>")
        append("<reference type=\"toc\" title=\"Table of Contents\" filepos=$tocRefPlaceholder />")
        append("</guide></head><body>")

        val chapterPos = IntArray(chapterCount + 1)
        for (i in 1..chapterCount) {
            chapterPos[i] = bytePos
            append("<h2>Chapter $i</h2>")
            append("<p>这是第 $i 章的正文内容，用于验证 UTF-8 字节偏移切章。</p>")
            append("<p>Paragraph two of chapter $i.</p>")
            append("<mbp:pagebreak/>")
        }
        val tocPagePos = bytePos
        append("<h2>Table of Contents</h2>")
        for (i in 1..chapterCount) {
            append("<a filepos=${pad10(chapterPos[i])}>Chapter $i</a><br/>")
        }
        append("</body></html>")

        // 回填 guide 的 toc filepos（同宽替换不影响任何字节偏移）
        val idx = sb.indexOf(tocRefPlaceholder)
        sb.replace(idx, idx + 10, pad10(tocPagePos))
        return sb.toString()
    }

    private fun pad10(n: Int): String = n.toString().padStart(10, '0')

    private fun buildRecord0(textLength: Int, recordCount: Int, compression: Int, encryption: Int): ByteArray {
        val exth = buildExth(
            100 to "测试作者".toByteArray(Charsets.UTF_8),
            503 to "合成测试书".toByteArray(Charsets.UTF_8),
        )
        val fullName = "合成测试书".toByteArray(Charsets.UTF_8)
        val headerLength = 0xE8
        val fullNameOffset = 16 + headerLength + exth.size
        val buf = ByteBuffer.allocate(fullNameOffset + fullName.size + 2)

        // PalmDOC header（16 字节）
        buf.putShort(compression.toShort())
        buf.putShort(0)
        buf.putInt(textLength)
        buf.putShort(recordCount.toShort())
        buf.putShort(4096)
        buf.putShort(encryption.toShort())
        buf.putShort(0)
        // MOBI header
        buf.put("MOBI".toByteArray(Charsets.US_ASCII)) // @16
        buf.putInt(headerLength)                        // @20
        buf.putInt(2)                                   // @24 mobiType = book
        buf.putInt(65001)                               // @28 UTF-8
        buf.putInt(42)                                  // @32 uid
        buf.putInt(6)                                   // @36 version
        buf.position(0x50)
        buf.putInt(recordCount + 1)                     // firstNonBookIndex
        buf.putInt(fullNameOffset)                      // @0x54
        buf.putInt(fullName.size)                       // @0x58
        buf.position(0x68)
        buf.putInt(6)                                   // fileVersion
        buf.putInt(-1)                                  // @0x6C firstImageIndex = NULL
        buf.position(0x80)
        buf.putInt(0x40)                                // exthFlags
        buf.position(0xF2)
        buf.putShort(0)                                 // extraDataFlags
        buf.putInt(-1)                                  // @0xF4 ncxIndex = NULL
        buf.position(16 + headerLength)
        buf.put(exth)
        buf.put(fullName)
        return buf.array()
    }

    private fun buildExth(vararg entries: Pair<Int, ByteArray>): ByteArray {
        val body = entries.flatMap { (type, data) ->
            val e = ByteBuffer.allocate(8 + data.size)
            e.putInt(type)
            e.putInt(8 + data.size)
            e.put(data)
            e.array().toList()
        }
        val buf = ByteBuffer.allocate(12 + body.size)
        buf.put("EXTH".toByteArray(Charsets.US_ASCII))
        buf.putInt(12 + body.size)
        buf.putInt(entries.size)
        buf.put(body.toByteArray())
        return buf.array()
    }

    private fun writePdb(file: File, records: List<ByteArray>) {
        val headerSize = 78 + records.size * 8
        val buf = ByteBuffer.allocate(headerSize + records.sumOf { it.size } + 2)
        buf.put("CeShiShu".toByteArray(Charsets.US_ASCII))
        buf.position(60)
        buf.put("BOOK".toByteArray(Charsets.US_ASCII))
        buf.put("MOBI".toByteArray(Charsets.US_ASCII))
        buf.position(76)
        buf.putShort(records.size.toShort())
        var offset = headerSize
        records.forEachIndexed { i, r ->
            buf.putInt(offset)
            buf.putInt(i) // attr u8 + uniqueId u24
            offset += r.size
        }
        records.forEach { buf.put(it) }
        file.writeBytes(buf.array())
    }
}
