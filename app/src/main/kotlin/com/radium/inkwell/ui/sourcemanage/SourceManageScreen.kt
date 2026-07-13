package com.radium.inkwell.ui.sourcemanage

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radium.inkwell.data.db.entity.BookSourceEntity
import com.radium.inkwell.ui.components.EmptyState
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceManageScreen(
    onBack: () -> Unit,
    onEdit: (String?) -> Unit,
    viewModel: SourceManageViewModel = koinViewModel(),
) {
    val sources by viewModel.sources.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var deleteTarget by remember { mutableStateOf<BookSourceEntity?>(null) }
    var showUrlImport by remember { mutableStateOf(false) }
    var showImportMenu by remember { mutableStateOf(false) }
    var importUrl by remember { mutableStateOf("") }

    val fileImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.importFromFile(it) } }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("书源管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
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
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(sources, key = { it.id }) { source ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onEdit(source.id) }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(source.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                source.id,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
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
                    OutlinedTextField(
                        value = importUrl,
                        onValueChange = { importUrl = it },
                        placeholder = { Text("https://…/sources.json") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
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
