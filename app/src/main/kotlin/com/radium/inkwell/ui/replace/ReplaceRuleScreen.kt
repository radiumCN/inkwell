package com.radium.inkwell.ui.replace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.rememberModalBottomSheetState
import com.radium.inkwell.ui.components.AppAlertDialog
import com.radium.inkwell.ui.components.AppIconButton
import com.radium.inkwell.ui.components.AppSnackbarHost
import com.radium.inkwell.ui.components.AppTopBar
import com.radium.inkwell.ui.components.rememberAppTopBarScroll
import com.radium.inkwell.ui.components.topBarScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.radium.inkwell.ui.components.PrimaryButton
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radium.inkwell.data.db.entity.ReplaceRuleEntity
import kotlinx.coroutines.launch
import com.radium.inkwell.ui.components.CollectMessages
import com.radium.inkwell.ui.components.ContentListDefaults
import com.radium.inkwell.ui.components.ContentListItem
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.ui.components.EmptyState
import com.radium.inkwell.ui.components.settingsPageColor
import com.radium.inkwell.ui.components.settingsStackListColors
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReplaceRuleScreen(
    onBack: () -> Unit,
    viewModel: ReplaceRuleViewModel,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var pendingDelete by remember { mutableStateOf<ReplaceRuleEntity?>(null) }

    CollectMessages(viewModel.messages, snackbar)

    val pageColor = settingsPageColor()
    val topBarScroll = rememberAppTopBarScroll()
    Scaffold(
        modifier = Modifier.topBarScroll(topBarScroll),
        containerColor = pageColor,
        topBar = { AppTopBar("净化替换规则", topBarScroll, onBack = onBack, containerColor = pageColor) },
        floatingActionButton = {
            FloatingActionButton(onClick = viewModel::newRule) {
                Icon(Icons.Default.Add, contentDescription = "新建规则")
            }
        },
        snackbarHost = { AppSnackbarHost(snackbar) },
    ) { padding ->
        if (!state.rulesLoaded) {
            // 库还没读回来：先空着，别闪一下「还没有净化规则」
        } else if (state.rules.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding)) {
                EmptyState(
                    icon = Icons.Default.CleaningServices,
                    title = "还没有净化规则",
                    hint = "用来删掉正文里的广告、水印和防盗段落",
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                // 底部给悬浮的「新建」按钮让位（56dp 高 + 16dp 边距）：不留的话，最后一条行尾的开关与删除
                // 正好压在按钮下面，还滚不出来
                contentPadding = ContentListDefaults.listContentPadding(bottom = Dimens.touchTarget + Dimens.gapXXL),
                verticalArrangement = Arrangement.spacedBy(ContentListDefaults.ListSpacing),
            ) {
                items(state.rules, key = { it.id }) { rule ->
                    RuleRow(
                        rule = rule,
                        onClick = { viewModel.edit(rule) },
                        onToggle = { viewModel.setEnabled(rule, it) },
                        onDelete = { pendingDelete = rule },
                    )
                }
            }
        }
    }

    state.editing?.let { draft ->
        // 有没保存的改动时，下滑、点遮罩、按返回都先问一句 —— 从前一碰就关，填了半天的正则说没就没。
        // 拦在 confirmValueChange 上：它说不，面板就不会收起，onDismissRequest 也不会来。
        // 「放弃」之后自己收起面板，那一下要放行（discarding），否则又被这里拦回去。
        val scope = rememberCoroutineScope()
        var askDiscard by remember { mutableStateOf(false) }
        var discarding by remember { mutableStateOf(false) }
        val dirty by rememberUpdatedState(state.editorDirty)
        val sheetState = rememberModalBottomSheetState(
            confirmValueChange = { target ->
                if (target == SheetValue.Hidden && dirty && !discarding) {
                    askDiscard = true
                    false
                } else {
                    true
                }
            },
        )
        ModalBottomSheet(onDismissRequest = viewModel::dismissEditor, sheetState = sheetState) {
            Editor(
                draft = draft,
                sampleText = state.sampleText,
                sampleResult = state.sampleResult,
                patternError = state.patternError,
                saving = state.saving,
                onChange = viewModel::updateDraft,
                onSampleChange = viewModel::updateSample,
                onSave = viewModel::save,
            )
        }
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
                    scope.launch { sheetState.hide() }.invokeOnCompletion {
                        if (!sheetState.isVisible) viewModel.dismissEditor()
                    }
                },
            )
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

