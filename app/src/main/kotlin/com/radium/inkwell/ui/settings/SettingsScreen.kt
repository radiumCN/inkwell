package com.radium.inkwell.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import com.radium.inkwell.ui.stats.formatDuration
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

/**
 * 设置入口页：高频操作留在本页，细节进二级页。
 *
 * 排列：使用 → 数据 → 维护 → 版本 → 关于。检查更新主页一点即查；
 * 版本号固定页脚，不随列表长短上下漂移。
 *
 * 这里从前有一整块「隐私」分区，写着「查看隐藏书籍需要验证 / 长按书架标题后先验证指纹」。
 * 开关已搬进隐藏区内部；设置页里一个字都不提隐藏书籍。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenReadingStats: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenReading: () -> Unit,
    onOpenRss: () -> Unit,
    onOpenWebDav: () -> Unit,
    onOpenUpdate: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    val cache = koinInject<ChapterContentCache>()
    val readingStats = koinInject<ReadingStatsRepository>()
    // 行尾直接给今天的时长：点进去之前就知道今天读了多少，这一行才不只是个入口
    val todayReading by remember(readingStats) {
        readingStats.observeAll().map { rows ->
            val today = LocalDate.now().toEpochDay()
            rows.filter { it.day == today }.sumOf { it.millis }
        }
    }.collectAsStateWithLifecycle(initialValue = 0L)
    val appPrefs = koinInject<AppPrefs>()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    // 提示一律走 MessageBus：emit 不挂起，转圈/「正在检查…」不必陪着提示多挂 4 秒，
    // 新提示也直接替换正在显示的那条
    val messages = remember { MessageBus() }
    CollectMessages(messages, snackbar)
    val updateCheck = rememberUpdateCheckState(messages)
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
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(top = Dimens.gapS, bottom = Dimens.gapL),
            ) {
                SettingGroup {
                    SettingRow(
                        title = "阅读记录",
                        value = if (todayReading > 0) "今天 ${formatDuration(todayReading)}" else null,
                        onClick = onOpenReadingStats,
                        position = SettingGroupPosition.Alone,
                    )
                }
                SettingGroup {
                    SettingRow(
                        title = "外观",
                        onClick = onOpenAppearance,
                        position = SettingGroupPosition.First,
                    )
                    SettingRow(
                        title = "阅读与规则",
                        onClick = onOpenReading,
                        position = SettingGroupPosition.Middle,
                    )
                    SettingRow(
                        title = "订阅源",
                        onClick = onOpenRss,
                        position = SettingGroupPosition.Last,
                    )
                }
                SettingGroup {
                    SettingRow(
                        title = "WebDAV 备份同步",
                        onClick = onOpenWebDav,
                        position = SettingGroupPosition.First,
                    )
                    SettingRow(
                        title = "自动清理已读缓存",
                        value = cacheAutoPruneLabel(pruneDays),
                        onClick = { showPrunePicker = true },
                        position = SettingGroupPosition.Middle,
                    )
                    SettingRow(
                        title = "清除正文缓存",
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

                SectionHeader("版本与更新")
                SettingGroup {
                    SettingRow(
                        title = "检查更新",
                        value = if (updateCheck.checking) {
                            "正在检查…"
                        } else {
                            "${updateCheck.source.label} · ${updateCheck.channel.label}"
                        },
                        onClick = updateCheck.check,
                        position = SettingGroupPosition.First,
                    )
                    SettingRow(
                        title = "更新源与渠道",
                        onClick = onOpenUpdate,
                        position = SettingGroupPosition.Last,
                    )
                }

                SettingGroup {
                    SettingRow(
                        title = "关于",
                        onClick = onOpenAbout,
                        position = SettingGroupPosition.Alone,
                    )
                }
            }

            Text(
                "Inkwell  v${updateCheck.currentVersion}",
                Modifier
                    .fillMaxWidth()
                    .padding(
                        start = Dimens.rowHorizontal,
                        end = Dimens.rowHorizontal,
                        top = Dimens.gapM,
                        bottom = Dimens.gapXL,
                    ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }

    updateCheck.UpdateDialog()

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
