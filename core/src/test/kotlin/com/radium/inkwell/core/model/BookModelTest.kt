package com.radium.inkwell.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BookModelTest {

    /**
     * 图不占偏移：好几个版本里书不收图，那时存下的进度是在只有字的字符流上算的，
     * 图占位的话升级后插图之前的进度全往前错、落到上一页（见 reader 的 Paginator）
     */
    @Test
    fun `charLength counts text and placeholders`() {
        assertEquals(4, (ContentElement.Paragraph("四个字呢") as ContentElement).charLength)
        assertEquals(0, (ContentElement.Image("rec:1") as ContentElement).charLength)
        assertEquals(1, (ContentElement.Divider as ContentElement).charLength)
    }

    @Test
    fun `registry rejects unknown format`() {
        val registry = BookParserRegistry(emptyList())
        val file = kotlin.io.path.createTempFile(suffix = ".bin").toFile()
        file.writeBytes(byteArrayOf(0, 1, 2, 3))
        try {
            assertFailsWith<BookParseException.Corrupted> { registry.open(file) }
        } finally {
            file.delete()
        }
    }
}
