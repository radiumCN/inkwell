package com.radium.inkwell.util

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ImportTextTest {
    @Test fun acceptsExactByteLimitAndStripsUtf8Bom() {
        val bytes = "\uFEFF书源".toByteArray()
        assertEquals("书源", bytes.inputStream().readImportText(bytes.size))
        assertFailsWith<IllegalStateException> { bytes.inputStream().readImportText(bytes.size - 1) }
        assertEquals("", byteArrayOf().inputStream().readImportText(0))
    }

    @Test fun rejectsOversizeStreamWithoutReadingToEnd() {
        val input = object : ByteArrayInputStream(ByteArray(8192)) {
            override fun available() = 0
            override fun read(buffer: ByteArray, offset: Int, length: Int) =
                super.read(buffer, offset, minOf(length, 7))
            val bytesRead get() = pos
        }
        assertFailsWith<IllegalStateException> { input.readImportText(100) }
        assertTrue(input.bytesRead in 101..107)
    }
}
