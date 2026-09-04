package com.radium.inkwell.ui.webdav

import android.content.ClipData
import android.content.Intent
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radium.inkwell.data.net.OfficialWebDav
import com.radium.inkwell.data.prefs.WebDavProvider
import com.radium.inkwell.ui.components.AppAlertDialog
import com.radium.inkwell.ui.components.AppIconButton
import com.radium.inkwell.ui.components.AppSnackbarHost
import com.radium.inkwell.ui.components.AppTopBar
import com.radium.inkwell.ui.components.ChipRow
import com.radium.inkwell.ui.components.CollectMessages
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.ui.components.PrimaryButton
import com.radium.inkwell.ui.components.SecondaryButton
import com.radium.inkwell.ui.components.SettingGroup
import com.radium.inkwell.ui.components.SettingGroupPosition
import com.radium.inkwell.ui.components.SettingRow
import com.radium.inkwell.ui.components.SwitchRow
import com.radium.inkwell.ui.components.rememberAppTopBarScroll
import com.radium.inkwell.ui.components.settingsPageColor
import com.radium.inkwell.ui.components.topBarScroll
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebDavSettingsScreen(onBack: () -> Unit, viewModel: WebDavViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    CollectMessages(viewModel.messages, snackbar)

    val pageColor = settingsPageColor()
    val topBarScroll = rememberAppTopBarScroll()
    Scaffold(
        modifier = Modifier.topBarScroll(topBarScroll),
        containerColor = pageColor,
        topBar = { AppTopBar("WebDAV 同步", topBarScroll, onBack = onBack, containerColor = pageColor) },
        snackbarHost = { AppSnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .padding(Dimens.screenPadding)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Dimens.gapM),
        ) {
            ChipRow(
                options = listOf("官方同步", "自备 WebDAV"),
                selectedIndex = if (state.provider == WebDavProvider.OFFICIAL) 0 else 1,
                onSelect = {
                    viewModel.setProvider(if (it == 0) WebDavProvider.OFFICIAL else WebDavProvider.CUSTOM)
                },
            )
            if (state.provider == WebDavProvider.OFFICIAL) {
                OfficialSection(state, viewModel)
            } else {
                CustomSection(state, viewModel)
            }
            SyncFooter(state)
        }
    }

    if (state.confirmSwitchToCustom) {
        AppAlertDialog(
            onDismissRequest = viewModel::dismissSwitchToCustom,
            title = "改为自备 WebDAV？",
            text = "将退出官方登录。已保存的地址与应用码会留在本页，方便改成自备盘或继续用同一套连接。",
            confirmText = "切换",
            onConfirm = viewModel::confirmSwitchToCustom,
        )
    }
    if (state.confirmDisconnect) {
        AppAlertDialog(
            onDismissRequest = viewModel::dismissDisconnect,
            title = "退出官方同步？",
            text = "本机上的官方连接信息会被清除。云端文件还在，重新登录即可继续同步。",
            confirmText = "退出",
            onConfirm = viewModel::disconnectOfficial,
        )
    }
    if (state.confirmRegenerate) {
        AppAlertDialog(
            onDismissRequest = viewModel::dismissRegenerate,
            title = "重新生成应用码？",
            text = "旧应用码会立刻失效。其他已填过旧码的设备需要重新登录一次。",
            confirmText = "生成",
            onConfirm = viewModel::regenerateAppPassword,
        )
    }
    if (state.showConnectionInfo) {
        ConnectionInfoDialog(state, onDismiss = { viewModel.showConnectionInfo(false) })
    }
}

@Composable
private fun OfficialSection(state: WebDavUiState, viewModel: WebDavViewModel) {
    if (state.officialConnected) {
        OfficialConnected(state, viewModel)
    } else {
        OfficialAuth(state, viewModel)
    }
}

