package com.radium.inkwell.reader.measure

import androidx.compose.ui.text.font.FontFamily
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FileFontRegistryTest {

    private val dir: File = Files.createTempDirectory("fonts").toFile()
    private val registry = FileFontRegistry(dir)

    @Test
    fun `预设字体照旧解析`() {
        assertEquals(FontFamily.Serif, registry.resolve("serif"))
        assertEquals(FontFamily.Default, registry.resolve("system"))
    }

    @Test
    fun `同步来的字体本机没有文件时回落默认字体，而不是抛`() {
        assertEquals(FontFamily.Default, registry.resolve("file:别处导入的.ttf"))
    }

    @Test
    fun `文件名带路径跳转的 id 不读字体目录以外的文件`() {
        val outside = File(dir.parentFile, "outside-${System.nanoTime()}.ttf").apply { writeBytes(byteArrayOf(0)) }
        try {
            assertEquals(FontFamily.Default, registry.resolve("file:../${outside.name}"))
        } finally {
            outside.delete()
        }
    }

    @Test
    fun `清除字体缓存后同名同时间文件也生成新字体对象`() {
        val file = File(dir, "cached.ttf").apply { writeBytes(byteArrayOf(0)) }
        val id = "file:${file.name}"
        val stamp = file.lastModified()
        val families = listOf(FontFamily.Serif, FontFamily.Monospace).iterator()
        val registry = FileFontRegistry(dir) { families.next() }
        try {
            val first = registry.resolve(id)
            assertSame(first, registry.resolve(id))
            registry.evictAfterDelete(id) { assertTrue(file.delete()) }
            file.writeBytes(byteArrayOf(1))
            assertTrue(file.setLastModified(stamp))
            assertNotSame(first, registry.resolve(id))
        } finally {
            file.delete()
        }
    }
}
