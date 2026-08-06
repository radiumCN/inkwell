package com.radium.inkwell.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import com.radium.inkwell.data.repo.ChapterContentCache
import com.radium.inkwell.ui.components.AppSnackbarHost
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.ui.components.SectionHeader
import com.radium.inkwell.ui.components.SettingRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/**
 * 设置入口页：高频操作留在本页，细节进二级页。
 *
 * - 检查更新：主页一点即查；版本号只在页脚展示，避免与列表行重复
 * - 更新源/渠道、外观细项、换源开关等：进二级，少打扰日常路径
 *
 * 这里从前有一整块「隐私」分区，写着「查看隐藏书籍需要验证 / 长按书架标题后先验证指纹」。
 * 开关已搬进隐藏区内部；设置页里一个字都不提隐藏书籍。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenReading: () -> Unit,
    onOpenRss: () -> Unit,
    onOpenWebDav: () -> Unit,
    onOpenUpdate: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    val cache = koinInject<ChapterContentCache>()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val updateCheck = rememberUpdateCheckState(snackbar)
    var confirmClearCache by remember { mutableStateOf(false) }
    var cacheBytes by remember { mutableLongStateOf(-1L) }

    suspend fun refreshCacheSize() {
        cacheBytes = withContext(Dispatchers.IO) { cache.sizeBytes() }
    }
    LaunchedEffect(Unit) { refreshCacheSize() }

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
            SettingRow(
                title = "外观",
                subtitle = "主题、应用图标与发现入口",
                onClick = onOpenAppearance,
            )
            SettingRow(
                title = "阅读与规则",
                subtitle = "书源管理、净化与换源行为",
                onClick = onOpenReading,
            )
            SettingRow(
                title = "订阅源",
                subtitle = "RSS / Atom 订阅",
                onClick = onOpenRss,
            )
            SettingRow(
                title = "清除正文缓存",
                subtitle = when {
                    cacheBytes < 0 -> "正在统计…"
                    cacheBytes == 0L -> "暂无缓存"
                    else -> "已缓存 ${formatSize(cacheBytes)}；清除不影响书架与阅读进度"
                },
                onClick = { if (cacheBytes > 0) confirmClearCache = true },
            )
            SettingRow(
                title = "WebDAV 备份同步",
                subtitle = "书架、进度与规则配置的多设备同步",
                onClick = onOpenWebDav,
            )

            SectionHeader("版本与更新")
            SettingRow(
                title = "检查更新",
                subtitle = if (updateCheck.checking) {
                    "正在检查…"
                } else {
                    "${updateCheck.source.label} · ${updateCheck.channel.label}"
                },
                onClick = updateCheck.check,
            )
            SettingRow(
                title = "更新源与渠道",
                subtitle = "较少改动的选项",
                onClick = onOpenUpdate,
            )
            SettingRow(
                title = "关于",
                subtitle = "反馈、协议与开源信息",
                onClick = onOpenAbout,
            )

            Text(
                "Inkwell  v${updateCheck.currentVersion}",
                Modifier
                    .fillMaxWidth()
                    .padding(
                        start = Dimens.rowHorizontal,
                        end = Dimens.rowHorizontal,
                        top = Dimens.gapXL,
                        bottom = Dimens.gapXL,
                    ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }

    updateCheck.UpdateDialog()

    if (confirmClearCache) {
        AlertDialog(
            onDismissRequest = { confirmClearCache = false },
            title = { Text("清除正文缓存") },
            text = {
                Text(
                    "将删除已下载的 ${formatSize(cacheBytes)} 章节正文。" +
                        "书架、阅读进度和书源都不受影响，下次阅读时如需正文会重新加载。",
                )
            },
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
}
