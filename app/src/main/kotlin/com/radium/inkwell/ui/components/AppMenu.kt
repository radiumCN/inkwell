package com.radium.inkwell.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import top.yukonga.miuix.kmp.basic.DropdownDefaults
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.popup.WindowDropdownPopup

/** [AppDropdownMenu] 里的一项 */
@Immutable
data class AppMenuItem(
    val text: String,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
    /** 第二行小字（「从文件导入」下面注一句支持的格式之类）；大多数菜单项不需要 */
    val summary: String? = null,
)

/**
 * 锚在某个按钮上的下拉菜单（顶栏 ⋮、导入方式）。Miuix [WindowDropdownPopup]：HyperOS 那种圆角浮层，
 * 从锚点处展开，点了某项自动收起。
 *
 * 用法与 M3 `DropdownMenu` 相同 —— **和锚点放进同一个 `Box`**，弹层按这个 Box 的位置定位：
 * ```
 * Box {
 *     AppIconButton(onClick = { open = true }) { … }
 *     AppDropdownMenu(expanded = open, onDismissRequest = { open = false }, items = …)
 * }
 * ```
 * 菜单项的 `onClick` 里不必再手动 `open = false`：点了就收（收起走 [onDismissRequest]）。写了也无妨。
 *
 * 「从 N 项里选一个」仍走 [OptionPickerSheet]，别拿下拉菜单做单选 —— 这里没有选中态。
 */
@Composable
fun AppDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    items: List<AppMenuItem>,
) {
    if (items.isEmpty()) return
    // 不 remember：调用方每次重组都新建一份带新 lambda 的列表，拿它当 key 永远不命中，缓存只是摆设。
    // 几项菜单现建一遍很便宜
    val entry = DropdownEntry(
        items = items.map { item ->
            DropdownItem(
                text = item.text,
                enabled = item.enabled,
                onClick = item.onClick,
                summary = item.summary,
            )
        },
    )
    WindowDropdownPopup(
        entry = entry,
        show = expanded,
        onDismiss = onDismissRequest,
        onDismissFinished = {},
        maxHeight = null,
        dropdownColors = DropdownDefaults.dropdownColors(),
        collapseOnSelection = true,
    )
}
