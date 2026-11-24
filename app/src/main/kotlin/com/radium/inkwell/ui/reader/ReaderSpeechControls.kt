package com.radium.inkwell.ui.reader

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.radium.inkwell.ui.components.AppBottomSheet
import com.radium.inkwell.ui.components.AppIconButton
import com.radium.inkwell.ui.components.AppLoadingIndicator
import com.radium.inkwell.ui.components.AppTextButton
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.ui.components.EmptyState
import com.radium.inkwell.ui.components.ErrorState
import com.radium.inkwell.ui.components.FloatingGlassBar
import com.radium.inkwell.ui.components.PickerOption
import com.radium.inkwell.ui.components.SettingGroupPosition
import com.radium.inkwell.ui.components.SettingRow
import com.radium.inkwell.ui.components.SheetPageHeader
import com.radium.inkwell.ui.components.SheetPages
import com.radium.inkwell.ui.components.SpinnerSettingRow
import com.radium.inkwell.ui.components.cardMargin
import com.radium.inkwell.ui.components.groupPosition
import com.radium.inkwell.ui.components.pageScroll
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.icon.extended.ExpandMore
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Pause
import top.yukonga.miuix.kmp.icon.extended.Play
import top.yukonga.miuix.kmp.icon.extended.VolumeUp
import top.yukonga.miuix.kmp.theme.MiuixTheme

private enum class SpeechPanel { CONTROLS, SETTINGS, VOICES }

private const val TTS_SETTINGS_ACTION = "com.android.settings.TTS_SETTINGS"
private const val MANAGE_VOICES = "系统语音设置"
private const val MISSING_VOICE_HINT = "请切换语音引擎，或在系统语音设置中添加中文音色。"
private const val NETWORK_VOICE_HINT = "联网音色可能将朗读文字发送给语音引擎的服务。"

private val sleepTimers = listOf(null, 15, 30, 45, 60, 0)

