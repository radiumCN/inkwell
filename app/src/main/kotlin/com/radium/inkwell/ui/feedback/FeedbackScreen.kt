package com.radium.inkwell.ui.feedback

import com.radium.inkwell.ui.components.horizontalOnly
import com.radium.inkwell.ui.components.pageScroll
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Info
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import com.radium.inkwell.ui.components.NO_BROWSER_MESSAGE
import com.radium.inkwell.ui.components.PrimaryButton
import com.radium.inkwell.ui.components.SecondaryButton
import com.radium.inkwell.ui.components.openExternalLink
import com.radium.inkwell.ui.components.settingsPageColor
import top.yukonga.miuix.kmp.basic.Icon
import com.radium.inkwell.ui.components.AppScaffold
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun FeedbackScreen(
    onBack: () -> Unit,
    viewModel: FeedbackViewModel,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    CollectMessages(viewModel.messages, snackbar)

    // 写了一半就走：系统返回、顶栏返回都先问一句。反馈常常是一大段复现步骤，
    // 手一滑划回去就全没了，而这页不存草稿。没写东西、或已经提交成功时照常直接退。
    val hasDraft = state.successUrl == null &&
        (state.content.isNotBlank() || state.contact.isNotBlank())
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
        val url = state.successUrl
        if (url != null) {
            SubmittedState(
                url = url,
                onView = {
                    if (!context.openExternalLink(url)) viewModel.messages.emit(NO_BROWSER_MESSAGE)
                },
                onBack = onBack,
                modifier = Modifier.fillMaxSize().padding(padding).padding(Dimens.screenPadding),
            )
            return@AppScaffold
        }

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
                .padding(Dimens.screenPadding)
                .padding(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(Dimens.gapL),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.gapS)) {
                CompactTextArea(
                    value = state.content,
                    onValueChange = viewModel::setContent,
                    placeholder = "遇到了什么问题",
                    isError = state.overLimit,
                    // 字数实时可见，别等提交被拒才发现白写了
                    supportingText = "${state.content.length} / ${FeedbackRepository.MAX_CONTENT_LENGTH}",
                )
                // 提示放框外：写进 placeholder 的话一落笔就消失，第一行当标题这件事就看不见了
                Text(
                    "第一行会成为标题，尽量写得具体些：什么操作触发的、是必现还是偶发",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }

            CompactTextField(
                value = state.contact,
                onValueChange = viewModel::setContact,
                placeholder = "联系方式（选填）",
            )

            // 这条警告不能省，也不能只写在副标题里：反馈会变成公开仓库里的一条 issue，
            // 用户不可能自己知道这件事。他填邮箱时以为只有开发者看得到，实际全网可见 ——
            // 必须在他填之前就说清楚。
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Dimens.gapS),
            ) {
                Icon(
                    MiuixIcons.Info,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.size(Dimens.iconSm),
                )
                Text(
                    "反馈会作为一条 issue 公开发布在开源仓库，任何人都能看到。" +
                        "填了联系方式就等于公开它 —— 不想公开可以留空，" +
                        "我们仍会看到你的反馈，只是没法直接回复你。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }

            PrimaryButton(
                text = "提交",
                onClick = viewModel::submit,
                enabled = state.canSubmit,
                loading = state.submitting,
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                "提交时会附带应用版本、机型与系统版本，便于定位问题。",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
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
 * 成功态。给出 issue 地址而不是只弹一句「提交成功」—— 用户能自己看进展、补充信息，
 * 反馈才不像扔进黑洞。
 */
@Composable
private fun SubmittedState(
    url: String,
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
            Icons.Outlined.CheckCircle,
            contentDescription = null,
            tint = MiuixTheme.colorScheme.primary,
            modifier = Modifier.size(Dimens.iconXL),
        )
        Text("反馈已提交", style = MiuixTheme.textStyles.headline2, fontWeight = FontWeight.Medium)
        Text(
            "感谢反馈。你可以在下面的页面里跟进处理进展，也可以补充信息。",
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            textAlign = TextAlign.Center,
        )
        PrimaryButton(text = "查看进展", onClick = onView, modifier = Modifier.fillMaxWidth())
        SecondaryButton(text = "完成", onClick = onBack, modifier = Modifier.fillMaxWidth())
    }
}
