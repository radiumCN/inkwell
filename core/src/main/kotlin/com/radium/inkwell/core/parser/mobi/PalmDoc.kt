package com.radium.inkwell.core.parser.mobi

/**
 * PalmDOC LZ77 解压：
 * 0x00 / 0x09-0x7F 直出；0x01-0x08 后跟 N 字节直出；
 * 0x80-0xBF 两字节 distance(11bit)+length(3bit)+3 回拷；
 * 0xC0-0xFF 输出空格 + (b xor 0x80)。
 */
internal object PalmDoc {

    fun decompress(data: ByteArray): ByteArray {
        var out = ByteArray(maxOf(64, data.size * 4))
        var len = 0

        fun push(b: Int) {
            if (len == out.size) out = out.copyOf(out.size * 2)
            out[len++] = b.toByte()
        }

        var pos = 0
        while (pos < data.size) {
            val b = data[pos].toInt() and 0xFF
            pos++
            when {
                b == 0x00 || b in 0x09..0x7F -> push(b)
                b in 0x01..0x08 -> {
                    // 后跟 b 个字节直出
                    val n = minOf(b, data.size - pos)
                    repeat(n) { push(data[pos + it].toInt() and 0xFF) }
                    pos += n
                }
                b <= 0xBF -> {
                    // 回拷：两字节 = 2bit 标志 + 11bit distance + 3bit length
                    if (pos >= data.size) break
                    val v = ((b and 0x3F) shl 8) or (data[pos].toInt() and 0xFF)
                    pos++
                    val distance = v ushr 3
                    if (distance == 0) continue
                    var src = len - distance
                    // 逐字节回拷，distance < length 时自然形成重叠复制
                    repeat((v and 0x07) + 3) {
                        push(if (src in 0 until len) out[src].toInt() and 0xFF else 0x20)
                        src++
                    }
                }
                else -> {
                    // 0xC0-0xFF：空格 + 去高位字符
                    push(0x20)
                    push(b xor 0x80)
                }
            }
        }
        return out.copyOf(len)
    }
}
