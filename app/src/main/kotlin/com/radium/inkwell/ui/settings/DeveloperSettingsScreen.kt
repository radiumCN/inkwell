package com.radium.inkwell.ui.settings

import com.radium.inkwell.ui.components.SectionHeader
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radium.inkwell.ui.components.AppAlertDialog
import com.radium.inkwell.ui.components.AppLoadingIndicator
import com.radium.inkwell.ui.components.AppSnackbarHost
import com.radium.inkwell.ui.components.AppTopBar
import com.radium.inkwell.ui.components.CollectMessages
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.ui.components.SettingGroup
import com.radium.inkwell.ui.components.SettingGroupPosition
import com.radium.inkwell.ui.components.SettingRow
import com.radium.inkwell.ui.components.SwitchRow
import com.radium.inkwell.ui.components.rememberAppTopBarScroll
import com.radium.inkwell.ui.components.settingsPageColor
import com.radium.inkwell.ui.components.topBarScroll

/** 开发者选项：关于页连点版本号进来。目前只管运行日志 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeveloperSettingsScreen(
    onBack: () -> Unit,
    viewModel: DeveloperViewModel,
) {
    val context = LocalContext.current
    val recording by viewModel.logRecording.collectAsStateWithLifecycle()
    val summary by viewModel.summary.collectAsStateWithLifecycle()
    val exporting by viewModel.exporting.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    CollectMessages(viewModel.messages, snackbar)
    var confirmClear by remember { mutableStateOf(false) }

    // 同导出书源：zip 落在 cacheDir/export/，交给系统分享（发给开发者、存网盘都行）
    LaunchedEffect(Unit) {
        viewModel.exportFile.collect { file ->
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "导出运行日志"))
        }
    }

    val pageColor = settingsPageColor()
    val topBarScroll = rememberAppTopBarScroll()
    Scaffold(
        modifier = Modifier.topBarScroll(topBarScroll),
        containerColor = pageColor,
        topBar = { AppTopBar("开发者选项", topBarScroll, onBack = onBack, containerColor = pageColor) },
        snackbarHost = { AppSnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(top = Dimens.gapS),
        ) {
            SectionHeader("日志")
            SettingGroup {
                SwitchRow(
                    title = "日志记录",
                    subtitle = "记录应用启动后的完整运行日志，用于定位问题。会持续写入存储，排查完建议关闭",
                    checked = recording,
                    onCheckedChange = viewModel::setLogRecording,
                    position = SettingGroupPosition.First,
                )
                SettingRow(
                    title = "导出运行日志",
                    // 日志里会有书源地址、书名这类信息 —— 导出前说清楚，免得随手发到公开群里
                    subtitle = buildString {
                        summary?.let {
                            append(
                                if (it.sessions == 0) "暂无已保存的日志（将导出当前快照）"
                                else "已保存 ${it.sessions} 次运行，共 ${formatSize(it.bytes)}",
                            )
                            append("\n")
                        }
                        append("可能包含书源地址、书名等信息，请只发给信任的人")
                    },
                    onClick = viewModel::export,
                    trailing = if (exporting) {
                        { AppLoadingIndicator(size = Dimens.buttonSpinner) }
                    } else null,
                    position = SettingGroupPosition.Middle,
                )
                SettingRow(
                    title = "清除日志",
                    onClick = { confirmClear = true },
                    position = SettingGroupPosition.Last,
                )
            }
            SectionHeader("开发者模式")
            SettingGroup {
                SettingRow(
                    title = "关闭开发者模式",
                    subtitle = "同时停止日志记录；在「关于」里连点版本号可再次打开",
                    onClick = { viewModel.disableDeveloperMode(onDone = onBack) },
                    position = SettingGroupPosition.Alone,
                )
            }
            Spacer(Modifier.height(Dimens.gapXL))
        }
    }

    if (confirmClear) {
        AppAlertDialog(
            onDismissRequest = { confirmClear = false },
            title = "清除日志",
            text = "删除已保存的运行日志。正在录制的这一份会保留。",
            confirmText = "清除",
            onConfirm = {
                confirmClear = false
                viewModel.clear()
            },
        )
    }
}
