package com.radium.inkwell.ui.reader

import com.radium.inkwell.core.model.ChapterContent
import com.radium.inkwell.core.model.ContentElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderSpeechTest {
    @Test
    fun `speech anchors use the same offsets and element indices as pagination`() {
        val chapter = speechChapter(
            2,
            "第三章",
            ChapterContent(
                listOf(
                    ContentElement.Image("cover"),
                    ContentElement.Paragraph("  "),
                    ContentElement.Heading(2, "小标题"),
                    ContentElement.Divider,
                    ContentElement.Paragraph("正文第一段"),
                ),
            ),
        )

        assertEquals(listOf(5, 9), chapter.paragraphs.map { it.offset })
        assertEquals(listOf(3, 5), chapter.paragraphs.map { it.elementIndex })
        assertEquals(0, chapter.paragraphIndexAt(0))
        assertEquals(0, chapter.paragraphIndexAt(7))
        assertEquals(1, chapter.paragraphIndexAt(8))
        assertEquals(1, chapter.paragraphIndexAt(Int.MAX_VALUE))
    }

    @Test
    fun `seeking into a paragraph keeps the requested chapter offset`() {
        val paragraph = SpeechParagraph(120, 1, "前页😀后页")
        val chapter = SpeechChapter(2, "第三章", listOf(paragraph))
        val start = paragraph.startWithin(124)
        val paused = SpeechState(status = SpeechStatus.PAUSED, chapter = chapter, paragraphStart = start)

        assertEquals("后页", paragraph.text.substring(start))
        assertEquals(124, paused.chapterOffset)
        assertEquals(122, paused.copy(paragraphStart = paragraph.startWithin(123)).chapterOffset)
        assertEquals(120, paused.copy(paragraphStart = 0).chapterOffset)
        assertEquals(0, paragraph.startWithin(Int.MAX_VALUE))
    }

    @Test
    fun `image-only chapters have no playable anchor`() {
        val chapter = speechChapter(0, "图", ChapterContent(listOf(ContentElement.Image("cover"))))

        assertEquals(-1, chapter.paragraphIndexAt(0))
    }

    @Test
    fun `long utterances never split a surrogate pair`() {
        val text = "甲😀乙"

        assertEquals(1, speechChunkEnd(text, 0, 2))
        assertEquals(3, speechChunkEnd(text, 1, 2))
        assertEquals(4, speechChunkEnd(text, 3, 2))
    }

    @Test
    fun `an unlisted Chinese default is offered without automatically selecting it`() {
        assertTrue(isChineseSpeechLanguage("zho"))
        val state = SpeechState(hasDefaultVoice = true, engineConnection = 4)
        assertTrue(state.offersVoice(SpeechVoiceChoice(), 4))
        assertNull(chooseOfflineSpeechVoice(state.voices, preferred = null, current = null) {
            error("No voice was advertised")
        })
    }

    @Test
    fun `the current offline voice wins over other advertised offline voices`() {
        val requested = mutableListOf<String>()
        val choice = chooseOfflineSpeechVoice(
            listOf(SpeechVoice("other", false), SpeechVoice("current", false)), preferred = null, current = "current",
            setVoice = { requested.add(it) },
        )
        assertEquals(SpeechVoiceChoice("current"), choice)
        assertEquals(listOf("current"), requested)
    }

    @Test
    fun `a saved or default network voice is never automatically restored`() {
        val voices = listOf(SpeechVoice("online", true))
        assertNull(chooseOfflineSpeechVoice(voices, preferred = "online", current = "online") {
            error("Network voice requires a new choice")
        })
    }

    @Test
    fun `a voice choice from the previous connection is rejected even if the name matches`() {
        val choice = SpeechVoiceChoice("online")
        val state = SpeechState(voices = listOf(SpeechVoice("online", true)), engineConnection = 8)
        assertFalse(state.offersVoice(choice, 7))
        assertTrue(state.offersVoice(choice, 8))
    }

    @Test
    fun `switching a listed voice does not show the voice catalog loading footer`() {
        val loading = SpeechState(status = SpeechStatus.LOADING)
        val voices = listOf(SpeechVoice("offline", false))
        val switching = loading.copy(
            voices = voices, hasDefaultVoice = true, voice = SpeechVoiceChoice("offline"), pendingVoice = SpeechVoiceChoice(),
        )
        assertTrue(loading.checkingVoiceOptions)
        assertFalse(switching.checkingVoiceOptions)
        assertFalse(switching.copy(voice = SpeechVoiceChoice(), pendingVoice = null).checkingVoiceOptions)
        assertFalse(loading.copy(hasDefaultVoice = true).checkingVoiceOptions)
    }

    @Test
    fun `a chapter loading after a voice switch keeps other voices selectable`() {
        val offline = SpeechVoiceChoice("offline")
        val default = SpeechVoiceChoice()
        val switching = SpeechState(
            status = SpeechStatus.LOADING,
            voices = listOf(SpeechVoice("offline", false), SpeechVoice("online", true)),
            hasDefaultVoice = true,
            voice = offline,
            pendingVoice = default,
        )
        assertFalse(switching.canRequestVoiceChange(default))
        assertFalse(switching.canRequestVoiceChange(SpeechVoiceChoice("online")))

        val loadingChapter = switching.copy(voice = default, pendingVoice = null)
        assertTrue(loadingChapter.canRequestVoiceChange(offline))
        assertFalse(loadingChapter.canRequestVoiceChange(default))
    }

    @Test
    fun `offline preferences use the returned voice catalog even when engines reuse a name`() {
        val voice = SpeechVoice("default", false, "zh-CN")
        val other = SpeechVoice("backup", false, "zh-TW")
        val first = offlineSpeechPreferenceKey(listOf(voice, other), voice.name)
        assertEquals(first, offlineSpeechPreferenceKey(listOf(other, voice), voice.name))
        assertNotEquals(first, offlineSpeechPreferenceKey(listOf(voice.copy(languageTag = "zh-HK"), other), voice.name))
    }

    @Test
    fun `a failed engine switch leaves the checkpoint and newer reader seeks intact`() {
        val chapter = SpeechChapter(2, "第三章", listOf(SpeechParagraph(120, 1, "前页后页")))
        val reader = ReaderSpeech({ "书" }, { false }, { 4 }, { chapter }, { _, _ -> }, {})
        val playing = SpeechState(status = SpeechStatus.PLAYING, chapter = chapter)
        reader.setTarget(chapter.index, 120)
        reader.update(playing)
        assertEquals(122, reader.checkpoint(2).chapterOffset)
        reader.update(playing.copy(status = SpeechStatus.NO_VOICE))
        reader.checkpoint(null)
        assertEquals(122, reader.targetOffset)
        reader.seek(3, 31)
        reader.checkpoint(null)
        assertEquals(3, reader.targetChapter)
        assertEquals(31, reader.targetOffset)
    }
}
