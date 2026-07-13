package com.radium.inkwell.core.parser.mobi

import java.nio.charset.Charset

/**
 * 通用 INDX 索引解析器（MOBI7 NCX / KF8 NCX / skeleton / fragment 共用）。
 * 结构与 calibre calibre/ebooks/mobi/reader/index.py 对齐：
 * header 记录（INDX 头 + TAGX 表）→ 若干数据记录（INDX 头 + entry + IDXT 偏移表）→ CNCX 字符串池。
 */
internal object IndxParser {

    class Entry(val ident: String, val tags: Map<Int, List<Long>>)

    class Index(val entries: List<Entry>, val cncx: Map<Long, String>) {
        fun cncxString(offset: Long?): String? = offset?.let { cncx[it] }
    }

    private class TagX(val tag: Int, val numValues: Int, val bitmask: Int, val eof: Int)

    /**
     * @param headerIndex INDX header 记录号（相对 record 回调的编号空间）
     * @return 解析失败返回 null，调用方自行退化
     */
    fun read(headerIndex: Int, record: (Int) -> ByteArray?, charset: Charset): Index? = runCatching {
        if (headerIndex < 0) return null
        val head = record(headerIndex) ?: return null
        if (!head.hasMagic(0, "INDX")) return null

        val indexCount = head.u32(0x18).toInt()          // 数据记录数
        val cncxCount = head.u32(0x34).toInt()           // CNCX 记录数
        if (indexCount < 0 || indexCount > 0xFFFF) return null

        // TAGX 表：偏移在 header 0xB4，magic 不符则顺序查找
        var tagxPos = head.u32(0xB4).toInt()
        if (!head.hasMagic(tagxPos, "TAGX")) {
            tagxPos = findMagic(head, "TAGX", 184)
            if (tagxPos < 0) return null
        }
        val firstEntryOffset = head.u32(tagxPos + 4).toInt()
        val controlByteCount = head.u32(tagxPos + 8).toInt()
        val tagx = mutableListOf<TagX>()
        var p = tagxPos + 12
        while (p + 4 <= tagxPos + firstEntryOffset && p + 4 <= head.size) {
            tagx += TagX(head.u8(p), head.u8(p + 1), head.u8(p + 2), head.u8(p + 3))
            p += 4
        }
        if (tagx.isEmpty() || controlByteCount !in 0..32) return null

        // CNCX 字符串池：紧跟在数据记录之后，key = 池内偏移（每条记录 +0x10000）
        val cncx = HashMap<Long, String>()
        for (i in 0 until cncxCount) {
            val raw = record(headerIndex + 1 + indexCount + i) ?: break
            val recordOffset = i.toLong() * 0x10000
            var pos = 0
            while (pos < raw.size) {
                val (len, consumed) = varint(raw, pos)
                if (consumed == 0) break
                if (len > 0 && pos + consumed + len <= raw.size) {
                    cncx[recordOffset + pos] = runCatching {
                        String(raw, pos + consumed, len.toInt(), charset)
                    }.getOrDefault("")
                }
                pos += consumed + len.toInt()
            }
        }

        // 数据记录：IDXT 偏移表切出每个 entry
        val entries = mutableListOf<Entry>()
        for (r in headerIndex + 1..headerIndex + indexCount) {
            val rec = record(r) ?: break
            if (!rec.hasMagic(0, "INDX")) continue
            val idxtPos = rec.u32(0x14).toInt()
            val count = rec.u32(0x18).toInt()
            if (!rec.hasMagic(idxtPos, "IDXT") || count < 0) continue
            val positions = IntArray(count + 1)
            for (j in 0 until count) positions[j] = rec.u16(idxtPos + 4 + 2 * j)
            positions[count] = idxtPos // 末条 entry 到 IDXT 为止（可能含零填充）
            for (j in 0 until count) {
                val start = positions[j]
                val end = positions[j + 1]
                if (start <= 0 || end <= start || end > rec.size) continue
                val identLen = rec.u8(start).coerceAtMost(end - start - 1)
                val ident = runCatching {
                    String(rec, start + 1, identLen, charset)
                }.getOrDefault("")
                val body = rec.copyOfRange(start + 1 + identLen, end)
                entries += Entry(ident, tagMap(controlByteCount, tagx, body))
            }
        }
        Index(entries, cncx)
    }.getOrNull()

    /** 前向 varint：每字节低 7 位，高位置 1 的字节为最后一字节；返回 (值, 消耗字节数) */
    fun varint(data: ByteArray, pos: Int): Pair<Long, Int> {
        var value = 0L
        var i = pos
        while (i < data.size) {
            val b = data[i].toInt() and 0xFF
            value = (value shl 7) or (b and 0x7F).toLong()
            i++
            if (b and 0x80 != 0) break
        }
        return value to (i - pos)
    }

    private class PTag(val tag: Int, val valueCount: Int?, val valueBytes: Long?, val numValues: Int)

    /** 按 TAGX bitmask 从 control bytes + varint 流解出 tag → 值列表 */
    private fun tagMap(controlByteCount: Int, tagx: List<TagX>, data: ByteArray): Map<Int, List<Long>> {
        val ans = HashMap<Int, List<Long>>()
        val ptags = mutableListOf<PTag>()
        var cb = 0
        var pos = controlByteCount
        for (x in tagx) {
            if (x.eof == 0x01) {
                cb++
                continue
            }
            if (x.bitmask == 0) continue
            var value = data.u8(cb) and x.bitmask
            if (value == 0) continue
            if (value == x.bitmask) {
                if (Integer.bitCount(x.bitmask) > 1) {
                    // 全位置 1 且掩码多于 1 位：后跟 varint 表示值区总字节数
                    val (vb, consumed) = varint(data, pos)
                    pos += consumed
                    ptags += PTag(x.tag, null, vb, x.numValues)
                } else {
                    ptags += PTag(x.tag, 1, null, x.numValues)
                }
            } else {
                var mask = x.bitmask
                while (mask and 1 == 0) {
                    mask = mask shr 1
                    value = value shr 1
                }
                ptags += PTag(x.tag, value, null, x.numValues)
            }
        }
        for (x in ptags) {
            val values = mutableListOf<Long>()
            if (x.valueCount != null) {
                repeat(x.valueCount * x.numValues) {
                    val (v, consumed) = varint(data, pos)
                    if (consumed == 0) return@repeat
                    pos += consumed
                    values += v
                }
            } else {
                var total = 0L
                while (total < (x.valueBytes ?: 0L)) {
                    val (v, consumed) = varint(data, pos)
                    if (consumed == 0) break
                    pos += consumed
                    total += consumed
                    values += v
                }
            }
            ans[x.tag] = values
        }
        return ans
    }

    private fun findMagic(data: ByteArray, magic: String, from: Int): Int {
        for (i in from..data.size - magic.length) {
            if (data.hasMagic(i, magic)) return i
        }
        return -1
    }
}
