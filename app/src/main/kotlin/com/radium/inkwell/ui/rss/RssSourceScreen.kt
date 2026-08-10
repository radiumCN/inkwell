package com.radium.inkwell.ui.rss

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radium.inkwell.data.db.entity.RssSourceEntity
import com.radium.inkwell.ui.components.CollectMessages
import com.radium.inkwell.ui.components.ContentListDefaults
import com.radium.inkwell.ui.components.ContentListItem
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.ui.components.EmptyState
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RssSourceScreen(
    onBack: () -> Unit,
    onOpenSource: (String) -> Unit,
    viewModel: RssSourceViewModel,
) {
    val sources by viewModel.sources.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    CollectMessages(viewModel.messages, snackbar)

    var showImportMenu by remember { mutableStateOf(false) }
    var showUrlImport by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<RssSourceEntity?>(null) }

    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.importFromFile(it) } }

    val topBarScroll = rememberAppTopBarScroll()
    Scaffold(
        modifier = Modifier.topBarScroll(topBarScroll),
        topBar = {
            AppTopBar("订阅", topBarScroll, onBack = onBack) {
                Box {
                    AppIconButton(onClick = { showImportMenu = true }) {
                        Icon(Icons.Default.Add, contentDescription = "添加订阅源")
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
        if (sources.isEmpty()) {
            EmptyState(
                icon = Icons.Default.RssFeed,
                title = "还没有订阅源",
                hint = "直接粘一个 RSS/Atom 地址就能订阅，也支持导入 Legado 订阅源",
                actionLabel = "添加订阅源",
                onAction = { importText = ""; showUrlImport = true },
                modifier = Modifier.padding(padding),
            )
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = ContentListDefaults.listContentPadding(),
                verticalArrangement = Arrangement.spacedBy(ContentListDefaults.ListSpacing),
            ) {
                items(sources, key = { it.id }) { source ->
                    ContentListItem(
                        onClick = { onOpenSource(source.id) },
                        enabled = source.enabled,
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                AppIconButton(onClick = { deleteTarget = source }) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "删除",
                                        tint = MaterialTheme.colorScheme.outline,
                                    )
                                }
                                Switch(
                                    checked = source.enabled,
                                    onCheckedChange = { viewModel.setEnabled(source.id, it) },
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
        AlertDialog(
            onDismissRequest = { dismissUrlImport() },
            title = { Text("添加订阅源") },
            text = {
                Column {
                    OutlinedTextField(
                        value = importText,
                        onValueChange = { importText = it },
                        label = { Text("地址或订阅源 JSON") },
                        placeholder = { Text("https://example.com/rss.xml") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "直接粘一个 RSS/Atom 地址即可 —— 不必先去找一份 Legado 格式的订阅源。",
                        Modifier.padding(top = Dimens.gapS),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val text = importText
                    dismissUrlImport()
                    viewModel.importFromText(text)
                }) { Text("添加") }
            },
            dismissButton = {
                TextButton(onClick = { dismissUrlImport() }) { Text("取消") }
            },
        )
    }

    deleteTarget?.let { source ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除订阅源") },
            text = { Text("确定删除「${source.name}」吗？") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(source.id)
                    deleteTarget = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }
}
