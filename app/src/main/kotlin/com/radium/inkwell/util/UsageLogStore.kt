package com.radium.inkwell.util

import java.io.File
import java.io.RandomAccessFile

/** Bounded local diagnostic history. Used only by the single writer (or its crash handler). */
internal class UsageLogStore(
    private val directory: File,
    private val fileBytes: Int = 256 * 1024,
    private val fileCount: Int = 4,
    private val retentionMillis: Long = 7L * 24 * 60 * 60 * 1000,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private fun file(index: Int) = File(directory, "usage-$index.jsonl")

    @Synchronized
    fun append(line: String) {
        val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
        require(bytes.size <= fileBytes)
        check(directory.isDirectory || directory.mkdirs())
        prune()
        recoverTail()
        if (file(0).length() + bytes.size > fileBytes) {
            check(!file(fileCount - 1).exists() || file(fileCount - 1).delete())
            for (index in fileCount - 2 downTo 0) {
                if (file(index).exists()) check(file(index).renameTo(file(index + 1)))
            }
        }
        file(0).appendBytes(bytes)
    }

    /** Take whole UTF-8 JSON lines, in chronological order, without loading unbounded files. */
    @Synchronized
    fun snapshot(maxBytes: Int = 512 * 1024): String {
        prune()
        var remaining = maxBytes
        val parts = mutableListOf<String>()
        for (index in 0 until fileCount) {
            val source = file(index)
            if (!source.isFile || remaining <= 0) continue
            RandomAccessFile(source, "r").use { input ->
                val length = input.length()
                val size = minOf(length, remaining.toLong()).toInt()
                val offset = length - size
                input.seek(offset)
                val bytes = ByteArray(size)
                input.readFully(bytes)
                val start = if (offset == 0L) 0 else bytes.indexOf('\n'.code.toByte()).let { if (it < 0) size else it + 1 }
                // A process may have died midway through its last append. Do not export an incomplete event.
                val end = bytes.indexOfLast { it == '\n'.code.toByte() } + 1
                if (end > start) parts += bytes.copyOfRange(start, end).toString(Charsets.UTF_8)
                remaining -= size
            }
        }
        return parts.asReversed().joinToString("")
    }

    @Synchronized
    fun clear() {
        for (index in 0 until fileCount) {
            val source = file(index)
            check(!source.exists() || source.delete())
        }
    }

    private fun prune() {
        for (index in 0 until fileCount) {
            val source = file(index)
            if (source.exists() && now() - source.lastModified() > retentionMillis) check(source.delete())
        }
    }

    private fun recoverTail() {
        val current = file(0)
        if (!current.isFile || current.length() == 0L) return
        RandomAccessFile(current, "rw").use { input ->
            input.seek(input.length() - 1)
            if (input.read() == '\n'.code) return
            val size = minOf(input.length(), fileBytes.toLong()).toInt()
            val start = input.length() - size
            input.seek(start)
            val bytes = ByteArray(size)
            input.readFully(bytes)
            val last = bytes.indexOfLast { it == '\n'.code.toByte() }
            input.setLength(if (last >= 0) start + last + 1 else 0)
        }
    }
}
