package com.radium.inkwell.ui.components

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
