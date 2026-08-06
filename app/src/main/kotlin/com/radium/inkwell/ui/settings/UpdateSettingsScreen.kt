package com.radium.inkwell.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.radium.inkwell.data.prefs.AppPrefs
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.ui.components.OptionPickerSheet
import com.radium.inkwell.ui.components.PickerOption
import com.radium.inkwell.ui.components.SettingRow
import com.radium.inkwell.update.UpdateChannel
import com.radium.inkwell.update.UpdateSource
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/** 更新源 / 渠道等少改选项。检查更新留在设置主页，避免多点一层。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateSettingsScreen(onBack: () -> Unit) {
    val appPrefs = koinInject<AppPrefs>()
    val scope = rememberCoroutineScope()
    val channel by appPrefs.updateChannel.collectAsState(initial = UpdateChannel.STABLE)
    val source by appPrefs.updateSource.collectAsState(initial = UpdateSource.GITHUB)
    var showChannelPicker by remember { mutableStateOf(false) }
    var showSourcePicker by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("更新源与渠道") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            SettingRow(
                title = "更新源",
                subtitle = when (source) {
                    UpdateSource.GITHUB -> "GitHub（需能访问 GitHub）"
                    UpdateSource.SERVER -> "中转服务器（GitHub 受限时用，应用内直接安装）"
                },
                onClick = { showSourcePicker = true },
            )
            SettingRow(
                title = "更新渠道",
                subtitle = channel.label +
                    if (channel == UpdateChannel.BETA) "（包含预发布版本，可能不稳定）" else "",
                onClick = { showChannelPicker = true },
            )
            Spacer(Modifier.height(Dimens.gapXL))
        }
    }

    if (showChannelPicker) {
        OptionPickerSheet(
            title = "更新渠道",
            options = UpdateChannel.entries.map {
                PickerOption(
                    id = it.name,
                    label = it.label,
                    subtitle = when (it) {
                        UpdateChannel.STABLE -> "只接收正式版本"
                        UpdateChannel.BETA -> "抢先体验新功能，可能不稳定"
                    },
                )
            },
            selectedId = channel.name,
            onSelect = { opt ->
                showChannelPicker = false
                runCatching { UpdateChannel.valueOf(opt.id) }.getOrNull()?.let { picked ->
                    scope.launch { appPrefs.setUpdateChannel(picked) }
                }
            },
            onDismiss = { showChannelPicker = false },
        )
    }

    if (showSourcePicker) {
        OptionPickerSheet(
            title = "更新源",
            options = UpdateSource.entries.map {
                PickerOption(
                    id = it.name,
                    label = it.label,
                    subtitle = when (it) {
                        UpdateSource.GITHUB -> "直接从 GitHub Releases 检查（需能访问 GitHub）"
                        UpdateSource.SERVER -> "中转服务器镜像，GitHub 受限时用；应用内下载校验后直接安装"
                    },
                )
            },
            selectedId = source.name,
            onSelect = { opt ->
                showSourcePicker = false
                runCatching { UpdateSource.valueOf(opt.id) }.getOrNull()?.let { picked ->
                    scope.launch { appPrefs.setUpdateSource(picked) }
                }
            },
            onDismiss = { showSourcePicker = false },
        )
    }
}
