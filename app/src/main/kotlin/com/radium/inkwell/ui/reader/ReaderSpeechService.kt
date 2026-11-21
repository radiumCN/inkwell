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
import android.speech.tts.Voice
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
    private var owner: ReaderSpeech? = null
    private var notificationIcon = AppIcon.DEFAULT.shape.notificationIcon
    private var notificationArt: Bitmap? = null
    private var publishedTitle: String? = null
    private var tts: TextToSpeech? = null
    private var voices = emptyList<Voice>()
    private var selectedVoice: Voice? = null
    private var loadJob: Job? = null
    private var timerJob: Job? = null
    /** 暂停太久就收场（见 [pause]） */
    private var pausedJob: Job? = null
    private var failedTarget: Pair<Int, Int>? = null
    private var resumeAfterLoad = true
    private var utteranceSequence = 0L
    private var utterance: String? = null
    private var chunkStart = 0
    private var nextChunk = 0
    private var paragraphStarted = false
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
                current.update(current.state.value.copy(rate = preferences.getFloat("rate", 1f)))
                media.isActive = true
                if (enterForeground()) initializeEngine()
            }
            PREVIOUS -> step(-1)
            NEXT -> step(1)
            TOGGLE -> if (owner?.state?.value?.status in setOf(SpeechStatus.PLAYING, SpeechStatus.LOADING)) pause() else play()
            STOP -> finishPlayback()
        }
        // 进程被杀后点了、划掉残留的通知：新起的实例没有朗读在跟，上面几样都无事可做，别留一个空服务挂着
        if (intent?.action != START && owner == null) stopSelf(startId)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    val defaultEnginePackage: String? get() = tts?.defaultEngine

    fun retryVoice(keepPaused: Boolean = false) {
        val current = owner ?: return
        if (!enterForeground()) return
        val state = current.state.value
        val offset = state.chapterOffset
        if (state.chapter != null && offset != null) {
            current.setTarget(state.chapter.index, offset)
        }
        loadJob?.cancel()
        stopUtterance()
        tts?.shutdown()
        tts = null
        initializeEngine(autoplay = !keepPaused)
    }

    private fun initializeEngine(autoplay: Boolean = true) {
        val current = owner ?: return
        selectedVoice = null
        publish(current.state.value.copy(status = SpeechStatus.LOADING, error = null, voiceError = null))
        // 部分引擎创建失败时立即回调。在主线程下一轮读取实例，避免读到尚未赋值的 tts。
        lateinit var engine: TextToSpeech
        engine = TextToSpeech(applicationContext) { result ->
            handler.post {
                if (owner !== current || tts !== engine) return@post
                voices = if (result == TextToSpeech.SUCCESS) {
                    val available = engine.voices.orEmpty()
                        .filter { it.locale.language == "zh" && !it.isNetworkConnectionRequired }
                        .filterNot { TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED in it.features.orEmpty() }
                        .sortedWith(
                            compareByDescending<Voice> { it.locale.country == "CN" }
                                .thenBy { it.name }.thenBy { it.locale.toLanguageTag() }.thenByDescending { it.quality },
                        )
                    available.distinctBy { it.name }
                } else {
                    emptyList()
                }
                val preferredVoice = preferences.getString("voice", null)
                selectedVoice = voices.firstOrNull { it.name == preferredVoice && engine.setVoice(it) == TextToSpeech.SUCCESS }
                    ?: voices.firstOrNull { engine.setVoice(it) == TextToSpeech.SUCCESS }
                if (selectedVoice == null) {
                    releaseFocus()
                    publish(current.state.value.copy(status = SpeechStatus.NO_VOICE, voices = emptyList(), selectedVoice = null))
                    leaveForeground()
                    return@post
                }
                engine.setAudioAttributes(attributes)
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String) = Unit
                    override fun onDone(id: String) { handler.post { if (utterance == id) speakNextChunk() } }
                    @Deprecated("Android may still dispatch this callback from a TTS engine")
                    override fun onError(id: String) { handler.post { if (utterance == id) fail("朗读失败，请重试") } }
                })
                val state = current.state.value
                publish(state.copy(voices = voices.map { it.name }, selectedVoice = selectedVoice?.name))
                seek(current.targetChapter, current.targetOffset, autoplay && state.status != SpeechStatus.PAUSED)
            }
        }
        tts = engine
    }

    fun selectVoice(name: String) {
        val state = owner?.state?.value ?: return
        val engine = tts ?: return
        if (state.status == SpeechStatus.LOADING || state.selectedVoice == name) return
        val voice = voices.firstOrNull { it.name == name } ?: return
        if (engine.setVoice(voice) != TextToSpeech.SUCCESS) {
            publish(state.copy(voiceError = "语音引擎无法切换到此音色。"))
            return
        }
        selectedVoice = voice
        preferences.edit().putString("voice", name).apply()
        publish(state.copy(selectedVoice = name, voiceError = null))
        if (state.status == SpeechStatus.PLAYING) restartChunk()
    }

    fun setRate(rate: Float) {
        val state = owner?.state?.value ?: return
        preferences.edit().putFloat("rate", rate).apply()
        publish(state.copy(rate = rate))
        // 倍速只在一段的第一片设给引擎（见 speakNextChunk）：不重播的话，要等这一段（一片最长几千字）读完才变
        if (state.status == SpeechStatus.PLAYING) restartChunk()
    }

    /** 用新音色 / 倍速重播当前播报片段，不用等到整段结束；章节进度仍用原锚点 */
    private fun restartChunk() {
        val restart = chunkStart
        stopUtterance()
        nextChunk = restart
        speakNextChunk()
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
        val state = owner?.state?.value ?: return
        when (state.status) {
            SpeechStatus.PLAYING, SpeechStatus.LOADING, SpeechStatus.IDLE, SpeechStatus.COMPLETE -> return
            SpeechStatus.NO_VOICE -> { retryVoice(); return }
            else -> Unit
        }
        pausedJob?.cancel()
        if (!enterForeground()) return
        val current = owner ?: return
        val target = failedTarget
        when {
            target != null -> seek(target.first, target.second, true)
            state.paragraph != null -> startParagraph(state.paragraphIndex, state.paragraphStart)
            tts == null || selectedVoice == null -> retryVoice()
            else -> seek(current.targetChapter, current.targetOffset, true)
        }
    }

    fun pause() {
        val state = owner?.state?.value ?: return
        if (state.status != SpeechStatus.PLAYING && state.status != SpeechStatus.LOADING) return
        loadJob?.cancel()
        stopUtterance()
        releaseFocus()
        publish(state.copy(status = SpeechStatus.PAUSED))
        // 暂停时留在前台，同媒体 App：出了前台就是个普通后台服务，App 退到后台一分钟左右就被系统收掉 ——
        // onDestroy 清掉朗读状态，通知、锁屏控件一起没了，回来想继续都没处点。继续时也不用再从后台进前台（Android 12 起会被拒）。
        // 也不一直挂着：暂停超过 PAUSED_TIMEOUT_MS 就当是不听了，记下位置、收掉通知
        pausedJob?.cancel()
        pausedJob = scope.launch {
            delay(PAUSED_TIMEOUT_MS)
            finishPlayback()
        }
    }

    fun step(direction: Int) {
        val state = owner?.state?.value ?: return
        val chapter = state.chapter ?: return
        if (state.status == SpeechStatus.LOADING || state.status == SpeechStatus.COMPLETE) return
        val target = state.paragraphIndex + direction
        val playing = state.status == SpeechStatus.PLAYING
        when {
            target in chapter.paragraphs.indices -> {
                if (playing) startParagraph(target)
                else {
                    selectParagraph(target)
                    recordPosition()
                    publish(owner?.state?.value?.copy(status = SpeechStatus.PAUSED) ?: return)
                }
            }
            target >= chapter.paragraphs.size -> nextChapter(chapter, playing, reachedEnd = false)
            chapter.index > 0 -> seek(chapter.index - 1, Int.MAX_VALUE, playing)
        }
    }

    fun seek(
        chapter: Int,
        offset: Int,
        autoplay: Boolean = when (owner?.state?.value?.status) {
            SpeechStatus.LOADING -> resumeAfterLoad
            SpeechStatus.PLAYING -> true
            else -> false
        },
    ) {
        val current = owner ?: return
        if (selectedVoice == null) {
            current.setTarget(chapter, offset)
            return
        }
        if (chapter !in 0 until current.chapterCount()) return
        loadJob?.cancel()
        stopUtterance()
        failedTarget = chapter to offset
        resumeAfterLoad = autoplay
        current.setTarget(chapter, offset)
        val previous = current.state.value
        publish(previous.copy(status = SpeechStatus.LOADING, error = null))
        loadJob = scope.launch {
            // 下一章无文本时再次 seek，让新任务接手，避免在纯图片章节链上递归。
            yield()
            try {
                val loaded = if (previous.chapter?.index == chapter) previous.chapter else current.load(chapter)
                val index = loaded.paragraphIndexAt(offset)
                if (index < 0) {
                    failedTarget = null
                    if (offset == Int.MAX_VALUE) {
                        if (loaded.index > 0) {
                            seek(loaded.index - 1, Int.MAX_VALUE, autoplay)
                        } else {
                            publish(previous.copy(status = SpeechStatus.PAUSED))
                            releaseFocus()
                            leaveForeground()
                        }
                    } else {
                        nextChapter(loaded, autoplay, reachedEnd = false)
                    }
                    return@launch
                }
                failedTarget = null
                val start = loaded.paragraphs[index].startWithin(offset)
                publish(current.state.value.copy(chapter = loaded, paragraphIndex = index, paragraphStart = start))
                if (autoplay) startParagraph(index, start) else {
                    recordPosition()
                    publish(current.state.value.copy(status = SpeechStatus.PAUSED))
                    leaveForeground()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                fail("章节加载失败，请重试")
            }
        }
    }

    private fun selectParagraph(index: Int, start: Int = 0) {
        val state = owner?.state?.value ?: return
        failedTarget = null
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
        val firstChunk = !paragraphStarted
        if (firstChunk) {
            val voice = selectedVoice ?: return
            if (engine.voice != voice && engine.setVoice(voice) != TextToSpeech.SUCCESS) {
                fail("所选音色暂不可用，请在播放设置中选择其他音色")
                return
            }
            engine.setSpeechRate(state.rate)
            paragraphStarted = true
        }
        val start = nextChunk
        chunkStart = start
        val end = speechChunkEnd(paragraph.text, start, TextToSpeech.getMaxSpeechInputLength())
        val id = (++utteranceSequence).toString()
        utterance = id
        nextChunk = end
        if (engine.speak(paragraph.text.substring(start, end), TextToSpeech.QUEUE_FLUSH, null, id) == TextToSpeech.SUCCESS) {
            if (firstChunk) recordPosition()
        } else {
            fail("朗读失败，请重试")
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
            failedTarget = chapter.index + 1 to 0
        } else {
            seek(chapter.index + 1, 0, autoplay)
        }
    }

    private fun fail(message: String) {
        val state = owner?.state?.value ?: return
        stopUtterance()
        releaseFocus()
        publish(state.copy(status = SpeechStatus.ERROR, error = message))
        leaveForeground()
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

    private fun stopUtterance() {
        utterance = null
        chunkStart = 0
        nextChunk = 0
        paragraphStarted = false
        tts?.stop()
    }

    private fun releaseFocus() {
        if (hasFocus) audio.abandonAudioFocusRequest(focus)
        hasFocus = false
    }

    private fun resetEngine() {
        loadJob?.cancel()
        timerJob?.cancel()
        pausedJob?.cancel()
        failedTarget = null
        resumeAfterLoad = true
        stopUtterance()
        releaseFocus()
        tts?.shutdown()
        tts = null
        selectedVoice = null
        voices = emptyList()
    }

    // 不要 POST_NOTIFICATIONS：这条是带 MediaSession 的 MediaStyle 通知（见 notification()），Android 13 起媒体会话通知
    // 豁免通知权限，没授权也照常显示锁屏、通知栏控件。声明权限也不会自动授予，还得为它另做运行时申请
    @SuppressLint("NotificationPermission")
    private fun publish(state: SpeechState) {
        val current = owner ?: return
        current.update(state)
        val status = when (state.status) {
            SpeechStatus.PLAYING -> PlaybackState.STATE_PLAYING
            SpeechStatus.LOADING -> PlaybackState.STATE_BUFFERING
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
        val playing = state.status == SpeechStatus.PLAYING || state.status == SpeechStatus.LOADING
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
