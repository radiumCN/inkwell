package com.radium.inkwell.ui.reader

import android.content.Context
import com.radium.inkwell.core.model.ChapterContent
import com.radium.inkwell.core.model.asParagraph
import com.radium.inkwell.core.model.charLength
import com.radium.inkwell.core.model.textOrNull
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal val speechRates = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
internal val experimentalSpeechRates = listOf(3f, 4f, 5f)

internal fun Float.speechRateLabel(): String = "${toString().removeSuffix(".0")}×"

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

internal data class SpeechEngine(val packageName: String, val label: String)

internal data class SpeechVoice(val name: String, val requiresNetwork: Boolean, val languageTag: String = "")

/** null 的 name 表示沿用引擎配置的中文音色。 */
internal data class SpeechVoiceChoice(val name: String? = null)

internal fun isChineseSpeechLanguage(language: String?): Boolean = when (language) {
    "zh", "zho", "chi", "cmn", "yue" -> true
    else -> false
}

/** A preferred package may have fallen back. Match offline preferences against the returned catalog. */
internal fun offlineSpeechPreferenceKey(voices: List<SpeechVoice>, defaultName: String?): String {
    val catalog = buildString {
        append(defaultName.orEmpty().length).append(':').append(defaultName.orEmpty())
        for (voice in voices.sortedBy { it.name }) {
            append(voice.name.length).append(':').append(voice.name)
            append(voice.languageTag.length).append(':').append(voice.languageTag)
            append(if (voice.requiresNetwork) '1' else '0')
        }
    }
    val digest = MessageDigest.getInstance("SHA-256").digest(catalog.encodeToByteArray())
    return "offline-voice:" + Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
}

internal fun chooseOfflineSpeechVoice(
    voices: List<SpeechVoice>,
    preferred: String?,
    current: String?,
    setVoice: (String) -> Boolean,
): SpeechVoiceChoice? {
    val saved = voices.firstOrNull { !it.requiresNetwork && it.name == preferred }
    if (saved != null && setVoice(saved.name)) return SpeechVoiceChoice(saved.name)
    val offline = voices.filterNot { it.requiresNetwork || it.name == preferred }
    offline.firstOrNull { it.name == current && setVoice(it.name) }?.let { return SpeechVoiceChoice(it.name) }
    offline.firstOrNull { it.name != current && setVoice(it.name) }?.let { return SpeechVoiceChoice(it.name) }
    return null
}

internal data class SpeechState(
    val status: SpeechStatus = SpeechStatus.IDLE,
    val chapter: SpeechChapter? = null,
    val paragraphIndex: Int = 0,
    val paragraphStart: Int = 0,
    val engines: List<SpeechEngine> = emptyList(),
    val enginePackage: String? = null,
    val systemEngine: String? = null,
    val engineConnection: Long = 0,
    val voices: List<SpeechVoice> = emptyList(),
    val hasDefaultVoice: Boolean = false,
    val voice: SpeechVoiceChoice? = null,
    val pendingVoice: SpeechVoiceChoice? = null,
    val voiceError: String? = null,
    val rate: Float = 1f,
    // null = 关闭，0 = 本章结束，正数 = 分钟。
    val timer: Int? = null,
    val error: String? = null,
) {
    val paragraph: SpeechParagraph? get() = chapter?.paragraphs?.getOrNull(paragraphIndex)
    val chapterOffset: Int? get() = paragraph?.let { it.offset + paragraphStart }
    val active: Boolean get() = status != SpeechStatus.IDLE
    val hasVoiceOptions: Boolean get() = hasDefaultVoice || voices.isNotEmpty()
    val checkingVoiceOptions: Boolean get() = status == SpeechStatus.LOADING && !hasVoiceOptions

    fun canRequestVoiceChange(choice: SpeechVoiceChoice): Boolean = pendingVoice == null && voice != choice

    fun offersVoice(choice: SpeechVoiceChoice, connection: Long): Boolean =
        engineConnection == connection && (choice.name?.let { name -> voices.any { it.name == name } } ?: hasDefaultVoice)
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

    fun checkpoint(spokenOffset: Int?): SpeechState {
        val current = state.value
        val chapter = current.chapter ?: return current
        val paragraph = current.paragraph ?: return current
        if (current.status != SpeechStatus.PLAYING || spokenOffset == null) return current
        val start = paragraph.startWithin(paragraph.offset + spokenOffset)
        setTarget(chapter.index, paragraph.offset + start)
        return current.copy(paragraphStart = start)
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
