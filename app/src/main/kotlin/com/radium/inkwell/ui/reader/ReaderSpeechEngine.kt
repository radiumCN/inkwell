package com.radium.inkwell.ui.reader

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Each connection serializes its platform calls. An old, blocked engine cannot hold up a new one. */
internal class ReaderSpeechEngine(private val context: Context) {
    private val dispatcher = Dispatchers.IO.limitedParallelism(1)
    private val cleanup = CoroutineScope(SupervisorJob() + dispatcher)
    @Volatile private var closed = false
    private var engine: TextToSpeech? = null
    private var connected = false
    private var voices = emptyMap<String, Voice>()
    private var initialVoice: Voice? = null
    private var configuredChinese = false
    private var changedVoice = false
    private var defaultLocale: Locale? = null

    data class Catalog(val engines: List<SpeechEngine>, val preferredPackage: String?)

    data class Connection(
        val systemEngine: String?,
        val voices: List<SpeechVoice>,
        val hasDefaultVoice: Boolean,
        val offlineVoice: SpeechVoiceChoice?,
        val offlinePreferenceKey: String?,
        val connected: Boolean,
    )

    suspend fun connect(
        preferredPackage: String?,
        offlinePreference: (String) -> String?,
        attributes: AudioAttributes,
        listener: UtteranceProgressListener,
        onCatalog: (Catalog) -> Unit,
    ): Connection = withContext(dispatcher) {
        val manager = context.packageManager
        val engines = manager.queryIntentServices(
            Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), PackageManager.MATCH_DEFAULT_ONLY,
        ).mapNotNull { info ->
            info.serviceInfo?.packageName?.let { SpeechEngine(it, info.loadLabel(manager).toString()) }
        }.distinctBy { it.packageName }.sortedBy { it.label }
        val requested = preferredPackage?.takeIf { saved -> engines.any { it.packageName == saved } }
        withContext(Dispatchers.Main.immediate) { onCatalog(Catalog(engines, requested)) }

        val result = CompletableDeferred<Int>()
        val tts = TextToSpeech(context, { result.complete(it) }, requested)
        engine = tts
        val systemEngine = tts.defaultEngine
        if (result.await() != TextToSpeech.SUCCESS) {
            return@withContext Connection(systemEngine, emptyList(), false, null, null, connected = false)
        }

        val current = tts.voice
        initialVoice = current?.takeIf { it.isAvailableChinese() }
        configuredChinese = current == null && isChineseSpeechLanguage(tts.configuredLanguage())
        voices = (tts.voices.orEmpty() + listOfNotNull(current))
            .filter { it.isAvailableChinese() }
            .sortedWith(compareBy<Voice> { it.isNetworkConnectionRequired }
                .thenByDescending { it.locale.country == "CN" || it.locale.country == "CHN" }
                .thenBy { it.name }.thenBy { it.locale.toLanguageTag() }.thenByDescending { it.quality })
            .distinctBy { it.name }
            .associateBy { it.name }
        val options = voices.values.map { SpeechVoice(it.name, it.isNetworkConnectionRequired, it.locale.toLanguageTag()) }
        val offlineKey = offlineSpeechPreferenceKey(options, initialVoice?.name)
        if (initialVoice == null && (!configuredChinese || voices.isNotEmpty())) {
            defaultLocale = chineseLocales.firstOrNull { tts.isLanguageAvailable(it) >= TextToSpeech.LANG_AVAILABLE }
        }
        val hasDefault = initialVoice != null || configuredChinese || defaultLocale != null
        connected = tts.setOnUtteranceProgressListener(listener) == TextToSpeech.SUCCESS &&
            tts.setAudioAttributes(attributes) == TextToSpeech.SUCCESS
        val offline = if (connected) chooseOfflineSpeechVoice(
            options, offlinePreference(offlineKey), initialVoice?.name,
            setVoice = { connected && activateVoice(tts, voices.getValue(it)) },
        ) else null
        Connection(systemEngine, options, hasDefault, offline, offlineKey, connected)
    }

    /** The unlisted default and all network voices can only be applied by a user action. */
    suspend fun selectVoice(choice: SpeechVoiceChoice): Boolean = withContext(dispatcher) {
        val tts = engine?.takeIf { connected && !closed } ?: return@withContext false
        val name = choice.name
        if (name != null) {
            val voice = voices[name] ?: return@withContext false
            return@withContext activateVoice(tts, voice)
        }
        initialVoice?.let { return@withContext activateVoice(tts, it) }
        if (configuredChinese && !changedVoice) return@withContext true
        val locale = defaultLocale ?: return@withContext false
        if (tts.setLanguage(locale) < TextToSpeech.LANG_AVAILABLE) return@withContext false
        changedVoice = true
        val configured = tts.configuredLanguage()
        configured.isNullOrBlank() || isChineseSpeechLanguage(configured)
    }

    suspend fun speak(text: String, rate: Float, id: String): Boolean = withContext(dispatcher) {
        val tts = engine?.takeIf { connected && !closed } ?: return@withContext false
        tts.setSpeechRate(rate)
        val accepted = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) == TextToSpeech.SUCCESS
        if (!accepted) connected = false
        accepted
    }

    suspend fun stop(): Boolean = withContext(dispatcher) {
        val tts = engine?.takeIf { connected && !closed } ?: return@withContext false
        connected = tts.stop() == TextToSpeech.SUCCESS
        connected
    }

    fun close() {
        if (closed) return
        closed = true
        cleanup.launch {
            try {
                runCatching { engine?.shutdown() }
                engine = null
            } finally {
                cleanup.cancel()
            }
        }
    }

    private fun activateVoice(tts: TextToSpeech, voice: Voice): Boolean {
        val result = tts.setVoice(voice)
        if (result == TextToSpeech.SUCCESS) changedVoice = true
        // AOSP reports LANG_NOT_SUPPORTED if the connection was lost during setVoice.
        if (result == TextToSpeech.LANG_NOT_SUPPORTED) connected = false
        return result == TextToSpeech.SUCCESS
    }

    private fun Voice.isAvailableChinese(): Boolean = isChineseSpeechLanguage(locale.language) &&
        TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in features.orEmpty()

    @Suppress("DEPRECATION") // The configured language is available even when the engine has no Voice entry.
    private fun TextToSpeech.configuredLanguage(): String? = language?.language

    private companion object {
        val chineseLocales = listOf(Locale.SIMPLIFIED_CHINESE, Locale.CHINESE)
    }
}
