package com.radium.inkwell.util

import com.radium.inkwell.reader.api.FlipDirection
import kotlinx.coroutines.flow.MutableSharedFlow

/** MainActivity 拦截音量键 → 阅读页消费。菜单打开时放行给系统调音量。 */
class KeyEventBus {
    @Volatile
    var volumeFlipEnabled: Boolean = false

    val flipEvents = MutableSharedFlow<FlipDirection>(extraBufferCapacity = 4)

    /** 返回 true 表示事件被消费 */
    fun onVolumeKey(isVolumeUp: Boolean): Boolean {
        if (!volumeFlipEnabled) return false
        flipEvents.tryEmit(if (isVolumeUp) FlipDirection.BACKWARD else FlipDirection.FORWARD)
        return true
    }
}
