package com.radium.inkwell.ui.components

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class MessageBusTest {

    @Test
    fun `identical consecutive messages are all delivered`() = runTest {
        // 回归：曾用 StateFlow 导致相同内容的第二条提示被去重吞掉，
        // 用户重复导入书源时看不到任何反馈，误以为功能坏了
        val bus = MessageBus()
        val received = mutableListOf<String>()
        val job = launch { bus.messages.collect { received += it } }
        runCurrent()

        bus.emit("新增 1 个书源")
        bus.emit("新增 1 个书源")
        bus.emit("新增 1 个书源")
        runCurrent()
        job.cancel()

        assertEquals(3, received.size)
        assertEquals(listOf("新增 1 个书源", "新增 1 个书源", "新增 1 个书源"), received)
    }
}
