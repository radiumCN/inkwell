package com.radium.inkwell.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.radium.inkwell.data.prefs.AppPrefs
import com.radium.inkwell.ui.components.AppAlertDialog
import com.radium.inkwell.ui.components.DeterminateProgressBar
import com.radium.inkwell.update.CheckResult
import com.radium.inkwell.update.UpdateChannel
import com.radium.inkwell.update.UpdateInfo
import com.radium.inkwell.update.UpdateManager
import com.radium.inkwell.update.UpdateSource
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/** 设置主页「检查更新」用的状态与弹窗，避免把整套下载逻辑塞进二级页。 */
internal class UpdateCheckState(
    val currentVersion: String,
    val channel: UpdateChannel,
    val source: UpdateSource,
    val checking: Boolean,
    val check: () -> Unit,
    val UpdateDialog: @Composable () -> Unit,
)

@Composable
internal fun rememberUpdateCheckState(snackbar: SnackbarHostState): UpdateCheckState {
    val context = LocalContext.current
    val updateManager = koinInject<UpdateManager>()
    val appPrefs = koinInject<AppPrefs>()
    val scope = rememberCoroutineScope()

    val currentVersion = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
    }
    val channel by appPrefs.updateChannel.collectAsState(initial = UpdateChannel.STABLE)
    val source by appPrefs.updateSource.collectAsState(initial = UpdateSource.GITHUB)

    var checking by remember { mutableStateOf(false) }
    var update by remember { mutableStateOf<UpdateInfo?>(null) }
    var downloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }

    fun checkUpdate() {
        if (checking) return
        checking = true
        scope.launch {
            when (val result = updateManager.check(source, channel, currentVersion)) {
                is CheckResult.Available -> update = result.info
                CheckResult.UpToDate ->
                    snackbar.showSnackbar("已是最新版本")
                is CheckResult.Failed ->
                    snackbar.showSnackbar("检查失败: ${result.message}")
            }
            checking = false
        }
    }

    val dialog: @Composable () -> Unit = {
        update?.let { info ->
            val direct = info.directInstall
            AppAlertDialog(
                onDismissRequest = { if (!downloading) update = null },
                title = "发现新版本 v${info.latestVersion}" + if (info.isPrerelease) "（测试版）" else "",
                text = info.notes.ifBlank { "暂无更新说明" },
                confirmText = when {
                    direct != null -> "下载并安装"
                    info.browserIsApk -> "下载 APK"
                    else -> "查看 Release"
                },
                dismissText = "以后再说",
                confirmLoading = downloading,
                dismissEnabled = !downloading,
                onConfirm = {
                    if (direct != null) {
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
                    } else {
                        info.browserUrl?.let {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it)))
                        }
                        update = null
                    }
                },
                content = {
                    if (downloading) {
                        DeterminateProgressBar(progress = { downloadProgress })
                        Text(
                            "正在下载并校验… ${(downloadProgress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        }
    }

    return UpdateCheckState(
        currentVersion = currentVersion,
        channel = channel,
        source = source,
        checking = checking,
        check = ::checkUpdate,
        UpdateDialog = dialog,
    )
}
