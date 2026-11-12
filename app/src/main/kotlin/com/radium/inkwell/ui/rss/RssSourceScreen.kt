package com.radium.inkwell.ui.rss

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.RssFeed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radium.inkwell.data.db.entity.RssSourceEntity
import com.radium.inkwell.ui.components.AppAlertDialog
import com.radium.inkwell.ui.components.AppDropdownMenu
import com.radium.inkwell.ui.components.AppIconButton
import com.radium.inkwell.ui.components.AppLoadingIndicator
import com.radium.inkwell.ui.components.AppMenuItem
import com.radium.inkwell.ui.components.AppScaffold
import com.radium.inkwell.ui.components.AppSnackbarHost
import com.radium.inkwell.ui.components.AppToolbar
import com.radium.inkwell.ui.components.BackButton
import com.radium.inkwell.ui.components.BatchAction
import com.radium.inkwell.ui.components.BatchActionBar
import com.radium.inkwell.ui.components.CollectExportShares
import com.radium.inkwell.ui.components.CollectMessages
import com.radium.inkwell.ui.components.CompactTextField
import com.radium.inkwell.ui.components.ContentListDefaults
import com.radium.inkwell.ui.components.ContentListItem
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.ui.components.EmptyState
import com.radium.inkwell.ui.components.GroupFilterChip
import com.radium.inkwell.ui.components.InlineTag
import com.radium.inkwell.ui.components.ListSearchHeader
import com.radium.inkwell.ui.components.LoadingState
import com.radium.inkwell.ui.components.SettingFootnote
import com.radium.inkwell.ui.components.ToolbarTitle
import com.radium.inkwell.ui.components.groupedCardItem
import com.radium.inkwell.ui.components.highlightMatches
import com.radium.inkwell.ui.components.horizontalOnly
import com.radium.inkwell.ui.components.pageScroll
import com.radium.inkwell.ui.components.settingsPageColor
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
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.icon.extended.SelectAll
import top.yukonga.miuix.kmp.icon.extended.Share
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun RssSourceScreen(
    onBack: () -> Unit,
    onOpenSource: (String) -> Unit,
    /** 单网页源（阅读的 singleUrl）：点开是网页页，不是文章列表 */
    onOpenWebSource: (String) -> Unit,
    viewModel: RssSourceViewModel,
) {
    val list by viewModel.list.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val groupFilter by viewModel.group.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val webSourceIds by viewModel.webSourceIds.collectAsStateWithLifecycle()
    val importing by viewModel.importing.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    CollectMessages(viewModel.messages, snackbar)
    CollectExportShares(viewModel.exportFile, chooserTitle = "导出订阅源")

    var showImportMenu by remember { mutableStateOf(false) }
    var showUrlImport by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }
    var confirmBatchDelete by remember { mutableStateOf(false) }
    var showGroupAssign by remember { mutableStateOf(false) }
    var groupInput by remember { mutableStateOf("") }
    // 多选态底部操作条占掉的高度（BatchActionBar 量好报上来），垫到列表末尾
    var batchBarHeight by remember { mutableStateOf(0.dp) }
    val selectionMode = selected.isNotEmpty()
    // 多选态下按系统返回：先退出多选，而不是直接退出整个页面
    BackHandler(selectionMode) { viewModel.clearSelection() }

    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.importFromFile(it) } }

    val pageColor = settingsPageColor()
    // 搜索框 + 筛选压在标题下面（顶栏的 bottomContent），同来源管理、净化替换。进出多选的两条栏都带上它，栏高不变
    val current = list
    val listHeader: @Composable () -> Unit = {
        if (current != null && current.all.isNotEmpty()) {
            ListSearchHeader(
                query = query,
                onQueryChange = viewModel::setQuery,
                placeholder = "搜索订阅源名称或地址",
                filters = RssSourceFilter.entries.map { it.label },
                selectedFilter = RssSourceFilter.entries.indexOf(filter),
                onFilterChange = { viewModel.setFilter(RssSourceFilter.entries[it]) },
                // 从阅读导入的订阅源合集常带分组（新闻、小说、漫画…）；一个源都没分组时不出这颗 chip
                trailing = if (current.groups.isNotEmpty()) {
                    { GroupFilterChip(current.groups, groupFilter, viewModel::setGroupFilter) }
                } else {
                    null
                },
            )
        }
    }
    AppScaffold(
        // 两条都用窄栏（AppToolbar），不用 AppTopBar 的大标题折叠栏（同来源管理）：这页有多选态，
        // 上下文操作栏与常规栏来回切，高度不同进出多选就像跳了一下；大标题栏夹着搜索框也会忽高忽低
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
                                Icon(MiuixIcons.Add, contentDescription = "添加订阅源")
                            }
                        }
                        AppDropdownMenu(
                            expanded = showImportMenu,
                            onDismissRequest = { showImportMenu = false },
                            items = listOf(
                                AppMenuItem("粘贴地址或订阅源", onClick = {
                                    showImportMenu = false
                                    importText = ""
                                    showUrlImport = true
                                }),
                                AppMenuItem("从剪贴板导入", onClick = {
                                    showImportMenu = false
                                    viewModel.importFromClipboard()
                                }),
                                AppMenuItem("从文件导入", onClick = {
                                    showImportMenu = false
                                    fileLauncher.launch(
                                        arrayOf("application/json", "text/plain", "application/octet-stream")
                                    )
                                }),
                            ),
                        )
                    }
                },
                bottomContent = listHeader,
            ) {
                ToolbarTitle("订阅源")
            }
        },
        snackbarHost = { AppSnackbarHost(snackbar) },
        // 批量动作：图标在上、字在下，导出走系统分享（分享的图标）；设分组低频，收进「更多」
        floatingToolbar = {
            BatchActionBar(
                visible = selectionMode,
                actions = listOf(
                    BatchAction(MiuixIcons.Ok, "启用", onClick = { viewModel.setEnabledSelected(true) }),
                    BatchAction(MiuixIcons.Blocklist, "禁用", onClick = { viewModel.setEnabledSelected(false) }),
                    BatchAction(MiuixIcons.Share, "导出", onClick = viewModel::exportSelected),
                    BatchAction(MiuixIcons.Delete, "删除", onClick = { confirmBatchDelete = true }, destructive = true),
                ),
                more = listOf(
                    AppMenuItem("设置分组", onClick = {
                        groupInput = ""
                        showGroupAssign = true
                    }),
                ),
                onHeightChange = { batchBarHeight = it },
            )
        },
    ) { padding ->
        // null = 库还没读回来：先空着，别闪一下「还没有订阅源」
        val l = current ?: return@AppScaffold
        if (l.all.isEmpty() && importing) {
            LoadingState(Modifier.padding(padding), label = "正在导入订阅源…")
        } else if (l.all.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.RssFeed,
                title = "还没有订阅源",
                hint = "直接粘一个 RSS/Atom 地址就能订阅，也支持导入 Legado 订阅源",
                actionLabel = "添加订阅源",
                onAction = { importText = ""; showUrlImport = true },
                modifier = Modifier.padding(padding),
            )
        } else if (l.visible.isEmpty()) {
            // 有订阅源、显示的却是空的，只可能是搜了或筛了：说一句并给出口，不是一片空白（同来源管理）
            EmptyState(
                icon = MiuixIcons.Search,
                title = "没有符合条件的订阅源",
                hint = "换个关键词，或放宽筛选条件",
                actionLabel = "清除筛选",
                onAction = viewModel::clearFilters,
                modifier = Modifier.padding(padding).consumeWindowInsets(padding).imePadding(),
            )
        } else {
            // 只让开两侧：顶栏（连同压在下面的搜索、筛选）的高度垫在列表开头，列表滚到毛玻璃下面；
            // 导航栏高度垫在末尾（见 AppScaffold）。顶栏里有搜索框：键盘弹起会盖住列表下半截，
            // padding 里已含导航栏高度，标成已消费，免得键盘上方多一道空隙
            LazyColumn(
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
                itemsIndexed(l.visible, key = { _, it -> it.id }) { index, source ->
                    val isWeb = source.id in webSourceIds
                    SourceRow(
                        source = source,
                        isWeb = isWeb,
                        query = query,
                        selectionMode = selectionMode,
                        selected = source.id in selected,
                        onClick = {
                            when {
                                selectionMode -> viewModel.toggleSelect(source.id)
                                isWeb -> onOpenWebSource(source.id)
                                else -> onOpenSource(source.id)
                            }
                        },
                        // 长按进多选（同来源管理、书架）：删除、启停、导出、分组都在底部的操作条里
                        onLongClick = { viewModel.toggleSelect(source.id) },
                        onToggle = { viewModel.setEnabled(source.id, it) },
                        modifier = Modifier.groupedCardItem(index, l.visible.size),
                    )
                }
                // 开关管的是「订阅」页里出不出现它的文章：说一句，不然关掉一个源，人不知道关了是什么效果
                item(key = "footnote", contentType = "footnote") {
                    SettingFootnote(
                        "关掉的订阅源不出现在「订阅」里。网页类订阅源没有文章列表，在这里点开。" +
                            "长按可以多选，批量启用、禁用、删除。",
                        Modifier.padding(top = Dimens.gapXS),
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
                    "RSS/Atom 地址、源仓库里订阅源合集的地址、阅读的一键导入链接，或者整段订阅源 JSON 都行。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            },
        )
    }

    if (showGroupAssign) {
        // 关掉时顺手清空：下次再开别还躺着上次的组名（同来源管理）
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
                viewModel.setGroupSelected(name)
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
            title = "删除订阅源",
            text = "确定删除选中的 ${selected.size} 个订阅源吗？",
            confirmText = "删除",
            onConfirm = {
                confirmBatchDelete = false
                viewModel.deleteSelected()
            },
        )
    }
}

