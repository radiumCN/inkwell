package com.radium.inkwell.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Snackbar
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
 * 全应用统一的一级提示宿主。
 *
 * 直接用 M3 [Snackbar] / [SnackbarDefaults]（形状、`inverseSurface`、elevation 全走主题），
 * 挂在 [MaterialExpressiveTheme] 下即是 Expressive 默认形态 —— **不再**手搓居中胶囊。
 * 页面只通过这里挂 host，别自己写 `snackbarHost = { SnackbarHost(...) }`。
 */
@Composable
fun AppSnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(
        hostState = hostState,
        // 左右/底边留白：M3 浮动 snackbar 与屏幕边缘的标准间距，不是改组件形态
        modifier = modifier.padding(
            horizontal = Dimens.gapS,
            vertical = Dimens.gapS,
        ),
    ) { data ->
        Snackbar(snackbarData = data)
    }
}
