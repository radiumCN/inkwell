package com.radium.inkwell.ui.sourcemanage

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Source
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.material3.SnackbarHostState
import com.radium.inkwell.ui.components.AppSnackbarHost
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.platform.LocalContext
import com.radium.inkwell.data.db.entity.CheckStatus
import com.radium.inkwell.ui.components.OptionPickerSheet
import com.radium.inkwell.ui.components.PickerOption
import com.radium.inkwell.ui.components.SearchField
import com.radium.inkwell.ui.components.SlimSlider
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.radium.inkwell.ui.components.Dimens
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radium.inkwell.data.db.entity.BookSourceEntity
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import com.radium.inkwell.ui.components.EmptyState
import com.radium.inkwell.ui.components.CollectMessages
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceManageScreen(
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    viewModel: SourceManageViewModel = koinViewModel(),
) {
    val sources by viewModel.sources.collectAsStateWithLifecycle()
    val visible by viewModel.visibleSources.collectAsStateWithLifecycle()
    val exploreOnlyIds by viewModel.exploreOnlyIds.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val checkProgress by viewModel.checkProgress.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val sort by viewModel.sort.collectAsStateWithLifecycle()
    val groupFilter by viewModel.group.collectAsStateWithLifecycle()
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val failedCount by viewModel.failedCount.collectAsStateWithLifecycle()
    val options by viewModel.options.collectAsStateWithLifecycle()

    var showCheckOptions by remember { mutableStateOf(false) }
    var showSortPicker by remember { mutableStateOf(false) }
    var showGroupPicker by remember { mutableStateOf(false) }
    var showGroupAssign by remember { mutableStateOf(false) }
    var groupInput by remember { mutableStateOf("") }
    val context = LocalContext.current

    // 导出：写到缓存目录后交给系统分享（存网盘、发给别人都行）
    LaunchedEffect(Unit) {
        viewModel.exportFile.collect { file ->
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", file,
            )
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(android.content.Intent.createChooser(intent, "导出书源"))
        }
    }
    val selectionMode = selected.isNotEmpty()
    // 多选态下按系统返回：先退出多选，而不是直接退出整个页面
    BackHandler(selectionMode) { viewModel.clearSelection() }
    var confirmBatchDelete by remember { mutableStateOf(false) }
    var confirmDeleteInvalid by remember { mutableStateOf(false) }
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
                        Box {
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
                            DropdownMenuItem(
                                text = { Text("置顶") },
                                onClick = { overflowOpen = false; viewModel.moveToTop() },
                            )
                            DropdownMenuItem(
                                text = { Text("置底") },
                                onClick = { overflowOpen = false; viewModel.moveToBottom() },
                            )
                            DropdownMenuItem(
                                text = { Text("设置分组") },
                                onClick = {
                                    overflowOpen = false
                                    groupInput = ""
                                    showGroupAssign = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("导出选中") },
                                onClick = { overflowOpen = false; viewModel.exportSelected() },
                            )
                        }
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
                        IconButton(onClick = { showCheckOptions = true }) {
                            Icon(Icons.Default.PlaylistAddCheck, contentDescription = "校验书源")
                        }
                    }
                    IconButton(onClick = { showSortPicker = true }) {
                        Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "排序")
                    }
                    Box {
                    IconButton(onClick = { showImportMenu = true }) {
                        Icon(Icons.Default.Add, contentDescription = "导入书源")
                    }
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
                            text = { Text("从网络链接导入") },
                            onClick = {
                                showImportMenu = false
                                showUrlImport = true
                            },
                        )
                    }
                    }
                },
            )
        },
        snackbarHost = { AppSnackbarHost(snackbar) },
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
            // 几百个书源，没有搜索和筛选就只能一路滑
            SearchField(
                value = query,
                onValueChange = viewModel::setQuery,
                placeholder = "搜索书源名称或网址",
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = Dimens.listHorizontal, vertical = Dimens.gapXS),
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = Dimens.listHorizontal),
                horizontalArrangement = Arrangement.spacedBy(Dimens.gapS),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SourceFilter.entries.forEach { f ->
                    FilterChip(
                        selected = filter == f,
                        onClick = { viewModel.setFilter(f) },
                        label = { Text(f.label) },
                    )
                }
                if (groups.isNotEmpty()) {
                    FilterChip(
                        selected = groupFilter != null,
                        onClick = { showGroupPicker = true },
                        label = { Text(groupFilter ?: "分组") },
                    )
                }
            }
            // 校验进度：一次几百个源，得让人看到还剩多少、并且能中断
            checkProgress?.let { p ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = Dimens.listHorizontal, vertical = Dimens.listVertical),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "正在校验 ${p.done}/${p.total}",
                            style = MaterialTheme.typography.labelMedium,
                        )
                        LinearProgressIndicator(
                            progress = { if (p.total == 0) 0f else p.done.toFloat() / p.total },
                            modifier = Modifier.fillMaxWidth().padding(top = Dimens.gapXS),
                        )
                    }
                    TextButton(onClick = viewModel::cancelCheck) { Text("停止") }
                }
            }
            // 校验完最想干的事。禁用放在删除前面：删是不可逆的，而站点抽风、临时封 IP
            // 都会造成一次性的"失效"，禁用了还能改天再校验一次
            if (checkProgress == null && failedCount > 0) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = Dimens.listHorizontal),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "失效 $failedCount 个",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { viewModel.setFilter(SourceFilter.FAILED) }) { Text("只看失效") }
                    TextButton(onClick = viewModel::disableInvalid) { Text("禁用") }
                    TextButton(onClick = { confirmDeleteInvalid = true }) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(visible, key = { it.id }) { source ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = {
                                    if (selectionMode) viewModel.toggleSelect(source.id)
                                    else onOpen(source.id)
                                },
                                // 长按进多选，和书架的删除手势一致
                                onLongClick = { viewModel.toggleSelect(source.id) },
                            )
                            .padding(horizontal = Dimens.listHorizontal, vertical = Dimens.listVertical),
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
                            if (source.checkStatus != CheckStatus.UNCHECKED) {
                                val ok = source.checkStatus == CheckStatus.OK
                                val statusColor = if (ok) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.error
                                // 用真图标而非 "✓/✗" 字符：字符当图标是全 App 已弃的写法（见 OptionPicker）
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        if (ok) Icons.Default.Check else Icons.Default.Close,
                                        contentDescription = null,
                                        Modifier.size(Dimens.iconSm),
                                        tint = statusColor,
                                    )
                                    Text(
                                        source.checkMessage,
                                        Modifier.padding(start = Dimens.gapXS),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = statusColor,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
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

    // 校验前先让人挑选项：正文那步最慢也最容易被站点限流，
    // 只想快速筛一遍死源时关掉它能快好几倍
    if (showCheckOptions) {
        var draft by remember { mutableStateOf(options) }
        val target = if (selected.isEmpty()) sources.size else selected.size
        AlertDialog(
            onDismissRequest = { showCheckOptions = false },
            title = { Text("校验 $target 个书源") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(
                        value = draft.keyword,
                        onValueChange = { draft = draft.copy(keyword = it) },
                        label = { Text("搜索关键词") },
                        supportingText = { Text("要一个几乎每个站都搜得出结果的常见词") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(Dimens.gapS))
                    CheckItemRow("校验详情页", draft.checkDetail) { draft = draft.copy(checkDetail = it) }
                    CheckItemRow("校验目录", draft.checkToc) { draft = draft.copy(checkToc = it) }
                    CheckItemRow("校验正文", draft.checkContent) { draft = draft.copy(checkContent = it) }
                    Spacer(Modifier.height(Dimens.gapS))
                    Text(
                        "单源超时 ${draft.timeoutMs / 1000} 秒",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    SlimSlider(
                        value = (draft.timeoutMs / 1000).toFloat(),
                        onValueChange = { draft = draft.copy(timeoutMs = it.toLong() * 1000) },
                        valueRange = 15f..180f,
                        steps = 10,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setOptions(draft)
                    showCheckOptions = false
                    viewModel.validate(selected)
                }) { Text("开始校验") }
            },
            dismissButton = {
                TextButton(onClick = { showCheckOptions = false }) { Text("取消") }
            },
        )
    }

    if (showSortPicker) {
        OptionPickerSheet(
            title = "排序",
            options = SourceSort.entries.map { PickerOption(id = it.name, label = it.label) },
            selectedId = sort.name,
            onSelect = { opt ->
                showSortPicker = false
                runCatching { SourceSort.valueOf(opt.id) }.getOrNull()?.let(viewModel::setSort)
            },
            onDismiss = { showSortPicker = false },
        )
    }

    if (showGroupPicker) {
        OptionPickerSheet(
            title = "按分组筛选",
            options = listOf(PickerOption(id = "", label = "全部分组")) +
                groups.map { PickerOption(id = it, label = it) },
            selectedId = groupFilter.orEmpty(),
            onSelect = { opt ->
                showGroupPicker = false
                viewModel.setGroupFilter(opt.id.takeIf { it.isNotEmpty() })
            },
            onDismiss = { showGroupPicker = false },
        )
    }

    if (showGroupAssign) {
        AlertDialog(
            onDismissRequest = { showGroupAssign = false },
            title = { Text("设置分组") },
            text = {
                OutlinedTextField(
                    value = groupInput,
                    onValueChange = { groupInput = it },
                    label = { Text("分组名") },
                    placeholder = { Text("留空则移出分组") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showGroupAssign = false
                    viewModel.setGroup(groupInput)
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showGroupAssign = false }) { Text("取消") }
            },
        )
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

    if (confirmDeleteInvalid) {
        AlertDialog(
            onDismissRequest = { confirmDeleteInvalid = false },
            title = { Text("删除失效书源") },
            text = { Text("确定删除全部 $failedCount 个失效书源吗？此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteInvalid = false
                    viewModel.deleteInvalid()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteInvalid = false }) { Text("取消") }
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
                    OutlinedTextField(
                        value = importUrl,
                        onValueChange = { importUrl = it },
                        label = { Text("书源 JSON 链接") },
                        placeholder = { Text("https://…/sources.json") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = Dimens.gapS),
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

@Composable
private fun CheckItemRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