@Composable
private fun RuleRow(
    rule: ReplaceRuleEntity,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    ContentListItem(
        onClick = onClick,
        colors = settingsStackListColors(),
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 删除在前、开关在后：与书源/订阅列表的行尾一致。图标走 onSurfaceVariant ——
                // outline 只给边框/分隔线，拿它画可点的图标在浅色下对比度不够（约 3.9:1）
                AppIconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "删除",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = rule.enabled,
                    onCheckedChange = onToggle,
                    // 行尾开关自己是一个焦点，得有名字：只念「开关，已开启」听不出开的是哪条规则
                    modifier = Modifier.semantics { contentDescription = "启用「${rule.name}」" },
                )
            }
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
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        content = {
            Text(
                rule.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}

@Composable
private fun Editor(
    draft: ReplaceRuleEntity,
    sampleText: String,
    sampleResult: String?,
    patternError: String?,
    saving: Boolean,
    onChange: (ReplaceRuleEntity) -> Unit,
    onSampleChange: (String) -> Unit,
    onSave: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = Dimens.sheetEditorMaxHeight)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Dimens.screenPadding)
            .padding(bottom = Dimens.gapXXL),
        verticalArrangement = Arrangement.spacedBy(Dimens.gapM),
    ) {
        Text(
            if (draft.id.isEmpty()) "新建净化规则" else "编辑净化规则",
            style = MaterialTheme.typography.titleMedium,
        )
        OutlinedTextField(
            value = draft.name,
            onValueChange = { onChange(draft.copy(name = it)) },
            label = { Text("名称") },
            placeholder = { Text("留空则用规则内容代替") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = draft.pattern,
            onValueChange = { onChange(draft.copy(pattern = it)) },
            label = { Text("替换什么") },
            isError = patternError != null,
            supportingText = patternError?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = draft.replacement,
            onValueChange = { onChange(draft.copy(replacement = it)) },
            label = { Text("替换成什么") },
            placeholder = { Text("留空即删除") },
            modifier = Modifier.fillMaxWidth(),
        )
        // 整行就是一个开关（读屏一个焦点，念「使用正则表达式，开关」），行内 Switch 只做展示；
        // Switch 只展示时不再自带 48dp 触控区，行自己撑够
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = Dimens.touchTarget)
                .toggleable(
                    value = draft.isRegex,
                    role = Role.Switch,
                    onValueChange = { onChange(draft.copy(isRegex = it)) },
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("使用正则表达式", style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (draft.isRegex) "按正则匹配" else "按纯文本原样匹配",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = draft.isRegex,
                onCheckedChange = null,
            )
        }
        OutlinedTextField(
            value = draft.scope,
            onValueChange = { onChange(draft.copy(scope = it)) },
            label = { Text("作用范围") },
            placeholder = { Text("留空 = 所有书源；否则填书源名或域名，逗号分隔") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        HorizontalDivider()

        // 净化是有损的：一条贪婪的正则能把整章正文删空，而在阅读页只表现为
        // 「正文规则未匹配到内容」，事后根本查不出是净化干的。所以保存前先让用户看见结果。
        Text("试一下", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = sampleText,
            onValueChange = onSampleChange,
            label = { Text("粘一段正文进来") },
            minLines = 2,
            maxLines = 4,
            modifier = Modifier.fillMaxWidth(),
        )
        // sampleResult 为 null = 规则还没写、或正则有误（错误已标在「替换什么」下面）：不显示结果，
        // 更不能当成「被删光了」标红
        if (sampleText.isNotEmpty() && sampleResult != null) {
            Text(
                "替换后",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                sampleResult.ifEmpty { "（整段被删光了 —— 这条规则大概太贪婪）" },
                style = MaterialTheme.typography.bodySmall,
                color = if (sampleResult.isEmpty()) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface,
            )
        }

        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            Spacer(Modifier.weight(1f))
            PrimaryButton(
                text = "保存",
                onClick = onSave,
                enabled = patternError == null && !saving,
                loading = saving,
            )
        }
    }
}
