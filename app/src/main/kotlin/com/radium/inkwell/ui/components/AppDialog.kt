package com.radium.inkwell.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 确认 / 表单弹窗的统一出口：Miuix [WindowDialog]（HyperOS 的居中标题弹窗，窄屏贴底、宽屏居中），
 * 标题 → 正文/内容 → 等宽「次要 | 主要」两块实心按钮。
 *
 * 用 `Window*` 不用 `Overlay*`：Overlay 系列要挂在 Miuix `Scaffold` 的弹层宿主上，而我们的页面是 Nav3
 * 多个 entry 各自一个 Scaffold、阅读器浮层又不在 Scaffold 里 —— Window 系列开独立窗口，放在哪都能弹。
 *
 * **调用方式不变**：调用方照旧 `if (show) AppAlertDialog(onDismissRequest = { show = false }, …)`。
 * Miuix 的弹窗是 `show` 驱动、自带退场动画的；若调用方一关就把它从组合树摘掉，退场动画会被掐断。
 * 所以这里自己持有可见状态：取消 / 点外面 / 返回键先把弹窗收下去，**动画播完**（`onDismissFinished`）
 * 才回调 [onDismiss]（默认即 [onDismissRequest]）。
 *
 * 确认键是例外，[onConfirm] 立刻回调、不自动收起：带 [confirmLoading] 的弹窗要留在原地转圈
 * （保存 WebDAV、导入中），收不收由调用方决定。
 *
 * [confirmLoading] 期间次按钮的意思也变了：不是「关弹窗」而是「取消这次操作」（更新弹窗的「取消下载」）——
 * 点了立刻回调 [onDismiss]、弹窗留着，下载停了还要接着显示「重试」。要在加载中禁掉次按钮，调用方自己传
 * [dismissEnabled] = false（新建书源的「重新识别」就是这么做的）。
 *
 * 一旦开始收起，两个按钮都不再响应：从前点了「不加入」、退场动画还没播完时再点「加入」，
 * 两条路先后都跑 —— 刚加进书架的书又被「不加入」那条删掉。
 *
 * 页面不要再手搓居中 Surface 冒充弹层。从几项里选一个走原位弹出的列表（[SpinnerSettingRow] /
 * [AppDropdownMenu]），不走对话框。
 *
 * @param title null 时不画 Miuix 自带的居中标题行，由 [content] 自己画头部（更新弹窗：图标 + 标题 + 版本）
 * @param scrollable false 时内容区不替调用方滚动、也不限高 —— 内容里自己有滚动区时传（更新弹窗的更新说明要自己的
 *   边缘渐隐）。外面再套一层滚动的话，里面那层会量出无限高直接崩
 */
@Composable
fun AppAlertDialog(
    onDismissRequest: () -> Unit,
    title: String?,
    confirmText: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    dismissText: String? = "取消",
    onDismiss: (() -> Unit)? = onDismissRequest,
    confirmEnabled: Boolean = true,
    confirmLoading: Boolean = false,
    dismissEnabled: Boolean = true,
    text: String? = null,
    scrollable: Boolean = true,
    content: @Composable (ColumnScope.() -> Unit)? = null,
) {
    var show by remember { mutableStateOf(true) }
    // 取消键与「点外面 / 返回键」可能是两个不同的回调（「稍后再说」要记一笔，点外面不记）：
    // 收起时记下这次走的是哪条，动画播完再调。取的是重组后最新的那个回调
    var viaCancel by remember { mutableStateOf(false) }
    val latestRequest by rememberUpdatedState(onDismissRequest)
    val latestCancel by rememberUpdatedState(onDismiss ?: onDismissRequest)
    WindowDialog(
        show = show,
        modifier = modifier,
        title = title,
        // 加载中、或调用方禁了取消（加入书架落库中）时，不让点外面 / 返回键把它关掉 ——
        // 否则保存到一半弹窗没了，结果提示落在别的页面上。取消键灰着，外面那条路也得一起关
        onDismissRequest = { if (!confirmLoading && dismissEnabled) show = false },
        onDismissFinished = { if (viaCancel) latestCancel() else latestRequest() },
    ) {
        DialogWindowBlur(visible = show)
        Column(Modifier.fillMaxWidth()) {
            if (text != null || content != null) {
                val bodyScroll = rememberScrollState()
                Column(
                    Modifier
                        .fillMaxWidth()
                        .then(
                            if (scrollable) {
                                Modifier.heightIn(max = Dimens.dialogBodyMaxHeight).verticalScroll(bodyScroll)
                            } else {
                                Modifier
                            },
                        ),
                    verticalArrangement = Arrangement.spacedBy(Dimens.gapM),
                ) {
                    if (text != null) {
                        // 字号/颜色照 Miuix 弹窗的 summary：居中、body1、onSurfaceSecondary
                        Text(
                            text,
                            modifier = Modifier.fillMaxWidth(),
                            fontSize = MiuixTheme.textStyles.body1.fontSize,
                            color = MiuixTheme.colorScheme.onSurfaceSecondary,
                            textAlign = TextAlign.Center,
                        )
                    }
                    content?.invoke(this)
                }
                Spacer(Modifier.height(Dimens.gapXL))
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Dimens.gapM),
            ) {
                if (dismissText != null) {
                    SecondaryButton(
                        text = dismissText,
                        // 收起中的点击只吞掉、不把按钮置灰：置灰的话两个按钮会在退场动画里闪一下禁用色
                        onClick = {
                            when {
                                !show -> Unit
                                confirmLoading -> latestCancel()
                                else -> {
                                    viaCancel = true
                                    show = false
                                }
                            }
                        },
                        enabled = dismissEnabled,
                        modifier = Modifier.weight(1f),
                    )
                }
                PrimaryButton(
                    text = confirmText,
                    onClick = { if (show) onConfirm() },
                    enabled = confirmEnabled,
                    loading = confirmLoading,
                    modifier = if (dismissText != null) Modifier.weight(1f) else Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