/**
 * 一个订阅源：点开看文章（网页类的点开是网页）；行尾平时是开关 —— HyperOS 的列表行尾就是一个开关或一个 ›。
 * 长按进多选，多选时行尾换成勾选框（勾在行尾，同来源管理、书架列表），整行一个复选语义目标、行不变色。
 * 从前长按只弹一个「删除」，一次一个；更早行尾是「删除图标 + 开关」，48dp 的垃圾桶紧挨着开关，挤，也容易手滑点到。
 *
 * 搜索时名称、地址里命中的字标强调色（同来源管理、净化替换）。
 */
@Composable
private fun SourceRow(
    source: RssSourceEntity,
    isWeb: Boolean,
    query: String,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = MiuixTheme.colorScheme.primary
    val headline: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                remember(source.name, query, accent) { highlightMatches(source.name, query, accent) },
                style = ContentListDefaults.titleStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            // 点开是网页、不是文章列表：灰标签，和来源管理的「仅发现」一个样子
            if (isWeb) InlineTag("网页", neutral = true)
        }
    }
    val supporting: @Composable () -> Unit = {
        Text(
            remember(source.id, query, accent) { highlightMatches(source.id, query, accent) },
            style = ContentListDefaults.supportingStyle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    if (selectionMode) {
        ContentListItem(
            checked = selected,
            onCheckedChange = { onClick() },
            onLongClick = onLongClick,
            modifier = modifier,
            // 勾没勾只看行尾的勾选框，行不变色；关着的源也不变淡 —— 多选时要看清的是勾，不是开关
            colors = ContentListDefaults.plainColors(),
            // 勾选框纯展示：整行是一个复选语义目标，框自己不可点，读屏不会多一个焦点
            trailingContent = {
                Checkbox(state = ToggleableState(selected), onClick = null, modifier = Modifier.clearAndSetSemantics {})
            },
            supportingContent = supporting,
            content = headline,
        )
    } else {
        ContentListItem(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = modifier,
            // 关着的源字变淡：一眼看出哪些不出现在「订阅」里。不用 enabled = false —— 那会连点击、长按一起关掉，
            // 从前关着的源点不开、长按也弹不出菜单；现在长按要进多选，「已禁用」筛出来得能选上
            colors = if (source.enabled) ContentListDefaults.colors() else ContentListDefaults.dimmedColors(),
            trailingContent = {
                Switch(
                    checked = source.enabled,
                    onCheckedChange = onToggle,
                    // 行尾开关自己是一个焦点，得有名字：只念「开关，已开启」听不出开的是哪个源
                    modifier = Modifier.semantics { contentDescription = "启用「${source.name}」" },
                )
            },
            supportingContent = supporting,
            content = headline,
        )
    }
}
