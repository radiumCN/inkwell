package com.radium.inkwell.core.util

import kotlin.test.Test
import kotlin.test.assertEquals

class HexTest {

    @Test
    fun `empty is empty`() {
        assertEquals("", ByteArray(0).toHex())
    }

    @Test
    fun `pads a single byte`() {
        assertEquals("0f", byteArrayOf(0x0f).toHex())
        assertEquals("00", byteArrayOf(0x00).toHex())
        assertEquals("ff", byteArrayOf(0xff.toByte()).toHex())
    }

    @Test
    fun `md5-shaped digest stays lowercase`() {
        assertEquals("deadbeef", byteArrayOf(0xde.toByte(), 0xad.toByte(), 0xbe.toByte(), 0xef.toByte()).toHex())
    }
}