@Composable
private fun OfficialConnected(state: WebDavUiState, viewModel: WebDavViewModel) {
    val context = LocalContext.current
    Text(
        "已连接官方同步" + state.officialEmail.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.gapM)) {
        PrimaryButton(
            text = "立即同步",
            onClick = viewModel::syncNow,
            enabled = !state.busy && state.configured,
            loading = state.syncing,
            modifier = Modifier.weight(1f),
        )
        SecondaryButton(
            text = "连接信息",
            onClick = { viewModel.showConnectionInfo(true) },
            enabled = !state.busy,
            modifier = Modifier.weight(1f),
        )
    }
    SettingGroup(applyHorizontalInset = false) {
        SwitchRow(
            title = "自动同步",
            checked = state.autoSync,
            onCheckedChange = viewModel::setAutoSync,
            position = SettingGroupPosition.Alone,
        )
    }
    SettingGroup(applyHorizontalInset = false) {
        SettingRow(
            title = "重新生成应用码",
            onClick = viewModel::askRegenerate,
            position = SettingGroupPosition.First,
        )
        SettingRow(
            title = "在网页管理账号",
            onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, OfficialWebDav.SITE.toUri()))
            },
            position = SettingGroupPosition.Middle,
        )
        SettingRow(
            title = "退出官方同步",
            onClick = viewModel::askDisconnect,
            position = SettingGroupPosition.Last,
        )
    }
    OfficialPrivacyNote()
}

@Composable
private fun OfficialAuth(state: WebDavUiState, viewModel: WebDavViewModel) {
    val pages = buildList {
        add(OfficialAuthPage.LOGIN)
        if (state.registrationOpen) add(OfficialAuthPage.REGISTER)
        add(OfficialAuthPage.RESET)
    }
    val labels = pages.map {
        when (it) {
            OfficialAuthPage.LOGIN -> "登录"
            OfficialAuthPage.REGISTER -> "注册"
            OfficialAuthPage.RESET -> "找回密码"
        }
    }
    ChipRow(
        options = labels,
        selectedIndex = pages.indexOf(state.officialAuthPage).coerceAtLeast(0),
        onSelect = { viewModel.setOfficialAuthPage(pages[it]) },
    )
    when (state.officialAuthPage) {
        OfficialAuthPage.LOGIN -> OfficialLoginForm(state, viewModel)
        OfficialAuthPage.REGISTER -> OfficialRegisterForm(state, viewModel)
        OfficialAuthPage.RESET -> OfficialResetForm(state, viewModel)
    }
    OfficialPrivacyNote()
}

