package com.radium.inkwell.ui.components

import android.view.WindowManager
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import com.radium.inkwell.ui.theme.AppTheme

/**
 * 弹层背后的页面模糊（HyperOS 对话框、底部面板背后那层毛玻璃）。**放在弹层内容里调用** ——
 * Miuix 的 `Window*` 弹层各开一个 Dialog 窗口，只有在那个窗口的组合里才拿得到它。
 *
 * 用的是系统的跨窗口模糊（`blurBehindRadius`，Android 12+）：由合成器把弹层窗口**后面**的应用窗口糊掉，
 * 不用我们录图层，也就不受 WebView、阅读器画布这些录不进去的东西影响。
 * 半径跟着 [visible] 渐变（弹层播退场动画时就开始收），系统动效「移除动画」时一步到位。
 *
 * 系统关了跨窗口模糊（省电模式、部分机型、开发者选项「停用窗口级模糊」）时什么也不做 ——
 * Miuix 自己的压暗还在，弹层照样分得清前后。
 */
@Composable
internal fun DialogWindowBlur(visible: Boolean) {
    val view = LocalView.current
    val window = (view.parent as? DialogWindowProvider)?.window ?: return
    val supported = rememberCrossWindowBlurEnabled()
    val target = if (visible && supported) with(LocalDensity.current) { BLUR_BEHIND_RADIUS.roundToPx() } else 0
    val radius by animateIntAsState(target, AppTheme.motionScheme.defaultEffectsSpec(), label = "blurBehind")
    LaunchedEffect(window) {
        window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        // 改 attributes 要整份赋回去，窗口才会重新提交给合成器；系统文档的模糊示例也是逐帧这么推的
        snapshotFlow { radius }.collect { r ->
            window.attributes = window.attributes.apply { blurBehindRadius = r }
        }
    }
}

private val BLUR_BEHIND_RADIUS = 24.dp
