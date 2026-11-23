package com.radium.inkwell.ui.feedback

import com.radium.inkwell.ui.components.horizontalOnly
import com.radium.inkwell.ui.components.pageScroll
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import top.yukonga.miuix.kmp.icon.MiuixIcons
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.state.ToggleableState
import com.radium.inkwell.ui.components.ContentListItem
import top.yukonga.miuix.kmp.basic.Checkbox
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radium.inkwell.data.prefs.FeedbackReceipt
import com.radium.inkwell.data.repo.FeedbackRepository
import com.radium.inkwell.ui.components.AppAlertDialog
import com.radium.inkwell.ui.components.AppSnackbarHost
import com.radium.inkwell.ui.components.AppTopBar
import com.radium.inkwell.ui.components.rememberAppTopBarScroll
import com.radium.inkwell.ui.components.topBarScroll
import com.radium.inkwell.ui.components.CollectMessages
import com.radium.inkwell.ui.components.CompactTextArea
import com.radium.inkwell.ui.components.CompactTextField
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.ui.components.PrimaryButton
import com.radium.inkwell.ui.components.SecondaryButton
import com.radium.inkwell.ui.components.SectionHeader
import com.radium.inkwell.ui.components.SettingBlock
import com.radium.inkwell.ui.components.SettingFootnote
import com.radium.inkwell.ui.components.SettingGroup
import com.radium.inkwell.ui.components.SettingRow
import com.radium.inkwell.ui.components.cardMargin
import com.radium.inkwell.ui.components.groupPosition
import com.radium.inkwell.ui.components.settingsPageColor
import top.yukonga.miuix.kmp.basic.Icon
import com.radium.inkwell.ui.components.AppScaffold
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.icon.extended.Ok

