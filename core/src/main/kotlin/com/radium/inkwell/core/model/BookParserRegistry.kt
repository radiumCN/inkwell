package com.radium.inkwell.core.model

import java.io.File

class BookParserRegistry(private val parsers: List<BookParser>) {

    fun open(file: File): BookHandle {
        val parser = parsers.firstOrNull { it.sniff(file) }
            ?: throw BookParseException.Corrupted("无法识别的文件格式: ${file.name}")
        return parser.open(file)
    }
}
