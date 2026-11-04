package com.radium.inkwell.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Check

/**
 * 「从 N 项里选一个」的统一形态。
 *
 * 从前每个页面各写各的：发现页和详情页用裸 `DropdownMenu`（弹个小浮层，选中态靠
 * 手拼 "✓ xxx" 字符串），阅读页却是带标题的底部面板，设置页又是 AlertDialog + RadioButton。
 * 同一条链路（发现 → 详情 → 阅读）里换个源能看见三种控件。这里收敛成一种。
 */
data class PickerOption(
    val id: String,
    val label: String,
    val subtitle: String? = null,
)

/**
 * 底部选择面板（[AppBottomSheet]，标题居中）。选中项左侧打勾、整行走选中色 —— 不再靠 "✓ " 前缀拼字符串。
 *
 * 选中后先把面板收下去（播完退场动画）再回调 [onSelect] / [onDismiss]：调用方若立刻把 `if (show)` 置 false，
 * 面板会从组合树摘掉，退场动画直接被掐断。
 */
@Composable
fun OptionPickerSheet(
    title: String,
    options: List<PickerOption>,
    selectedId: String?,
    onSelect: (PickerOption) -> Unit,
    onDismiss: () -> Unit,
    header: @Composable (() -> Unit)? = null,
) {
    // 打开时直接定位到当前选中项：书源一多，每次都从头翻找刚才选的那个很折磨。
    // 往上留两行余量，让选中项不贴着列表顶边、一眼能看出它前后是谁。
    // 只取首次组合的初值 —— 面板开着时用户自己滚动不该被拽回去
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = options.indexOfFirst { it.id == selectedId }
            .let { if (it < 0) 0 else (it - 2).coerceAtLeast(0) },
    )

    // 列表会滚动：导航栏高度垫进列表末尾（见 sheetBottomInset），不让面板在外面垫
    AppBottomSheet(onDismissRequest = onDismiss, title = title, padNavigationBar = false) {
        Column(Modifier.fillMaxWidth()) {
            header?.invoke()
            val bottomInset = Dimens.gapXL + sheetBottomInset()
            LazyColumn(
                Modifier.heightIn(max = Dimens.sheetListMaxHeight + bottomInset),
                state = listState,
                contentPadding = ContentListDefaults.listContentPadding(bottom = bottomInset),
                verticalArrangement = Arrangement.spacedBy(ContentListDefaults.ListSpacing),
            ) {
                items(options, key = { it.id }) { opt ->
                    OptionRow(
                        option = opt,
                        selected = opt.id == selectedId,
                        onClick = { dismiss { onSelect(opt) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun OptionRow(option: PickerOption, selected: Boolean, onClick: () -> Unit) {
    ContentListItem(
        selected = selected,
        onClick = onClick,
        leadingContent = {
            // 勾选位始终占宽，选中与否不会让文字左右跳动
            if (selected) {
                Icon(
                    MiuixIcons.Basic.Check,
                    contentDescription = null,
                    Modifier.size(Dimens.iconSm),
                )
            } else {
                Spacer(Modifier.size(Dimens.iconSm))
            }
        },
        supportingContent = option.subtitle?.takeIf { it.isNotBlank() }?.let {
            {
                Text(it, style = ContentListDefaults.supportingStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        content = {
            Text(
                option.label,
                style = ContentListDefaults.titleStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}
