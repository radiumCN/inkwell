package com.radium.inkwell.ui.reader

import android.provider.Settings
import android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 亮度属于 Activity 的窗口，退出阅读后也要能完成自动亮度的交还。 */
internal class ReaderWindowBrightness(private val activity: ComponentActivity) {
    private var activeReader: Any? = null
    private var beforeOverride: Float? = null
    private var handoff: Job? = null

    fun attach(reader: Any) {
        activeReader = reader
    }

    fun update(reader: Any, brightness: Float?) {
        if (activeReader !== reader) return
        if (brightness == null) {
            restoreSystemBrightness()
            return
        }

        handoff?.cancel()
        handoff = null
        if (activity.window.attributes.screenBrightness == BRIGHTNESS_OVERRIDE_NONE) {
            val mode = Settings.System.getInt(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, 0)
            beforeOverride = if (mode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC) {
                systemBrightnessLevel(activity)
            } else {
                null
            }
        }
        applyBrightness(brightness.coerceIn(MIN_BRIGHTNESS, 1f))
    }

    fun detach(reader: Any) {
        if (activeReader !== reader) return
        activeReader = null
        restoreSystemBrightness()
    }

    private fun restoreSystemBrightness() {
        if (handoff != null || activity.window.attributes.screenBrightness == BRIGHTNESS_OVERRIDE_NONE) return
        val previous = beforeOverride
        if (previous == null) {
            applyBrightness(BRIGHTNESS_OVERRIDE_NONE)
            return
        }

        // 部分系统以当前面板亮度作为自动亮度初值。先让窗口应用原来的亮度，再清除覆盖。
        applyBrightness(previous)
        handoff = activity.lifecycleScope.launch {
            delay(AUTO_BRIGHTNESS_HANDOFF_MS)
            applyBrightness(BRIGHTNESS_OVERRIDE_NONE)
            beforeOverride = null
            handoff = null
        }
    }

    private fun applyBrightness(target: Float) {
        val window = activity.window
        val attrs = window.attributes
        if (attrs.screenBrightness == target) return
        attrs.screenBrightness = target
        window.attributes = attrs
    }
}

private const val AUTO_BRIGHTNESS_HANDOFF_MS = 120L
