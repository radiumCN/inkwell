package com.radium.inkwell.core.parser.mobi

import kotlin.test.Test
import kotlin.test.assertEquals

class MobiTextTest {

    @Test
    fun `no extra flags means no trailing data`() {
        val data = ByteArray(100) { 0x41 }
        assertEquals(0, MobiText.trailingSize(data, 0))
    }

    @Test
    fun `single trailing entry with one byte varint`() {
        // 末尾 5 字节 trailing 块，长度值 5 含 varint 自身（0x85 = 高位置 1 + 值 5）
        val data = ByteArray(100) { 0x41 } + byteArrayOf(0, 0, 0, 0, 0x85.toByte())
        assertEquals(5, MobiText.trailingSize(data, 0b10))
    }

    @Test
    fun `multibyte backward varint`() {
        // 反向 varint 300 = [0x82, 0x2C]：块总长 300，数据不足时按损坏处理返回 0
        val payload = ByteArray(298)
        val data = ByteArray(700) { 0x41 } + payload + byteArrayOf(0x82.toByte(), 0x2C)
        assertEquals(300, MobiText.trailingSize(data, 0b10))
    }

    @Test
    fun `bit0 multibyte overlap count`() {
        // bit0：再去掉 (lastByte and 0x3) + 1 字节
        val data = ByteArray(50) { 0x41 } + byteArrayOf(0x02)
        assertEquals(3, MobiText.trailingSize(data, 0b1))
    }

    @Test
    fun `combined flags strip entries then multibyte bytes`() {
        // bit1 块 4 字节（含 varint），随后 bit0 按剩余末字节 0x01 再去 2 字节
        val content = ByteArray(40) { 0x41 }
        val data = content + byteArrayOf(0x00, 0x01) + byteArrayOf(0, 0, 0, 0x84.toByte())
        assertEquals(4 + 2, MobiText.trailingSize(data, 0b11))
    }

    @Test
    fun `corrupt trailing size falls back to zero`() {
        // 声称 200 字节 trailing 但记录只有 10 字节
        val data = ByteArray(9) + byteArrayOf(0xC8.toByte())
        assertEquals(0, MobiText.trailingSize(data, 0b10))
    }

    @Test
    fun `forward varint parses calibre doc example`() {
        // calibre encint 文档用例：0x11111 前向编码为 04 22 91
        val (value, consumed) = IndxParser.varint(byteArrayOf(0x04, 0x22, 0x91.toByte()), 0)
        assertEquals(0x11111L, value)
        assertEquals(3, consumed)
    }

    @Test
    fun `forward varint single byte`() {
        val (value, consumed) = IndxParser.varint(byteArrayOf(0x8A.toByte(), 0x55), 0)
        assertEquals(0x0AL, value)
        assertEquals(1, consumed)
    }
}
