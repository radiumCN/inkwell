package com.radium.inkwell.ui.reader

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.graphics.drawable.toBitmap
import com.radium.inkwell.MainActivity
import com.radium.inkwell.R
import com.radium.inkwell.util.AppIcon
import com.radium.inkwell.util.AppIconManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

internal class ReaderSpeechService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var media: MediaSession
    private lateinit var audio: AudioManager
    private lateinit var notifications: NotificationManager
    private lateinit var focus: AudioFocusRequest
    private val preferences by lazy { getSharedPreferences(CHANNEL, MODE_PRIVATE) }
    val availableRates by lazy {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) speechRates + experimentalSpeechRates
        else speechRates
    }
    private var owner: ReaderSpeech? = null
    private var notificationIcon = AppIcon.DEFAULT.shape.notificationIcon
    private var notificationArt: Bitmap? = null
    private var publishedTitle: String? = null
    private var tts: ReaderSpeechEngine? = null
    private var offlinePreferenceKey: String? = null
    private var engineOperation: Job? = null
    private var engineTimeout: Job? = null
    private var speechSubmission: Job? = null
    private var loadJob: Job? = null
    private var timerJob: Job? = null
    /** 暂停太久就收场（见 [pause]） */
    private var pausedJob: Job? = null
    private var playWhenReady = true
    private var engineSequence = 0L
    private var utteranceSequence = 0L
    private var utterance: String? = null
    private var chunkStart = 0
    private var spokenOffset = 0
    private var nextChunk = 0
    private var foreground = false
    private var hasFocus = false

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause()
        }
    }

    override fun onCreate() {
        super.onCreate()
        notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL, "阅读朗读", NotificationManager.IMPORTANCE_LOW),
        )
        audio = getSystemService(AudioManager::class.java)
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener({ change -> if (change < 0) pause() }, handler)
            .build()
        registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), RECEIVER_NOT_EXPORTED)
        media = MediaSession(this, CHANNEL).apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = play()
                override fun onPause() = pause()
                override fun onStop() = finishPlayback()
                override fun onSkipToPrevious() = step(-1)
                override fun onSkipToNext() = step(1)
            }, handler)
            setSessionActivity(openReader())
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            START -> {
                val current = pending
                pending = null
                if (current == null) {
                    if (owner == null) stopSelf(startId)
                    return START_NOT_STICKY
                }
                resetEngine()
                owner?.let {
                    it.service = null
                    it.update(SpeechState())
                }
                owner = current
                val icon = AppIconManager.current(this)
                notificationIcon = icon.shape.notificationIcon
                notificationArt = AppIconManager.drawable(this, icon).toBitmap(ART_SIZE_PX, ART_SIZE_PX, Bitmap.Config.ARGB_8888)
                publishedTitle = null
                current.service = this
                val savedRate = preferences.getFloat("rate", 1f)
                current.update(current.state.value.copy(rate = savedRate.takeIf { it in availableRates } ?: 1f))
                media.isActive = true
                if (enterForeground()) initializeEngine()
            }
            PREVIOUS -> step(-1)
            NEXT -> step(1)
            TOGGLE -> if (owner?.state?.value?.let(::isPlayingOrPreparing) == true) pause() else play()
            STOP -> finishPlayback()
        }
        // 进程被杀后点了、划掉残留的通知：新起的实例没有朗读在跟，上面几样都无事可做，别留一个空服务挂着
        if (intent?.action != START && owner == null) stopSelf(startId)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    fun selectEngine(packageName: String?) {
        val state = owner?.state?.value ?: return
        if (packageName == state.enginePackage) return
        if (packageName != null && state.engines.none { it.packageName == packageName }) return
        preferences.edit().putString("engine", packageName).apply()
        retryVoice()
    }

    fun retryVoice() {
        val current = owner ?: return
        val state = checkpoint(current)
        loadJob?.cancel()
        stopUtterance(sendStop = false)
        retireEngine()
        releaseFocus()
        if (!enterForeground()) return
        initializeEngine(state)
    }

    private fun initializeEngine(previous: SpeechState? = null) {
        val current = owner ?: return
        val preferred = preferences.getString("engine", null)
        val engine = ReaderSpeechEngine(applicationContext)
        tts = engine
        publish((previous ?: current.state.value).copy(
            status = SpeechStatus.LOADING, enginePackage = preferred, systemEngine = null, voice = null,
            engineConnection = ++engineSequence,
            voices = emptyList(), hasDefaultVoice = false, pendingVoice = null, error = null, voiceError = null,
        ))
        runEngineOperation(engine, "语音引擎准备超时，请切换引擎或重试。") {
            val connection = engine.connect(
                preferredPackage = preferred,
                offlinePreference = { key ->
                    preferences.getString(key, null)
                        ?: preferences.getString("voice", null).takeIf { preferred == null }
                },
                attributes = attributes,
                listener = speechListener(engine),
                onCatalog = { catalog ->
                    if (tts === engine && owner === current) {
                        if (preferred != null && catalog.preferredPackage == null) {
                            preferences.edit().remove("engine").apply()
                        }
                        publish(current.state.value.copy(engines = catalog.engines, enginePackage = catalog.preferredPackage))
                    }
                },
            )
            if (!finishEngineOperation(engine)) return@runEngineOperation
            val state = current.state.value.copy(systemEngine = connection.systemEngine)
            if (!connection.connected) {
                retireEngine()
                val message = if (state.engines.isEmpty()) null else "无法连接语音引擎，请重试或切换引擎。"
                unavailableVoice(state, message)
                return@runEngineOperation
            }
            offlinePreferenceKey = connection.offlinePreferenceKey
            val ready = state.copy(
                voices = connection.voices,
                hasDefaultVoice = connection.hasDefaultVoice,
                voice = connection.offlineVoice,
            )
            if (ready.voice == null) unavailableVoice(ready)
            else resumeAtTarget(current, ready)
        }
    }

    private fun runEngineOperation(engine: ReaderSpeechEngine, timeoutMessage: String, action: suspend () -> Unit) {
        // The timer stays on the service thread while the engine may be waiting in Binder.
        engineTimeout = scope.launch {
            delay(ENGINE_INIT_TIMEOUT_MS)
            if (tts === engine) engineUnavailable(timeoutMessage)
        }
        engineOperation = scope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (tts === engine) engineUnavailable("无法使用语音引擎，请重试或切换引擎。")
            }
        }
    }

    private fun finishEngineOperation(engine: ReaderSpeechEngine): Boolean {
        if (tts !== engine) return false
        engineTimeout?.cancel()
        engineTimeout = null
        engineOperation = null
        return true
    }

    private fun retireEngine() {
        engineTimeout?.cancel()
        engineTimeout = null
        engineOperation?.cancel()
        engineOperation = null
        val engine = tts
        tts = null
        offlinePreferenceKey = null
        engine?.close()
    }

    private fun engineUnavailable(message: String) {
        val current = owner ?: return
        val state = checkpoint(current)
        loadJob?.cancel()
        stopUtterance(sendStop = false)
        retireEngine()
        unavailableVoice(state.copy(voice = null, voices = emptyList(), hasDefaultVoice = false), message)
        if (state.status == SpeechStatus.PLAYING) recordPosition()
    }

    private fun unavailableVoice(state: SpeechState, message: String? = null) {
        releaseFocus()
        publish(state.copy(status = SpeechStatus.NO_VOICE, pendingVoice = null, voiceError = message))
        leaveForeground()
    }

    private fun resumeAtTarget(current: ReaderSpeech, ready: SpeechState) {
        publish(ready)
        seek(current.targetChapter, current.targetOffset, playWhenReady)
    }

    private fun speechListener(engine: ReaderSpeechEngine): UtteranceProgressListener = object : UtteranceProgressListener() {
        override fun onStart(id: String) = Unit

        override fun onRangeStart(id: String, start: Int, end: Int, frame: Int) {
            handler.post {
                if (tts === engine && utterance == id && start in 0 until nextChunk - chunkStart) {
                    spokenOffset = maxOf(spokenOffset, chunkStart + start)
                }
            }
        }

        override fun onDone(id: String) {
            handler.post {
                if (tts !== engine || utterance != id) return@post
                utterance = null
                speakNextChunk()
            }
        }

        @Deprecated("Android may still dispatch this callback from a TTS engine")
        override fun onError(id: String) = onError(id, TextToSpeech.ERROR)

        override fun onError(id: String, errorCode: Int) {
            handler.post {
                if (tts !== engine || utterance != id) return@post
                if (errorCode == TextToSpeech.ERROR_SERVICE) {
                    engineUnavailable("语音引擎连接已断开，请重试或切换引擎。")
                    return@post
                }
                fail(when (errorCode) {
                    TextToSpeech.ERROR_NETWORK, TextToSpeech.ERROR_NETWORK_TIMEOUT -> "联网音色暂不可用，请检查网络或切换音色"
                    TextToSpeech.ERROR_NOT_INSTALLED_YET -> "音色数据待下载，请在系统设置中检查或切换音色"
                    else -> "朗读失败，请重试或切换语音引擎"
                }, utteranceEnded = true)
            }
        }
    }

    fun selectVoice(name: String?, connection: Long) {
        val current = owner ?: return
        val engine = tts ?: return
        val state = current.state.value
        val choice = SpeechVoiceChoice(name)
        if (engineTimeout != null || !state.canRequestVoiceChange(choice) || !state.offersVoice(choice, connection)) return
        val option = state.voices.firstOrNull { it.name == name }

        val checkpoint = checkpoint(current)
        loadJob?.cancel()
        stopUtterance()
        if (!enterForeground()) return
        publish(checkpoint.copy(status = SpeechStatus.LOADING, pendingVoice = choice, voiceError = null, error = null))
        runEngineOperation(engine, "切换音色超时，请重试或切换引擎。") {
            val selected = engine.selectVoice(choice)
            if (!finishEngineOperation(engine)) return@runEngineOperation
            val latest = current.state.value
            if (!selected) {
                engineUnavailable("无法切换到所选音色，请重试或切换引擎。")
                return@runEngineOperation
            }
            if (option != null && !option.requiresNetwork) {
                offlinePreferenceKey?.let { key ->
                    preferences.edit().putString(key, option.name).apply()
                }
            }
            resumeAtTarget(current, latest.copy(voice = choice, pendingVoice = null))
        }
    }

    fun setRate(rate: Float) {
        val state = owner?.state?.value ?: return
        if (rate == state.rate || rate !in availableRates) return
        preferences.edit().putFloat("rate", rate).apply()
        publish(state.copy(rate = rate))
        val paragraph = state.paragraph
        if (state.status == SpeechStatus.PLAYING && utterance != null && paragraph != null) {
            // 没有字词回调时 spokenOffset 仍是片段开头。新播报用 QUEUE_FLUSH 换掉当前这一段。
            val restart = paragraph.startWithin(paragraph.offset + spokenOffset)
            stopUtterance(sendStop = false)
            nextChunk = restart
            speakNextChunk()
        }
    }

    fun setTimer(minutes: Int?) {
        val state = owner?.state?.value ?: return
        timerJob?.cancel()
        publish(state.copy(timer = minutes))
        if (minutes != null && minutes > 0) {
            timerJob = scope.launch {
                delay(minutes * 60_000L)
                owner?.state?.value?.let { publish(it.copy(timer = null)) }
                pause()
            }
        }
    }

    fun play() {
        val current = owner ?: return
        val state = current.state.value
        when (state.status) {
            SpeechStatus.PLAYING, SpeechStatus.IDLE, SpeechStatus.COMPLETE -> return
            else -> Unit
        }
        val alreadyRequested = playWhenReady
        playWhenReady = true
        pausedJob?.cancel()
        when {
            state.status == SpeechStatus.LOADING -> if (!alreadyRequested) publish(state)
            state.voice != null && tts != null -> if (enterForeground()) seek(current.targetChapter, current.targetOffset, autoplay = true)
            tts != null && state.hasVoiceOptions -> publish(state)
            else -> retryVoice()
        }
    }

    fun pause() {
        val current = owner ?: return
        if (!playWhenReady) return
        playWhenReady = false
        val state = checkpoint(current)
        releaseFocus()
        if (state.status == SpeechStatus.PLAYING || state.status == SpeechStatus.LOADING && engineTimeout == null) {
            loadJob?.cancel()
            stopUtterance()
            publish(state.copy(status = SpeechStatus.PAUSED))
            if (state.status == SpeechStatus.PLAYING) recordPosition()
        } else {
            publish(state)
        }
        // 暂停时留在前台，同媒体 App：出了前台就是个普通后台服务，App 退到后台一分钟左右就被系统收掉 ——
        // onDestroy 清掉朗读状态，通知、锁屏控件一起没了，回来想继续都没处点。继续时也不用再从后台进前台（Android 12 起会被拒）。
        // 也不一直挂着：暂停超过 PAUSED_TIMEOUT_MS 就当是不听了，记下位置、收掉通知
        schedulePausedStop()
    }

    fun step(direction: Int) {
        val state = owner?.state?.value ?: return
        val chapter = state.chapter ?: return
        if (state.voice == null || state.status == SpeechStatus.LOADING || state.status == SpeechStatus.COMPLETE) return
        val target = state.paragraphIndex + direction
        val playing = state.status == SpeechStatus.PLAYING
        playWhenReady = playing
        when {
            target in chapter.paragraphs.indices -> {
                if (playing) startParagraph(target)
                else {
                    selectParagraph(target)
                    recordPosition()
                    publish(owner?.state?.value?.copy(status = SpeechStatus.PAUSED) ?: return)
                    schedulePausedStop()
                }
            }
            target >= chapter.paragraphs.size -> nextChapter(chapter, playing, reachedEnd = false)
            chapter.index > 0 -> seek(chapter.index - 1, Int.MAX_VALUE, playing)
        }
    }

    fun seek(chapter: Int, offset: Int) {
        val current = owner ?: return
        if (current.state.value.status == SpeechStatus.ERROR) {
            if (chapter in 0 until current.chapterCount()) current.setTarget(chapter, offset)
            return
        }
        seek(chapter, offset, playWhenReady)
    }

    private fun seek(
        chapter: Int,
        offset: Int,
        autoplay: Boolean,
    ) {
        val current = owner ?: return
        if (chapter !in 0 until current.chapterCount()) return
        playWhenReady = autoplay
        current.setTarget(chapter, offset)
        val state = current.state.value
        if (state.voice == null || state.pendingVoice != null) return
        loadJob?.cancel()
        stopUtterance()
        val previous = current.state.value
        publish(previous.copy(status = SpeechStatus.LOADING, error = null))
        loadJob = scope.launch {
            // 下一章无文本时再次 seek，让新任务接手，避免在纯图片章节链上递归。
            yield()
            try {
                val loaded = if (previous.chapter?.index == chapter) previous.chapter else current.load(chapter)
                val index = loaded.paragraphIndexAt(offset)
                if (index < 0) {
                    if (offset == Int.MAX_VALUE) {
                        if (loaded.index > 0) {
                            seek(loaded.index - 1, Int.MAX_VALUE, autoplay)
                        } else {
                            val previousOffset = previous.chapterOffset
                            if (previous.chapter != null && previousOffset != null) {
                                current.setTarget(previous.chapter.index, previousOffset)
                            }
                            playWhenReady = false
                            releaseFocus()
                            publish(previous.copy(status = SpeechStatus.PAUSED))
                            schedulePausedStop()
                        }
                    } else {
                        nextChapter(loaded, autoplay, reachedEnd = false)
                    }
                    return@launch
                }
                val paragraph = loaded.paragraphs[index]
                val start = paragraph.startWithin(offset)
                current.setTarget(chapter, paragraph.offset + start)
                publish(current.state.value.copy(chapter = loaded, paragraphIndex = index, paragraphStart = start))
                if (playWhenReady) startParagraph(index, start) else {
                    recordPosition()
                    publish(current.state.value.copy(status = SpeechStatus.PAUSED))
                    schedulePausedStop()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                fail("章节加载失败，请重试")
            }
        }
    }

    private fun selectParagraph(index: Int, start: Int = 0) {
        val current = owner ?: return
        val state = current.state.value
        val chapter = state.chapter ?: return
        val paragraph = chapter.paragraphs.getOrNull(index) ?: return
        current.setTarget(chapter.index, paragraph.offset + start)
        stopUtterance()
        nextChunk = start
        publish(state.copy(paragraphIndex = index, paragraphStart = start, error = null))
    }

    private fun startParagraph(index: Int, start: Int = 0) {
        if (!enterForeground()) return
        if (!hasFocus) {
            hasFocus = audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            if (!hasFocus) {
                fail("音频输出暂不可用，请重试")
                return
            }
        }
        selectParagraph(index, start)
        val state = owner?.state?.value ?: return
        publish(state.copy(status = SpeechStatus.PLAYING))
        speakNextChunk()
    }

    private fun speakNextChunk() {
        val state = owner?.state?.value ?: return
        val chapter = state.chapter ?: return
        val paragraph = state.paragraph ?: return
        val engine = tts ?: return
        if (nextChunk >= paragraph.text.length) {
            val next = state.paragraphIndex + 1
            if (next < chapter.paragraphs.size) startParagraph(next)
            else nextChapter(chapter, autoplay = true, reachedEnd = true)
            return
        }
        val start = nextChunk
        chunkStart = start
        spokenOffset = start
        val end = speechChunkEnd(paragraph.text, start, TextToSpeech.getMaxSpeechInputLength())
        val id = (++utteranceSequence).toString()
        utterance = id
        nextChunk = end
        val text = paragraph.text.substring(start, end)
        speechSubmission = scope.launch {
            val accepted = try {
                engine.speak(text, state.rate, id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            if (tts !== engine || utterance != id) return@launch
            if (!accepted) engineUnavailable("语音引擎未接收朗读请求，请重试或切换引擎。")
            else if (start == state.paragraphStart) recordPosition()
        }
    }

    private fun recordPosition() {
        val current = owner ?: return
        val state = current.state.value
        val chapter = state.chapter ?: return
        val offset = state.chapterOffset ?: return
        current.onPosition(chapter.index, offset)
    }

    private fun nextChapter(chapter: SpeechChapter, autoplay: Boolean, reachedEnd: Boolean) {
        val current = owner ?: return
        if (chapter.index + 1 >= current.chapterCount()) {
            resetEngine()
            publish(current.state.value.copy(status = SpeechStatus.COMPLETE, timer = null))
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else if (reachedEnd && current.state.value.timer == 0) {
            publish(current.state.value.copy(timer = null))
            // 章末定时后继续时从下一章起播。
            pause()
            current.setTarget(chapter.index + 1, 0)
        } else {
            seek(chapter.index + 1, 0, autoplay)
        }
    }

    private fun fail(message: String, utteranceEnded: Boolean = false) {
        val current = owner ?: return
        val state = checkpoint(current)
        stopUtterance(sendStop = !utteranceEnded)
        releaseFocus()
        publish(state.copy(status = SpeechStatus.ERROR, error = message))
        if (state.status == SpeechStatus.PLAYING) recordPosition()
        leaveForeground()
    }

    private fun checkpoint(current: ReaderSpeech): SpeechState = current.checkpoint(spokenOffset.takeIf { utterance != null })

    private fun schedulePausedStop() {
        if (pausedJob?.isActive == true) return
        pausedJob = scope.launch {
            delay(PAUSED_TIMEOUT_MS)
            finishPlayback()
        }
    }

    fun finishPlayback(notifyReader: Boolean = true) {
        val current = owner ?: return
        owner = null
        current.service = null
        current.update(SpeechState())
        if (notifyReader) current.onServiceEnded()
        resetEngine()
        media.isActive = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        notifications.cancel(NOTIFICATION)
        stopSelf()
    }

    private fun stopUtterance(sendStop: Boolean = true) {
        val hadUtterance = utterance != null
        speechSubmission?.cancel()
        speechSubmission = null
        utterance = null
        chunkStart = 0
        spokenOffset = 0
        nextChunk = 0
        val engine = tts ?: return
        if (sendStop && hadUtterance) scope.launch {
            val stopped = try {
                engine.stop()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            if (!stopped && tts === engine) engineUnavailable("语音引擎连接已断开，请重试或切换引擎。")
        }
    }

    private fun releaseFocus() {
        if (hasFocus) audio.abandonAudioFocusRequest(focus)
        hasFocus = false
    }

    private fun resetEngine() {
        loadJob?.cancel()
        timerJob?.cancel()
        pausedJob?.cancel()
        playWhenReady = true
        stopUtterance(sendStop = false)
        releaseFocus()
        retireEngine()
    }

    // 不要 POST_NOTIFICATIONS：这条是带 MediaSession 的 MediaStyle 通知（见 notification()），Android 13 起媒体会话通知
    // 豁免通知权限，没授权也照常显示锁屏、通知栏控件。声明权限也不会自动授予，还得为它另做运行时申请
    @SuppressLint("NotificationPermission")
    private fun publish(state: SpeechState) {
        val current = owner ?: return
        current.update(state)
        val status = when (state.status) {
            SpeechStatus.PLAYING -> PlaybackState.STATE_PLAYING
            SpeechStatus.LOADING -> if (playWhenReady) PlaybackState.STATE_BUFFERING else PlaybackState.STATE_PAUSED
            SpeechStatus.COMPLETE, SpeechStatus.IDLE -> PlaybackState.STATE_STOPPED
            else -> PlaybackState.STATE_PAUSED
        }
        media.setPlaybackState(
            PlaybackState.Builder()
                .setState(status, PlaybackState.PLAYBACK_POSITION_UNKNOWN, if (status == PlaybackState.STATE_PLAYING) 1f else 0f)
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_STOP,
                )
                .build(),
        )
        val title = displayTitle(current, state)
        if (publishedTitle != title) {
            val metadata = MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, getString(R.string.app_name))
            notificationArt?.let { metadata.putBitmap(MediaMetadata.METADATA_KEY_ART, it) }
            media.setMetadata(metadata.build())
            publishedTitle = title
        }
        if (foreground || state.chapter != null) notifications.notify(NOTIFICATION, notification(current, state))
    }

    private fun enterForeground(): Boolean {
        if (foreground) return true
        val current = owner ?: return false
        return try {
            startForeground(NOTIFICATION, notification(current, current.state.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            foreground = true
            true
        } catch (_: SecurityException) {
            current.update(current.state.value.copy(status = SpeechStatus.ERROR, error = "无法启动后台朗读"))
            false
        } catch (_: IllegalStateException) {
            current.update(current.state.value.copy(status = SpeechStatus.ERROR, error = "无法启动后台朗读"))
            false
        }
    }

    private fun leaveForeground() {
        if (!foreground) return
        val hasChapter = owner?.state?.value?.chapter != null
        stopForeground(if (hasChapter) STOP_FOREGROUND_DETACH else STOP_FOREGROUND_REMOVE)
        foreground = false
    }

    private fun displayTitle(current: ReaderSpeech, state: SpeechState): String =
        if (current.isPrivate()) "正在朗读"
        else state.chapter?.title?.takeIf { it.isNotBlank() } ?: current.bookTitle()

    private fun notification(current: ReaderSpeech, state: SpeechState): Notification {
        val playing = isPlayingOrPreparing(state)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(notificationIcon)
            .setLargeIcon(notificationArt)
            .setContentTitle(displayTitle(current, state))
            .setContentText(if (playing) "朗读中" else "朗读已暂停")
            .setContentIntent(openReader())
            .setVisibility(if (current.isPrivate()) Notification.VISIBILITY_PRIVATE else Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(playing)
            .addAction(notificationAction(android.R.drawable.ic_media_previous, "上一段", PREVIOUS))
            .addAction(notificationAction(if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (playing) "暂停" else "继续", TOGGLE))
            .addAction(notificationAction(android.R.drawable.ic_media_next, "下一段", NEXT))
            .setDeleteIntent(serviceAction(STOP))
            .setStyle(Notification.MediaStyle().setMediaSession(media.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    private fun isPlayingOrPreparing(state: SpeechState): Boolean =
        state.status == SpeechStatus.PLAYING || state.status == SpeechStatus.LOADING && playWhenReady

    private fun notificationAction(icon: Int, label: String, action: String): Notification.Action =
        Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, icon), label, serviceAction(action)).build()

    private fun serviceAction(action: String) = PendingIntent.getService(
        this, 0, Intent(this, ReaderSpeechService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE,
    )

    private fun openReader() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    override fun onTaskRemoved(rootIntent: Intent?) {
        finishPlayback()
    }

    override fun onDestroy() {
        owner?.let {
            it.service = null
            val state = it.state.value
            if (state.status != SpeechStatus.COMPLETE && state.status != SpeechStatus.ERROR) {
                it.update(SpeechState())
                it.onServiceEnded()
            }
        }
        owner = null
        resetEngine()
        scope.cancel()
        unregisterReceiver(noisy)
        media.release()
        notifications.cancel(NOTIFICATION)
        super.onDestroy()
    }

    companion object {
        private const val ART_SIZE_PX = 128
        private const val ENGINE_INIT_TIMEOUT_MS = 15_000L
        /** 暂停多久算不听了：够接个电话、吃顿饭 */
        private const val PAUSED_TIMEOUT_MS = 30 * 60 * 1000L
        private const val CHANNEL = "reader_read_aloud"
        private const val NOTIFICATION = 20261120
        private const val START = "reader.tts.START"
        private const val STOP = "reader.tts.STOP"
        private const val TOGGLE = "reader.tts.TOGGLE"
        private const val NEXT = "reader.tts.NEXT"
        private const val PREVIOUS = "reader.tts.PREVIOUS"
        private var pending: ReaderSpeech? = null

        fun start(context: Context, owner: ReaderSpeech): Boolean {
            if (pending !== owner) pending?.update(SpeechState())
            pending = owner
            return try {
                // 入口只在前台阅读页，服务收到请求后立即升为 mediaPlayback 前台服务。
                // 用户在交付前退出时可取消请求，避免留下一个必须升为前台的空服务。
                context.startService(Intent(context, ReaderSpeechService::class.java).setAction(START))
                true
            } catch (_: IllegalStateException) {
                cancelPending(owner)
                false
            } catch (_: SecurityException) {
                cancelPending(owner)
                false
            }
        }

        fun cancelPending(owner: ReaderSpeech) {
            if (pending === owner) pending = null
        }
    }
}
