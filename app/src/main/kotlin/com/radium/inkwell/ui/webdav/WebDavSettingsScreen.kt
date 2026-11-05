package com.radium.inkwell.ui.webdav

import com.radium.inkwell.ui.components.horizontalOnly
import com.radium.inkwell.ui.components.pageScroll
import com.radium.inkwell.ui.components.SectionHeader
import com.radium.inkwell.ui.components.SettingBlock
import com.radium.inkwell.ui.components.SettingFootnote
import com.radium.inkwell.ui.components.SegmentedChoice
import android.content.ClipData
import androidx.compose.foundation.clickable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import com.radium.inkwell.ui.components.formatRelativeTime
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radium.inkwell.data.net.OfficialWebDav
import com.radium.inkwell.data.prefs.WebDavProvider
import com.radium.inkwell.ui.components.AppAlertDialog
import com.radium.inkwell.ui.components.AppIconButton
import com.radium.inkwell.ui.components.AppSnackbarHost
import com.radium.inkwell.ui.components.AppSurface
import com.radium.inkwell.ui.components.AppTopBar
import com.radium.inkwell.ui.components.CollectMessages
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.ui.components.NO_BROWSER_MESSAGE
import com.radium.inkwell.ui.components.PrimaryButton
import com.radium.inkwell.ui.components.SecondaryButton
import com.radium.inkwell.ui.components.SettingGroup
import com.radium.inkwell.ui.components.SettingGroupPosition
import com.radium.inkwell.ui.components.SettingRow
import com.radium.inkwell.ui.components.SwitchRow
import com.radium.inkwell.ui.components.rememberExternalLinkOpener
import com.radium.inkwell.ui.components.rememberAppTopBarScroll
import com.radium.inkwell.ui.components.settingsPageColor
import com.radium.inkwell.ui.components.topBarScroll
import com.radium.inkwell.util.LocalNetworkAccess
import com.radium.inkwell.ui.theme.AppShapes
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import androidx.compose.ui.Alignment
import com.radium.inkwell.ui.components.AppFormField
import top.yukonga.miuix.kmp.basic.Icon
import com.radium.inkwell.ui.components.AppScaffold
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.icon.extended.Copy
import top.yukonga.miuix.kmp.icon.extended.Hide
import top.yukonga.miuix.kmp.icon.extended.Show
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun WebDavSettingsScreen(onBack: () -> Unit, viewModel: WebDavViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    CollectMessages(viewModel.messages, snackbar)

    val localNetworkPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> viewModel.onLocalNetworkPermissionResult(granted) }

    val pageColor = settingsPageColor()
    val topBarScroll = rememberAppTopBarScroll()
    AppScaffold(
        modifier = Modifier.topBarScroll(topBarScroll),
        containerColor = pageColor,
        topBar = { AppTopBar("WebDAV 同步", topBarScroll, onBack = onBack, containerColor = pageColor) },
        snackbarHost = { AppSnackbarHost(snackbar) },
    ) { padding ->
        // 配置还没读回来：先空着，别先画一遍「自备 WebDAV」再跳回「官方同步」
        if (!state.loaded) return@AppScaffold
        Column(
            Modifier
                .fillMaxSize()
                // 顶栏、导航栏的高度都垫在滚动内容首尾：内容能滚到玻璃顶栏和手势条下面（见 AppScaffold）
                .padding(padding.horizontalOnly())
                // padding 里已含导航栏高度：先把这份 inset 标成已消费，下面的 imePadding 才不会把导航栏那截再算一遍（键盘上方多一道空隙）
                .consumeWindowInsets(padding)
                .imePadding()
                .pageScroll()
                .verticalScroll(rememberScrollState(), overscrollEffect = null)
                .padding(
                    top = padding.calculateTopPadding() + Dimens.gapS,
                    bottom = Dimens.gapXL + padding.calculateBottomPadding(),
                ),
        ) {
            // 与别的设置页同一副骨架：分组标题 + 卡片，控件都装在卡里（见 SettingBlock）
            SectionHeader("同步方式")
            SettingGroup {
                SettingBlock {
                    SegmentedChoice(
                        options = listOf("官方同步", "自备 WebDAV"),
                        selectedIndex = if (state.provider == WebDavProvider.OFFICIAL) 0 else 1,
                        onSelect = {
                            viewModel.setProvider(if (it == 0) WebDavProvider.OFFICIAL else WebDavProvider.CUSTOM)
                        },
                    )
                }
            }
            if (state.provider == WebDavProvider.OFFICIAL) {
                OfficialSection(state, viewModel)
            } else {
                CustomSection(state, viewModel)
            }
            SyncFooter()
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
    if (state.askLocalNetwork) {
        // 系统授权框只写「查找、连接附近设备」，和 WebDAV 看不出关系 —— 不先讲一句，多数人会点拒绝
        AppAlertDialog(
            onDismissRequest = viewModel::dismissLocalNetwork,
            title = "需要「附近设备」权限",
            text = "这个地址在局域网里（比如家里的 NAS）。从 Android 17 起，连接局域网里的设备要先授予" +
                "「附近设备」权限，否则连接会一直超时。\n\n只用于连接你填写的这个 WebDAV 地址。",
            confirmText = "去授权",
            onConfirm = {
                viewModel.confirmLocalNetwork()
                localNetworkPermission.launch(LocalNetworkAccess.PERMISSION)
            },
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
    // 「在网页管理账号」：先把要去的地址亮出来问一句（见 rememberExternalLinkOpener）
    val links = rememberExternalLinkOpener { viewModel.messages.emit(NO_BROWSER_MESSAGE) }
    SectionHeader("账号")
    // 邮箱做主行、连接状态做副行，下面一个通栏「立即同步」。从前是一句平铺的小字「已连接官方同步 · 邮箱」，
    // 「连接信息」很少用，却和「立即同步」平分一排大按钮 —— 它挪进了下面的「管理」
    SettingGroup {
        SettingRow(
            title = state.officialEmail.ifBlank { "官方同步" },
            subtitle = if (state.officialEmail.isBlank()) "已连接" else "官方同步 · 已连接",
            position = SettingGroupPosition.First,
        )
        PrimaryButton(
            text = "立即同步",
            onClick = viewModel::syncNow,
            enabled = !state.busy && state.configured,
            loading = state.syncing,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = Dimens.listHorizontal, end = Dimens.listHorizontal, bottom = Dimens.listHorizontal),
        )
    }
    SyncStatus(state)
    SettingGroup {
        SwitchRow(
            title = "自动同步",
            checked = state.autoSync,
            onCheckedChange = viewModel::setAutoSync,
            position = SettingGroupPosition.Alone,
        )
    }
    SectionHeader("管理")
    SettingGroup {
        // 两行挨着：都是给其他设备用的那一套码
        SettingRow(
            title = "连接信息",
            subtitle = "网页版、其他设备登录时要填的地址和应用码",
            onClick = { viewModel.showConnectionInfo(true) },
            position = SettingGroupPosition.First,
        )
        SettingRow(
            title = "重新生成应用码",
            subtitle = "旧码立刻失效，其他设备要重新登录",
            onClick = viewModel::askRegenerate,
            position = SettingGroupPosition.Middle,
        )
        SettingRow(
            title = "在网页管理账号",
            onClick = { links.open(OfficialWebDav.SITE) },
            position = SettingGroupPosition.Last,
        )
    }
    // 退出单独一张卡、红字、不带 ›（点了先确认），别和普通入口长一个样
    SettingGroup {
        SettingRow(
            title = "退出官方同步",
            onClick = viewModel::askDisconnect,
            position = SettingGroupPosition.Alone,
            destructive = true,
        )
    }
    OfficialPrivacyNote()
}

@Composable
private fun OfficialAuth(state: WebDavUiState, viewModel: WebDavViewModel) {
    SectionHeader("登录")
    SettingGroup {
        SettingBlock {
            Text(
                if (state.registrationOpen) {
                    "用邮箱收取验证码即可。没有账号会自动开通。"
                } else {
                    "暂未开放新账号，已有账号仍可用邮箱验证码登录。"
                },
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            AppFormField(
                value = state.email,
                onValueChange = viewModel::setEmail,
                label = "邮箱",
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            )
            CodeRow(state, viewModel)
            PrimaryButton(
                text = "验证并开启同步",
                onClick = viewModel::submitOfficial,
                enabled = !state.busy,
                loading = state.testing,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    OfficialPrivacyNote()
}

@Composable
private fun CustomSection(state: WebDavUiState, viewModel: WebDavViewModel) {
    SectionHeader("服务器")
    SettingGroup {
        SettingBlock {
            // 例子放在框里当提示，「服务器地址」退到框上方做标题（AppFormField 有 placeholder 时的排法）：
            // 一个现成的地址比一行「地址」好懂
            AppFormField(
                value = state.url,
                onValueChange = viewModel::setUrl,
                label = "服务器地址",
                placeholder = "https://dav.jianguoyun.com/dav/",
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            if (state.url.isNotBlank() && !state.url.trim().startsWith("https://", ignoreCase = true)) {
                Text(
                    "该地址不是 HTTPS，账号与密码将以明文在网络上传输。公网请务必改用 https://",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.error,
                )
            }
            AppFormField(
                value = state.davUsername,
                onValueChange = viewModel::setDavUsername,
                label = "账号",
                singleLine = true,
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
        }
    }
    SyncStatus(state)
    SettingGroup {
        SwitchRow(
            title = "自动同步",
            checked = state.autoSync,
            onCheckedChange = viewModel::setAutoSync,
            position = SettingGroupPosition.Alone,
        )
    }
}

/**
 * 上次同步，紧贴在「立即同步」那张卡下面：点完立刻看得到成了没有。从前沉在页面最底下，得滑到底才知道。
 * 相对时间每分钟走一次，开着页面「刚刚」也会变成「3 分钟前」。
 */
@Composable
private fun SyncStatus(state: WebDavUiState) {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(SYNC_TIME_TICK_MS)
            value = System.currentTimeMillis()
        }
    }
    // 失败比成功新才说：之后成功过就不再翻旧账。重试就是上面那个「立即同步」
    if (state.lastSyncErrorAt > state.lastSyncAt) {
        SettingFootnote(
            "上次同步失败（${formatRelativeTime(state.lastSyncErrorAt, now)}）：${state.lastSyncError}",
            color = MiuixTheme.colorScheme.error,
        )
    }
    if (state.lastSyncAt > 0) {
        SettingFootnote("上次同步：${syncTimeLabel(state.lastSyncAt, now)}")
    }
}

/**
 * 「3 分钟前（14:32）」「昨天（21:05）」：一周内相对时间 + 钟点；再早的直接写日期时间
 * （相对时间那时已是「11 月 3 日」，再括一个日期就重复了）。
 */
internal fun syncTimeLabel(at: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val time = Instant.ofEpochMilli(at).atZone(zone)
    val days = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toEpochDay() - time.toLocalDate().toEpochDay()
    return if (days in 0 until RELATIVE_SYNC_DAYS) {
        "${formatRelativeTime(at, now, zone)}（${time.format(CLOCK)}）"
    } else {
        time.format(FULL_TIME)
    }
}

private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")
private val FULL_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
private const val RELATIVE_SYNC_DAYS = 7
private const val SYNC_TIME_TICK_MS = 60_000L

@Composable
private fun SyncFooter() {
    SettingFootnote(
        "会同步：书架、阅读进度、阅读时长、书源、订阅源、净化规则，以及阅读与应用设置（含排版方案）。\n" +
            "不会同步：本地书的文件、导入的字体和已缓存的章节。换设备后，本地书与字体需要重新导入。\n" +
            "两边都改过时，以较新的为准。",
    )
}

@Composable
private fun OfficialPrivacyNote() {
    SettingFootnote("每位用户独立空间，互不可见。传输走 HTTPS，登录凭据只保存在本机。")
}

@Composable
private fun CodeRow(state: WebDavUiState, viewModel: WebDavViewModel) {
    // 按钮与输入框垂直居中：Miuix 的输入框没有 M3 描边框那截浮在上沿的 label，不必再给按钮垫顶
    Row(
        horizontalArrangement = Arrangement.spacedBy(Dimens.gapS),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        AppFormField(
            value = state.code,
            onValueChange = viewModel::setCode,
            label = "验证码",
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
        SecondaryButton(
            text = if (state.codeWait > 0) "${state.codeWait}s" else "发送验证码",
            onClick = viewModel::sendCode,
            enabled = !state.busy && state.codeWait == 0,
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
    AppFormField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            AppIconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) MiuixIcons.Hide else MiuixIcons.Show,
                    contentDescription = if (visible) "隐藏密码" else "显示密码",
                )
            }
        },
    )
}

@Composable
private fun ConnectionInfoDialog(state: WebDavUiState, onDismiss: () -> Unit) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    // 反馈必须画在弹层里：页面 Snackbar 被对话框挡住，点了像没反应。就地画在复制的那一行上，过一会儿恢复
    var copiedLabel by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(copiedLabel) {
        if (copiedLabel != null) {
            delay(COPIED_FEEDBACK_MS)
            copiedLabel = null
        }
    }
    suspend fun copy(label: String, value: String) {
        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(label, value)))
        copiedLabel = label
    }
    // 一项一行（灰字标题 + 值、行尾复制图标），三项装进一块浅灰面板，一屏放得下。
    // 从前每项是「蓝色小标题 + 值 + 一个通栏灰按钮」，三项摞起来超出弹窗，「复制应用码」被裁掉半截
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = "WebDAV 连接信息",
        text = "网页版与其他设备填同一套即可。密码是应用码，不能用登录密码。",
        confirmText = "关闭",
        dismissText = null,
        onConfirm = onDismiss,
    ) {
        // 弹窗底是 background，面板用页面底色，深 / 暗一档（同更新弹窗的说明面板）
        AppSurface(
            cornerRadius = AppShapes.radiusLarge,
            color = MiuixTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column {
                CopyRow("地址", state.url.ifBlank { state.officialDavUrl }, copiedLabel == "地址") {
                    scope.launch { copy("地址", it) }
                }
                CopyRow("用户名", state.davUsername, copiedLabel == "用户名") {
                    scope.launch { copy("用户名", it) }
                }
                CopyRow("应用码", state.davPassword, copiedLabel == "应用码", secret = true) {
                    scope.launch { copy("应用码", it) }
                }
            }
        }
    }
}

