package com.radium.inkwell.ui.reader

import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
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
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.ExpandMore
import top.yukonga.miuix.kmp.icon.extended.Pause
import top.yukonga.miuix.kmp.icon.extended.Play
import top.yukonga.miuix.kmp.icon.extended.VolumeUp
import top.yukonga.miuix.kmp.theme.MiuixTheme

private enum class SpeechPanel { CONTROLS, SETTINGS, VOICES }

private val speechRates = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
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
    var installUnavailable by remember { mutableStateOf(false) }
    val installLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        speech.service?.retryVoice()
    }
    val install = {
        installUnavailable = false
        try {
            installLauncher.launch(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA))
        } catch (_: ActivityNotFoundException) {
            installUnavailable = true
        } catch (_: SecurityException) {
            installUnavailable = true
        }
    }
    val back: () -> Unit = {
        page = if (page == SpeechPanel.VOICES) SpeechPanel.SETTINGS else SpeechPanel.CONTROLS
    }
    AppBottomSheet(onDismissRequest = onDismiss, enableWindowDim = false) {
        val panelHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
        SheetPages(
            page = page,
            root = SpeechPanel.CONTROLS,
            onBack = back,
            background = readerOverlayColor(),
            modifier = Modifier
                .fillMaxWidth()
                .height((panelHeight * 0.47f).coerceAtMost(Dimens.sheetListMaxHeight)),
        ) { current ->
            Column(Modifier.fillMaxSize()) {
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
                    .weight(1f)
                    .fillMaxWidth()
                    .pageScroll()
                    .verticalScroll(rememberScrollState(), overscrollEffect = null)
                    .padding(horizontal = cardMargin(), vertical = Dimens.gapS)
                Column(content, verticalArrangement = Arrangement.spacedBy(Dimens.gapS)) {
                    when (current) {
                        SpeechPanel.CONTROLS -> when (state.status) {
                            SpeechStatus.NO_VOICE -> EmptyState(
                                icon = MiuixIcons.VolumeUp,
                                title = "缺少中文离线音色",
                                hint = if (installUnavailable) "请在系统文字转语音设置中安装中文语音数据。" else "安装后可从当前位置继续。",
                                actionLabel = if (installUnavailable) "重新检查" else "安装语音数据",
                                onAction = if (installUnavailable) onRetry else install,
                            )
                            SpeechStatus.ERROR -> ErrorState(
                                title = "朗读已暂停",
                                message = state.error ?: "暂时无法播放",
                                onRetry = onRetry,
                            )
                            else -> SpeechTransport(state, speech)
                        }
                        SpeechPanel.SETTINGS -> ReaderGroup {
                            SpinnerSettingRow(
                                title = "播放倍速",
                                options = speechRates.map { PickerOption(it.toString(), "${it}×") },
                                selectedId = state.rate.toString(),
                                onSelect = { speech.service?.setRate(it.id.toFloat()) },
                                position = SettingGroupPosition.First,
                            )
                            SettingRow(
                                title = "朗读音色",
                                value = state.selectedVoice?.let { "中文离线音色" } ?: "未安装",
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
                            ReaderGroup {
                                val size = state.voices.size + 1
                                state.voices.forEachIndexed { index, name ->
                                    SettingRow(
                                        title = "中文离线音色 ${index + 1}",
                                        subtitle = name,
                                        value = if (name == state.selectedVoice) "当前" else null,
                                        onClick = { speech.service?.selectVoice(name) },
                                        position = groupPosition(index, size),
                                    )
                                }
                                SettingRow(
                                    title = if (installUnavailable) "重新检查音色" else "安装语音数据",
                                    onClick = if (installUnavailable) onRetry else install,
                                    position = groupPosition(size - 1, size),
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
            else "${state.description()} · 第 ${state.paragraphIndex + 1} / ${chapter.paragraphs.size} 段",
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
    SpeechStatus.NO_VOICE -> "需要离线音色"
    SpeechStatus.ERROR -> "朗读加载失败"
    SpeechStatus.COMPLETE -> "朗读已结束"
}
