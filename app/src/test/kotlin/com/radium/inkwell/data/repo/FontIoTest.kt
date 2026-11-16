package com.radium.inkwell.data.repo

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FontIoTest {
    @Test fun `oversized stream stops at boundary without copying entire input`() = runBlocking {
        var read = 0
        val stream = object : InputStream() {
            override fun read(): Int { read++; return 1 }
            override fun read(b: ByteArray, off: Int, len: Int): Int { read += len; b.fill(1, off, off + len); return len }
        }
        val output = ByteArrayOutputStream()
        assertFailsWith<java.io.IOException> { copyFontBytes(stream, output, 1024) }
        assertEquals(1025, read)
        assertTrue(output.size() <= 1024)
    }

    @Test fun `exactly sized and empty fonts copy without reporting overflow`() = runBlocking {
        for (size in listOf(0, 7, 8192, 8193)) {
            val bytes = ByteArray(size) { (it % 127).toByte() }
            val output = ByteArrayOutputStream()
            copyFontBytes(ByteArrayInputStream(bytes), output, size.toLong())
            assertContentEquals(bytes, output.toByteArray())
        }
    }

    @Test fun `availability uses actual local file before asynchronous catalog has loaded`() {
        val dir = Files.createTempDirectory("font-available").toFile()
        try {
            assertTrue(fontFileAvailable(dir, "serif"))
            assertFalse(fontFileAvailable(dir, "file:missing.ttf"))
            val font = dir.resolve("installed.ttf").apply { writeBytes(byteArrayOf(1)) }
            assertTrue(fontFileAvailable(dir, "file:installed.ttf"))
            assertFalse(fontFileAvailable(dir, "file:../installed.ttf"))
            font.delete()
            assertFalse(fontFileAvailable(dir, "file:installed.ttf"))
        } finally { dir.deleteRecursively() }
    }
}
