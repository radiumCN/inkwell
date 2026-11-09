package com.radium.inkwell.ui.sourcemanage

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radium.inkwell.data.db.entity.BookSourceListItem
import com.radium.inkwell.data.db.entity.CheckStatus
import com.radium.inkwell.ui.components.AppAlertDialog
import com.radium.inkwell.ui.components.AppDropdownMenu
import com.radium.inkwell.ui.components.AppFilterChip
import com.radium.inkwell.ui.components.AppIconButton
import com.radium.inkwell.ui.components.AppLoadingIndicator
import com.radium.inkwell.ui.components.AppMenuItem
import com.radium.inkwell.ui.components.AppScaffold
import com.radium.inkwell.ui.components.AppSlider
import com.radium.inkwell.ui.components.AppSnackbarHost
import com.radium.inkwell.ui.components.AppTextButton
import com.radium.inkwell.ui.components.AppToolbar
import com.radium.inkwell.ui.components.BackButton
import com.radium.inkwell.ui.components.BatchAction
import com.radium.inkwell.ui.components.BatchActionBar
import com.radium.inkwell.ui.components.ChipRow
import com.radium.inkwell.ui.components.CollectMessages
import com.radium.inkwell.ui.components.CompactTextField
import com.radium.inkwell.ui.components.ContentListDefaults
import com.radium.inkwell.ui.components.ContentListItem
import com.radium.inkwell.ui.components.DeterminateProgressBar
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.ui.components.EmptyState
import com.radium.inkwell.ui.components.LoadingState
import com.radium.inkwell.ui.components.SearchField
import com.radium.inkwell.ui.components.ToolbarTitle
import com.radium.inkwell.ui.components.groupedCardItem
import com.radium.inkwell.ui.components.horizontalOnly
import com.radium.inkwell.ui.components.pageScroll
import com.radium.inkwell.ui.components.settingsPageColor
import com.radium.inkwell.ui.theme.AppShapes
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Blocklist
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Import
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.icon.extended.SelectAll
import top.yukonga.miuix.kmp.icon.extended.Sort
import top.yukonga.miuix.kmp.icon.extended.Tasks
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun SourceManageScreen(
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    /** 新建书源（按网址识别），见 SourceWizardScreen */
    onCreate: () -> Unit,
    viewModel: SourceManageViewModel,
) {
    val sources by viewModel.sources.collectAsStateWithLifecycle()
    val sourcesLoaded by viewModel.sourcesLoaded.collectAsStateWithLifecycle()
    val visible by viewModel.visibleSources.collectAsStateWithLifecycle()
    val searchless by viewModel.searchless.collectAsStateWithLifecycle()
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
            context.startActivity(android.content.Intent.createChooser(intent, "导出来源"))
        }
    }
    val selectionMode = selected.isNotEmpty()
    // 多选态下按系统返回：先退出多选，而不是直接退出整个页面
    BackHandler(selectionMode) { viewModel.clearSelection() }
    var confirmBatchDelete by remember { mutableStateOf(false) }
    var confirmDeleteInvalid by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    CollectMessages(viewModel.messages, snackbar)
    // 多选态底部操作条占掉的高度（BatchActionBar 量好报上来），垫到列表末尾
    var batchBarHeight by remember { mutableStateOf(0.dp) }
    var showUrlImport by remember { mutableStateOf(false) }
    var showImportMenu by remember { mutableStateOf(false) }
    var importUrl by remember { mutableStateOf("") }

    val fileImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.importFromFile(it) } }

    val pageColor = settingsPageColor()
    // 有书源时列表上面压着的那一截（搜索、筛选、导入 / 校验进度、失效汇总）。放进顶栏的 bottomContent，
    // 而不是内容区顶上：列表才能从它下面滚过去、和顶栏一起被毛玻璃盖住。两条顶栏都带上它，进出多选高度不变
    val showList = sourcesLoaded && sources.isNotEmpty()
    val listHeader: @Composable () -> Unit = {
        if (showList) {
            SourceListHeader(
                query = query,
                onQueryChange = viewModel::setQuery,
                filter = filter,
                onFilterChange = viewModel::setFilter,
                groups = groups,
                groupFilter = groupFilter,
                onGroupFilterChange = viewModel::setGroupFilter,
                importing = importing,
                checkProgress = checkProgress,
                onCancelCheck = viewModel::cancelCheck,
                failedCount = failedCount,
                onDisableInvalid = viewModel::disableInvalid,
                onDeleteInvalid = { confirmDeleteInvalid = true },
            )
        }
    }
    AppScaffold(
        // 两条都用窄栏（AppToolbar），不换 AppTopBar 的大标题折叠栏：这页有多选态，
        // 上下文操作栏与常规栏来回切，两者高度不同就会让进出多选像跳了一下
        containerColor = pageColor,
        topBar = {
            if (selectionMode) {
                // HyperOS 编辑模式：顶栏只留 × · 已选 N 个 · 全选，批量动作在底部的操作条里（见 floatingToolbar）
                AppToolbar(
                    navigationIcon = {
                        AppIconButton(onClick = viewModel::clearSelection) {
                            Icon(MiuixIcons.Close, contentDescription = "退出多选")
                        }
                    },
                    containerColor = pageColor,
                    actions = {
                        AppIconButton(onClick = viewModel::selectAll) {
                            Icon(MiuixIcons.SelectAll, contentDescription = "全选")
                        }
                    },
                    bottomContent = listHeader,
                ) {
                    ToolbarTitle("已选 ${selected.size} 个")
                }
            } else AppToolbar(
                navigationIcon = { BackButton(onClick = onBack) },
                containerColor = pageColor,
                actions = {
                    if (sources.isNotEmpty() && checkProgress == null) {
                        AppIconButton(onClick = { showCheckOptions = true }) {
                            Icon(MiuixIcons.Tasks, contentDescription = "校验来源")
                        }
                    }
                    Box {
                        AppIconButton(onClick = { showSortPicker = true }) {
                            Icon(MiuixIcons.Sort, contentDescription = "排序")
                        }
                        AppDropdownMenu(
                            expanded = showSortPicker,
                            onDismissRequest = { showSortPicker = false },
                            items = SourceSort.entries.map { option ->
                                AppMenuItem(
                                    option.label,
                                    onClick = {
                                        showSortPicker = false
                                        viewModel.setSort(option)
                                    },
                                    selected = option == sort,
                                )
                            },
                        )
                    }
                    Box {
                        AppIconButton(onClick = { showImportMenu = true }) {
                            Icon(MiuixIcons.Add, contentDescription = "添加来源")
                        }
                        AppDropdownMenu(
                            expanded = showImportMenu,
                            onDismissRequest = { showImportMenu = false },
                            items = listOf(
                                AppMenuItem("从文件导入", onClick = {
                                    showImportMenu = false
                                    fileImportLauncher.launch(
                                        arrayOf("application/json", "text/plain", "application/octet-stream")
                                    )
                                }),
                                AppMenuItem("从网络链接导入", onClick = {
                                    showImportMenu = false
                                    showUrlImport = true
                                }),
                                // 和两种导入分开说清：导入要的是现成的书源文件，这个只要一本书的网址
                                AppMenuItem("按书的网址新建", onClick = {
                                    showImportMenu = false
                                    onCreate()
                                }),
                            ),
                        )
                    }
                },
                bottomContent = listHeader,
            ) {
                ToolbarTitle("来源管理")
            }
        },
        snackbarHost = { AppSnackbarHost(snackbar) },
        // 批量动作：高频的四个摆出来（图标在上、字在下），低频的收进「更多」。
        // 校验同普通状态：先挑选项（弹窗按「有选中就只校验选中的」写好了），校验进行中不给
        floatingToolbar = {
            BatchActionBar(
                visible = selectionMode,
                actions = listOf(
                    BatchAction(MiuixIcons.Ok, "启用", onClick = { viewModel.setEnabledSelected(true) }),
                    BatchAction(MiuixIcons.Blocklist, "禁用", onClick = { viewModel.setEnabledSelected(false) }),
                    BatchAction(
                        MiuixIcons.Tasks,
                        "校验",
                        onClick = { showCheckOptions = true },
                        enabled = checkProgress == null,
                    ),
                    BatchAction(MiuixIcons.Delete, "删除", onClick = { confirmBatchDelete = true }, destructive = true),
                ),
                more = listOf(
                    AppMenuItem("置顶", onClick = viewModel::moveToTop),
                    AppMenuItem("置底", onClick = viewModel::moveToBottom),
                    AppMenuItem("设置分组", onClick = {
                        groupInput = ""
                        showGroupAssign = true
                    }),
                    AppMenuItem("导出选中", onClick = viewModel::exportSelected),
                ),
                onHeightChange = { batchBarHeight = it },
            )
        },
    ) { padding ->
        if (!sourcesLoaded) {
            // 库还没读回来：先空着，别闪一下「还没有书源」
        } else if (sources.isEmpty() && importing) {
            // 从空态点了导入：别还对着「还没有书源」干等，几 MB 的合集要解析好几秒
            LoadingState(Modifier.padding(padding), label = "正在导入来源…")
        } else if (sources.isEmpty()) {
            EmptyState(
                icon = MiuixIcons.Import,
                title = "还没有来源",
                hint = "支持导入 Inkwell 与 Legado（阅读）格式的来源；" +
                    "没有现成来源的网站，可以点右上角「＋」按书的网址新建",
                actionLabel = "从文件导入",
                onAction = {
                    fileImportLauncher.launch(
                        arrayOf("application/json", "text/plain", "application/octet-stream")
                    )
                },
                modifier = Modifier.padding(padding),
            )
        } else {
            // 搜不到 / 筛完是空的：从前就是一个空的列表，一片空白，也没有退出这个筛选的出口
            // （「失效」筛选下把失效源删完就是这样）。只在真的筛了的时候说 —— 没筛却是空的只可能是还没算完
            val narrowed = query.isNotBlank() || filter != SourceFilter.ALL || groupFilter != null
            if (visible.isEmpty() && narrowed) {
                EmptyState(
                    icon = MiuixIcons.Search,
                    title = "没有符合条件的来源",
                    hint = "换个关键词，或放宽筛选条件",
                    actionLabel = "清除筛选",
                    onAction = viewModel::clearFilters,
                    modifier = Modifier.padding(padding).consumeWindowInsets(padding).imePadding(),
                )
            } else LazyColumn(
                // 只让开两侧：顶栏（连同压在下面的搜索、筛选）的高度垫在列表开头，列表滚到毛玻璃下面；
                // 导航栏高度垫在末尾，滚到手势条下面（见 AppScaffold）。
                // 顶栏里有搜索框：edge-to-edge 下键盘弹起会盖住列表下半截，最后几条滚不出来 ——
                // padding 里已含导航栏高度，标成已消费，免得键盘上方多一道空隙
                Modifier
                    .fillMaxSize()
                    .padding(padding.horizontalOnly())
                    .consumeWindowInsets(padding)
                    .imePadding()
                    .pageScroll(),
                contentPadding = PaddingValues(
                    top = padding.calculateTopPadding() + Dimens.gapS,
                    // 多选时底部还压着操作条：最后几行要能滚到它上面
                    bottom = padding.calculateBottomPadding() + if (selectionMode) batchBarHeight else 0.dp,
                ),
                overscrollEffect = null,
            ) {
                // 一张卡装下整个列表（HyperOS 的灰底白卡），行与行之间不留缝
                itemsIndexed(visible, key = { _, it -> it.id }) { index, source ->
                    val progress = checkProgress
                    SourceListRow(
                        source = source,
                        tag = when (source.id) {
                            in searchless.exploreOnly -> "仅发现"
                            in searchless.urlOnly -> "无搜索"
                            else -> null
                        },
                        checkState = when {
                            progress == null -> RowCheck.IDLE
                            source.id in progress.running -> RowCheck.RUNNING
                            source.id in progress.pending -> RowCheck.QUEUED
                            else -> RowCheck.IDLE
                        },
                        selected = source.id in selected,
                        selectionMode = selectionMode,
                        onClick = {
                            if (selectionMode) viewModel.toggleSelect(source.id)
                            else onOpen(source.id)
                        },
                        // 长按进多选（删除、启停、分组都在多选的底部操作条里）
                        onLongClick = { viewModel.toggleSelect(source.id) },
                        onToggleEnabled = { viewModel.setEnabled(source.id, it) },
                        modifier = Modifier.groupedCardItem(index, visible.size),
                    )
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
            title = "校验 $target 个来源",
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
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                CheckItemRow("校验详情页", draft.checkDetail) { draft = draft.copy(checkDetail = it) }
                CheckItemRow("校验目录", draft.checkToc) { draft = draft.copy(checkToc = it) }
                CheckItemRow("校验正文", draft.checkContent) { draft = draft.copy(checkContent = it) }
                Text(
                    "单源超时 ${draft.timeoutMs / 1000} 秒",
                    style = MiuixTheme.textStyles.footnote1,
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
            title = "删除来源",
            text = "确定删除选中的 ${selected.size} 个来源吗？",
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
            title = "删除失效来源",
            text = "确定删除全部 $failedCount 个失效来源吗？此操作不可撤销。",
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
            title = "从 URL 导入来源",
            text = "支持 Inkwell 与 Legado（阅读）格式的来源 JSON 链接",
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
}

/**
 * 列表上面压着的那一截：搜索、筛选 chip、导入 / 校验进度、失效汇总。放在顶栏的 bottomContent 里（见调用处）。
 */
@Composable
private fun SourceListHeader(
    query: String,
    onQueryChange: (String) -> Unit,
    filter: SourceFilter,
    onFilterChange: (SourceFilter) -> Unit,
    groups: List<String>,
    groupFilter: String?,
    onGroupFilterChange: (String?) -> Unit,
    importing: Boolean,
    checkProgress: CheckProgress?,
    onCancelCheck: () -> Unit,
    failedCount: Int,
    onDisableInvalid: () -> Unit,
    onDeleteInvalid: () -> Unit,
) {
    var showGroupPicker by remember { mutableStateOf(false) }
    Column {
        // 几百个书源，没有搜索和筛选就只能一路滑
        SearchField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = "搜索来源名称或网址",
            modifier = Modifier.fillMaxWidth()
                .padding(horizontal = Dimens.listHorizontal, vertical = Dimens.gapXS),
        )
        ChipRow(
            options = SourceFilter.entries.map { it.label },
            selectedIndex = SourceFilter.entries.indexOf(filter),
            onSelect = { onFilterChange(SourceFilter.entries[it]) },
            contentPadding = PaddingValues(horizontal = Dimens.listHorizontal),
            trailing = if (groups.isNotEmpty()) {
                {
                    // 分组从这颗 chip 上弹出来选（当前分组打勾），不再另起一个底部面板
                    Box {
                        AppFilterChip(
                            selected = groupFilter != null,
                            onClick = { showGroupPicker = true },
                            label = groupFilter ?: "分组",
                        )
                        AppDropdownMenu(
                            expanded = showGroupPicker,
                            onDismissRequest = { showGroupPicker = false },
                            items = listOf(
                                AppMenuItem(
                                    "全部分组",
                                    onClick = {
                                        showGroupPicker = false
                                        onGroupFilterChange(null)
                                    },
                                    selected = groupFilter == null,
                                ),
                            ) + groups.map { group ->
                                AppMenuItem(
                                    group,
                                    onClick = {
                                        showGroupPicker = false
                                        onGroupFilterChange(group)
                                    },
                                    selected = group == groupFilter,
                                )
                            },
                        )
                    }
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
                Text("正在导入来源…", style = MiuixTheme.textStyles.footnote1)
            }
        }
        // 校验进度：一次几百个源，得让人看到还剩多少、这一轮已经坏了几个，并且能中断。
        // 文字和「停止」一行、下面一条通栏细进度条（同换源面板的概况行）；从前进度条夹在文字和「停止」之间，
        // 「停止」上下都对不齐
        checkProgress?.let { p ->
            Row(
                Modifier.fillMaxWidth().padding(start = Dimens.listHorizontal, end = TRAILING_BUTTON_END),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    buildAnnotatedString {
                        append("正在校验 ${p.done} / ${p.total}")
                        if (p.failed > 0) {
                            append(" · ")
                            withStyle(SpanStyle(color = MiuixTheme.colorScheme.error)) { append("失效 ${p.failed}") }
                        }
                    },
                    style = MiuixTheme.textStyles.footnote1,
                    modifier = Modifier.weight(1f),
                )
                AppTextButton("停止", onClick = onCancelCheck)
            }
            DeterminateProgressBar(
                progress = if (p.total == 0) 0f else p.done.toFloat() / p.total,
                modifier = Modifier.padding(start = Dimens.listHorizontal, end = Dimens.listHorizontal, bottom = Dimens.gapXS),
            )
        }
        // 校验完最想干的事。禁用放在删除前面：删是不可逆的，而站点抽风、临时封 IP
        // 都会造成一次性的"失效"，禁用了还能改天再校验一次
        if (checkProgress == null && failedCount > 0) {
            Row(
                Modifier.fillMaxWidth().padding(start = Dimens.listHorizontal, end = TRAILING_BUTTON_END),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "失效 $failedCount 个",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                AppTextButton("只看失效", onClick = { onFilterChange(SourceFilter.FAILED) })
                AppTextButton("禁用", onClick = onDisableInvalid)
                AppTextButton(
                    text = "删除",
                    onClick = onDeleteInvalid,
                    contentColor = MiuixTheme.colorScheme.error,
                )
            }
        }
    }
}

/** 一行在这一轮校验里的位置。不在校验时都是 [IDLE] */
private enum class RowCheck {
    IDLE,
    /** 正在跑：就地转圈 */
    RUNNING,
    /** 排队还没轮到：行上是上一次的结果，变淡 */
    QUEUED,
}

@Composable
private fun SourceListRow(
    source: BookSourceListItem,
    /** 没有搜索规则的源挂在标题后的标签：「仅发现」「无搜索」；能搜索的为 null */
    tag: String?,
    checkState: RowCheck,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 行尾：平时是启用开关；多选时换成勾选框（HyperOS 编辑模式的勾在行尾，与书架列表一致）。
    // 从前平时行尾是「删除图标 + 开关」，48dp 的垃圾桶紧挨着开关，挤，也容易手滑点到 —— 删除走多选。
    // 勾选框纯展示：整行是一个复选语义目标（ContentListItem 的 checked 重载），框自己不可点，读屏不会多一个焦点
    val trailing: @Composable () -> Unit = if (selectionMode) {
        {
            Checkbox(
                state = ToggleableState(selected),
                onClick = null,
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
    } else {
        {
            Switch(
                checked = source.enabled,
                onCheckedChange = onToggleEnabled,
                // 行尾开关自己是一个焦点，得有名字：只念「开关，已开启」听不出开的是哪个源
                modifier = Modifier.semantics { contentDescription = "启用「${source.name}」" },
            )
        }
    }
    val supporting: @Composable () -> Unit = {
        Column {
            Text(
                source.id,
                style = ContentListDefaults.supportingStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // 没有搜索规则的源一步都没校验（库里只记了一句「不校验搜索」）：不写状态，标题后的标签已经说清了。
            // 从前它们和「可用」一样打蓝勾，十几个字每个这类源重复一遍
            CheckStatusLine(
                source = source,
                state = checkState,
                searchless = tag != null || CheckMessages.skippedSearch(source.checkMessage),
            )
        }
    }
    val headline: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                source.name,
                style = ContentListDefaults.titleStyle,
                maxLines = 1,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (tag != null) {
                // 淡强调底 + 强调色字（tertiaryContainer 一对），与选中的 chip 同一种「标签」语汇
                Text(
                    tag,
                    style = MiuixTheme.textStyles.footnote2,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier
                        .padding(start = Dimens.gapS)
                        .background(
                            MiuixTheme.colorScheme.tertiaryContainer,
                            AppShapes.extraSmall,
                        )
                        // 只留左右：上下贴着 footnote2 的行高，标签和标题行一样矮
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
            modifier = modifier,
            // 勾没勾只看行尾的勾选框，行不变色（同书架列表）
            colors = ContentListDefaults.plainColors(),
            trailingContent = trailing,
            supportingContent = supporting,
            content = headline,
        )
    } else {
        ContentListItem(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = modifier,
            trailingContent = trailing,
            supportingContent = supporting,
            content = headline,
        )
    }
}

/**
 * 行上的校验状态。满屏扫下去只有两种颜色：失效红（✕ + 原因）、正在跑的蓝（就地转圈）；
 * 可用的灰字、不带勾 —— 从前每行一条蓝 ✓，几百个源满屏强调色，真正要处理的红字反倒不显眼。
 * 排队还没轮到的，上一次的结果变淡：分得清哪些是这一轮刚出的、哪些还是旧账。
 * 库里的文案经 [CheckMessages] 重写再显示，旧结果也是新说法。
 */
@Composable
private fun CheckStatusLine(source: BookSourceListItem, state: RowCheck, searchless: Boolean) {
    if (searchless) return
    if (state == RowCheck.RUNNING) {
        Row(
            Modifier.padding(top = Dimens.gapXS),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.gapXS),
        ) {
            AppLoadingIndicator(size = Dimens.buttonSpinner)
            Text(
                "正在校验…",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.primary,
                maxLines = 1,
            )
        }
        return
    }
    if (source.checkStatus == CheckStatus.UNCHECKED) return
    val ok = source.checkStatus == CheckStatus.OK
    val color = if (ok) MiuixTheme.colorScheme.onSurfaceVariantSummary else MiuixTheme.colorScheme.error
    Row(
        Modifier
            .padding(top = Dimens.gapXS)
            .alpha(if (state == RowCheck.QUEUED) STALE_ALPHA else 1f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.gapXS),
    ) {
        if (!ok) {
            Icon(MiuixIcons.Close, contentDescription = null, Modifier.size(Dimens.iconSm), tint = color)
        }
        Text(
            if (ok) {
                CheckMessages.okLine(source.respondTime, source.checkMessage)
            } else {
                CheckMessages.display(source.checkMessage)
            },
            style = MiuixTheme.textStyles.footnote1,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
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
        Text(label, Modifier.weight(1f), style = MiuixTheme.textStyles.body2)
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** 排队还没轮到的行：上一次的结果淡到这个程度（同禁用态的 0.38） */
private const val STALE_ALPHA = 0.38f

/**
 * 行尾是文字按钮的那一行（「停止」「删除」）右边只留这么多：按钮自带 gapM 的左右内边距，
 * 4 + 12 = 16，按钮的字和上面的搜索框、下面通栏进度条的右端对齐
 */
private val TRAILING_BUTTON_END = Dimens.gapXS
