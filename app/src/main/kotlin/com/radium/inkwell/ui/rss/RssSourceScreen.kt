package com.radium.inkwell.ui.rss

import com.radium.inkwell.ui.components.exceptBottom
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import com.radium.inkwell.ui.components.AppAlertDialog
import com.radium.inkwell.ui.components.AppIconButton
import com.radium.inkwell.ui.components.AppSnackbarHost
import com.radium.inkwell.ui.components.AppTopBar
import com.radium.inkwell.ui.components.CompactTextField
import com.radium.inkwell.ui.components.rememberAppTopBarScroll
import com.radium.inkwell.ui.components.topBarScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radium.inkwell.data.db.entity.RssSourceEntity
import com.radium.inkwell.ui.components.CollectMessages
import com.radium.inkwell.ui.components.ContentListDefaults
import com.radium.inkwell.ui.components.ContentListItem
import com.radium.inkwell.ui.components.EmptyState
import com.radium.inkwell.ui.components.AppLoadingIndicator
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.ui.components.LoadingState
import com.radium.inkwell.ui.components.settingsPageColor
import com.radium.inkwell.ui.components.settingsStackListColors
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RssSourceScreen(
    onBack: () -> Unit,
    onOpenSource: (String) -> Unit,
    viewModel: RssSourceViewModel,
) {
    val sources by viewModel.sources.collectAsStateWithLifecycle()
    val importing by viewModel.importing.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    CollectMessages(viewModel.messages, snackbar)

    var showImportMenu by remember { mutableStateOf(false) }
    var showUrlImport by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<RssSourceEntity?>(null) }

    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.importFromFile(it) } }

    val pageColor = settingsPageColor()
    val topBarScroll = rememberAppTopBarScroll()
    Scaffold(
        modifier = Modifier.topBarScroll(topBarScroll),
        containerColor = pageColor,
        topBar = {
            AppTopBar("订阅", topBarScroll, onBack = onBack, containerColor = pageColor) {
                Box {
                    // 导入进行中（粘地址要联网抓 feed）换成转圈：从前这个状态 VM 里有、页面从没读过，点了像没点上
                    if (importing) {
                        // 转圈没有名字，读屏只会念「按钮，已停用」：补一句它在干什么
                        AppIconButton(
                            onClick = {},
                            enabled = false,
                            modifier = Modifier.semantics { contentDescription = "正在导入订阅源" },
                        ) {
                            AppLoadingIndicator(size = Dimens.buttonSpinner)
                        }
                    } else {
                        AppIconButton(onClick = { showImportMenu = true }) {
                            Icon(Icons.Default.Add, contentDescription = "添加订阅源")
                        }
                    }
                    DropdownMenu(
                        expanded = showImportMenu,
                        onDismissRequest = { showImportMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("粘贴地址或订阅源") },
                            onClick = { showImportMenu = false; importText = ""; showUrlImport = true },
                        )
                        DropdownMenuItem(
                            text = { Text("从剪贴板导入") },
                            onClick = { showImportMenu = false; viewModel.importFromClipboard() },
                        )
                        DropdownMenuItem(
                            text = { Text("从文件导入") },
                            onClick = {
                                showImportMenu = false
                                fileLauncher.launch(
                                    arrayOf("application/json", "text/plain", "application/octet-stream")
                                )
                            },
                        )
                    }
                }
            }
        },
        snackbarHost = { AppSnackbarHost(snackbar) },
    ) { padding ->
        // null = 库还没读回来：先空着，别闪一下「还没有订阅源」
        val list = sources ?: return@Scaffold
        if (list.isEmpty() && importing) {
            LoadingState(Modifier.padding(padding), label = "正在导入订阅源…")
        } else if (list.isEmpty()) {
            EmptyState(
                icon = Icons.Default.RssFeed,
                title = "还没有订阅源",
                hint = "直接粘一个 RSS/Atom 地址就能订阅，也支持导入 Legado 订阅源",
                actionLabel = "添加订阅源",
                onAction = { importText = ""; showUrlImport = true },
                modifier = Modifier.padding(padding),
            )
        } else {
            // 内容滚到手势条下面，导航栏高度垫在末尾（见 exceptBottom）
            LazyColumn(
                Modifier.fillMaxSize().padding(padding.exceptBottom()),
                contentPadding = ContentListDefaults.listContentPadding(bottom = padding.calculateBottomPadding()),
                verticalArrangement = Arrangement.spacedBy(ContentListDefaults.ListSpacing),
            ) {
                items(list, key = { it.id }) { source ->
                    ContentListItem(
                        onClick = { onOpenSource(source.id) },
                        enabled = source.enabled,
                        colors = settingsStackListColors(),
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // 删除图标走 onSurfaceVariant：outline 只给边框/分隔线用，
                                // 拿它画可点的图标在浅色下对比度不够（约 3.9:1）
                                AppIconButton(onClick = { deleteTarget = source }) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "删除",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Switch(
                                    checked = source.enabled,
                                    onCheckedChange = { viewModel.setEnabled(source.id, it) },
                                    // 行尾开关自己是一个焦点，得有名字：只念「开关，已开启」听不出开的是哪个源
                                    modifier = Modifier.semantics {
                                        contentDescription = "启用「${source.name}」"
                                    },
                                )
                            }
                        },
                        supportingContent = {
                            Text(
                                source.id,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        content = {
                            Text(
                                source.name,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
            }
        }
    }

    if (showUrlImport) {
        fun dismissUrlImport() {
            showUrlImport = false
            importText = ""
        }
        AppAlertDialog(
            onDismissRequest = { dismissUrlImport() },
            title = "添加订阅源",
            confirmText = "添加",
            onConfirm = {
                val text = importText
                dismissUrlImport()
                viewModel.importFromText(text)
            },
            confirmEnabled = importText.isNotBlank(),
            content = {
                CompactTextField(
                    value = importText,
                    onValueChange = { importText = it },
                    placeholder = "地址或订阅源 JSON",
                )
                Text(
                    "直接粘一个 RSS/Atom 地址即可 —— 不必先去找一份 Legado 格式的订阅源。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )
    }

    deleteTarget?.let { source ->
        AppAlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = "删除订阅源",
            text = "确定删除「${source.name}」吗？",
            confirmText = "删除",
            onConfirm = {
                viewModel.delete(source.id)
                deleteTarget = null
            },
        )
    }
}
