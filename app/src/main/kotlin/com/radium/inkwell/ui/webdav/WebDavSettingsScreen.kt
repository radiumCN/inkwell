package com.radium.inkwell.ui.webdav

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import com.radium.inkwell.ui.components.AppIconButton
import com.radium.inkwell.ui.components.AppSnackbarHost
import com.radium.inkwell.ui.components.AppTopBar
import com.radium.inkwell.ui.components.rememberAppTopBarScroll
import com.radium.inkwell.ui.components.topBarScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.radium.inkwell.ui.components.CollectMessages
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.ui.components.PrimaryButton
import com.radium.inkwell.ui.components.SettingGroup
import com.radium.inkwell.ui.components.SwitchRow
import com.radium.inkwell.ui.components.SecondaryButton
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.SimpleDateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebDavSettingsScreen(onBack: () -> Unit, viewModel: WebDavViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    CollectMessages(viewModel.messages, snackbar)

    val topBarScroll = rememberAppTopBarScroll()
    Scaffold(
        modifier = Modifier.topBarScroll(topBarScroll),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        topBar = { AppTopBar("WebDAV 同步", topBarScroll, onBack = onBack) },
        snackbarHost = { AppSnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                // edge-to-edge 下窗口不再自动 resize，键盘会盖住下方按钮/说明；让出 IME 高度
                .imePadding()
                .padding(Dimens.screenPadding)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Dimens.gapM),
        ) {
            OutlinedTextField(
                value = state.url,
                onValueChange = viewModel::setUrl,
                label = { Text("服务器地址") },
                placeholder = { Text("https://dav.jianguoyun.com/dav/") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            // 书源里有三分之一的站只有 http，应用因此整体放开了明文流量（见
            // network_security_config.xml）。可 WebDAV 走的是 Basic 认证 —— 账号口令等于明文上路。
            // 局域网 NAS 常年只有 http，硬性禁止会把这类用户挡在门外，所以只提示、不阻断。
            if (state.url.isNotBlank() && !state.url.trim().startsWith("https://", ignoreCase = true)) {
                Text(
                    "该地址不是 HTTPS，账号与密码将以明文在网络上传输。公网请务必改用 https://",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            OutlinedTextField(
                value = state.username,
                onValueChange = viewModel::setUsername,
                label = { Text("账号") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            var passwordVisible by remember { mutableStateOf(false) }
            OutlinedTextField(
                value = state.password,
                onValueChange = viewModel::setPassword,
                label = { Text("密码 / 应用密码") },
                singleLine = true,
                visualTransformation = if (passwordVisible) VisualTransformation.None
                else PasswordVisualTransformation(),
                trailingIcon = {
                    AppIconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (passwordVisible) "隐藏密码" else "显示密码",
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.gapM)) {
                SecondaryButton(
                    text = "测试并保存",
                    onClick = viewModel::testAndSave,
                    enabled = !state.busy,
                    loading = state.testing,
                    modifier = Modifier.weight(1f),
                )
                PrimaryButton(
                    text = "立即同步",
                    onClick = viewModel::syncNow,
                    enabled = !state.busy && state.configured,
                    loading = state.syncing,
                    modifier = Modifier.weight(1f),
                )
            }
            SettingGroup {
                SwitchRow(
                    title = "自动同步",
                    checked = state.autoSync,
                    onCheckedChange = viewModel::setAutoSync,
                    grouped = true,
                )
            }

            if (state.lastSyncAt > 0) {
                Text(
                    "上次同步: " + SimpleDateFormat("yyyy-MM-dd HH:mm", LocalLocale.current.platformLocale)
                        .format(Date(state.lastSyncAt)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "同步内容：书架与阅读进度、书源（含原文）、订阅源、净化替换规则、阅读排版与应用设置。\n" +
                    "不同步：本地书籍文件、已缓存的章节正文 —— 换设备后本地书需重新导入文件。\n" +
                    "冲突按“新者胜”合并：书与书源逐条比时间戳，设置整块比。删除不会同步。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
