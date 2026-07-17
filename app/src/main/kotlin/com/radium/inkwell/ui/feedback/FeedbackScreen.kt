package com.radium.inkwell.ui.feedback

import android.content.Intent
import androidx.core.net.toUri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radium.inkwell.data.repo.FeedbackRepository
import com.radium.inkwell.ui.components.AppSnackbarHost
import com.radium.inkwell.ui.components.CollectMessages
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.ui.components.PrimaryButton
import com.radium.inkwell.ui.components.SecondaryButton
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedbackScreen(
    onBack: () -> Unit,
    viewModel: FeedbackViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    CollectMessages(viewModel.messages, snackbar)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("意见反馈") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        snackbarHost = { AppSnackbarHost(snackbar) },
    ) { padding ->
        val url = state.successUrl
        if (url != null) {
            SubmittedState(
                url = url,
                onView = { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) },
                onBack = onBack,
                modifier = Modifier.fillMaxSize().padding(padding).padding(Dimens.screenPadding),
            )
            return@Scaffold
        }

        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                // edge-to-edge 下窗口不再自动 resize，键盘会盖住提交按钮；让出 IME 高度
                .imePadding()
                .padding(Dimens.screenPadding)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Dimens.gapM),
        ) {
            OutlinedTextField(
                value = state.content,
                onValueChange = viewModel::setContent,
                label = { Text("遇到了什么问题") },
                placeholder = { Text("第一行会成为标题，尽量写得具体些：\n什么操作触发的、是必现还是偶发") },
                // 不 singleLine：反馈天然是多行的，挤在一行里没法写清楚
                modifier = Modifier.fillMaxWidth().heightIn(min = Dimens.textAreaMinHeight),
                isError = state.overLimit,
                supportingText = {
                    // 字数实时可见，别等提交被拒才发现白写了
                    Text(
                        "${state.content.length} / ${FeedbackRepository.MAX_CONTENT_LENGTH}",
                        color = if (state.overLimit) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )

            OutlinedTextField(
                value = state.contact,
                onValueChange = viewModel::setContact,
                label = { Text("联系方式（选填）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            // 这条警告不能省，也不能只写在副标题里：反馈会变成公开仓库里的一条 issue，
            // 用户不可能自己知道这件事。他填邮箱时以为只有开发者看得到，实际全网可见 ——
            // 必须在他填之前就说清楚。
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Dimens.gapS),
            ) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(Dimens.iconSm),
                )
                Text(
                    "反馈会作为一条 issue 公开发布在开源仓库，任何人都能看到。" +
                        "填了联系方式就等于公开它 —— 不想公开可以留空，" +
                        "我们仍会看到你的反馈，只是没法直接回复你。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
            Icons.Default.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(Dimens.iconXL),
        )
        Text("反馈已提交", style = MaterialTheme.typography.titleMedium)
        Text(
            "感谢反馈。你可以在下面的页面里跟进处理进展，也可以补充信息。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        PrimaryButton(text = "查看进展", onClick = onView, modifier = Modifier.fillMaxWidth())
        SecondaryButton(text = "完成", onClick = onBack, modifier = Modifier.fillMaxWidth())
    }
}