@Composable
internal fun ReaderSpeechSheet(
    speech: ReaderSpeech,
    state: SpeechState,
    onDismiss: () -> Unit,
    onStop: () -> Unit,
    onRetry: () -> Unit,
) {
    var page by remember { mutableStateOf(SpeechPanel.CONTROLS) }
    var settingsMessage by remember { mutableStateOf<String?>(null) }
    val settingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val service = speech.service
        if (service != null) {
            service.retryVoice()
            settingsMessage = null
        } else {
            settingsMessage = "请重新打开朗读以检查已安装的音色。"
        }
    }
    val launch: (Intent) -> Boolean = { intent ->
        try {
            settingsLauncher.launch(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
    val manageVoices: () -> Unit = {
        val engineInstaller = (state.enginePackage ?: state.systemEngine)?.let { engine ->
            Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).setPackage(engine)
        }
        settingsMessage = when {
            launch(Intent(TTS_SETTINGS_ACTION)) -> null
            engineInstaller != null && launch(engineInstaller) -> null
            launch(Intent(Settings.ACTION_SETTINGS)) -> "请在系统设置中搜索「文字转语音」或「文字转语音输出」。"
            else -> "无法打开系统设置，请手动搜索「文字转语音」。"
        }
    }
    val back: () -> Unit = {
        page = if (page == SpeechPanel.VOICES) SpeechPanel.SETTINGS else SpeechPanel.CONTROLS
    }
    AppBottomSheet(onDismissRequest = onDismiss) {
        val panelHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
        SheetPages(
            page = page,
            root = SpeechPanel.CONTROLS,
            onBack = back,
            background = readerOverlayColor(),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = (panelHeight * 0.65f).coerceAtMost(Dimens.sheetListMaxHeight)),
            wrapContentHeight = true,
        ) { current ->
            Column(Modifier.fillMaxWidth()) {
                if (current == SpeechPanel.CONTROLS) {
                    Box(Modifier.fillMaxWidth().height(Dimens.touchTarget)) {
                        Text(
                            "朗读",
                            modifier = Modifier.align(Alignment.Center),
                            style = MiuixTheme.textStyles.title4,
                            fontWeight = FontWeight.Medium,
                        )
                        AppIconButton(onClick = { dismiss {} }, modifier = Modifier.align(Alignment.CenterEnd)) {
                            Icon(MiuixIcons.ExpandMore, contentDescription = "收起朗读面板")
                        }
                    }
                } else {
                    SheetPageHeader(
                        if (current == SpeechPanel.SETTINGS) "播放设置" else "朗读音色",
                        onBack = back,
                    )
                }
                val content = Modifier
                    .weight(1f, fill = false)
                    .fillMaxWidth()
                    .pageScroll()
                    .verticalScroll(rememberScrollState(), overscrollEffect = null)
                    .padding(horizontal = cardMargin(), vertical = Dimens.gapS)
                Column(content, verticalArrangement = Arrangement.spacedBy(Dimens.gapS)) {
                    when (current) {
                        SpeechPanel.CONTROLS -> when (state.status) {
                            SpeechStatus.NO_VOICE -> {
                                val hasVoices = state.hasVoiceOptions
                                val hasEngines = state.engines.isNotEmpty()
                                val connectionError = state.voiceError
                                if (connectionError != null && !hasVoices) {
                                    ErrorState(
                                        title = "语音引擎暂不可用",
                                        message = settingsMessage ?: connectionError,
                                        onRetry = onRetry,
                                        secondaryLabel = if (hasEngines) "选择引擎" else MANAGE_VOICES,
                                        onSecondary = if (hasEngines) ({ page = SpeechPanel.SETTINGS }) else manageVoices,
                                    )
                                } else EmptyState(
                                    icon = MiuixIcons.VolumeUp,
                                    title = when {
                                        hasVoices -> "请选择朗读音色"
                                        hasEngines -> "当前引擎没有可用音色"
                                        else -> "没有可用的语音引擎"
                                    },
                                    hint = settingsMessage ?: when {
                                        hasVoices -> "可以选择当前引擎提供的音色，也可以切换引擎。"
                                        hasEngines -> MISSING_VOICE_HINT
                                        else -> "请在系统语音设置中检查已安装的引擎。"
                                    },
                                    actionLabel = when {
                                        hasVoices -> "选择音色"
                                        hasEngines -> "选择语音引擎"
                                        else -> MANAGE_VOICES
                                    },
                                    onAction = when {
                                        hasVoices -> ({ page = SpeechPanel.VOICES })
                                        hasEngines -> ({ page = SpeechPanel.SETTINGS })
                                        else -> manageVoices
                                    },
                                )
                            }
                            SpeechStatus.ERROR -> ErrorState(
                                title = "朗读已暂停",
                                message = state.error ?: "暂时无法播放",
                                onRetry = onRetry,
                            )
                            else -> SpeechTransport(state, speech)
                        }
                        SpeechPanel.SETTINGS -> ReaderGroup {
                            val systemLabel = state.engines.firstOrNull { it.packageName == state.systemEngine }?.label
                            val engineOptions = listOf(PickerOption("", "跟随系统", systemLabel)) +
                                state.engines.map { PickerOption(it.packageName, it.label) }
                            SpinnerSettingRow(
                                title = "语音引擎",
                                options = engineOptions,
                                subtitle = "首选引擎不可用时，系统可能回退",
                                selectedId = state.enginePackage.orEmpty(),
                                onSelect = { speech.service?.selectEngine(it.id.ifEmpty { null }) },
                                enabled = state.engines.isNotEmpty(),
                                position = SettingGroupPosition.First,
                            )
                            val rates = speech.service?.availableRates ?: speechRates
                            SpinnerSettingRow(
                                title = "播放倍速",
                                options = rates.map { PickerOption(it.toString(), it.speechRateLabel()) },
                                subtitle = if (rates.size > speechRates.size) "3× 及以上用于调试，实际语速由引擎决定" else null,
                                selectedId = state.rate.toString(),
                                onSelect = { speech.service?.setRate(it.id.toFloat()) },
                                position = SettingGroupPosition.Middle,
                            )
                            SettingRow(
                                title = "朗读音色",
                                value = state.voice?.let { selected ->
                                    val index = state.voices.indexOfFirst { it.name == selected.name }
                                    if (index < 0) "引擎默认音色" else voiceTitle(state.voices, index)
                                } ?: if (state.status == SpeechStatus.LOADING) "正在检查" else "未选择",
                                onClick = { page = SpeechPanel.VOICES },
                                position = SettingGroupPosition.Middle,
                            )
                            SpinnerSettingRow(
                                title = "定时停止",
                                options = sleepTimers.map { minutes ->
                                    PickerOption(
                                        minutes?.toString().orEmpty(),
                                        when (minutes) {
                                            null -> "关闭"
                                            0 -> "本章结束"
                                            else -> "${minutes} 分钟"
                                        },
                                    )
                                },
                                selectedId = state.timer?.toString().orEmpty(),
                                onSelect = { speech.service?.setTimer(it.id.toIntOrNull()) },
                                position = SettingGroupPosition.Last,
                            )
                        }
                        SpeechPanel.VOICES -> {
                            val pending = state.pendingVoice
                            var showPending by remember(pending) { mutableStateOf(false) }
                            LaunchedEffect(pending) {
                                if (pending != null) {
                                    delay(VOICE_SWITCH_INDICATOR_DELAY_MS)
                                    showPending = true
                                }
                            }
                            if (state.hasDefaultVoice || state.voices.any { it.requiresNetwork }) {
                                Text(
                                    NETWORK_VOICE_HINT,
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                            ReaderGroup {
                                val defaultCount = if (state.hasDefaultVoice) 1 else 0
                                val size = state.voices.size + defaultCount + 1
                                if (state.hasDefaultVoice) {
                                    SpeechVoiceRow(
                                        choice = SpeechVoiceChoice(),
                                        state = state,
                                        showPending = showPending,
                                        title = "引擎默认音色",
                                        subtitle = "联网状态由引擎决定",
                                        position = SettingGroupPosition.First,
                                    ) { speech.service?.selectVoice(null, state.engineConnection) }
                                }
                                state.voices.forEachIndexed { index, voice ->
                                    SpeechVoiceRow(
                                        choice = SpeechVoiceChoice(voice.name),
                                        state = state,
                                        showPending = showPending,
                                        title = voiceTitle(state.voices, index),
                                        subtitle = voice.name,
                                        position = groupPosition(index + defaultCount, size),
                                    ) { speech.service?.selectVoice(voice.name, state.engineConnection) }
                                }
                                SettingRow(
                                    title = MANAGE_VOICES,
                                    subtitle = "管理已安装的引擎和音色",
                                    onClick = manageVoices,
                                    position = groupPosition(size - 1, size),
                                )
                            }
                            val message = settingsMessage ?: state.voiceError ?: when {
                                state.checkingVoiceOptions -> "正在检查音色…"
                                !state.hasVoiceOptions -> MISSING_VOICE_HINT
                                else -> null
                            }
                            if (message != null) {
                                Text(
                                    message,
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                        }
                    }
                }
                if (current == SpeechPanel.CONTROLS) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = cardMargin()),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        AppTextButton("播放设置", onClick = { page = SpeechPanel.SETTINGS })
                        AppTextButton("结束朗读", onClick = { dismiss(onStop) })
                    }
                }
            }
        }
    }
}

private const val VOICE_SWITCH_INDICATOR_DELAY_MS = 180L

@Composable
private fun SpeechVoiceRow(
    choice: SpeechVoiceChoice,
    state: SpeechState,
    showPending: Boolean,
    title: String,
    subtitle: String,
    position: SettingGroupPosition,
    onClick: () -> Unit,
) {
    val selected = state.voice == choice
    SettingRow(
        title = title,
        subtitle = subtitle,
        onClick = onClick.takeIf { state.canRequestVoiceChange(choice) },
        position = position,
        trailing = {
            // 勾选、等待和未选共用同一个行尾尺寸。换音色时长音色名也不会重新换行。
            Box(Modifier.size(Dimens.iconSm), contentAlignment = Alignment.Center) {
                when {
                    showPending && state.pendingVoice == choice -> AppLoadingIndicator(size = Dimens.iconSm)
                    selected -> Icon(MiuixIcons.Basic.Check, contentDescription = "当前音色", tint = MiuixTheme.colorScheme.primary)
                }
            }
        },
    )
}

private fun voiceTitle(voices: List<SpeechVoice>, index: Int): String {
    val kind = if (voices[index].requiresNetwork) "联网音色" else "离线音色"
    return if (voices.size == 1) kind else "$kind ${index + 1}"
}

@Composable
private fun SpeechTransport(state: SpeechState, speech: ReaderSpeech) {
    val chapter = state.chapter
    val playing = state.status == SpeechStatus.PLAYING
    val loading = state.status == SpeechStatus.LOADING
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (chapter != null) {
            Text(chapter.title, style = MiuixTheme.textStyles.body1, textAlign = TextAlign.Center,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Text(
            if (chapter == null) state.description()
            else "${state.description()} · 第 ${state.paragraphIndex + 1} / ${chapter.paragraphs.size} 段 · ${state.rate.speechRateLabel()}",
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(top = Dimens.gapXS, bottom = Dimens.gapS),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.gapL)) {
            val canStep = chapter != null && !loading && state.status != SpeechStatus.COMPLETE
            AppIconButton(onClick = { speech.service?.step(-1) }, enabled = canStep) {
                Icon(MiuixIcons.Back, contentDescription = "上一段")
            }
            if (loading) {
                Box(Modifier.height(Dimens.touchTarget), contentAlignment = Alignment.Center) { AppLoadingIndicator() }
            } else {
                AppIconButton(
                    onClick = { if (playing) speech.service?.pause() else speech.service?.play() },
                    enabled = state.status != SpeechStatus.COMPLETE,
                    backgroundColor = MiuixTheme.colorScheme.primary,
                    contentColor = MiuixTheme.colorScheme.onPrimary,
                ) {
                    Icon(if (playing) MiuixIcons.Pause else MiuixIcons.Play, contentDescription = if (playing) "暂停朗读" else "继续朗读")
                }
            }
            AppIconButton(onClick = { speech.service?.step(1) }, enabled = canStep) {
                Icon(MiuixIcons.Back, contentDescription = "下一段", modifier = Modifier.graphicsLayer { scaleX = -1f })
            }
        }
    }
}

@Composable
internal fun MiniSpeechPlayer(
    speech: ReaderSpeech,
    state: SpeechState,
    visible: Boolean,
    onOpen: () -> Unit,
    onHeightChange: (Dp) -> Unit,
) {
    FloatingGlassBar(visible = visible, onHeightChange = onHeightChange) { floor ->
        val playing = state.status == SpeechStatus.PLAYING
        Row(
            Modifier.padding(horizontal = Dimens.gapM),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppTextButton(
                // 单独的标题动作，和右侧播放键各只有一个焦点。
                onClick = onOpen,
                modifier = Modifier.weight(1f, fill = false),
                contentColor = com.radium.inkwell.ui.theme.AppThemes.readableOn(MiuixTheme.colorScheme.onSurface, floor),
            ) {
                Column {
                    Text(state.description(), style = MiuixTheme.textStyles.body2)
                    state.chapter?.title?.takeIf { it.isNotBlank() }?.let { title ->
                        Text(
                            "$title · 第 ${state.paragraphIndex + 1} 段",
                            style = MiuixTheme.textStyles.footnote1,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (state.status == SpeechStatus.PLAYING || state.status == SpeechStatus.PAUSED) {
                AppIconButton(onClick = { if (playing) speech.service?.pause() else speech.service?.play() }) {
                    Icon(if (playing) MiuixIcons.Pause else MiuixIcons.Play, contentDescription = if (playing) "暂停朗读" else "继续朗读")
                }
            }
        }
    }
}

private fun SpeechState.description(): String = when (status) {
    SpeechStatus.IDLE -> "朗读"
    SpeechStatus.LOADING -> "正在准备朗读…"
    SpeechStatus.PLAYING -> "正在朗读"
    SpeechStatus.PAUSED -> "朗读已暂停"
    SpeechStatus.NO_VOICE -> "需要选择音色"
    SpeechStatus.ERROR -> "朗读加载失败"
    SpeechStatus.COMPLETE -> "朗读已结束"
}
