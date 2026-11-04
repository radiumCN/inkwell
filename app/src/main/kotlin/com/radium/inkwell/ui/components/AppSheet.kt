package com.radium.inkwell.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowBottomSheet

/** [AppBottomSheet] 内容里拿得到的收起动作 */
@Stable
interface AppSheetScope {
    /**
     * 先把面板收下去（播完退场动画），再执行 [then]、并回调 `onDismissRequest`。
     *
     * 点了选项要「收起后再办事」时用它：从前各页手写 `scope.launch { sheetState.hide(); … }`，
     * 漏写的那几处一点就把面板从组合树摘掉，退场动画被掐断、一帧之内消失。
     */
    fun dismiss(then: () -> Unit = {})
}

/**
 * 底部面板的统一出口：Miuix [WindowBottomSheet]（HyperOS 的圆角上沿 + 拖动条 + 居中标题）。
 *
 * 用 `Window*` 的理由同 [AppAlertDialog]：开独立窗口，不依赖 Miuix `Scaffold` 的弹层宿主。
 *
 * **调用方式与 M3 `ModalBottomSheet` 一样是条件组合**：`if (show) AppBottomSheet(onDismissRequest = { show = false }) { … }`。
 * 这里自己持有可见状态 —— 下滑、点蒙层、返回键、[AppSheetScope.dismiss] 都先播退场动画，播完才回调
 * [onDismissRequest]，调用方据此把它摘掉。
 *
 * 底部边距：Miuix 的面板一直铺到屏幕底，**不**替内容让导航栏。所以默认（[padNavigationBar] = true）
 * 在内容末尾垫一份导航栏高度，几行选项的面板不必操心。会滚动的面板（目录、换源、选项列表）传 false，
 * 把 [sheetBottomInset] 垫进列表的 `contentPadding` 末尾 —— 列表才能滚到手势条下面、最后一行又露得出来。
 * 键盘由 Miuix 自己 `imePadding()` 让开。
 *
 * @param title 居中标题（Miuix 面板自带的那一行）。要放自定义抬头（书名 + 副标题）就传 null，在内容里自己排
 * @param backgroundColor 面板底色。默认 Miuix 的 `background`（与卡片同色，浅色白、深色亮一档）；
 *   阅读器里传 readerOverlayColor()
 * @param enableWindowDim 是否压暗面板后面的内容。只有阅读设置面板传 false：上面露出来的正文就是排版的实时预览，
 *   隔着一层黑看，纸色和亮度根本没法判断
 * @param allowDismiss false 时下滑、点蒙层、返回键都关不掉（下滑会弹回原位），只有 [AppSheetScope.dismiss] 能关。
 *   给「有没保存的改动」的编辑面板用：Miuix 是先把面板动画移出屏幕、再回调关闭请求，回调里说「不」就晚了，
 *   只能事先把这条路关上。返回键要不要改成「问一句」由调用方在内容里另挂 NavigationBackHandler
 *   （内容里的比面板自己的后注册，优先拿到返回事件）
 * @param startAction 标题行左侧的动作位（HyperOS 编辑面板的「×」）。要配 [title] 才有那一行
 */
@Composable
fun AppBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    backgroundColor: Color = MiuixTheme.colorScheme.background,
    padNavigationBar: Boolean = true,
    enableWindowDim: Boolean = true,
    allowDismiss: Boolean = true,
    startAction: (@Composable AppSheetScope.() -> Unit)? = null,
    content: @Composable AppSheetScope.() -> Unit,
) {
    var show by remember { mutableStateOf(true) }
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val latestDismiss by rememberUpdatedState(onDismissRequest)
    val scope = remember {
        object : AppSheetScope {
            override fun dismiss(then: () -> Unit) {
                pending = then
                show = false
            }
        }
    }
    WindowBottomSheet(
        show = show,
        modifier = modifier,
        title = title,
        startAction = startAction?.let { action -> { scope.action() } },
        backgroundColor = backgroundColor,
        enableWindowDim = enableWindowDim,
        allowDismiss = allowDismiss,
        // 内容自己管左右边距：列表行、设置行都带着 listHorizontal，Miuix 默认的 24dp 再叠一层就窄了
        insideMargin = DpSize(0.dp, 0.dp),
        onDismissRequest = { show = false },
        onDismissFinished = {
            // 先让调用方摘掉面板，再办事：事情若是「打开另一个面板」，两个面板不会叠着同时在组合树里
            latestDismiss()
            pending?.invoke()
            pending = null
        },
    ) {
        Column(Modifier.fillMaxWidth()) {
            scope.content()
            if (padNavigationBar) {
                Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
            }
        }
    }
}

