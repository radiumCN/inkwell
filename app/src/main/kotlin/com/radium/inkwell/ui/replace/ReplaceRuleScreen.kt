package com.radium.inkwell.ui.replace

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.radium.inkwell.data.db.entity.ReplaceRuleEntity
import com.radium.inkwell.ui.components.AppAlertDialog
import com.radium.inkwell.ui.components.AppBottomSheet
import com.radium.inkwell.ui.components.AppDropdownMenu
import com.radium.inkwell.ui.components.AppFormField
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
import com.radium.inkwell.ui.components.InlineTag
import com.radium.inkwell.ui.components.ListSearchHeader
import com.radium.inkwell.ui.components.SectionHeader
import com.radium.inkwell.ui.components.SettingGroup
import com.radium.inkwell.ui.components.SettingGroupPosition
import com.radium.inkwell.ui.components.SwitchRow
import com.radium.inkwell.ui.components.ToolbarTitle
import com.radium.inkwell.ui.components.bottomBarEnter
import com.radium.inkwell.ui.components.bottomBarExit
import com.radium.inkwell.ui.components.cardMargin
import com.radium.inkwell.ui.components.groupedCardItem
import com.radium.inkwell.ui.components.highlightMatches
import com.radium.inkwell.ui.components.horizontalOnly
import com.radium.inkwell.ui.components.pageScroll
import com.radium.inkwell.ui.components.settingsPageColor
import com.radium.inkwell.ui.components.sheetBottomInset
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.FloatingActionButton
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Blocklist
import top.yukonga.miuix.kmp.icon.extended.Clear
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Import
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.icon.extended.SelectAll
import top.yukonga.miuix.kmp.icon.extended.Share
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun ReplaceRuleScreen(
    onBack: () -> Unit,
    viewModel: ReplaceRuleViewModel,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showImportMenu by remember { mutableStateOf(false) }
    var showUrlImport by remember { mutableStateOf(false) }
    var importUrl by remember { mutableStateOf("") }
    var confirmBatchDelete by remember { mutableStateOf(false) }
    // 多选态底部操作条占掉的高度（BatchActionBar 量好报上来），垫到列表末尾
    var batchBarHeight by remember { mutableStateOf(0.dp) }
    val selectionMode = state.selected.isNotEmpty()
    // 多选态下按系统返回：先退出多选，而不是直接退出整个页面
    BackHandler(selectionMode) { viewModel.clearSelection() }

    CollectMessages(viewModel.messages, snackbar)
    CollectExportShares(viewModel.exportFile, chooserTitle = "导出净化规则")

    val fileImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.importFromFile(it) } }

    // 阅读导出的是 .json；有人存成 .txt，也有文件管理器报不出类型（octet-stream）
    fun launchFileImport() =
        fileImportLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))

    val pageColor = settingsPageColor()
    // 搜索框 + 筛选压在标题下面（顶栏的 bottomContent）：列表从它底下滚过去、和顶栏一起被毛玻璃盖住，
    // 搜索框不随列表滚走。进出多选的两条栏都带上它，栏高不变
    val showList = state.rulesLoaded && state.rules.isNotEmpty()
    val listHeader: @Composable () -> Unit = {
        if (showList) {
            ListSearchHeader(
                query = state.query,
                onQueryChange = viewModel::setQuery,
                placeholder = "搜索规则名称或内容",
                filters = RuleFilter.entries.map { it.label },
                selectedFilter = RuleFilter.entries.indexOf(state.filter),
                onFilterChange = { viewModel.setFilter(RuleFilter.entries[it]) },
            )
        }
    }
    AppScaffold(
        // 两条都用窄栏（AppToolbar），不用 AppTopBar 的大标题折叠栏（同来源管理）：这页有多选态，
        // 上下文操作栏与常规栏来回切，高度不同进出多选就像跳了一下；大标题栏夹着搜索框也会忽高忽低
        containerColor = pageColor,
        topBar = {
            if (selectionMode) {
                // HyperOS 编辑模式：顶栏只留 × · 已选 N 条 · 全选，批量动作在底部的操作条里（见 floatingToolbar）
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
                    ToolbarTitle("已选 ${state.selected.size} 条")
                }
            } else AppToolbar(
                navigationIcon = { BackButton(onClick = onBack) },
                containerColor = pageColor,
                actions = {
                    // 导入（同来源管理的「＋」：一个玻璃圆，点开选从哪儿导）。新建仍是右下角的悬浮按钮
                    Box {
                        AppIconButton(onClick = { showImportMenu = true }, enabled = !state.importLoading) {
                            if (state.importLoading) {
                                AppLoadingIndicator(color = LocalContentColor.current, size = Dimens.buttonSpinner)
                            } else {
                                Icon(MiuixIcons.Import, contentDescription = "导入规则")
                            }
                        }
                        AppDropdownMenu(
                            expanded = showImportMenu,
                            onDismissRequest = { showImportMenu = false },
                            items = listOf(
                                AppMenuItem(
                                    "从文件导入",
                                    onClick = {
                                        showImportMenu = false
                                        launchFileImport()
                                    },
                                    summary = ".json / .txt，Inkwell 与阅读的格式",
                                ),
                                AppMenuItem(
                                    "从网络链接导入",
                                    onClick = {
                                        showImportMenu = false
                                        showUrlImport = true
                                    },
                                    summary = "粘贴规则的地址",
                                ),
                                AppMenuItem(
                                    "从剪贴板导入",
                                    onClick = {
                                        showImportMenu = false
                                        viewModel.importFromClipboard()
                                    },
                                    summary = "复制好的规则或链接",
                                ),
                            ),
                        )
                    }
                },
                bottomContent = listHeader,
            ) {
                ToolbarTitle("净化替换")
            }
        },
        floatingActionButton = {
            // 多选时收起来，底部让给操作条（同一组进退令牌，一个滑下去、一个滑上来）
            AnimatedVisibility(visible = !selectionMode, enter = bottomBarEnter(), exit = bottomBarExit()) {
                FloatingActionButton(onClick = viewModel::newRule) {
                    // Miuix 的悬浮按钮不替内容设前景色，图标色自己给
                    Icon(MiuixIcons.Add, contentDescription = "新建规则", tint = MiuixTheme.colorScheme.onPrimary)
                }
            }
        },
        snackbarHost = { AppSnackbarHost(snackbar) },
        // 批量动作：图标在上、字在下。导出走系统分享，所以用分享的图标
        floatingToolbar = {
            BatchActionBar(
                visible = selectionMode,
                actions = listOf(
                    BatchAction(MiuixIcons.Ok, "启用", onClick = { viewModel.setEnabledSelected(true) }),
                    BatchAction(MiuixIcons.Blocklist, "禁用", onClick = { viewModel.setEnabledSelected(false) }),
                    BatchAction(MiuixIcons.Share, "导出", onClick = viewModel::exportSelected),
                    BatchAction(MiuixIcons.Delete, "删除", onClick = { confirmBatchDelete = true }, destructive = true),
                ),
                onHeightChange = { batchBarHeight = it },
            )
        },
    ) { padding ->
        if (!state.rulesLoaded) {
            // 库还没读回来：先空着，别闪一下「还没有净化规则」
        } else if (state.rules.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding)) {
                EmptyState(
                    icon = MiuixIcons.Clear,
                    title = "还没有净化规则",
                    hint = "用来删掉正文里的广告、水印和防盗段落。" +
                        "支持导入阅读（Legado）格式的净化规则，链接和剪贴板在右上角",
                    actionLabel = "从文件导入",
                    onAction = ::launchFileImport,
                )
            }
        } else if (state.visibleRules.isEmpty() && state.narrowed) {
            // 搜不到 / 筛完是空的：说一句并给出口，不是一片空白（同来源管理）
            EmptyState(
                icon = MiuixIcons.Search,
                title = "没有符合条件的规则",
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
                    // 平时给悬浮的「新建」按钮让位（Miuix 悬浮按钮 60dp 高 + 边距）：不留的话最后一条行尾的开关
                    // 正好压在按钮下面，还滚不出来；按钮坐在导航栏上面，所以再加上导航栏的高度。
                    // 多选时按钮收起，底部压着的是操作条：最后几行要能滚到它上面
                    bottom = padding.calculateBottomPadding() +
                        if (selectionMode) batchBarHeight else Dimens.touchTarget + Dimens.gapXXL,
                ),
                overscrollEffect = null,
            ) {
                // 一张卡装下整个列表（HyperOS 的灰底白卡），行与行之间不留缝
                itemsIndexed(state.visibleRules, key = { _, it -> it.id }) { index, rule ->
                    RuleRow(
                        rule = rule,
                        query = state.query,
                        selectionMode = selectionMode,
                        selected = rule.id in state.selected,
                        onClick = {
                            if (selectionMode) viewModel.toggleSelect(rule.id) else viewModel.edit(rule)
                        },
                        // 长按进多选（同来源管理、书架）：删除、启停、导出都在底部的操作条里
                        onLongClick = { viewModel.toggleSelect(rule.id) },
                        onToggle = { viewModel.setEnabled(rule, it) },
                        modifier = Modifier.groupedCardItem(index, state.visibleRules.size),
                    )
                }
            }
        }
    }

    state.editing?.let { draft ->
        // 有没保存的改动时，下滑、按返回都不能一碰就关 —— 从前一碰就关，填了半天的正则说没就没。
        // Miuix 面板是先把面板动画移出屏幕、再回调关闭请求，回调里说不就晚了，所以分三路拦：
        // - 有改动时 allowDismiss = false：下滑、点遮罩一律弹回原位，不关；
        // - 返回键另挂一个 NavigationBackHandler 在面板内容里：比面板自己的那个后注册，优先拿到返回事件，
        //   有改动时弹「放弃编辑？」；
        // - 标题行左边的 ×（HyperOS 编辑面板的「取消」位）：有改动先问，没改动直接收。
        // 「放弃」之后自己收起面板，那一下要放行（discarding），否则又被这里拦回去。
        var askDiscard by remember { mutableStateOf(false) }
        var discarding by remember { mutableStateOf(false) }
        val guarded = state.editorDirty && !discarding
        AppBottomSheet(
            // 动画播完才让 ViewModel 清掉草稿：先清的话面板当场从组合树摘掉，退场动画被掐断
            onDismissRequest = viewModel::dismissEditor,
            title = if (draft.id.isEmpty()) "新建净化规则" else "编辑净化规则",
            startAction = {
                AppIconButton(onClick = { if (guarded) askDiscard = true else dismiss() }) {
                    Icon(MiuixIcons.Close, contentDescription = "关闭")
                }
            },
            // HyperOS 编辑面板的「✓」= 保存（从前是表单末尾右下角一个「保存」按钮，长表单得滚到底才找得到）。
            // 正则写错时不让点（错误标在「替换什么」下面）；保存中换成转圈，占同一个 48dp 的位
            endAction = {
                if (state.saving) {
                    Box(Modifier.size(Dimens.touchTarget), contentAlignment = Alignment.Center) {
                        AppLoadingIndicator(size = Dimens.buttonSpinner)
                    }
                } else {
                    AppIconButton(onClick = viewModel::save, enabled = state.patternError == null) {
                        Icon(MiuixIcons.Ok, contentDescription = "保存")
                    }
                }
            },
            allowDismiss = !guarded,
            // 表单会滚动：导航栏高度垫在表单末尾（见 Editor）
            padNavigationBar = false,
        ) {
            val backState = rememberNavigationEventState(currentInfo = NavigationEventInfo.None)
            NavigationBackHandler(
                state = backState,
                isBackEnabled = guarded,
                onBackCompleted = { askDiscard = true },
            )
            Editor(
                draft = draft,
                sampleText = state.sampleText,
                sampleResult = state.sampleResult,
                patternError = state.patternError,
                onChange = viewModel::updateDraft,
                onSampleChange = viewModel::updateSample,
            )
            // 放在面板内容里，「放弃」才拿得到 dismiss()
            if (askDiscard) {
                AppAlertDialog(
                    onDismissRequest = { askDiscard = false },
                    title = "放弃编辑？",
                    text = "这条规则的改动还没保存，关掉就没了。",
                    confirmText = "放弃",
                    dismissText = "继续编辑",
                    onConfirm = {
                        askDiscard = false
                        discarding = true
                        dismiss()
                    },
                )
            }
        }
    }

    state.importItems?.let { items ->
        ReplaceRuleImportSheet(
            items = items,
            selected = state.importSelected,
            onToggle = viewModel::toggleImport,
            onSelectAll = viewModel::setAllImport,
            onConfirm = viewModel::confirmImport,
            onDismiss = viewModel::dismissImport,
        )
    }

    if (showUrlImport) {
        // 关掉时顺手清空：否则下次再开还躺着上次的链接，像「改不动」（同来源管理）
        fun dismissUrlImport() {
            showUrlImport = false
            importUrl = ""
        }
        AppAlertDialog(
            onDismissRequest = { dismissUrlImport() },
            title = "从网络链接导入",
            text = "支持 Inkwell 与阅读（Legado）格式的净化规则链接，阅读分享出来的链接也行",
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
                    placeholder = "https://…/replaceRule.json",
                )
            },
        )
    }

    if (confirmBatchDelete) {
        val count = state.selected.size
        AppAlertDialog(
            onDismissRequest = { confirmBatchDelete = false },
            title = "删除规则",
            text = "删除选中的 $count 条规则？已缓存的章节不会重新净化，需要清除正文缓存后重读。",
            confirmText = "删除",
            onConfirm = {
                confirmBatchDelete = false
                viewModel.deleteSelected()
            },
        )
    }
}

