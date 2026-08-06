package com.radium.inkwell.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.radium.inkwell.data.prefs.AppPrefs
import com.radium.inkwell.ui.components.AppSnackbarHost
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.ui.components.OptionPickerSheet
import com.radium.inkwell.ui.components.PickerOption
import com.radium.inkwell.ui.components.SettingRow
import com.radium.inkwell.update.CheckResult
import com.radium.inkwell.update.UpdateChannel
import com.radium.inkwell.update.UpdateInfo
import com.radium.inkwell.update.UpdateManager
import com.radium.inkwell.update.UpdateSource
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val updateManager = koinInject<UpdateManager>()
    val appPrefs = koinInject<AppPrefs>()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val currentVersion = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
    }
    val channel by appPrefs.updateChannel.collectAsState(initial = UpdateChannel.STABLE)
    val source by appPrefs.updateSource.collectAsState(initial = UpdateSource.GITHUB)

    var checking by remember { mutableStateOf(false) }
    var update by remember { mutableStateOf<UpdateInfo?>(null) }
    var showChannelPicker by remember { mutableStateOf(false) }
    var showSourcePicker by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }

    fun checkUpdate() {
        if (checking) return
        checking = true
        scope.launch {
            when (val result = updateManager.check(source, channel, currentVersion)) {
                is CheckResult.Available -> update = result.info
                CheckResult.UpToDate ->
                    snackbar.showSnackbar("已是最新版本 v$currentVersion（${source.label} · ${channel.label}）")
                is CheckResult.Failed ->
                    snackbar.showSnackbar("检查失败: ${result.message}")
            }
            checking = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("更新") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        snackbarHost = { AppSnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            SettingRow(
                title = "检查更新",
                subtitle = if (checking) "正在检查…" else "当前版本 v$currentVersion",
                onClick = ::checkUpdate,
            )
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

    update?.let { info ->
        val direct = info.directInstall
        AlertDialog(
            onDismissRequest = { if (!downloading) update = null },
            title = {
                Text("发现新版本 v${info.latestVersion}" + if (info.isPrerelease) "（测试版）" else "")
            },
            text = {
                Column(
                    Modifier.heightIn(max = Dimens.dialogBodyMaxHeight).verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        info.notes.ifBlank { "暂无更新说明" },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (downloading) {
                        Spacer(Modifier.height(Dimens.gapM))
                        LinearProgressIndicator(
                            progress = { downloadProgress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "正在下载并校验… ${(downloadProgress * 100).toInt()}%",
                            Modifier.padding(top = Dimens.gapXS),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                if (direct != null) {
                    TextButton(
                        enabled = !downloading,
                        onClick = {
                            downloading = true
                            downloadProgress = 0f
                            scope.launch {
                                runCatching {
                                    val apk = updateManager.downloadAndVerify(
                                        direct, context.cacheDir,
                                    ) { downloadProgress = it }
                                    if (updateManager.install(context, apk)) {
                                        update = null
                                    } else {
                                        snackbar.showSnackbar("请先在系统里授予「安装未知应用」权限，再重试")
                                    }
                                }.onFailure {
                                    snackbar.showSnackbar("下载失败: ${it.message?.take(80)}")
                                }
                                downloading = false
                            }
                        },
                    ) { Text(if (downloading) "下载中…" else "下载并安装") }
                } else {
                    TextButton(onClick = {
                        info.browserUrl?.let {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it)))
                        }
                        update = null
                    }) { Text(if (info.browserIsApk) "下载 APK" else "查看 Release") }
                }
            },
            dismissButton = {
                TextButton(enabled = !downloading, onClick = { update = null }) { Text("以后再说") }
            },
        )
    }
}
