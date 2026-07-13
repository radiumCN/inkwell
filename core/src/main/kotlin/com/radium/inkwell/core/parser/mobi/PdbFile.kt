package com.radium.inkwell.core.parser.mobi

import com.radium.inkwell.core.model.BookParseException
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/**
 * PDB（Palm Database）容器，全部大端：
 * 78 字节头（name[32]，type/creator 在 offset 60/64，numRecords u16 在 offset 76），
 * 之后是记录目录，每条 8 字节（offset u32 + attr u8 + uniqueId u24）。
 */
internal class PdbFile private constructor(
    private val raf: RandomAccessFile,
    val name: String,
    val type: String,
    val creator: String,
    /** size = numRecords + 1，末位为文件长度 */
    private val offsets: LongArray,
) : Closeable {

    val numRecords: Int get() = offsets.size - 1

    /** 第 i 条记录的完整字节；越界返回 null */
    fun record(i: Int): ByteArray? {
        if (i < 0 || i >= numRecords) return null
        val start = offsets[i]
        val end = offsets[i + 1].coerceAtLeast(start)
        val len = (end - start).coerceAtMost(MAX_RECORD).toInt()
        if (len <= 0) return ByteArray(0)
        val buf = ByteArray(len)
        synchronized(raf) {
            raf.seek(start)
            raf.readFully(buf)
        }
        return buf
    }

    override fun close() = raf.close()

    companion object {
        /** 单条记录上限，防御损坏的目录 */
        private const val MAX_RECORD = 64L shl 20

        fun open(file: File): PdbFile {
            val raf = RandomAccessFile(file, "r")
            try {
                val fileLen = raf.length()
                if (fileLen < 78) throw BookParseException.Corrupted("文件太小，不是 PDB: ${file.name}")
                val head = ByteArray(78)
                raf.seek(0)
                raf.readFully(head)
                val name = String(head, 0, 32, Charsets.ISO_8859_1).substringBefore('\u0000').trim()
                val type = String(head, 60, 4, Charsets.ISO_8859_1)
                val creator = String(head, 64, 4, Charsets.ISO_8859_1)
                val num = head.u16(76)
                if (num == 0 || fileLen < 78L + num * 8) {
                    throw BookParseException.Corrupted("PDB 记录目录不完整: ${file.name}")
                }
                val dir = ByteArray(num * 8)
                raf.readFully(dir)
                val offsets = LongArray(num + 1)
                for (i in 0 until num) offsets[i] = dir.u32(i * 8).coerceIn(0, fileLen)
                offsets[num] = fileLen
                return PdbFile(raf, name, type, creator, offsets)
            } catch (e: Throwable) {
                raf.close()
                throw e
            }
        }
    }
}

// ---- 大端字节读取工具（越界按 0 处理，容错不合规文件） ----

internal fun ByteArray.u8(pos: Int): Int =
    if (pos in indices) this[pos].toInt() and 0xFF else 0

internal fun ByteArray.u16(pos: Int): Int = (u8(pos) shl 8) or u8(pos + 1)

internal fun ByteArray.u32(pos: Int): Long =
    (u16(pos).toLong() shl 16) or u16(pos + 2).toLong()

/** data 从 pos 起是否为给定 ASCII magic */
internal fun ByteArray.hasMagic(pos: Int, magic: String): Boolean {
    if (pos < 0 || pos + magic.length > size) return false
    for (i in magic.indices) if (this[pos + i].toInt().toChar() != magic[i]) return false
    return true
}
