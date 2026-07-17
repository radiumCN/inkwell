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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import com.radium.inkwell.ui.components.AppSnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.data.prefs.AppPrefs
import com.radium.inkwell.data.repo.ChapterContentCache
import com.radium.inkwell.ui.components.OptionPickerSheet
import com.radium.inkwell.ui.components.PickerOption
import com.radium.inkwell.ui.components.SectionHeader
import com.radium.inkwell.ui.components.SettingRow
import com.radium.inkwell.ui.components.SwitchRow
import com.radium.inkwell.util.AppIcon
import com.radium.inkwell.util.AppIconManager
import com.radium.inkwell.update.CheckResult
import com.radium.inkwell.update.UpdateChannel
import com.radium.inkwell.update.UpdateChecker
import com.radium.inkwell.update.UpdateInfo
import com.radium.inkwell.update.UpdateManager
import com.radium.inkwell.update.UpdateSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenFeedback: () -> Unit,
    onOpenWebDav: () -> Unit,
    onOpenTheme: () -> Unit,
    onOpenSources: () -> Unit,
    onOpenReplaceRules: () -> Unit,
    onOpenRss: () -> Unit,
) {
    val context = LocalContext.current
    val updateManager = koinInject<UpdateManager>()
    val appPrefs = koinInject<AppPrefs>()
    val cache = koinInject<ChapterContentCache>()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val currentVersion = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
    }
    val channel by appPrefs.updateChannel.collectAsState(initial = UpdateChannel.STABLE)
    val source by appPrefs.updateSource.collectAsState(initial = UpdateSource.GITHUB)
    val checkAuthor by appPrefs.changeSourceCheckAuthor.collectAsState(initial = true)
    val autoChangeSource by appPrefs.autoChangeSource.collectAsState(initial = true)
    val exploreEnabled by appPrefs.exploreEnabled.collectAsState(initial = true)

    var checking by remember { mutableStateOf(false) }
    var update by remember { mutableStateOf<UpdateInfo?>(null) }
    var showChannelPicker by remember { mutableStateOf(false) }
    var showSourcePicker by remember { mutableStateOf(false) }
    // 中转服务器源的应用内下载进度
    var downloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    // 以 PackageManager 的组件状态为准，不另存偏好 —— 两边一旦不同步，界面就会勾着一个
    // 桌面上根本没显示的图标
    var appIcon by remember { mutableStateOf(AppIconManager.current(context)) }
    var showIconPicker by remember { mutableStateOf(false) }
    var confirmClearCache by remember { mutableStateOf(false) }
    var cacheBytes by remember { mutableLongStateOf(-1L) }

    // 磁盘遍历不能放主线程；-1 表示还没算出来
    suspend fun refreshCacheSize() {
        cacheBytes = withContext(Dispatchers.IO) { cache.sizeBytes() }
    }
    LaunchedEffect(Unit) { refreshCacheSize() }

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
                title = { Text("设置") },
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
            SectionHeader("外观")
            SettingRow(
                title = "主题外观",
                subtitle = "日间/夜间模式与自定义配色",
                onClick = onOpenTheme,
            )
            SettingRow(
                title = "应用图标",
                subtitle = "${appIcon.label} · ${appIcon.description}",
                onClick = { showIconPicker = true },
            )

            SwitchRow(
                title = "显示「发现」入口",
                subtitle = if (exploreEnabled) {
                    "书架顶栏显示发现按钮，浏览书源的分类榜单"
                } else {
                    "已隐藏。仍可从搜索找书"
                },
                checked = exploreEnabled,
                onCheckedChange = { on -> scope.launch { appPrefs.setExploreEnabled(on) } },
            )

            SectionHeader("书源")
            SettingRow(
                title = "书源管理",
                subtitle = "导入、启用、校验与删除",
                onClick = onOpenSources,
            )
            SettingRow(
                title = "净化替换规则",
                subtitle = "删掉正文里的广告、水印与防盗段落",
                onClick = onOpenReplaceRules,
            )
            SwitchRow(
                title = "自动换源",
                subtitle = if (autoChangeSource) {
                    "正文读不出来或超过 15 秒没回来时，自动找一个能读出这一章的源。换完会提示，可撤销"
                } else {
                    "已关闭。读不出来时停在错误页，由你手动换源"
                },
                checked = autoChangeSource,
                onCheckedChange = { on -> scope.launch { appPrefs.setAutoChangeSource(on) } },
            )
            SwitchRow(
                title = "换源时匹配作者",
                subtitle = if (checkAuthor) {
                    "只换到同一作者的书。书源返回的作者常带前缀或干脆为空，卡太死会换不到源"
                } else {
                    "只认书名。可能换到同名不同作者的书"
                },
                checked = checkAuthor,
                onCheckedChange = { on -> scope.launch { appPrefs.setChangeSourceCheckAuthor(on) } },
            )

            SectionHeader("订阅")
            SettingRow(
                title = "订阅源",
                subtitle = "RSS / Atom 订阅；粘个地址就能订阅",
                onClick = onOpenRss,
            )

            // 这里从前有一整块「隐私」分区，写着「查看隐藏书籍需要验证 / 长按书架标题后先验证指纹」。
            //
            // 它把秘密完整地说了两遍：既宣告「这个 App 能藏书」，又把暗号（长按书架标题）
            // 印在副标题里。任何人翻一下设置就全知道了 —— 隐藏功能等于不存在。
            //
            // 一个只有你知道的东西，它的开关也只能长在**你已经进去之后**的地方。
            // 所以这个开关搬进了隐藏区内部（展开隐藏的书之后才看得到）。
            // 设置页里现在一个字都不提隐藏书籍，翻起来就是个普通阅读器。

            SectionHeader("存储")
            SettingRow(
                title = "清除正文缓存",
                subtitle = when {
                    cacheBytes < 0 -> "正在统计…"
                    cacheBytes == 0L -> "暂无缓存"
                    else -> "已缓存 ${formatSize(cacheBytes)}；清除不影响书架与阅读进度"
                },
                onClick = { if (cacheBytes > 0) confirmClearCache = true },
            )

            SectionHeader("备份")
            SettingRow(
                title = "WebDAV 备份同步",
                subtitle = "书架、进度与书源的多设备同步",
                onClick = onOpenWebDav,
            )

            SectionHeader("更新")
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

            SectionHeader("关于")
            SettingRow(
                title = "意见反馈",
                subtitle = "问题与建议直接提给开发者",
                onClick = onOpenFeedback,
            )
            SettingRow(title = "版本", subtitle = "v$currentVersion")
            SettingRow(title = "开源许可", subtitle = "MIT License")
            SettingRow(
                title = "开源地址",
                subtitle = UpdateChecker.REPO_URL,
                onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(UpdateChecker.REPO_URL)))
                },
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

    if (showIconPicker) {
        AppIconSheet(
            selected = appIcon,
            onSelect = { picked ->
                showIconPicker = false
                if (picked != appIcon) {
                    AppIconManager.apply(context, picked)
                    appIcon = picked
                    scope.launch {
                        snackbar.showSnackbar("已换成「${picked.label}」，桌面图标稍后刷新")
                    }
                }
            },
            onDismiss = { showIconPicker = false },
        )
    }

    if (confirmClearCache) {
        AlertDialog(
            onDismissRequest = { confirmClearCache = false },
            title = { Text("清除正文缓存") },
            text = { Text("将删除已下载的 ${formatSize(cacheBytes)} 章节正文。书架、阅读进度和书源都不受影响，下次阅读会重新联网抓取。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClearCache = false
                    scope.launch {
                        withContext(Dispatchers.IO) { cache.clearAll() }
                        refreshCacheSize()
                        snackbar.showSnackbar("已清除正文缓存")
                    }
                }) { Text("清除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearCache = false }) { Text("取消") }
            },
        )
    }

    update?.let { info ->
        val direct = info.directInstall
        AlertDialog(
            // 下载中不允许点外面关掉
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
                    // 中转服务器源：应用内下载 → 校验 sha256 → 拉起系统安装器
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
                    // GitHub 源：浏览器下载 APK / 查看 Release
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

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
