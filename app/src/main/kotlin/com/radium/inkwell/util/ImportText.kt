package com.radium.inkwell.util

import okio.buffer
import okio.source
import java.io.InputStream

internal const val MAX_SOURCE_IMPORT_BYTES = 32 shl 20

/** 调用方负责关闭输入流。未知长度的文件也只预读到能判断超限为止。 */
internal fun InputStream.readImportText(maxBytes: Int): String {
    val input = source().buffer()
    check(!input.request(maxBytes.toLong() + 1)) { "文件太大（超过 ${maxBytes shr 20}MB）" }
    return input.buffer.readUtf8().removePrefix("\uFEFF")
}
