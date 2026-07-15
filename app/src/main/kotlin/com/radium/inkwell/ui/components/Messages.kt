package com.radium.inkwell.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
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
 * 全应用统一的一次性提示样式。
 *
 * M3 默认那条是 4dp 直角、贴边的深色横杠 —— 收进 App 的语汇里，做成一颗**浮起的圆角药丸**：
 * - 底色/字色沿用 App 已主题化的 `inverseSurface`/`inverseOnSurface`（暖墨底 + 纸色字），
 *   日间是暖墨、夜间自动翻成浅纸，两边都自带足够对比；
 * - 圆角放到 `extraLarge`(24dp)，短提示就是一颗药丸，长提示也只是更圆的卡片；
 * - **不投影** —— 同色纸面上的投影会糊成一道脏灰线（全 App 一贯做法），靠深浅对比自然浮起；
 * - 从两侧再内缩一点，像一层浮起来的提示，而不是一条焊在底边的横杠。
 */
@Composable
fun AppSnackbar(data: SnackbarData) {
    Snackbar(
        snackbarData = data,
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        actionColor = MaterialTheme.colorScheme.inversePrimary,
    )
}

/** 替代裸 `SnackbarHost`：注入统一的 [AppSnackbar] 样式，并从两侧多留一点空隙让它"浮"起来 */
@Composable
fun AppSnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(
        hostState = hostState,
        modifier = modifier.padding(horizontal = Dimens.gapS),
    ) { data -> AppSnackbar(data) }
}
