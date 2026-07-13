package com.radium.inkwell.core.parser.mobi

import com.radium.inkwell.core.model.BookParseException
import java.io.ByteArrayOutputStream

/** 正文重组：解压 record[1..recordCount] 并去掉每条记录尾部的额外数据 */
internal object MobiText {

    private const val COMPRESSION_NONE = 1
    private const val COMPRESSION_PALMDOC = 2
    private const val COMPRESSION_HUFF = 17480 // 'DH'

    /**
     * @param record 取记录的回调，下标相对本段 record0（joint 文件传入带 boundary 偏移的闭包）
     */
    fun extract(header: MobiHeader, record: (Int) -> ByteArray?): ByteArray {
        when (header.compression) {
            COMPRESSION_NONE, COMPRESSION_PALMDOC -> {}
            COMPRESSION_HUFF -> throw BookParseException.UnsupportedFeature(
                "暂不支持 HUFF/CDIC 压缩的 MOBI，请用 Calibre 转换为 AZW3"
            )
            else -> throw BookParseException.Corrupted("未知的 MOBI 压缩类型: ${header.compression}")
        }
        val out = ByteArrayOutputStream()
        for (i in 1..header.textRecordCount) {
            val data = record(i) ?: break
            val body = data.copyOf(data.size - trailingSize(data, header.extraDataFlags))
            out.write(if (header.compression == COMPRESSION_PALMDOC) PalmDoc.decompress(body) else body)
            if (header.textLength in 1..out.size().toLong()) break
        }
        var bytes = out.toByteArray()
        // 拼接结果可能略超 textLength（对齐填充），截断到声明长度
        if (header.textLength in 1 until bytes.size.toLong()) bytes = bytes.copyOf(header.textLength.toInt())
        return bytes
    }

    /**
     * 记录尾部额外数据总长：按 extraDataFlags 从高 bit 到 bit1，
     * 每块长度是记录末尾的反向 varint；bit0 置位再去 (lastByte and 0x3) + 1 字节。
     */
    fun trailingSize(data: ByteArray, extraDataFlags: Int): Int {
        var num = 0
        var flags = extraDataFlags ushr 1
        while (flags != 0) {
            if (flags and 1 != 0) {
                val n = backwardVarint(data, data.size - num)
                if (n <= 0 || num + n > data.size) return 0 // 尾部数据损坏，按无处理
                num += n
            }
            flags = flags ushr 1
        }
        if (extraDataFlags and 1 != 0) {
            val off = data.size - num - 1
            num += if (off >= 0) (data[off].toInt() and 0x3) + 1 else 1
        }
        return num.coerceIn(0, data.size)
    }

    /** 记录末尾的反向 varint：从 end 往前读，低 7 位有效，高位置 1 的字节为最高位 */
    private fun backwardVarint(data: ByteArray, end: Int): Int {
        var pos = end - 1
        var bitpos = 0
        var result = 0
        while (pos >= 0) {
            val v = data[pos].toInt() and 0xFF
            result = result or ((v and 0x7F) shl bitpos)
            bitpos += 7
            pos--
            if (v and 0x80 != 0 || bitpos >= 28) break
        }
        return result
    }
}
