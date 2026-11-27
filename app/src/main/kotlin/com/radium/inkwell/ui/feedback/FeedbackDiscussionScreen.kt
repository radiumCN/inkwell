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
import com.radium.inkwell.data.repo.FeedbackLogUploadResult
import com.radium.inkwell.data.repo.FeedbackRepository
import com.radium.inkwell.ui.components.AppAlertDialog
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
import com.radium.inkwell.util.RunLogRecorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File
import java.util.UUID

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
internal fun FeedbackDiscussionScreen(
    receipt: FeedbackReceipt,
    repo: FeedbackRepository,
    recorder: RunLogRecorder,
    onBack: () -> Unit,
) {
    var status by remember(receipt.url) { mutableStateOf<String?>(null) }
    var comments by remember(receipt.url) { mutableStateOf(emptyList<FeedbackComment>()) }
    var nextCursor by remember(receipt.url) { mutableStateOf<String?>(null) }
    var loading by remember(receipt.url) { mutableStateOf(true) }
    var loadFailed by remember(receipt.url) { mutableStateOf(false) }
    var sending by remember(receipt.url) { mutableStateOf(false) }
    var draft by rememberSaveable(receipt.url) { mutableStateOf("") }
    var confirmLogUpload by rememberSaveable(receipt.url) { mutableStateOf(false) }
    var uploadingLogs by remember(receipt.url) { mutableStateOf(false) }
    var logUploadId by rememberSaveable(receipt.url) { mutableStateOf<String?>(null) }
    val hasReplyToken = !receipt.replyToken.isNullOrBlank()
    val discussionBusy = loading || sending || uploadingLogs
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
                val rowCount = if (hasReplyToken) 4 else 3
                SettingGroup {
                    SettingRow(
                        title = "公开状态",
                        value = status ?: if (loading) "正在查询" else "暂时无法读取",
                        position = groupPosition(0, rowCount),
                    )
                    SettingRow(
                        title = "刷新进展",
                        position = groupPosition(1, rowCount),
                        onClick = if (discussionBusy) null else ({ scope.launch { load() } }),
                    )
                    SettingRow(
                        title = "在 GitHub 查看讨论",
                        position = groupPosition(2, rowCount),
                        onClick = { links.open(receipt.url) },
                    )
                    if (hasReplyToken) {
                        SettingRow(
                            title = "追加应用日志",
                            value = if (uploadingLogs) "正在上传…" else null,
                            position = groupPosition(3, rowCount),
                            onClick = if (discussionBusy) null else ({ confirmLogUpload = true }),
                        )
                    }
                }
                SectionHeader("讨论")
            }
            items(comments, key = { it.id }) { comment ->
                SettingGroup {
                    SettingBlock {
                        Column(verticalArrangement = Arrangement.spacedBy(Dimens.gapXS)) {
                            val time = comment.createdAt.takeIf(String::isNotBlank)
                                ?.let { it.replace('T', ' ').removeSuffix("Z") + " UTC" }
                            Text(
                                listOfNotNull(comment.authorLabel, time).joinToString(" · "),
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
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
                        enabled = !discussionBusy,
                        onClick = { scope.launch { load(cursor) } },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.screenPadding),
                    )
                }
            }
            item {
                if (!hasReplyToken) {
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
                        enabled = !discussionBusy && draft.isNotBlank() &&
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

    if (hasReplyToken && confirmLogUpload) {
        AppAlertDialog(
            onDismissRequest = { confirmLogUpload = false },
            title = "追加应用日志？",
            text = "将上传最近的操作记录和可获取的系统日志。日志经过基础脱敏，可能仍包含书名等信息。" +
                "仅供开发者排查，私下保存 30 天。",
            confirmText = "上传日志",
            closeOnConfirm = true,
            onConfirm = {
                confirmLogUpload = false
                uploadingLogs = true
                val uploadId = logUploadId ?: UUID.randomUUID().toString().also { logUploadId = it }
                scope.launch {
                    var logs: File? = null
                    try {
                        val archive = withContext(Dispatchers.IO) {
                            recorder.exportForFeedback().also { logs = it }
                        }
                        val result = repo.appendLogs(receipt, uploadId, archive)
                        if (result == FeedbackLogUploadResult.SUCCESS) {
                            logUploadId = null
                            load()
                        }
                        messages.emit(when (result) {
                            FeedbackLogUploadResult.SUCCESS -> "应用日志已追加"
                            FeedbackLogUploadResult.FORBIDDEN -> "反馈凭证已失效，无法追加日志"
                            FeedbackLogUploadResult.TOO_LARGE -> "应用日志超过上传大小限制"
                            FeedbackLogUploadResult.RATE_LIMITED -> "日志上传过于频繁，请稍后再试"
                            FeedbackLogUploadResult.UNCONFIRMED -> "日志上传未确认，请稍后重试"
                        })
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        messages.emit("应用日志收集失败，请重试")
                    } finally {
                        logs?.delete()
                        uploadingLogs = false
                    }
                }
            },
        )
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
