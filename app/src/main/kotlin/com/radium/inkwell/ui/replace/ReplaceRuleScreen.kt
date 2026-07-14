package com.radium.inkwell.ui.replace

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.radium.inkwell.ui.components.PrimaryButton
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radium.inkwell.data.db.entity.ReplaceRuleEntity
import com.radium.inkwell.ui.components.CollectMessages
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.ui.components.EmptyState
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReplaceRuleScreen(
    onBack: () -> Unit,
    viewModel: ReplaceRuleViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var pendingDelete by remember { mutableStateOf<ReplaceRuleEntity?>(null) }

    CollectMessages(viewModel.messages, snackbar)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("净化替换规则") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = viewModel::newRule) {
                Icon(Icons.Default.Add, contentDescription = "新建规则")
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (state.rules.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding)) {
                EmptyState(
                    icon = Icons.Default.CleaningServices,
                    title = "还没有净化规则",
                    hint = "用来删掉正文里的广告、水印和防盗段落",
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(state.rules, key = { it.id }) { rule ->
                    RuleRow(
                        rule = rule,
                        onClick = { viewModel.edit(rule) },
                        onToggle = { viewModel.setEnabled(rule, it) },
                        onDelete = { pendingDelete = rule },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    state.editing?.let { draft ->
        ModalBottomSheet(onDismissRequest = viewModel::dismissEditor) {
            Editor(
                draft = draft,
                sampleText = state.sampleText,
                sampleResult = state.sampleResult,
                patternError = state.patternError,
                onChange = viewModel::updateDraft,
                onSampleChange = viewModel::updateSample,
                onSave = viewModel::save,
            )
        }
    }

    pendingDelete?.let { rule ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除规则") },
            text = { Text("删除「${rule.name}」？已缓存的章节不会重新净化，需要清除正文缓存后重读。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(rule)
                    pendingDelete = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
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
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = Dimens.rowHorizontal, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(rule.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Switch(checked = rule.enabled, onCheckedChange = onToggle)
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "删除",
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun Editor(
    draft: ReplaceRuleEntity,
    sampleText: String,
    sampleResult: String,
    patternError: String?,
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
            .padding(bottom = 32.dp),
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
        Row(verticalAlignment = Alignment.CenterVertically) {
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
                onCheckedChange = { onChange(draft.copy(isRegex = it)) },
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
        if (sampleText.isNotEmpty()) {
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
            PrimaryButton(text = "保存", onClick = onSave, enabled = patternError == null)
        }
    }
}
