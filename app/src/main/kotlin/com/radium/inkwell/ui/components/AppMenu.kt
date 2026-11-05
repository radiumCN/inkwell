package com.radium.inkwell.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import top.yukonga.miuix.kmp.basic.DropdownItem

/** [AppDropdownMenu] 里的一项 */
@Immutable
data class AppMenuItem(
    val text: String,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
    /** 第二行小字（「从文件导入」下面注一句支持的格式之类）；大多数菜单项不需要 */
    val summary: String? = null,
    /** 单选弹出列表里的当前项：淡强调底 + 行尾打勾（Miuix 下拉选择器的长相）。动作菜单不用 */
    val selected: Boolean = false,
)

/**
 * 锚在某个按钮 / 某一行上的弹出列表：HyperOS 那种圆角浮层，从锚点处展开，点了某项自动收起。
 * 浮层是毛玻璃（背后整页糊开、卡片半透明，见 [GlassDropdownPopup]）。两种用法：
 * - **动作菜单**（顶栏 ⋮、导入方式、书架长按一本书）：各项只给 `onClick`；
 * - **从几项里选一个**（排序方式、切换书源）：当前项标 [AppMenuItem.selected]，弹出来就看得到选的是谁。
 *   设置页里「标题 + 当前值」那种选择行别用它，走 [SpinnerSettingRow]（锚点就是那一行）。
 *
 * 用法与 M3 `DropdownMenu` 相同 —— **和锚点放进同一个 `Box`**，弹层按这个 Box 的位置定位：
 * ```
 * Box {
 *     AppIconButton(onClick = { open = true }) { … }
 *     AppDropdownMenu(expanded = open, onDismissRequest = { open = false }, items = …)
 * }
 * ```
 * 菜单项的 `onClick` 里不必再手动 `open = false`：点了就收（收起走 [onDismissRequest]）。写了也无妨。
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
    GlassDropdownPopup(
        show = expanded,
        items = items.map { item ->
            DropdownItem(
                text = item.text,
                enabled = item.enabled,
                onClick = item.onClick,
                summary = item.summary,
                selected = item.selected,
            )
        },
        onDismissRequest = onDismissRequest,
    )
}
