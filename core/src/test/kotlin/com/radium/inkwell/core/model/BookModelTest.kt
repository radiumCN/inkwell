package com.radium.inkwell.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BookModelTest {

    @Test
    fun `charLength counts text and placeholders`() {
        assertEquals(4, (ContentElement.Paragraph("四个字呢") as ContentElement).charLength)
        assertEquals(1, (ContentElement.Image("rec:1") as ContentElement).charLength)
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
