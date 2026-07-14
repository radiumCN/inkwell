package com.radium.inkwell.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.radium.inkwell.data.prefs.AppPrefs
import com.radium.inkwell.data.repo.ChapterContentCache
import com.radium.inkwell.ui.components.OptionPickerSheet
import com.radium.inkwell.ui.components.PickerOption
import com.radium.inkwell.ui.components.SectionHeader
import com.radium.inkwell.ui.components.SettingRow
import com.radium.inkwell.ui.components.SwitchRow
import com.radium.inkwell.util.BiometricAuth
import com.radium.inkwell.update.UpdateChannel
import com.radium.inkwell.update.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenWebDav: () -> Unit,
    onOpenTheme: () -> Unit,
    onOpenSources: () -> Unit,
    onOpenReplaceRules: () -> Unit,
    onOpenRss: () -> Unit,
) {
    val context = LocalContext.current
    val updateChecker = koinInject<UpdateChecker>()
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
    val checkAuthor by appPrefs.changeSourceCheckAuthor.collectAsState(initial = true)
    val textSelection by appPrefs.textSelectionEnabled.collectAsState(initial = true)
    val exploreEnabled by appPrefs.exploreEnabled.collectAsState(initial = true)
    val hiddenRequireAuth by appPrefs.hiddenRequireAuth.collectAsState(initial = false)
    val biometricAvailable = remember { BiometricAuth.isAvailable(context) }

    var checking by remember { mutableStateOf(false) }
    var update by remember { mutableStateOf<UpdateChecker.UpdateInfo?>(null) }
    var showChannelPicker by remember { mutableStateOf(false) }
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
                title = "阅读页长按选字",
                subtitle = if (textSelection) {
                    "长按正文选中文字，可复制或建一条只对这本书生效的净化规则"
                } else {
                    "已关闭。长按不再选字（翻页时手指停顿久了就不会误触选中）"
                },
                checked = textSelection,
                onCheckedChange = { on -> scope.launch { appPrefs.setTextSelectionEnabled(on) } },
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

            SectionHeader("隐私")
            SwitchRow(
                title = "查看隐藏书籍需要验证",
                subtitle = when {
                    !biometricAvailable ->
                        "这台设备还没设置指纹/面容或锁屏密码，无法开启"
                    hiddenRequireAuth ->
                        "展开隐藏的书前先验证指纹/面容（也可用设备密码）"
                    else ->
                        "已关闭。任何人点开「⋮ → 显示隐藏的书」都能看到"
                },
                checked = hiddenRequireAuth && biometricAvailable,
                onCheckedChange = { on ->
                    // 设备没有任何锁，开了就是把自己锁在外面 —— 这个锁没有找回途径
                    if (!biometricAvailable) return@SwitchRow
                    scope.launch { appPrefs.setHiddenRequireAuth(on) }
                },
            )

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
                title = "更新渠道",
                subtitle = channel.label +
                    if (channel == UpdateChannel.BETA) "（包含预发布版本，可能不稳定）" else "",
                onClick = { showChannelPicker = true },
            )

            SectionHeader("关于")
            SettingRow(title = "版本", subtitle = "v$currentVersion")
            SettingRow(title = "开源许可", subtitle = "MIT License")
            SettingRow(
                title = "开源地址",
                subtitle = UpdateChecker.REPO_URL,
                onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(UpdateChecker.REPO_URL)))
                },
            )
            Column(Modifier.padding(bottom = 24.dp)) {}
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

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
