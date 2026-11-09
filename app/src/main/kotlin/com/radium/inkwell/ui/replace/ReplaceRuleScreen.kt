package com.radium.inkwell.ui.replace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import com.radium.inkwell.ui.components.AppTopBar
import com.radium.inkwell.ui.components.CollectMessages
import com.radium.inkwell.ui.components.ContentListDefaults
import com.radium.inkwell.ui.components.ContentListItem
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.ui.components.EmptyState
import com.radium.inkwell.ui.components.SectionHeader
import com.radium.inkwell.ui.components.SettingGroup
import com.radium.inkwell.ui.components.SettingGroupPosition
import com.radium.inkwell.ui.components.SwitchRow
import com.radium.inkwell.ui.components.cardMargin
import com.radium.inkwell.ui.components.groupedCardItem
import com.radium.inkwell.ui.components.horizontalOnly
import com.radium.inkwell.ui.components.pageScroll
import com.radium.inkwell.ui.components.rememberAppTopBarScroll
import com.radium.inkwell.ui.components.settingsPageColor
import com.radium.inkwell.ui.components.sheetBottomInset
import com.radium.inkwell.ui.components.topBarScroll
import top.yukonga.miuix.kmp.basic.FloatingActionButton
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Clear
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun ReplaceRuleScreen(
    onBack: () -> Unit,
    viewModel: ReplaceRuleViewModel,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var pendingDelete by remember { mutableStateOf<ReplaceRuleEntity?>(null) }
    // 长按哪条，就从哪条上弹出它的菜单（同书架长按一本书）
    var menuTarget by remember { mutableStateOf<String?>(null) }

    CollectMessages(viewModel.messages, snackbar)

    val pageColor = settingsPageColor()
    val topBarScroll = rememberAppTopBarScroll()
    AppScaffold(
        modifier = Modifier.topBarScroll(topBarScroll),
        containerColor = pageColor,
        topBar = { AppTopBar("净化替换", topBarScroll, onBack = onBack, containerColor = pageColor) },
        floatingActionButton = {
            FloatingActionButton(onClick = viewModel::newRule) {
                // Miuix 的悬浮按钮不替内容设前景色，图标色自己给
                Icon(MiuixIcons.Add, contentDescription = "新建规则", tint = MiuixTheme.colorScheme.onPrimary)
            }
        },
        snackbarHost = { AppSnackbarHost(snackbar) },
    ) { padding ->
        if (!state.rulesLoaded) {
            // 库还没读回来：先空着，别闪一下「还没有净化规则」
        } else if (state.rules.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding)) {
                EmptyState(
                    icon = MiuixIcons.Clear,
                    title = "还没有净化规则",
                    hint = "用来删掉正文里的广告、水印和防盗段落",
                )
            }
        } else {
            // 只让开两侧：顶栏高度垫在列表开头（列表滚到毛玻璃顶栏下面），导航栏高度垫在末尾（见 AppScaffold）
            LazyColumn(
                Modifier.fillMaxSize().padding(padding.horizontalOnly()).pageScroll(),
                // 底部给悬浮的「新建」按钮让位（Miuix 悬浮按钮 60dp 高 + 边距）：不留的话，最后一条行尾的开关
                // 正好压在按钮下面，还滚不出来。按钮本身坐在导航栏上面，所以再加上导航栏的高度
                contentPadding = PaddingValues(
                    top = padding.calculateTopPadding() + Dimens.gapS,
                    bottom = Dimens.touchTarget + Dimens.gapXXL + padding.calculateBottomPadding(),
                ),
                overscrollEffect = null,
            ) {
                // 一张卡装下整个列表（HyperOS 的灰底白卡），行与行之间不留缝
                itemsIndexed(state.rules, key = { _, it -> it.id }) { index, rule ->
                    // 菜单和行放进同一个 Box，从这一行上弹出来（见 AppDropdownMenu）
                    Box(Modifier.groupedCardItem(index, state.rules.size)) {
                        RuleRow(
                            rule = rule,
                            onClick = { viewModel.edit(rule) },
                            onLongClick = { menuTarget = rule.id },
                            onToggle = { viewModel.setEnabled(rule, it) },
                        )
                        AppDropdownMenu(
                            expanded = menuTarget == rule.id,
                            onDismissRequest = { menuTarget = null },
                            items = listOf(
                                AppMenuItem("删除", onClick = {
                                    menuTarget = null
                                    pendingDelete = rule
                                }),
                            ),
                        )
                    }
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

    pendingDelete?.let { rule ->
        AppAlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = "删除规则",
            text = "删除「${rule.name}」？已缓存的章节不会重新净化，需要清除正文缓存后重读。",
            confirmText = "删除",
            onConfirm = {
                viewModel.delete(rule)
                pendingDelete = null
            },
        )
    }
}

/**
 * 一条规则：点开编辑，长按出菜单（删除），行尾只有开关 —— HyperOS 的列表行尾就是一个开关或一个 ›。
 * 从前行尾是「删除图标 + 开关」，48dp 的垃圾桶紧挨着开关，挤，也容易手滑点到；删除不是高频动作，收进长按。
 */
@Composable
private fun RuleRow(
    rule: ReplaceRuleEntity,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
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
        supportingContent = {
            Text(
                buildString {
                    append(if (rule.isRegex) "正则  " else "文本  ")
                    append(rule.pattern)
                    if (rule.replacement.isNotEmpty()) append("  →  ${rule.replacement}")
                    if (rule.scope.isNotBlank()) append("  ·  仅 ${rule.scope}")
                    // 阅读页长按建的规则只对那一本书生效，列表里得看得出来，
                    // 否则用户会纳闷"这条规则怎么在别的书里不管用"
                    if (rule.bookId.isNotEmpty()) append("  ·  本书专属")
                },
                style = ContentListDefaults.supportingStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        content = {
            Text(
                rule.name,
                style = ContentListDefaults.titleStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
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
