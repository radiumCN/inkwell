package com.radium.inkwell.reader.measure

import androidx.compose.ui.text.font.FontFamily
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