@Composable
private fun OfficialLoginForm(state: WebDavUiState, viewModel: WebDavViewModel) {
    ChipRow(
        options = listOf("密码", "验证码"),
        selectedIndex = if (state.officialLoginMode == OfficialLoginMode.PASSWORD) 0 else 1,
        onSelect = {
            viewModel.setOfficialLoginMode(if (it == 0) OfficialLoginMode.PASSWORD else OfficialLoginMode.CODE)
        },
    )
    if (state.officialLoginMode == OfficialLoginMode.PASSWORD) {
        OutlinedTextField(
            value = state.login,
            onValueChange = viewModel::setLogin,
            label = { Text("用户名或邮箱") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        SecretField(
            value = state.accountPassword,
            onValueChange = viewModel::setAccountPassword,
            label = "登录密码",
        )
    } else {
        OutlinedTextField(
            value = state.email,
            onValueChange = viewModel::setEmail,
            label = { Text("邮箱") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )
        CodeRow(state, viewModel)
    }
    PrimaryButton(
        text = "登录并开启同步",
        onClick = viewModel::submitOfficial,
        enabled = !state.busy,
        loading = state.testing,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun OfficialRegisterForm(state: WebDavUiState, viewModel: WebDavViewModel) {
    OutlinedTextField(
        value = state.username,
        onValueChange = viewModel::setUsername,
        label = { Text("用户名") },
        placeholder = { Text("字母开头，3-32 位") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = state.email,
        onValueChange = viewModel::setEmail,
        label = { Text("邮箱") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        modifier = Modifier.fillMaxWidth(),
    )
    SecretField(
        value = state.accountPassword,
        onValueChange = viewModel::setAccountPassword,
        label = "登录密码",
    )
    CodeRow(state, viewModel)
    PrimaryButton(
        text = "注册并开启同步",
        onClick = viewModel::submitOfficial,
        enabled = !state.busy,
        loading = state.testing,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun OfficialResetForm(state: WebDavUiState, viewModel: WebDavViewModel) {
    OutlinedTextField(
        value = state.email,
        onValueChange = viewModel::setEmail,
        label = { Text("邮箱") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        modifier = Modifier.fillMaxWidth(),
    )
    CodeRow(state, viewModel)
    SecretField(
        value = state.accountPassword,
        onValueChange = viewModel::setAccountPassword,
        label = "新登录密码",
    )
    PrimaryButton(
        text = "重置并开启同步",
        onClick = viewModel::submitOfficial,
        enabled = !state.busy,
        loading = state.testing,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun CustomSection(state: WebDavUiState, viewModel: WebDavViewModel) {
    OutlinedTextField(
        value = state.url,
        onValueChange = viewModel::setUrl,
        label = { Text("服务器地址") },
        placeholder = { Text("https://dav.jianguoyun.com/dav/") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
    )
    if (state.url.isNotBlank() && !state.url.trim().startsWith("https://", ignoreCase = true)) {
        Text(
            "该地址不是 HTTPS，账号与密码将以明文在网络上传输。公网请务必改用 https://",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    OutlinedTextField(
        value = state.davUsername,
        onValueChange = viewModel::setDavUsername,
        label = { Text("账号") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    SecretField(
        value = state.davPassword,
        onValueChange = viewModel::setDavPassword,
        label = "密码 / 应用密码",
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
    SettingGroup(applyHorizontalInset = false) {
        SwitchRow(
            title = "自动同步",
            checked = state.autoSync,
            onCheckedChange = viewModel::setAutoSync,
            position = SettingGroupPosition.Alone,
        )
    }
}

@Composable
private fun SyncFooter(state: WebDavUiState) {
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
            "冲突按“新者胜”合并：书与书源逐条比时间戳，设置整块比。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun OfficialPrivacyNote() {
    Text(
        "每位用户独立存储空间，互不可见。传输使用 HTTPS，凭据保存在本机。\n" +
            "备份文件尚未端到端加密。书籍正文与本地文件不会上传。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun CodeRow(state: WebDavUiState, viewModel: WebDavViewModel) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(Dimens.gapS),
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = state.code,
            onValueChange = viewModel::setCode,
            label = { Text("验证码") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
        SecondaryButton(
            text = if (state.codeWait > 0) "${state.codeWait}s" else "发送验证码",
            onClick = viewModel::sendCode,
            enabled = !state.busy && state.codeWait == 0,
            modifier = Modifier.padding(top = Dimens.gapS),
        )
    }
}

@Composable
private fun SecretField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            AppIconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = if (visible) "隐藏密码" else "显示密码",
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ConnectionInfoDialog(state: WebDavUiState, onDismiss: () -> Unit) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    suspend fun copy(label: String, value: String) {
        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(label, value)))
    }
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = "WebDAV 连接信息",
        text = "网页版与其他设备填同一套即可。密码是应用码，不能用登录密码。",
        confirmText = "关闭",
        dismissText = null,
        onConfirm = onDismiss,
    ) {
        CopyRow("地址", state.url.ifBlank { state.officialDavUrl }) { scope.launch { copy("地址", it) } }
        CopyRow("用户名", state.davUsername) { scope.launch { copy("用户名", it) } }
        CopyRow("应用码", state.davPassword) { scope.launch { copy("应用码", it) } }
    }
}

@Composable
private fun CopyRow(label: String, value: String, onCopy: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.gapXS)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(
            value.ifBlank { "—" },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        SecondaryButton(
            text = "复制$label",
            onClick = { onCopy(value) },
            enabled = value.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
