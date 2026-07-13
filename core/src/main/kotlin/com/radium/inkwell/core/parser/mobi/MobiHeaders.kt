package com.radium.inkwell.core.parser.mobi

import com.radium.inkwell.core.model.BookParseException
import java.nio.charset.Charset

/** MOBI 中表示"无"的索引值 */
internal const val NULL_INDEX = 0xFFFFFFFFL

/**
 * record0 解析：16 字节 PalmDOC header + MOBI header + 可选 EXTH。
 * 字段偏移与 calibre calibre/ebooks/mobi/reader/headers.py 对齐（均为 record0 内绝对偏移）。
 */
internal class MobiHeader(record0: ByteArray) {

    // PalmDOC header（前 16 字节）
    val compression = record0.u16(0)
    val textLength = record0.u32(4)
    val textRecordCount = record0.u16(8)
    val textRecordSize = record0.u16(10)
    val encryption = record0.u16(12)

    private val hasMobi = record0.hasMagic(16, "MOBI")
    val headerLength = if (hasMobi) record0.u32(20).toInt() else 0
    val mobiType = if (hasMobi) record0.u32(24).toInt() else 0
    val textEncoding = if (hasMobi) record0.u32(28).toInt() else 1252
    val charset: Charset =
        if (textEncoding == 65001) Charsets.UTF_8
        else runCatching { Charset.forName("windows-1252") }.getOrDefault(Charsets.ISO_8859_1)

    val fileVersion = if (hasMobi) record0.u32(0x68).toInt() else 0
    val firstNonBookIndex = if (hasMobi) record0.u32(0x50) else NULL_INDEX
    val firstImageIndex = if (hasMobi) record0.u32(0x6C) else NULL_INDEX
    val huffmanRecordOffset = if (hasMobi) record0.u32(0x70) else NULL_INDEX
    val huffmanRecordCount = if (hasMobi) record0.u32(0x74) else 0L
    val exthFlags = if (hasMobi) record0.u32(0x80) else 0L

    /** 记录尾部额外数据标志；headerLength 超范围时按 0 处理（老文件/垃圾值） */
    val extraDataFlags: Int =
        if (hasMobi && headerLength in 0xE4..500) record0.u16(0xF2) else 0

    /** NCX INDX 记录号 */
    val ncxIndex: Long =
        if (hasMobi && headerLength >= 0xE8 && record0.size >= 0xF8) record0.u32(0xF4) else NULL_INDEX

    // KF8 追加字段（fileVersion == 8 且头足够长时才有意义）
    private val hasKf8Fields = hasMobi && fileVersion >= 8 && headerLength >= 0x108 && record0.size >= 0x108
    val fragmentIndex = if (hasKf8Fields) record0.u32(0xF8) else NULL_INDEX   // DIVTBL
    val skeletonIndex = if (hasKf8Fields) record0.u32(0xFC) else NULL_INDEX
    val datpIndex = if (hasKf8Fields) record0.u32(0x100) else NULL_INDEX
    val guideIndex = if (hasKf8Fields) record0.u32(0x104) else NULL_INDEX
    val fdstCount = if (hasKf8Fields) record0.u32(0xC4) else 0L
    // fdstCount <= 1 时 fdst 记录号可能是垃圾值
    val fdstIndex = if (hasKf8Fields && fdstCount > 1) record0.u32(0xC0) else NULL_INDEX

    /** PDB 名之外的完整书名（fullNameOffset/Length 在 0x54/0x58） */
    val fullName: String? = run {
        if (!hasMobi) return@run null
        val off = record0.u32(0x54).toInt()
        val len = record0.u32(0x58).toInt()
        if (off <= 0 || len <= 0 || off + len > record0.size) null
        else runCatching { String(record0, off, len, charset).trim().ifBlank { null } }.getOrNull()
    }

    /** EXTH 条目：type → 数据列表（同 type 可出现多次） */
    val exth: Map<Int, List<ByteArray>> = parseExth(record0)

    init {
        if (encryption != 0) {
            throw BookParseException.DrmProtected("该 MOBI 受 DRM 保护，无法打开")
        }
    }

    fun exthString(type: Int): String? = exth[type]?.firstOrNull()?.let {
        runCatching { String(it, charset).trim().ifBlank { null } }.getOrNull()
    }

    fun exthU32(type: Int): Long? = exth[type]?.firstOrNull()
        ?.takeIf { it.size >= 4 }?.u32(0)?.takeIf { it != NULL_INDEX }

    val author: String? get() = exthString(100)
    val description: String? get() = exthString(103)
    val isbn: String? get() = exthString(104)
    val updatedTitle: String? get() = exthString(503)
    val coverOffset: Long? get() = exthU32(201)
    /** EXTH 121：combo 文件中 KF8 record0 的记录号 */
    val kf8BoundaryIndex: Long? get() = exthU32(121)

    private fun parseExth(record0: ByteArray): Map<Int, List<ByteArray>> {
        if (!hasMobi || exthFlags and 0x40L == 0L) return emptyMap()
        return runCatching {
            val base = 16 + headerLength
            if (!record0.hasMagic(base, "EXTH")) return emptyMap()
            val count = record0.u32(base + 8).toInt()
            val out = HashMap<Int, MutableList<ByteArray>>()
            var pos = base + 12
            repeat(count) {
                val type = record0.u32(pos).toInt()
                val size = record0.u32(pos + 4).toInt()
                if (size < 8 || pos + size > record0.size) return@runCatching out
                out.getOrPut(type) { mutableListOf() } += record0.copyOfRange(pos + 8, pos + size)
                pos += size
            }
            out
        }.getOrDefault(emptyMap())
    }
}
