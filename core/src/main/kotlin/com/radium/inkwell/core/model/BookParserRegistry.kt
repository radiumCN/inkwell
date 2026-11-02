package com.radium.inkwell.core.model

import java.io.File

class BookParserRegistry(private val parsers: List<BookParser>) {

    fun open(file: File): BookHandle {
        val parser = parsers.firstOrNull { it.sniff(file) }
            ?: throw BookParseException.Corrupted("无法识别的文件格式: ${file.name}")
        return parser.open(file)
    }

    /** 各解析器为这个文件留下的缓存一并清掉（删书时调；文件本身由调用方删） */
    fun evictCaches(file: File) = parsers.forEach { it.evictCache(file) }
}
