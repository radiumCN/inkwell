package com.radium.inkwell.core.source

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BookSourceTypesTest {

    @Test
    fun `parse supports int string and float-like`() {
        assertEquals(0, typeOf("""{"bookSourceType":0}"""))
        assertEquals(2, typeOf("""{"bookSourceType":2}"""))
        assertEquals(4, typeOf("""{"bookSourceType":"4"}"""))
        assertEquals(1, typeOf("""{"bookSourceType":1.0}"""))
        assertEquals(0, typeOf("""{}"""))
    }

    @Test
    fun `only text is supported`() {
        assertTrue(BookSourceTypes.isTextNovel(0))
        assertFalse(BookSourceTypes.isTextNovel(1))
        assertFalse(BookSourceTypes.isTextNovel(2))
        assertFalse(BookSourceTypes.isTextNovel(3))
        assertFalse(BookSourceTypes.isTextNovel(4))
    }

    @Test
    fun `unsupportedReason names media kinds`() {
        assertEquals("音频源不支持", BookSourceTypes.unsupportedReason(1))
        assertEquals("漫画源不支持", BookSourceTypes.unsupportedReason(2))
        assertEquals("文件源不支持", BookSourceTypes.unsupportedReason(3))
        assertEquals("视频源不支持", BookSourceTypes.unsupportedReason(4))
        assertTrue(BookSourceTypes.unsupportedReason(9).contains("type=9"))
    }

    private fun typeOf(json: String): Int =
        BookSourceTypes.parse(Json.parseToJsonElement(json).jsonObject)
}