/**
 * 一条规则：点开编辑；行尾平时是开关 —— HyperOS 的列表行尾就是一个开关或一个 ›。
 * 长按进多选，多选时行尾换成勾选框（勾在行尾，同来源管理、书架列表），整行一个复选语义目标、行不变色。
 * 从前长按只弹一个「删除」，一次一条；更早行尾是「删除图标 + 开关」，48dp 的垃圾桶紧挨着开关，挤，也容易手滑点到。
 *
 * 搜索时名字、规则内容里命中的字标强调色（[highlightMatches]，同目录搜索）：净化规则常常是名字里没有、
 * 内容里命中，不标出来看不出为什么搜到它。
 */
@Composable
private fun RuleRow(
    rule: ReplaceRuleEntity,
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
                remember(rule.name, query, accent) { highlightMatches(rule.name, query, accent) },
                style = ContentListDefaults.titleStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            // 阅读页长按建的规则只对那一本书生效，列表里得看得出来，否则用户会纳闷「这条规则怎么在别的书里不管用」。
            // 从前写在说明行最后，规则内容一长就被省略号吃掉了；挪到名字后面当标签（同订阅源的「网页」）
            if (rule.bookId.isNotEmpty()) InlineTag("本书专属", neutral = true)
        }
    }
    val supporting: @Composable () -> Unit = {
        Text(
            remember(rule, query, accent) { ruleSummary(rule, query, accent) },
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
            // 勾没勾只看行尾的勾选框，行不变色（同来源管理、书架列表）
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
            trailingContent = {
                Switch(
                    checked = rule.enabled,
                    onCheckedChange = onToggle,
                    // 行尾开关自己是一个焦点，得有名字：只念「开关，已开启」听不出开的是哪条规则
                    modifier = Modifier.semantics { contentDescription = "启用「${rule.name}」" },
                )
            },
            supportingContent = supporting,
            content = headline,
        )
    }
}

