package com.radium.inkwell.core.parser.mobi

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class PalmDocTest {

    @Test
    fun `literal bytes pass through`() {
        val data = "hello world".toByteArray(Charsets.US_ASCII)
        // 纯 0x09-0x7F 字面量（空格未用 0xC0 组合时也是字面量）
        assertContentEquals(data, PalmDoc.decompress(data))
    }

    @Test
    fun `space plus char combo`() {
        // 0xE2 = 0x62('b') xor 0x80 → 输出 " b"
        val compressed = byteArrayOf(0x61, 0xE2.toByte())
        assertEquals("a b", String(PalmDoc.decompress(compressed), Charsets.US_ASCII))
    }

    @Test
    fun `escape sequence emits raw high bytes`() {
        // 0x02 后跟 2 个字节直出
        val compressed = byteArrayOf(0x02, 0xFF.toByte(), 0x80.toByte())
        assertContentEquals(byteArrayOf(0xFF.toByte(), 0x80.toByte()), PalmDoc.decompress(compressed))
    }

    @Test
    fun `back reference copies previous output`() {
        // "abc" + 回拷 distance=3 length=3 → "abcabc"
        val v = (3 shl 3) or 0
        val compressed = byteArrayOf(0x61, 0x62, 0x63, (0x80 or (v ushr 8)).toByte(), (v and 0xFF).toByte())
        assertEquals("abcabc", String(PalmDoc.decompress(compressed), Charsets.US_ASCII))
    }

    @Test
    fun `overlapping back reference repeats pattern`() {
        // "ab" + 回拷 distance=2 length=6 → "abababab"（重叠复制）
        val v = (2 shl 3) or 3
        val compressed = byteArrayOf(0x61, 0x62, (0x80 or (v ushr 8)).toByte(), (v and 0xFF).toByte())
        assertEquals("abababab", String(PalmDoc.decompress(compressed), Charsets.US_ASCII))
    }

    @Test
    fun `round trip with reference compressor`() {
        val text = buildString {
            repeat(20) { i ->
                append("第 $i 章 这是一段中文内容，PalmDoc round-trip 测试。")
                append("The quick brown fox jumps over the lazy dog. ")
                append("重复重复重复重复重复。abcabcabcabc.\n")
            }
        }
        val original = text.toByteArray(Charsets.UTF_8)
        val compressed = compress(original)
        assertContentEquals(original, PalmDoc.decompress(compressed))
        // 有回拷与空格组合时应真的变小
        kotlin.test.assertTrue(compressed.size < original.size)
    }

    /** 参考压缩器：贪心找最长回拷（3-10 字节，距离 <=2047），其余按字面量/转义/空格组合 */
    private fun compress(input: ByteArray): ByteArray {
        val out = ArrayList<Byte>()
        var i = 0
        while (i < input.size) {
            var bestLen = 0
            var bestDist = 0
            val maxLen = minOf(10, input.size - i)
            for (start in maxOf(0, i - 2047) until i) {
                var l = 0
                while (l < maxLen && input[start + l] == input[i + l]) l++
                if (l > bestLen) {
                    bestLen = l
                    bestDist = i - start
                }
            }
            val b = input[i].toInt() and 0xFF
            if (bestLen >= 3) {
                val v = (bestDist shl 3) or (bestLen - 3)
                out += (0x80 or (v ushr 8)).toByte()
                out += (v and 0xFF).toByte()
                i += bestLen
            } else if (b == 0x20 && i + 1 < input.size && (input[i + 1].toInt() and 0xFF) in 0x40..0x7F) {
                out += ((input[i + 1].toInt() and 0xFF) xor 0x80).toByte()
                i += 2
            } else if (b == 0x00 || b in 0x09..0x7F) {
                out += b.toByte()
                i++
            } else {
                out += 1.toByte()
                out += b.toByte()
                i++
            }
        }
        return out.toByteArray()
    }
}
