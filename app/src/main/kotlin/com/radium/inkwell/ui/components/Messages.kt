package com.radium.inkwell.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * 一次性提示事件流。
 * 不能用 StateFlow：它会对相同值去重，连续两次结果相同的操作（如重复导入）
 * 第二次提示会被静默吞掉，用户以为操作没生效。
 */
class MessageBus {
    private val _messages = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val messages: Flow<String> = _messages

    fun emit(message: String) {
        _messages.tryEmit(message)
    }
}

/** 订阅提示事件并弹 Snackbar；新消息到达时替换正在显示的那条 */
@Composable
fun CollectMessages(bus: MessageBus, snackbar: SnackbarHostState) {
    LaunchedEffect(bus, snackbar) {
        bus.messages.collect { msg ->
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(msg)
        }
    }
}

/**
 * 全应用统一的一次性提示。
 *
 * 不走 M3 默认 [androidx.compose.material3.Snackbar]：那条默认 `fillMaxWidth`，
 * 短到「已是最新版本」六个字也会被拉成贴底横杠，再加默认 elevation 投影，
 * 在浅色纸面上糊成一道脏灰边 —— 看起来像系统 Toast 的廉价版。
 *
 * 这里改成**居中、随文案收窄的浮起胶囊**：
 * - 短提示是一颗药丸，长提示才横向展开（上限吃满宿主宽度）；
 * - 底色/字色用已主题化的 `inverseSurface` / `inverseOnSurface`
 *   （日间暖墨底 + 纸色字，夜间自动对调），对比度自带；
 * - 圆角 `extraLarge`(24dp)；**不投影**，靠深浅对比浮起；
 * - 内边距走 [Dimens]，正文用 `bodyMedium`。
 */
@Composable
fun AppSnackbar(
    data: SnackbarData,
    modifier: Modifier = Modifier,
) {
    val visuals = data.visuals
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            Modifier.padding(
                start = Dimens.gapL,
                // 有操作按钮时右侧少留一点，避免按钮外侧空一截
                end = if (visuals.actionLabel != null) Dimens.gapS else Dimens.gapL,
                top = Dimens.gapM,
                bottom = Dimens.gapM,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.gapS),
        ) {
            Text(
                text = visuals.message,
                // fill=false：短文案按自身宽度收，别被 Row 撑满
                modifier = Modifier.weight(1f, fill = false),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            visuals.actionLabel?.let { label ->
                TextButton(onClick = { data.performAction() }) {
                    Text(
                        label,
                        color = MaterialTheme.colorScheme.inversePrimary,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

/**
 * 替代裸 `SnackbarHost`：把 [AppSnackbar] 居中托起。
 * 两侧/底边留白走 [Dimens.screenPadding]，像浮在页面上，不是焊在底边。
 */
@Composable
fun AppSnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(
        hostState = hostState,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.screenPadding)
            .padding(bottom = Dimens.gapS),
    ) { data ->
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            AppSnackbar(
                data = data,
                modifier = Modifier
                    .widthIn(max = maxWidth)
                    .wrapContentWidth(align = Alignment.CenterHorizontally),
            )
        }
    }
}