/**
 * 说明行：「正则  规则内容  →  替换成  ·  仅 作用范围」。命中搜索词的只标规则内容、替换成、作用范围这几段 ——
 * 搜「正则」不该把开头那个类型标亮，它不是搜到这条的原因。
 */
private fun ruleSummary(rule: ReplaceRuleEntity, query: String, accent: Color): AnnotatedString = buildAnnotatedString {
    append(if (rule.isRegex) "正则  " else "文本  ")
    append(highlightMatches(rule.pattern, query, accent))
    if (rule.replacement.isNotEmpty()) {
        append("  →  ")
        append(highlightMatches(rule.replacement, query, accent))
    }
    if (rule.scope.isNotBlank()) {
        append("  ·  仅 ")
        append(highlightMatches(rule.scope, query, accent))
    }
}

/**
 * 规则表单，照 Miuix 示例 App 的底部面板排：输入框直接放在面板上、离两边 [cardMargin]（24dp）；开关装进一张卡；
 * 「试一下」是一个分组小标题。保存在标题行右边的 ✓（见调用处）。
 */
@Composable
private fun Editor(
    draft: ReplaceRuleEntity,
    sampleText: String,
    sampleResult: String?,
    patternError: String?,
    onChange: (ReplaceRuleEntity) -> Unit,
    onSampleChange: (String) -> Unit,
) {
    // 输入框离面板边 cardMargin；卡外的字（试出来的结果）与输入框里的字、卡里的字对齐，再缩一档
    val field = Modifier.padding(horizontal = cardMargin())
    val text = Modifier.padding(horizontal = cardMargin() + Dimens.listHorizontal)
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = Dimens.sheetEditorMaxHeight)
            .verticalScroll(rememberScrollState())
            // Miuix 面板铺到屏幕底、不替内容让导航栏：导航栏高度垫在滚动内容末尾（见 sheetBottomInset）
            .padding(bottom = Dimens.gapXXL + sheetBottomInset()),
        verticalArrangement = Arrangement.spacedBy(Dimens.gapM),
    ) {
        // 标题在面板自带的标题行里（居中，左边 ×、右边 ✓），这里不再排一行
        AppFormField(
            value = draft.name,
            onValueChange = { onChange(draft.copy(name = it)) },
            label = "名称",
            modifier = field,
            placeholder = "留空则用规则内容代替",
            singleLine = true,
        )
        AppFormField(
            value = draft.pattern,
            onValueChange = { onChange(draft.copy(pattern = it)) },
            label = "替换什么",
            modifier = field,
            singleLine = false,
            isError = patternError != null,
            supportingText = patternError,
        )
        AppFormField(
            value = draft.replacement,
            onValueChange = { onChange(draft.copy(replacement = it)) },
            label = "替换成什么",
            modifier = field,
            placeholder = "留空即删除",
            singleLine = false,
        )
        // 开关和 Miuix 示例面板里的一样装在卡里（SettingGroup 自己按 cardMargin 让开两边）。
        // SwitchRow 整行就是一个开关，读屏一个焦点
        SettingGroup {
            SwitchRow(
                title = "使用正则表达式",
                subtitle = if (draft.isRegex) "按正则匹配" else "按纯文本原样匹配",
                checked = draft.isRegex,
                onCheckedChange = { onChange(draft.copy(isRegex = it)) },
                position = SettingGroupPosition.Alone,
            )
        }
        AppFormField(
            value = draft.scope,
            onValueChange = { onChange(draft.copy(scope = it)) },
            label = "作用范围",
            modifier = field,
            placeholder = "留空 = 所有来源；否则填来源名或域名，逗号分隔",
            singleLine = true,
        )

        // 净化是有损的：一条贪婪的正则能把整章正文删空，而在阅读页只表现为
        // 「正文规则未匹配到内容」，事后根本查不出是净化干的。所以保存前先让用户看见结果。
        SectionHeader("试一下")
        AppFormField(
            value = sampleText,
            onValueChange = onSampleChange,
            label = "粘一段正文进来",
            modifier = field,
            singleLine = false,
            minLines = 2,
            maxLines = 4,
        )
        // sampleResult 为 null = 规则还没写、或正则有误（错误已标在「替换什么」下面）：不显示结果，
        // 更不能当成「被删光了」标红
        if (sampleText.isNotEmpty() && sampleResult != null) {
            Column(text) {
                Text(
                    "替换后",
                    style = MiuixTheme.textStyles.footnote1,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Text(
                    sampleResult.ifEmpty { "（整段被删光了 —— 这条规则大概太贪婪）" },
                    style = MiuixTheme.textStyles.footnote1,
                    color = if (sampleResult.isEmpty()) MiuixTheme.colorScheme.error
                    else MiuixTheme.colorScheme.onSurface,
                )
            }
        }
    }
}
