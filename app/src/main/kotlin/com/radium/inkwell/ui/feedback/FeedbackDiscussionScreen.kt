package com.radium.inkwell.ui.feedback

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.radium.inkwell.data.prefs.FeedbackReceipt
import com.radium.inkwell.data.repo.FeedbackComment
import com.radium.inkwell.data.repo.FeedbackRepository
import com.radium.inkwell.ui.components.AppScaffold
import com.radium.inkwell.ui.components.AppSnackbarHost
import com.radium.inkwell.ui.components.AppToolbar
import com.radium.inkwell.ui.components.BackButton
import com.radium.inkwell.ui.components.CollectMessages
import com.radium.inkwell.ui.components.CompactTextArea
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.ui.components.MessageBus
import com.radium.inkwell.ui.components.NO_BROWSER_MESSAGE
import com.radium.inkwell.ui.components.PrimaryButton
import com.radium.inkwell.ui.components.SecondaryButton
import com.radium.inkwell.ui.components.SectionHeader
import com.radium.inkwell.ui.components.SettingBlock
import com.radium.inkwell.ui.components.SettingFootnote
import com.radium.inkwell.ui.components.SettingGroup
import com.radium.inkwell.ui.components.SettingRow
import com.radium.inkwell.ui.components.ToolbarTitle
import com.radium.inkwell.ui.components.groupPosition
import com.radium.inkwell.ui.components.rememberExternalLinkOpener
import com.radium.inkwell.ui.components.settingsPageColor
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun FeedbackHistoryScreen(
    receipts: List<FeedbackReceipt>,
    onSelect: (FeedbackReceipt) -> Unit,
    onBack: () -> Unit,
) {
    FeedbackPage("反馈记录", onBack) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
            if (receipts.isEmpty()) {
                item { SettingFootnote("暂无本机反馈记录。新的反馈提交成功后会显示在这里。") }
            } else {
                item {
                    SettingGroup {
                        receipts.forEachIndexed { index, receipt ->
                            SettingRow(
                                title = receipt.title,
                                subtitle = "#${receipt.issue}",
                                position = groupPosition(index, receipts.size),
                                onClick = { onSelect(receipt) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun FeedbackDiscussionScreen(receipt: FeedbackReceipt, repo: FeedbackRepository, onBack: () -> Unit) {
    var status by remember(receipt.url) { mutableStateOf<String?>(null) }
    var comments by remember(receipt.url) { mutableStateOf(emptyList<FeedbackComment>()) }
    var nextCursor by remember(receipt.url) { mutableStateOf<String?>(null) }
    var loading by remember(receipt.url) { mutableStateOf(true) }
    var loadFailed by remember(receipt.url) { mutableStateOf(false) }
    var sending by remember(receipt.url) { mutableStateOf(false) }
    var draft by rememberSaveable(receipt.url) { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val messages = remember { MessageBus() }
    val snackbar = remember { SnackbarHostState() }
    val links = rememberExternalLinkOpener { messages.emit(NO_BROWSER_MESSAGE) }
    CollectMessages(messages, snackbar)

    suspend fun load(cursor: String? = null) {
        loading = true
        loadFailed = false
        try {
            val result = repo.discussion(receipt, cursor)
            if (result == null) {
                loadFailed = true
            } else {
                status = result.status?.label
                comments = (if (cursor == null) result.comments else comments + result.comments).distinctBy { it.id }
                nextCursor = result.nextCursor
            }
        } finally {
            loading = false
        }
    }

    LaunchedEffect(receipt.url) { load() }

    FeedbackPage("处理进展", onBack, snackbar) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().consumeWindowInsets(padding).imePadding(),
            contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(Dimens.gapS),
        ) {
            item {
                SectionHeader(receipt.title)
                SettingGroup {
                    SettingRow(
                        title = "公开状态",
                        value = status ?: if (loading) "正在查询" else "暂时无法读取",
                        position = groupPosition(0, 3),
                    )
                    SettingRow(
                        title = "刷新进展",
                        position = groupPosition(1, 3),
                        onClick = if (loading || sending) null else ({ scope.launch { load() } }),
                    )
                    SettingRow(
                        title = "在 GitHub 查看讨论",
                        position = groupPosition(2, 3),
                        onClick = { links.open(receipt.url) },
                    )
                }
                SectionHeader("讨论")
            }
            items(comments, key = { it.id }) { comment ->
                SettingGroup {
                    SettingBlock {
                        Column(verticalArrangement = Arrangement.spacedBy(Dimens.gapXS)) {
                            if (comment.createdAt.isNotBlank()) {
                                Text(
                                    comment.createdAt.replace('T', ' ').removeSuffix("Z") + " UTC",
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                            Text(comment.message, style = MiuixTheme.textStyles.body1)
                        }
                    }
                }
            }
            if (loading || loadFailed || comments.isEmpty()) {
                item {
                    SettingFootnote(
                        when {
                            loading -> "正在加载讨论…"
                            loadFailed -> "暂时无法加载讨论，请稍后刷新。"
                            else -> "暂无讨论。"
                        },
                    )
                }
            }
            nextCursor?.let { cursor ->
                item {
                    SecondaryButton(
                        text = "加载更多",
                        enabled = !loading && !sending,
                        onClick = { scope.launch { load(cursor) } },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.screenPadding),
                    )
                }
            }
            item {
                if (receipt.replyToken.isNullOrBlank()) {
                    SettingFootnote("这条反馈没有应用内回复凭证，可在 GitHub 讨论中回复。")
                } else {
                    SectionHeader("回复")
                    SettingGroup {
                        SettingBlock {
                            CompactTextArea(
                                value = draft,
                                onValueChange = { draft = it },
                                placeholder = "补充复现步骤或回复开发者",
                                isError = draft.length > FeedbackRepository.MAX_CONTENT_LENGTH,
                                readOnly = sending,
                            )
                        }
                    }
                    SettingFootnote("回复会公开显示在 GitHub 讨论中。")
                    PrimaryButton(
                        text = "发送回复",
                        enabled = !sending && !loading && draft.isNotBlank() &&
                            draft.length <= FeedbackRepository.MAX_CONTENT_LENGTH,
                        loading = sending,
                        onClick = {
                            val body = draft.trim()
                            sending = true
                            scope.launch {
                                try {
                                    val comment = repo.reply(receipt, body)
                                    if (comment == null) {
                                        messages.emit("回复未确认，请刷新讨论后再试")
                                    } else {
                                        comments = (comments + comment).distinctBy { it.id }
                                        draft = ""
                                    }
                                } finally {
                                    sending = false
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.screenPadding),
                    )
                }
            }
        }
    }
}

@Composable
private fun FeedbackPage(
    title: String,
    onBack: () -> Unit,
    snackbar: SnackbarHostState? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    val pageColor = settingsPageColor()
    AppScaffold(
        containerColor = pageColor,
        topBar = {
            AppToolbar(navigationIcon = { BackButton(onClick = onBack) }, containerColor = pageColor) {
                ToolbarTitle(title)
            }
        },
        snackbarHost = { if (snackbar != null) AppSnackbarHost(snackbar) },
        content = content,
    )
}