/**
 * 连接信息的一项。[secret] 的值（应用码）默认遮住中间，点值那一块显示全部 —— 截一张图就带出去的东西，
 * 先别明晃晃摆着；复制照常复制完整的。值用等宽字：应用码里的 0 / O、1 / l 才分得清。
 */
@Composable
private fun CopyRow(
    label: String,
    value: String,
    copied: Boolean,
    secret: Boolean = false,
    onCopy: (String) -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    val canReveal = secret && value.isNotBlank()
    var revealed by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(end = Dimens.gapXS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier
                .weight(1f)
                .heightIn(min = Dimens.listItemMinHeight)
                .then(
                    if (canReveal) {
                        Modifier.clickable(
                            role = Role.Button,
                            onClickLabel = if (revealed) "隐藏$label" else "显示$label",
                        ) { revealed = !revealed }
                    } else {
                        Modifier
                    },
                )
                .padding(start = Dimens.listHorizontal, top = Dimens.gapS, bottom = Dimens.gapS),
            verticalArrangement = Arrangement.spacedBy(Dimens.gapXS, Alignment.CenterVertically),
        ) {
            Text(label, style = MiuixTheme.textStyles.footnote1, color = scheme.onSurfaceVariantSummary)
            if (copied) {
                Text("已复制", style = MiuixTheme.textStyles.body2, color = scheme.primary)
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Dimens.gapXS),
                ) {
                    Text(
                        when {
                            value.isBlank() -> "—"
                            canReveal && !revealed -> maskSecret(value)
                            else -> value
                        },
                        style = MiuixTheme.textStyles.body2.copy(fontFamily = FontFamily.Monospace),
                        color = scheme.onSurface,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (canReveal) {
                        // 点的是整块（上面的 clickable），图标只是个提示
                        Icon(
                            if (revealed) MiuixIcons.Hide else MiuixIcons.Show,
                            contentDescription = null,
                            tint = scheme.onSurfaceVariantSummary,
                            modifier = Modifier.size(Dimens.iconSm),
                        )
                    }
                }
            }
        }
        AppIconButton(onClick = { onCopy(value) }, enabled = value.isNotBlank()) {
            Icon(
                if (copied) MiuixIcons.Basic.Check else MiuixIcons.Copy,
                contentDescription = if (copied) "已复制$label" else "复制$label",
                tint = if (copied) scheme.primary else scheme.onSurface,
            )
        }
    }
}

/** 「wd_3f9a ············ 4e81」：留头尾认得出是哪一个，中间遮住。短的只留头尾各两个字 */
internal fun maskSecret(value: String): String {
    val keep = if (value.length >= SECRET_LONG) SECRET_HEAD to SECRET_TAIL else 2 to 2
    if (value.length <= keep.first + keep.second) return MASK.repeat(value.length)
    return "${value.take(keep.first)} ${MASK.repeat(SECRET_DOTS)} ${value.takeLast(keep.second)}"
}

private const val MASK = "•"
private const val SECRET_LONG = 16
private const val SECRET_HEAD = 7
private const val SECRET_TAIL = 4
private const val SECRET_DOTS = 12
private const val COPIED_FEEDBACK_MS = 2_000L
