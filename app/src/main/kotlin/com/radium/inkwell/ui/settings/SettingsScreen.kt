package com.radium.inkwell.ui.settings

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import com.radium.inkwell.ui.components.exceptBottom
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.AutoDelete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.filled.Info
import com.radium.inkwell.data.repo.WebDavRepository
import com.radium.inkwell.data.repo.describeSyncError
import com.radium.inkwell.util.LocalNetworkPermissionMissing
import com.radium.inkwell.ui.stats.summarize
import kotlinx.coroutines.flow.flowOn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radium.inkwell.data.prefs.AppPrefs
import com.radium.inkwell.data.repo.ChapterContentCache
import com.radium.inkwell.data.repo.ReadingStatsRepository
import com.radium.inkwell.data.prefs.WebDavPrefs
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import com.radium.inkwell.ui.components.AppAlertDialog
import com.radium.inkwell.ui.components.AppSnackbarHost
import com.radium.inkwell.ui.components.AppTopBar
import com.radium.inkwell.ui.components.CollectMessages
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.ui.components.MessageBus
import com.radium.inkwell.ui.components.OptionPickerSheet
import com.radium.inkwell.ui.components.PickerOption
import com.radium.inkwell.ui.components.SectionHeader
import com.radium.inkwell.ui.components.SettingGroup
import com.radium.inkwell.ui.components.SettingGroupPosition
import com.radium.inkwell.ui.components.SettingRow
import com.radium.inkwell.ui.components.settingsPageColor
import com.radium.inkwell.ui.components.rememberAppTopBarScroll
import com.radium.inkwell.ui.components.topBarScroll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 设置入口页：高频操作留在本页，细节进二级页。
 *
 * 排列：使用 → 数据 → 维护 → 版本 → 关于。检查更新主页一点即查；
 * 版本号固定页脚，不随列表长短上下漂移。
 *
 * 这里从前有一整块「隐私」分区，写着「查看隐藏书籍需要验证 / 长按书架标题后先验证指纹」。
 * 开关已搬进隐藏区内部；设置页里一个字都不提隐藏书籍。
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenReadingStats: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenReading: () -> Unit,
    onOpenRss: () -> Unit,
    onOpenWebDav: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    val cache = koinInject<ChapterContentCache>()
    val readingStats = koinInject<ReadingStatsRepository>()
    val webDavPrefs = koinInject<WebDavPrefs>()
    val syncConfig by webDavPrefs.config.collectAsStateWithLifecycle(initialValue = null)
    // 概览卡要的今日 / 连续 / 累计。整张表在内存里汇总，行数是「设备 × 天 × 书」，几千行而已
    val readingSummary by remember(readingStats) {
        readingStats.observeAll()
            .map { rows -> summarize(rows, LocalDate.now()) }
            .flowOn(Dispatchers.Default)
    }.collectAsStateWithLifecycle(initialValue = null)
    val webDavRepo = koinInject<WebDavRepository>()
    /** 概览卡上点了「立即同步」、还没回来 */
    var syncing by remember { mutableStateOf(false) }
    val appPrefs = koinInject<AppPrefs>()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    // 提示一律走 MessageBus：emit 不挂起，转圈/「正在检查…」不必陪着提示多挂 4 秒，
    // 新提示也直接替换正在显示的那条
    val messages = remember { MessageBus() }
    CollectMessages(messages, snackbar)
    val updateCheck = rememberUpdateCheckState(messages)
    var showSourcePicker by remember { mutableStateOf(false) }
    var showChannelPicker by remember { mutableStateOf(false) }

    /**
     * 概览卡上的「立即同步」。跑在页面的协程作用域上：离开设置页就取消 —— 与 WebDAV 页的「立即同步」
     * 同一个取舍（那边跟着 ViewModel 走）。取消是安全的，同步本来就要扛住被进程回收（见 WebDavRepository）。
     * 局域网 NAS 缺「附近设备」权限时，授权的说明与系统弹框都在 WebDAV 页，直接把人带过去。
     */
    fun syncNow() {
        if (syncing) return
        syncing = true
        scope.launch {
            val result = webDavRepo.sync()
            syncing = false
            val error = result.exceptionOrNull()
            when {
                error is LocalNetworkPermissionMissing -> {
                    messages.emit(describeSyncError(error))
                    onOpenWebDav()
                }
                error != null -> messages.emit("同步失败：" + describeSyncError(error))
                else -> messages.emit(result.getOrDefault("同步完成"))
            }
        }
    }
    var confirmClearCache by remember { mutableStateOf(false) }
    var showPrunePicker by remember { mutableStateOf(false) }
    var cacheBytes by remember { mutableLongStateOf(-1L) }
    /** 正在删缓存：几千个文件要删一会儿，期间行上说一声、不许再点 */
    var clearingCache by remember { mutableStateOf(false) }
    val pruneDays = appPrefs.cacheAutoPruneDaysState.collectAsStateWithLifecycle().value
        ?: AppPrefs.DEFAULT_CACHE_AUTO_PRUNE_DAYS

    suspend fun refreshCacheSize() {
        cacheBytes = withContext(Dispatchers.IO) { cache.sizeBytes() }
    }
    LaunchedEffect(Unit) { refreshCacheSize() }

    val pageColor = settingsPageColor()
    val topBarScroll = rememberAppTopBarScroll()
    Scaffold(
        modifier = Modifier.topBarScroll(topBarScroll),
        containerColor = pageColor,
        topBar = { AppTopBar("设置", topBarScroll, onBack = onBack, containerColor = pageColor) },
        snackbarHost = { AppSnackbarHost(snackbar) },
    ) { padding ->
        // 版本号从前钉在滚动区外面的页脚：列表在页脚上方就截断了，手势条底下垫的是一块空底色。
        // 现在它是滚动内容的最后一行，整页能滚到手势条下面（见 exceptBottom）。内容不满一屏时仍压在底部：
        // 滚动区最矮也是一整屏高（heightIn(min)），中间那个 weight 撑开 —— Column 在最大高度无界时，
        // 按最小高度分配 weight，所以滚动容器里也撑得开
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .padding(padding.exceptBottom()),
        ) {
            val viewport = maxHeight
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = viewport)
                    .padding(top = Dimens.gapS),
            ) {
                SettingsOverviewCard(
                    reading = readingSummary,
                    sync = syncConfig,
                    syncing = syncing,
                    onOpenStats = onOpenReadingStats,
                    onOpenWebDav = onOpenWebDav,
                    onSyncNow = ::syncNow,
                )

                SectionHeader("阅读与外观")
                SettingGroup {
                    SettingRow(
                        title = "阅读与规则",
                        icon = Icons.AutoMirrored.Filled.MenuBook,
                        onClick = onOpenReading,
                        position = SettingGroupPosition.First,
                    )
                    SettingRow(
                        title = "外观",
                        icon = Icons.Default.Palette,
                        onClick = onOpenAppearance,
                        position = SettingGroupPosition.Middle,
                    )
                    SettingRow(
                        title = "订阅源",
                        icon = Icons.Default.RssFeed,
                        onClick = onOpenRss,
                        position = SettingGroupPosition.Last,
                    )
                }

                SectionHeader("同步与存储")
                SettingGroup {
                    // 同步状态与「立即同步」在顶上的概览卡里，这一行只是进 WebDAV 页的入口
                    SettingRow(
                        title = "WebDAV 备份同步",
                        icon = Icons.Default.CloudSync,
                        onClick = onOpenWebDav,
                        position = SettingGroupPosition.First,
                    )
                    SettingRow(
                        title = "自动清理已读缓存",
                        icon = Icons.Default.AutoDelete,
                        value = cacheAutoPruneLabel(pruneDays),
                        onClick = { showPrunePicker = true },
                        position = SettingGroupPosition.Middle,
                    )
                    SettingRow(
                        title = "清除正文缓存",
                        icon = Icons.Default.DeleteSweep,
                        value = when {
                            clearingCache -> "正在清除…"
                            cacheBytes < 0 -> "正在统计…"
                            cacheBytes == 0L -> "暂无缓存"
                            else -> formatSize(cacheBytes)
                        },
                        // 没东西可清、或正在清时不给点：从前「暂无缓存」照样能点，点了什么都不发生
                        onClick = if (cacheBytes > 0 && !clearingCache) ({ confirmClearCache = true }) else null,
                        position = SettingGroupPosition.Last,
                    )
                }

                SectionHeader("更新")
                SettingGroup {
                    SettingRow(
                        title = "检查更新",
                        icon = Icons.Default.SystemUpdate,
                        value = if (updateCheck.checking) "正在检查…" else null,
                        onClick = updateCheck.check,
                        position = SettingGroupPosition.First,
                    )
                    // 更新源 / 渠道从前单独占一个二级页（整页就这两行），现在直接摆在这里，点了弹选择面板
                    SettingRow(
                        title = "更新源",
                        icon = Icons.Default.Public,
                        value = updateCheck.source.label,
                        onClick = { showSourcePicker = true },
                        position = SettingGroupPosition.Middle,
                    )
                    SettingRow(
                        title = "更新渠道",
                        icon = Icons.AutoMirrored.Filled.AltRoute,
                        value = updateCheck.channel.label,
                        onClick = { showChannelPicker = true },
                        position = SettingGroupPosition.Last,
                    )
                }

                SettingGroup {
                    SettingRow(
                        title = "关于",
                        icon = Icons.Default.Info,
                        onClick = onOpenAbout,
                        position = SettingGroupPosition.Alone,
                    )
                }

                Spacer(Modifier.weight(1f))
                Text(
                    "Inkwell  v${updateCheck.currentVersion}",
                    Modifier
                        .fillMaxWidth()
                        .padding(
                            start = Dimens.rowHorizontal,
                            end = Dimens.rowHorizontal,
                            top = Dimens.gapXL,
                            bottom = Dimens.gapXL + padding.calculateBottomPadding(),
                        ),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }

    updateCheck.UpdateDialog()

    if (showSourcePicker) {
        UpdateSourcePicker(
            current = updateCheck.source,
            onPick = { picked ->
                showSourcePicker = false
                scope.launch { appPrefs.setUpdateSource(picked) }
            },
            onDismiss = { showSourcePicker = false },
        )
    }

    if (showChannelPicker) {
        UpdateChannelPicker(
            current = updateCheck.channel,
            onPick = { picked ->
                showChannelPicker = false
                scope.launch { appPrefs.setUpdateChannel(picked) }
            },
            onDismiss = { showChannelPicker = false },
        )
    }

    if (showPrunePicker) {
        OptionPickerSheet(
            title = "自动清理已读缓存",
            options = listOf(
                PickerOption(
                    id = "0",
                    label = "关闭",
                    subtitle = "已读章节正文一直保留，直到手动清除",
                ),
                PickerOption(
                    id = "7",
                    label = "7 天",
                    subtitle = "进度之前且超过 7 天未回看的章节会删掉缓存",
                ),
                PickerOption(
                    id = "14",
                    label = "14 天",
                    subtitle = "进度之前且超过 14 天未回看的章节会删掉缓存",
                ),
                PickerOption(
                    id = "30",
                    label = "30 天",
                    subtitle = "进度之前且超过 30 天未回看的章节会删掉缓存",
                ),
            ),
            selectedId = pruneDays.toString(),
            onSelect = { opt ->
                showPrunePicker = false
                opt.id.toIntOrNull()
                    ?.takeIf { it in AppPrefs.CACHE_AUTO_PRUNE_DAYS_OPTIONS }
                    ?.let { days -> scope.launch { appPrefs.setCacheAutoPruneDays(days) } }
            },
            onDismiss = { showPrunePicker = false },
        )
    }

    if (confirmClearCache) {
        AppAlertDialog(
            onDismissRequest = { confirmClearCache = false },
            title = "清除正文缓存",
            text = "将删除已下载的 ${formatSize(cacheBytes)} 章节正文。" +
                "书架、阅读进度和书源都不受影响，下次阅读时如需正文会重新加载。",
            confirmText = "清除",
            onConfirm = {
                confirmClearCache = false
                clearingCache = true
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { cache.clearAll() }
                        refreshCacheSize()
                    } finally {
                        clearingCache = false
                    }
                    messages.emit("已清除正文缓存")
                }
            },
        )
    }
}

private fun cacheAutoPruneLabel(days: Int): String = when (days) {
    0 -> "关闭"
    else -> "$days 天"
}
