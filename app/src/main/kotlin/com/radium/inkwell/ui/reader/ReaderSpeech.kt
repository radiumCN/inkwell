package com.radium.inkwell.ui.reader

import android.content.Context
import com.radium.inkwell.core.model.ChapterContent
import com.radium.inkwell.core.model.asParagraph
import com.radium.inkwell.core.model.charLength
import com.radium.inkwell.core.model.textOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class SpeechParagraph(val offset: Int, val elementIndex: Int, val text: String) {
    fun startWithin(chapterOffset: Int): Int {
        val start = chapterOffset - offset
        if (start !in text.indices) return 0
        return if (start > 0 && text[start].isLowSurrogate() && text[start - 1].isHighSurrogate()) start - 1 else start
    }
}

internal data class SpeechChapter(val index: Int, val title: String, val paragraphs: List<SpeechParagraph>) {
    fun paragraphIndexAt(offset: Int): Int {
        val next = paragraphs.indexOfFirst { it.offset + it.text.length > offset }
        return if (next < 0) paragraphs.lastIndex else next
    }
}

internal fun speechChunkEnd(text: String, start: Int, limit: Int): Int {
    val end = (start + limit).coerceAtMost(text.length)
    return if (end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end - 1 else end
}

internal fun speechChapter(index: Int, title: String, content: ChapterContent): SpeechChapter {
    // 和 Paginator 一样，标题占章内偏移，正文元素从 1 开始。
    var offset = title.length
    val paragraphs = buildList {
        content.elements.forEachIndexed { index, original ->
            val element = original.asParagraph()
            val text = element.textOrNull
            if (!text.isNullOrBlank()) add(SpeechParagraph(offset, index + 1, text))
            offset += element.charLength
        }
    }
    return SpeechChapter(index, title, paragraphs)
}

internal enum class SpeechStatus { IDLE, LOADING, PLAYING, PAUSED, NO_VOICE, ERROR, COMPLETE }

internal data class SpeechState(
    val status: SpeechStatus = SpeechStatus.IDLE,
    val chapter: SpeechChapter? = null,
    val paragraphIndex: Int = 0,
    val paragraphStart: Int = 0,
    val voices: List<String> = emptyList(),
    val selectedVoice: String? = null,
    val voiceError: String? = null,
    val rate: Float = 1f,
    // null = 关闭，0 = 本章结束，正数 = 分钟。
    val timer: Int? = null,
    val error: String? = null,
) {
    val paragraph: SpeechParagraph? get() = chapter?.paragraphs?.getOrNull(paragraphIndex)
    val chapterOffset: Int? get() = paragraph?.let { it.offset + paragraphStart }
    val active: Boolean get() = status != SpeechStatus.IDLE
}

internal class ReaderSpeech(
    val bookTitle: () -> String,
    val isPrivate: () -> Boolean,
    val chapterCount: () -> Int,
    val load: suspend (Int) -> SpeechChapter,
    val onPosition: (chapter: Int, offset: Int) -> Unit,
    val onServiceEnded: () -> Unit,
) {
    private val mutableState = MutableStateFlow(SpeechState())
    val state = mutableState.asStateFlow()
    var service: ReaderSpeechService? = null
    var targetChapter = 0
        private set
    var targetOffset = 0
        private set

    fun update(state: SpeechState) {
        mutableState.value = state
    }

    fun start(context: Context, chapter: Int, offset: Int) {
        if (state.value.active) return
        setTarget(chapter, offset)
        update(state.value.copy(status = SpeechStatus.LOADING))
        if (!ReaderSpeechService.start(context, this)) {
            update(state.value.copy(status = SpeechStatus.ERROR, error = "无法启动朗读"))
        }
    }

    fun setTarget(chapter: Int, offset: Int) {
        targetChapter = chapter
        targetOffset = offset
    }

    fun seek(chapter: Int, offset: Int) {
        val activeService = service
        if (activeService != null) activeService.seek(chapter, offset) else setTarget(chapter, offset)
    }

    fun stop() {
        service?.finishPlayback(notifyReader = false)
        ReaderSpeechService.cancelPending(this)
        update(SpeechState())
    }
}