@Composable
fun FeedbackScreen(
    onBack: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenDiscussion: (FeedbackReceipt) -> Unit,
    viewModel: FeedbackViewModel,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val pickAttachments = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
        viewModel::addAttachments,
    )
    val canEditAttachments = !state.submitting && !state.pending && !state.readingAttachments

    CollectMessages(viewModel.messages, snackbar)

    // 写了一半就走：系统返回、顶栏返回都先问一句。反馈常常是一大段复现步骤，
    // 手一滑划回去就全没了，而这页不存草稿。没写东西、或已经提交成功时照常直接退。
    val hasDraft = state.submitted == null &&
        (state.content.isNotBlank() || state.contact.isNotBlank() || state.attachments.isNotEmpty())
    var confirmLeave by remember { mutableStateOf(false) }
    BackHandler(enabled = hasDraft) { confirmLeave = true }
    val leave: () -> Unit = {
        if (hasDraft) confirmLeave = true else onBack()
    }

    val pageColor = settingsPageColor()
    val topBarScroll = rememberAppTopBarScroll()
    AppScaffold(
        modifier = Modifier.topBarScroll(topBarScroll),
        containerColor = pageColor,
        topBar = { AppTopBar("意见反馈", topBarScroll, onBack = leave, containerColor = pageColor) },
        snackbarHost = { AppSnackbarHost(snackbar) },
    ) { padding ->
        val submitted = state.submitted
        if (submitted != null) {
            SubmittedState(
                onView = { onOpenDiscussion(submitted) },
                onBack = onBack,
                modifier = Modifier.fillMaxSize().padding(padding).padding(Dimens.screenPadding),
            )
            return@AppScaffold
        }

        // 与别的设置页同一副骨架：小标题 + 白卡，输入框装进卡里（同 WebDAV 登录）。
        // 从前输入框直接铺在灰底上，灰框压灰底看不清边；几段说明从屏幕边起、ⓘ 那段又缩进一截，左边对不齐
        Column(
            Modifier
                .fillMaxSize()
                // 顶栏、导航栏的高度都垫在滚动内容首尾：内容能滚到玻璃顶栏和手势条下面（见 AppScaffold）
                .padding(padding.horizontalOnly())
                // padding 里已含导航栏高度：先把这份 inset 标成已消费，下面的 imePadding 才不会把导航栏那截再算一遍（键盘上方多一道空隙）
                .consumeWindowInsets(padding)
                // edge-to-edge 下窗口不再自动 resize，键盘会盖住提交按钮；让出 IME 高度
                .imePadding()
                .pageScroll()
                .verticalScroll(rememberScrollState(), overscrollEffect = null)
                .padding(
                    top = padding.calculateTopPadding() + Dimens.gapS,
                    bottom = Dimens.gapXL + padding.calculateBottomPadding(),
                ),
        ) {
            SettingGroup {
                SettingRow(title = "反馈记录", position = groupPosition(0, 1), onClick = onOpenHistory)
            }
            SectionHeader("问题描述")
            SettingGroup {
                SettingBlock {
                    Column(verticalArrangement = Arrangement.spacedBy(Dimens.gapXS)) {
                        CompactTextArea(
                            value = state.content,
                            onValueChange = viewModel::setContent,
                            placeholder = "遇到了什么问题",
                            isError = state.overLimit,
                            readOnly = state.submitting || state.pending,
                        )
                        // 字数实时可见，别等提交被拒才发现白写了。贴在框下靠右，不单独占一行
                        Text(
                            "${state.content.length} / ${FeedbackRepository.MAX_CONTENT_LENGTH}",
                            style = MiuixTheme.textStyles.footnote1,
                            color = if (state.overLimit) {
                                MiuixTheme.colorScheme.error
                            } else {
                                MiuixTheme.colorScheme.onSurfaceVariantSummary
                            },
                            modifier = Modifier.align(Alignment.End),
                        )
                    }
                }
            }
            // 提示放框外：写进 placeholder 的话一落笔就消失，第一行当标题这件事就看不见了
            SettingFootnote("第一行会成为标题，尽量写得具体些：什么操作触发的、是必现还是偶发")

            SectionHeader("联系方式")
            SettingGroup {
                SettingBlock {
                    CompactTextField(
                        value = state.contact,
                        onValueChange = viewModel::setContact,
                        placeholder = "邮箱、QQ 等（选填）",
                        readOnly = state.submitting || state.pending,
                    )
                }
            }
            // 这条警告不能省，也不能只写在副标题里：反馈会变成公开仓库里的一条 issue，
            // 用户不可能自己知道这件事。他填邮箱时以为只有开发者看得到，实际全网可见 ——
            // 必须在他填之前就说清楚，所以紧挨着联系方式。分量靠第一句加深，不靠图标
            // （从前挂一个 ⓘ，是全页唯一带图标的一段，左边还差一截）
            SettingFootnote(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = MiuixTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)) {
                        append("反馈会作为一条 issue 公开发布在开源仓库，任何人都能看到。")
                    }
                    append("填了联系方式就等于公开它 —— 不想公开可以留空，我们仍会看到你的反馈，只是没法直接回复你。")
                },
            )

            SectionHeader("附件")
            SettingGroup {
                val rowCount = state.attachments.size + 1
                SettingRow(
                    title = if (state.readingAttachments) "正在读取附件" else "添加截图、视频或文件",
                    value = "${state.attachments.size} / $MAX_FEEDBACK_FILES",
                    position = groupPosition(0, rowCount),
                    onClick = if (canEditAttachments && state.attachments.size < MAX_FEEDBACK_FILES) {
                        { pickAttachments.launch(arrayOf("*/*")) }
                    } else null,
                )
                state.attachments.forEachIndexed { index, attachment ->
                    SettingRow(
                        title = attachment.name,
                        subtitle = if (canEditAttachments) "点按移除" else null,
                        position = groupPosition(index + 1, rowCount),
                        onClick = if (canEditAttachments) {
                            { viewModel.removeAttachment(attachment.uri) }
                        } else null,
                    )
                }
            }
            SettingFootnote("最多 $MAX_FEEDBACK_FILES 个文件，合计 $MAX_FEEDBACK_FILE_MIB MiB。附件仅供开发者排查，私下保存 30 天。")

            SectionHeader("问题定位")
            SettingGroup {
                ContentListItem(
                    checked = state.attachLogs,
                    onCheckedChange = viewModel::setAttachLogs,
                    enabled = !state.submitting && !state.pending,
                    leadingContent = {
                        Checkbox(
                            state = ToggleableState(state.attachLogs),
                            onClick = null,
                            modifier = Modifier.clearAndSetSemantics {},
                        )
                    },
                ) {
                    Text("附带运行日志，帮助定位问题")
                }
            }
            SettingFootnote("包含最近的页面切换、关键操作、翻页及系统日志，经过基础脱敏，可能仍包含书名等信息；仅供开发者排查，不公开，上传后保留 30 天。本地操作日志限量保存最多 7 天；未开启日志记录时，系统日志仅提供当前可获取的快照。")
            if (state.pending) {
                SettingFootnote("提交结果待确认，请勿重复提交。请保留提交编号：${state.requestId}，供开发者排查。")
            }

            PrimaryButton(
                text = when {
                    state.packingAttachments -> "正在准备附件"
                    state.collectingLogs -> "正在收集日志"
                    state.submitting -> "正在提交"
                    else -> "提交"
                },
                onClick = viewModel::submit,
                enabled = state.canSubmit,
                loading = state.submitting,
                // 和卡片左右对齐（从前比卡宽一截）
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = cardMargin())
                    .padding(top = Dimens.gapXL),
            )
            // 写出具体附带了什么，不让人猜
            SettingFootnote(
                "提交时会附带：${viewModel.attachedInfo}",
                modifier = Modifier.padding(top = Dimens.gapS),
            )
        }
    }

    if (confirmLeave) {
        AppAlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = "放弃编辑？",
            text = "写好的反馈还没提交，离开后不会保留。",
            confirmText = "放弃",
            dismissText = "继续编辑",
            onConfirm = {
                confirmLeave = false
                onBack()
            },
        )
    }
}

/**
 * 提交成功后可直接打开处理进展。
 */
@Composable
private fun SubmittedState(
    onView: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier,
        verticalArrangement = Arrangement.spacedBy(Dimens.gapM, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            MiuixIcons.Ok,
            contentDescription = null,
            tint = MiuixTheme.colorScheme.primary,
            modifier = Modifier.size(Dimens.iconXL),
        )
        Text("反馈已提交", style = MiuixTheme.textStyles.headline2, fontWeight = FontWeight.Medium)
        Text(
            "你可以在反馈记录中跟进处理进展，也可以在详情中补充信息。",
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            textAlign = TextAlign.Center,
        )
        PrimaryButton(text = "查看进展", onClick = onView, modifier = Modifier.fillMaxWidth())
        SecondaryButton(text = "完成", onClick = onBack, modifier = Modifier.fillMaxWidth())
    }
}
