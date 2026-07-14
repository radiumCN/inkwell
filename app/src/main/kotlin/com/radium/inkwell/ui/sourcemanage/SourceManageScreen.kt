package com.radium.inkwell.ui.sourcemanage

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Source
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlaylistAddCheck
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radium.inkwell.data.db.entity.BookSourceEntity
import com.radium.inkwell.ui.components.CompactTextField
import com.radium.inkwell.ui.components.EmptyState
import com.radium.inkwell.ui.components.CollectMessages
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceManageScreen(
    onBack: () -> Unit,
    onEdit: (String?) -> Unit,
    viewModel: SourceManageViewModel = koinViewModel(),
) {
    val sources by viewModel.sources.collectAsStateWithLifecycle()
    val exploreOnlyIds by viewModel.exploreOnlyIds.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val checks by viewModel.checks.collectAsStateWithLifecycle()
    val checkProgress by viewModel.checkProgress.collectAsStateWithLifecycle()
    val selectionMode = selected.isNotEmpty()
    var confirmBatchDelete by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    CollectMessages(viewModel.messages, snackbar)
    var deleteTarget by remember { mutableStateOf<BookSourceEntity?>(null) }
    var showUrlImport by remember { mutableStateOf(false) }
    var showImportMenu by remember { mutableStateOf(false) }
    var importUrl by remember { mutableStateOf("") }

    val fileImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.importFromFile(it) } }

    Scaffold(
        topBar = {
            if (selectionMode) {
                // 批量操作栏：文字按钮塞不下五个（标题会被挤成竖排单字）。
                // 按 Material 的 overflow 规则 —— 高频动作留成图标，低频的收进溢出菜单。
                var overflowOpen by remember { mutableStateOf(false) }
                TopAppBar(
                    title = {
                        Text(
                            "已选 ${selected.size} 个",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = viewModel::clearSelection) {
                            Icon(Icons.Default.Close, contentDescription = "退出多选")
                        }
                    },
                    actions = {
                        IconButton(onClick = viewModel::selectAll) {
                            Icon(Icons.Default.SelectAll, contentDescription = "全选")
                        }
                        IconButton(onClick = { viewModel.validate(selected) }) {
                            Icon(Icons.Default.PlaylistAddCheck, contentDescription = "校验")
                        }
                        IconButton(onClick = { confirmBatchDelete = true }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "删除",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                        IconButton(onClick = { overflowOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "更多")
                        }
                        DropdownMenu(
                            expanded = overflowOpen,
                            onDismissRequest = { overflowOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("启用") },
                                onClick = {
                                    overflowOpen = false
                                    viewModel.setEnabledSelected(true)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("禁用") },
                                onClick = {
                                    overflowOpen = false
                                    viewModel.setEnabledSelected(false)
                                },
                            )
                        }
                    },
                )
            } else TopAppBar(
                title = { Text("书源管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (sources.isNotEmpty() && checkProgress == null) {
                        TextButton(onClick = { viewModel.validate() }) { Text("校验全部") }
                    }
                    TextButton(onClick = { showImportMenu = true }) { Text("导入书源") }
                    DropdownMenu(
                        expanded = showImportMenu,
                        onDismissRequest = { showImportMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("从文件导入") },
                            onClick = {
                                showImportMenu = false
                                fileImportLauncher.launch(
                                    arrayOf("application/json", "text/plain", "application/octet-stream")
                                )
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("从剪贴板导入") },
                            onClick = {
                                showImportMenu = false
                                viewModel.importFromClipboard()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("从网络链接导入") },
                            onClick = {
                                showImportMenu = false
                                showUrlImport = true
                            },
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            FloatingActionButton(onClick = { onEdit(null) }) {
                Icon(Icons.Default.Add, contentDescription = "新建书源")
            }
        },
    ) { padding ->
        if (sources.isEmpty()) {
            EmptyState(
                icon = Icons.Default.Source,
                title = "还没有书源",
                hint = "支持导入 Inkwell 与 Legado（阅读）格式的书源",
                actionLabel = "从文件导入",
                onAction = {
                    fileImportLauncher.launch(
                        arrayOf("application/json", "text/plain", "application/octet-stream")
                    )
                },
                modifier = Modifier.padding(padding),
            )
        } else {
            Column(Modifier.fillMaxSize().padding(padding)) {
            // 校验进度：一次几百个源，得让人看到还剩多少、并且能中断
            checkProgress?.let { p ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "正在校验 ${p.done}/${p.total}",
                            style = MaterialTheme.typography.labelMedium,
                        )
                        LinearProgressIndicator(
                            progress = { if (p.total == 0) 0f else p.done.toFloat() / p.total },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        )
                    }
                    TextButton(onClick = viewModel::cancelCheck) { Text("停止") }
                }
            }
            // 校验完了最想干的事：把失效的一键清掉
            if (checkProgress == null && checks.values.any { !it.ok }) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "失效 ${checks.values.count { !it.ok }} 个",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = viewModel::deleteInvalid) { Text("删除失效书源") }
                }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(sources, key = { it.id }) { source ->
                    val check = checks[source.id]
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = {
                                    if (selectionMode) viewModel.toggleSelect(source.id)
                                    else onEdit(source.id)
                                },
                                // 长按进多选，和书架的删除手势一致
                                onLongClick = { viewModel.toggleSelect(source.id) },
                            )
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (selectionMode) {
                            Checkbox(
                                checked = source.id in selected,
                                onCheckedChange = { viewModel.toggleSelect(source.id) },
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    source.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                if (source.id in exploreOnlyIds) {
                                    Text(
                                        "仅发现",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier
                                            .padding(start = 6.dp)
                                            .background(
                                                MaterialTheme.colorScheme.secondaryContainer,
                                                MaterialTheme.shapes.extraSmall,
                                            )
                                            .padding(horizontal = 6.dp, vertical = 1.dp),
                                    )
                                }
                            }
                            Text(
                                source.id,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (check != null) {
                                Text(
                                    (if (check.ok) "✓ " else "✗ ") + check.message,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (check.ok) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.error,
                                    maxLines = 1,
                                )
                            }
                        }
                        if (!selectionMode) {
                            IconButton(onClick = { deleteTarget = source }) {
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
                    }
                }
            }
            }
        }
    }

    if (confirmBatchDelete) {
        AlertDialog(
            onDismissRequest = { confirmBatchDelete = false },
            title = { Text("删除书源") },
            text = { Text("确定删除选中的 ${selected.size} 个书源吗？") },
            confirmButton = {
                TextButton(onClick = {
                    confirmBatchDelete = false
                    viewModel.deleteSelected()
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmBatchDelete = false }) { Text("取消") }
            },
        )
    }

    if (showUrlImport) {
        AlertDialog(
            onDismissRequest = { showUrlImport = false },
            title = { Text("从 URL 导入书源") },
            text = {
                Column {
                    Text(
                        "支持 Inkwell 与 Legado（阅读）格式的书源 JSON 链接",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    CompactTextField(
                        value = importUrl,
                        onValueChange = { importUrl = it },
                        placeholder = "https://…/sources.json",
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.importFromUrl(importUrl)
                    showUrlImport = false
                }) { Text("导入") }
            },
            dismissButton = {
                TextButton(onClick = { showUrlImport = false }) { Text("取消") }
            },
        )
    }

    deleteTarget?.let { source ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除书源") },
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
