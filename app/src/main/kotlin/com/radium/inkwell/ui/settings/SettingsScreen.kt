package com.radium.inkwell.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.radium.inkwell.data.prefs.AppPrefs
import com.radium.inkwell.update.UpdateChannel
import com.radium.inkwell.update.UpdateChecker
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenWebDav: () -> Unit,
) {
    val context = LocalContext.current
    val updateChecker = koinInject<UpdateChecker>()
    val appPrefs = koinInject<AppPrefs>()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val currentVersion = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
    }
    val channel by appPrefs.updateChannel.collectAsState(initial = UpdateChannel.STABLE)
    var checking by remember { mutableStateOf(false) }
    var update by remember { mutableStateOf<UpdateChecker.UpdateInfo?>(null) }
    var showChannelDialog by remember { mutableStateOf(false) }

    fun checkUpdate() {
        if (checking) return
        checking = true
        scope.launch {
            when (val result = updateChecker.check(currentVersion, channel)) {
                is UpdateChecker.CheckResult.Available -> update = result.info
                UpdateChecker.CheckResult.UpToDate ->
                    snackbar.showSnackbar("已是最新版本 v$currentVersion（${channel.label}渠道）")
                is UpdateChecker.CheckResult.Failed ->
                    snackbar.showSnackbar("检查失败: ${result.message}")
            }
            checking = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            SettingsItem(
                title = "WebDAV 备份同步",
                subtitle = "书架、进度与书源的多设备同步",
                onClick = onOpenWebDav,
            )
            HorizontalDivider()
            SettingsItem(
                title = "检查更新",
                subtitle = if (checking) "正在检查…" else "当前版本 v$currentVersion",
                onClick = ::checkUpdate,
            )
            SettingsItem(
                title = "更新渠道",
                subtitle = "${channel.label}${if (channel == UpdateChannel.BETA) "（包含预发布版本，可能不稳定）" else ""}",
                onClick = { showChannelDialog = true },
            )
            HorizontalDivider()
            SettingsItem(
                title = "开源地址",
                subtitle = UpdateChecker.REPO_URL,
                onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(UpdateChecker.REPO_URL)))
                },
            )
        }
    }

    if (showChannelDialog) {
        AlertDialog(
            onDismissRequest = { showChannelDialog = false },
            title = { Text("更新渠道") },
            text = {
                Column {
                    UpdateChannel.entries.forEach { option ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch { appPrefs.setUpdateChannel(option) }
                                    showChannelDialog = false
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = channel == option,
                                onClick = {
                                    scope.launch { appPrefs.setUpdateChannel(option) }
                                    showChannelDialog = false
                                },
                            )
                            Column {
                                Text(option.label)
                                Text(
                                    when (option) {
                                        UpdateChannel.STABLE -> "只接收正式版本"
                                        UpdateChannel.BETA -> "抢先体验新功能，可能不稳定"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showChannelDialog = false }) { Text("关闭") }
            },
        )
    }

    update?.let { info ->
        AlertDialog(
            onDismissRequest = { update = null },
            title = {
                Text("发现新版本 v${info.latestVersion}" + if (info.isPrerelease) "（测试版）" else "")
            },
            text = {
                Column(
                    Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        info.notes.ifBlank { "暂无更新说明" },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val url = info.apkUrl ?: info.htmlUrl
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    update = null
                }) { Text(if (info.apkUrl != null) "下载 APK" else "查看 Release") }
            },
            dismissButton = {
                TextButton(onClick = { update = null }) { Text("以后再说") }
            },
        )
    }
}

@Composable
private fun SettingsItem(title: String, subtitle: String, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
