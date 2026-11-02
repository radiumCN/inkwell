package com.radium.inkwell.ui.sourcemanage

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Source
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.filled.PlaylistAddCheck
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import com.radium.inkwell.ui.components.AppSnackbarHost
import com.radium.inkwell.ui.components.AppTextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.ui.platform.LocalContext
import com.radium.inkwell.data.db.entity.CheckStatus
import com.radium.inkwell.ui.components.ChipRow
import com.radium.inkwell.ui.components.DeterminateProgressBar
import com.radium.inkwell.ui.components.OptionPickerSheet
import com.radium.inkwell.ui.components.PickerOption
import com.radium.inkwell.ui.components.SearchField
import com.radium.inkwell.ui.components.AppFilterChip
import com.radium.inkwell.ui.components.AppAlertDialog
import com.radium.inkwell.ui.components.AppIconButton
import com.radium.inkwell.ui.components.AppSlider
import com.radium.inkwell.ui.components.CompactTextField
import com.radium.inkwell.ui.components.BackButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.radium.inkwell.ui.components.ContentListDefaults
import com.radium.inkwell.ui.components.ContentListItem
import com.radium.inkwell.ui.components.Dimens
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radium.inkwell.data.db.entity.BookSourceListItem
import com.radium.inkwell.ui.components.EmptyState
import com.radium.inkwell.ui.components.AppLoadingIndicator
import com.radium.inkwell.ui.components.LoadingState
import com.radium.inkwell.ui.components.CollectMessages
import com.radium.inkwell.ui.components.settingsPageColor
import com.radium.inkwell.ui.components.settingsStackListColors
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceManageScreen(
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    viewModel: SourceManageViewModel,
) {
    val sources by viewModel.sources.collectAsStateWithLifecycle()
    val sourcesLoaded by viewModel.sourcesLoaded.collectAsStateWithLifecycle()
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
    val importing by viewModel.importing.collectAsStateWithLifecycle()

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
    var deleteTarget by remember { mutableStateOf<BookSourceListItem?>(null) }
    var showUrlImport by remember { mutableStateOf(false) }
    var showImportMenu by remember { mutableStateOf(false) }
    var importUrl by remember { mutableStateOf("") }

    val fileImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.importFromFile(it) } }

    val pageColor = settingsPageColor()
    val topBarColors = TopAppBarDefaults.topAppBarColors(
        containerColor = pageColor,
        scrolledContainerColor = pageColor,
    )
    Scaffold(
        // 两条都留经典窄栏，不换 AppTopBar 的两段式 Flexible：这页有多选态，
        // 上下文操作栏与常规栏来回切，两者高度不同就会让进出多选像跳了一下
        containerColor = pageColor,
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
                        AppIconButton(onClick = viewModel::clearSelection) {
                            Icon(Icons.Default.Close, contentDescription = "退出多选")
                        }
                    },
                    colors = topBarColors,
                    actions = {
                        AppIconButton(onClick = viewModel::selectAll) {
                            Icon(Icons.Default.SelectAll, contentDescription = "全选")
                        }
                        // 同普通状态：先挑选项（弹窗按「有选中就只校验选中的」写好了），校验进行中不给 ——
                        // 从前多选里直接开跑、跳过了选项；正在校验时再点则静默无效
                        if (checkProgress == null) {
                            AppIconButton(onClick = { showCheckOptions = true }) {
                                Icon(Icons.AutoMirrored.Filled.PlaylistAddCheck, contentDescription = "校验")
                            }
                        }
                        AppIconButton(onClick = { confirmBatchDelete = true }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "删除",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                        Box {
                        AppIconButton(onClick = { overflowOpen = true }) {
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
                    BackButton(onClick = onBack)
                },
                colors = topBarColors,
                actions = {
                    if (sources.isNotEmpty() && checkProgress == null) {
                        AppIconButton(onClick = { showCheckOptions = true }) {
                            Icon(Icons.AutoMirrored.Filled.PlaylistAddCheck, contentDescription = "校验书源")
                        }
                    }
                    AppIconButton(onClick = { showSortPicker = true }) {
                        Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "排序")
                    }
                    Box {
                    AppIconButton(onClick = { showImportMenu = true }) {
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
        if (!sourcesLoaded) {
            // 库还没读回来：先空着，别闪一下「还没有书源」
        } else if (sources.isEmpty() && importing) {
            // 从空态点了导入：别还对着「还没有书源」干等，几 MB 的合集要解析好几秒
            LoadingState(Modifier.padding(padding), label = "正在导入书源…")
        } else if (sources.isEmpty()) {
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
            // 里面的列表各自 imePadding：padding 里已含导航栏高度，标成已消费，免得键盘上方多一道空隙
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            // 几百个书源，没有搜索和筛选就只能一路滑
            SearchField(
                value = query,
                onValueChange = viewModel::setQuery,
                placeholder = "搜索书源名称或网址",
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = Dimens.listHorizontal, vertical = Dimens.gapXS),
            )
            ChipRow(
                options = SourceFilter.entries.map { it.label },
                selectedIndex = SourceFilter.entries.indexOf(filter),
                onSelect = { viewModel.setFilter(SourceFilter.entries[it]) },
                contentPadding = PaddingValues(horizontal = Dimens.listHorizontal),
                trailing = if (groups.isNotEmpty()) {
                    {
                        AppFilterChip(
                            selected = groupFilter != null,
                            onClick = { showGroupPicker = true },
                            label = groupFilter ?: "分组",
                        )
                    }
                } else {
                    null
                },
            )
            // 导入进行中：几 MB 的合集要解析好几秒，没有这一行就像没点上
            if (importing) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = Dimens.listHorizontal, vertical = Dimens.listVertical),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Dimens.gapS),
                ) {
                    AppLoadingIndicator(size = Dimens.buttonSpinner)
                    Text("正在导入书源…", style = MaterialTheme.typography.labelMedium)
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
                        DeterminateProgressBar(
                            progress = { if (p.total == 0) 0f else p.done.toFloat() / p.total },
                            modifier = Modifier.padding(top = Dimens.gapXS),
                        )
                    }
                    AppTextButton(onClick = viewModel::cancelCheck) { Text("停止") }
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
                    AppTextButton(onClick = { viewModel.setFilter(SourceFilter.FAILED) }) { Text("只看失效") }
                    AppTextButton(onClick = viewModel::disableInvalid) { Text("禁用") }
                    AppTextButton(
                        onClick = { confirmDeleteInvalid = true },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Text("删除")
                    }
                }
            }
            // 搜不到 / 筛完是空的：从前就是一个空的列表，一片空白，也没有退出这个筛选的出口
            // （「失效」筛选下把失效源删完就是这样）。只在真的筛了的时候说 —— 没筛却是空的只可能是还没算完
            val narrowed = query.isNotBlank() || filter != SourceFilter.ALL || groupFilter != null
            if (visible.isEmpty() && narrowed) {
                EmptyState(
                    icon = Icons.Default.SearchOff,
                    title = "没有符合条件的书源",
                    hint = "换个关键词，或放宽筛选条件",
                    actionLabel = "清除筛选",
                    onAction = viewModel::clearFilters,
                    modifier = Modifier.imePadding(),
                )
            } else LazyColumn(
                // 上面压着搜索框：edge-to-edge 下键盘弹起会盖住列表下半截，最后几条滚不出来
                Modifier.fillMaxSize().imePadding(),
                contentPadding = ContentListDefaults.listContentPadding(),
                verticalArrangement = Arrangement.spacedBy(ContentListDefaults.ListSpacing),
            ) {
                items(visible, key = { it.id }) { source ->
                    SourceListRow(
                        source = source,
                        exploreOnly = source.id in exploreOnlyIds,
                        selected = source.id in selected,
                        selectionMode = selectionMode,
                        onClick = {
                            if (selectionMode) viewModel.toggleSelect(source.id)
                            else onOpen(source.id)
                        },
                        // 长按进多选，和书架的删除手势一致
                        onLongClick = { viewModel.toggleSelect(source.id) },
                        onDelete = { deleteTarget = source },
                        onToggleEnabled = { viewModel.setEnabled(source.id, it) },
                    )
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
        AppAlertDialog(
            onDismissRequest = { showCheckOptions = false },
            title = "校验 $target 个书源",
            confirmText = "开始校验",
            onConfirm = {
                viewModel.setOptions(draft)
                showCheckOptions = false
                viewModel.validate(selected)
            },
            content = {
                CompactTextField(
                    value = draft.keyword,
                    onValueChange = { draft = draft.copy(keyword = it) },
                    placeholder = "搜索关键词",
                )
                Text(
                    "要一个几乎每个站都搜得出结果的常见词",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CheckItemRow("校验详情页", draft.checkDetail) { draft = draft.copy(checkDetail = it) }
                CheckItemRow("校验目录", draft.checkToc) { draft = draft.copy(checkToc = it) }
                CheckItemRow("校验正文", draft.checkContent) { draft = draft.copy(checkContent = it) }
                Text(
                    "单源超时 ${draft.timeoutMs / 1000} 秒",
                    style = MaterialTheme.typography.labelMedium,
                )
                AppSlider(
                    value = (draft.timeoutMs / 1000).toFloat(),
                    onValueChange = { draft = draft.copy(timeoutMs = it.toLong() * 1000) },
                    valueRange = 15f..180f,
                    steps = 10,
                    label = "单源超时",
                )
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
        fun dismissGroupAssign() {
            showGroupAssign = false
            groupInput = ""
        }
        AppAlertDialog(
            onDismissRequest = { dismissGroupAssign() },
            title = "设置分组",
            confirmText = "确定",
            onConfirm = {
                val name = groupInput
                dismissGroupAssign()
                viewModel.setGroup(name)
            },
            content = {
                CompactTextField(
                    value = groupInput,
                    onValueChange = { groupInput = it },
                    placeholder = "分组名，留空则移出",
                )
            },
        )
    }

    if (confirmBatchDelete) {
        AppAlertDialog(
            onDismissRequest = { confirmBatchDelete = false },
            title = "删除书源",
            text = "确定删除选中的 ${selected.size} 个书源吗？",
            confirmText = "删除",
            onConfirm = {
                confirmBatchDelete = false
                viewModel.deleteSelected()
            },
        )
    }

    if (confirmDeleteInvalid) {
        AppAlertDialog(
            onDismissRequest = { confirmDeleteInvalid = false },
            title = "删除失效书源",
            text = "确定删除全部 $failedCount 个失效书源吗？此操作不可撤销。",
            confirmText = "删除",
            onConfirm = {
                confirmDeleteInvalid = false
                viewModel.deleteInvalid()
            },
        )
    }

    if (showUrlImport) {
        // 关掉时顺手清空：否则下次再开还躺着上次的链接，像「改不动」
        fun dismissUrlImport() {
            showUrlImport = false
            importUrl = ""
        }
        AppAlertDialog(
            onDismissRequest = { dismissUrlImport() },
            title = "从 URL 导入书源",
            text = "支持 Inkwell 与 Legado（阅读）格式的书源 JSON 链接",
            confirmText = "导入",
            confirmEnabled = importUrl.isNotBlank(),
            onConfirm = {
                viewModel.importFromUrl(importUrl)
                dismissUrlImport()
            },
            content = {
                CompactTextField(
                    value = importUrl,
                    onValueChange = { importUrl = it },
                    placeholder = "https://…/sources.json",
                )
            },
        )
    }

    deleteTarget?.let { source ->
        AppAlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = "删除书源",
            text = "确定删除「${source.name}」吗？",
            confirmText = "删除",
            onConfirm = {
                viewModel.delete(source.id)
                deleteTarget = null
            },
        )
    }
}

@Composable
private fun SourceListRow(
    source: BookSourceListItem,
    exploreOnly: Boolean,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDelete: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
) {
    val leading: (@Composable () -> Unit)? = if (selectionMode) {
        {
            Checkbox(
                checked = selected,
                onCheckedChange = null,
            )
        }
    } else {
        null
    }
    val trailing: (@Composable () -> Unit)? = if (!selectionMode) {
        {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 图标走 onSurfaceVariant：outline 只给边框/分隔线，拿它画可点的图标在浅色下对比度不够
                AppIconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "删除",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = source.enabled,
                    onCheckedChange = onToggleEnabled,
                    // 行尾开关自己是一个焦点，得有名字：只念「开关，已开启」听不出开的是哪个源
                    modifier = Modifier.semantics { contentDescription = "启用「${source.name}」" },
                )
            }
        }
    } else {
        null
    }
    val supporting: @Composable () -> Unit = {
        Column {
            Text(
                source.id,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (source.checkStatus != CheckStatus.UNCHECKED) {
                val ok = source.checkStatus == CheckStatus.OK
                val statusColor = if (ok) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                }
                Row(
                    Modifier.padding(top = Dimens.gapXS),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
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
    }
    val headline: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                source.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (exploreOnly) {
                Text(
                    "仅发现",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier
                        .padding(start = Dimens.gapS)
                        .background(
                            MaterialTheme.colorScheme.secondaryContainer,
                            MaterialTheme.shapes.extraSmall,
                        )
                        // 只留左右：上下贴着 labelSmall 的行高，正好是 M3 大号 Badge 的 16dp
                        .padding(horizontal = Dimens.gapS),
                )
            }
        }
    }

    if (selectionMode) {
        ContentListItem(
            checked = selected,
            onCheckedChange = { onClick() },
            onLongClick = onLongClick,
            colors = settingsStackListColors(),
            leadingContent = leading,
            trailingContent = trailing,
            supportingContent = supporting,
            content = headline,
        )
    } else {
        ContentListItem(
            onClick = onClick,
            onLongClick = onLongClick,
            colors = settingsStackListColors(),
            leadingContent = leading,
            trailingContent = trailing,
            supportingContent = supporting,
            content = headline,
        )
    }
}

/**
 * 校验选项里的开关行。整行就是一个开关：toggleable(role = Switch)，行内 Switch 只做展示 ——
 * 从前是 `clickable {}` 套一个能单独拨的 Switch，读屏上一行出两个焦点，行那个还念不出开关状态。
 */
@Composable
private fun CheckItemRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            // Switch 只展示时不再自带 48dp 触控区，行自己撑够
            .heightIn(min = Dimens.touchTarget)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = null)
    }
}
