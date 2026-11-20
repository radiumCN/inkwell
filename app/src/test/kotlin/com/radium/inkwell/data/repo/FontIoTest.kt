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

    @Test fun `delete removes only the selected font and can be retried`() {
        val dir = Files.createTempDirectory("font-delete").toFile()
        try {
            val selected = dir.resolve("selected.ttf").apply { writeBytes(ByteArray(4096)) }
            val other = dir.resolve("other.otf").apply { writeBytes(ByteArray(512)) }
            assertEquals(4608L, dir.listFiles()!!.sumOf { it.length() })
            deleteFontFile(dir, "file:selected.ttf")
            deleteFontFile(dir, "file:selected.ttf")
            assertFalse(selected.exists())
            assertEquals(512L, other.length())
            assertEquals(512L, dir.listFiles()!!.sumOf { it.length() })
        } finally { dir.deleteRecursively() }
    }

    @Test fun `invalid ids and directories are never reported as deleted fonts`() {
        val parent = Files.createTempDirectory("font-delete-invalid").toFile()
        try {
            val dir = parent.resolve("fonts").apply { mkdir() }
            val outside = parent.resolve("outside.ttf").apply { writeBytes(byteArrayOf(1)) }
            val folder = dir.resolve("folder.ttf").apply { mkdir() }
            assertFailsWith<java.io.IOException> { deleteFontFile(dir, "file:../outside.ttf") }
            assertFailsWith<java.io.IOException> { deleteFontFile(dir, "file:folder.ttf") }
            assertTrue(outside.isFile)
            assertTrue(folder.isDirectory)
        } finally { parent.deleteRecursively() }
    }
}
