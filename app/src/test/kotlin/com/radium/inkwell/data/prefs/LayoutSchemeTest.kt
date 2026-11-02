package com.radium.inkwell.data.prefs

import com.radium.inkwell.reader.api.ReaderSettings
import com.radium.inkwell.reader.api.ReaderTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LayoutSchemeTest {

    private val tight = ReaderSettings(
        fontSizeSp = 16f, lineSpacingMult = 1.4f, paragraphSpacingEm = 0.3f, letterSpacingEm = 0.05f,
        firstLineIndentEm = 0f, justify = false, marginHorizontalDp = 16f, fontId = "file:霞鹜文楷.ttf",
    )

    @Test
    fun `存下来再套回去是同一份排版`() {
        val scheme = LayoutScheme.capture("紧凑", tight)
        val applied = scheme.applyTo(ReaderSettings())
        assertTrue(scheme.matches(applied))
        assertEquals(tight.fontId, applied.fontId)
        assertEquals(tight.letterSpacingEm, applied.letterSpacingEm)
        assertEquals(tight.justify, applied.justify)
    }

    @Test
    fun `套方案不动纸色和翻页这些非排版设置`() {
        val base = ReaderSettings(theme = ReaderTheme.KRAFT, autoFlipSeconds = 30, volumeKeyFlip = false)
        val applied = LayoutScheme.capture("紧凑", tight).applyTo(base)
        assertEquals(ReaderTheme.KRAFT, applied.theme)
        assertEquals(30, applied.autoFlipSeconds)
        assertFalse(applied.volumeKeyFlip)
    }

    @Test
    fun `换了纸色仍算同一套方案，改了字号就不算`() {
        val scheme = LayoutScheme.capture("紧凑", tight)
        assertTrue(scheme.matches(tight.copy(theme = ReaderTheme.NIGHT)))
        assertFalse(scheme.matches(tight.copy(fontSizeSp = 17f)))
        assertFalse(scheme.matches(tight.copy(justify = true)))
    }

    @Test
    fun `编解码往返；坏 JSON 当没有`() {
        val list = listOf(LayoutScheme.capture("紧凑", tight), LayoutScheme.capture("默认", ReaderSettings()))
        assertEquals(list, LayoutSchemeCodec.decode(LayoutSchemeCodec.encode(list)))
        assertEquals(emptyList(), LayoutSchemeCodec.decode("{oops"))
        assertEquals(emptyList(), LayoutSchemeCodec.decode(null))
    }

    @Test
    fun `老版本存的方案缺字段时按默认补上`() {
        val decoded = LayoutSchemeCodec.decode("""[{"name":"旧方案","fontSizeSp":20.0}]""").single()
        assertEquals(20f, decoded.fontSizeSp)
        assertEquals(0f, decoded.letterSpacingEm)
        assertTrue(decoded.justify)
    }

    @Test
    fun `同名覆盖原地替换，新名追加到末尾`() {
        val a = LayoutScheme.capture("甲", ReaderSettings())
        val b = LayoutScheme.capture("乙", ReaderSettings())
        val a2 = LayoutScheme.capture("甲", tight)
        assertEquals(listOf(a2, b), LayoutSchemeCodec.upsert(listOf(a, b), a2))
        assertEquals(listOf(a, b), LayoutSchemeCodec.upsert(listOf(a), b))
    }
}
