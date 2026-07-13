package com.radium.inkwell.core.parser.txt

import org.mozilla.universalchardet.UniversalDetector
import java.nio.charset.Charset

object EncodingDetector {

    /** 用文件头部字节探测编码；GBK 系一律升级 GB18030（超集，避免生僻字乱码） */
    fun detect(head: ByteArray): Charset {
        if (head.size >= 3 &&
            head[0] == 0xEF.toByte() && head[1] == 0xBB.toByte() && head[2] == 0xBF.toByte()
        ) return Charsets.UTF_8
        if (head.size >= 2) {
            if (head[0] == 0xFF.toByte() && head[1] == 0xFE.toByte()) return Charsets.UTF_16LE
            if (head[0] == 0xFE.toByte() && head[1] == 0xFF.toByte()) return Charsets.UTF_16BE
        }
        val detector = UniversalDetector(null)
        detector.handleData(head, 0, head.size)
        detector.dataEnd()
        return when (detector.detectedCharset?.uppercase()) {
            null, "UTF-8", "ASCII" -> Charsets.UTF_8
            "GB18030", "GBK", "GB2312" -> charset("GB18030")
            "BIG5" -> charset("Big5")
            "UTF-16LE" -> Charsets.UTF_16LE
            "UTF-16BE" -> Charsets.UTF_16BE
            "WINDOWS-1252" ->
                // universalchardet 对短中文样本的常见误判：高位字节多时按 GB18030 兜底
                if (head.count { it < 0 } > head.size / 8) charset("GB18030") else Charsets.UTF_8
            else -> Charsets.UTF_8
        }
    }

    /** BOM 长度，解码时跳过 */
    fun bomLength(head: ByteArray): Int = when {
        head.size >= 3 && head[0] == 0xEF.toByte() && head[1] == 0xBB.toByte() && head[2] == 0xBF.toByte() -> 3
        head.size >= 2 && head[0] == 0xFF.toByte() && head[1] == 0xFE.toByte() -> 2
        head.size >= 2 && head[0] == 0xFE.toByte() && head[1] == 0xFF.toByte() -> 2
        else -> 0
    }
}
