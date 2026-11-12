package com.radium.inkwell.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/**
 * 管理类列表（来源管理、净化替换、订阅源）压在标题下面的那一截：搜索框 + 一排单选筛选 chip。
 * 放进 [AppToolbar] 的 `bottomContent`，而不是列表的第一行：列表从它底下滚过去、和顶栏一起被毛玻璃盖住，
 * 搜索框也不会随列表滚走。进出多选的两条栏都带上它，栏高不变、不跳。
 *
 * 几百条的列表没有搜索和筛选就只能一路滑；几十条也一样 —— 想找某个站的规则、挑出关掉的源，全靠眼睛。
 *
 * @param trailing 筛选条末尾的额外 chip（分组：[GroupFilterChip]）
 */
@Composable
fun ListSearchHeader(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    filters: List<String>,
    selectedFilter: Int,
    onFilterChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Column(modifier) {
        SearchField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = placeholder,
            modifier = Modifier.fillMaxWidth()
                .padding(horizontal = Dimens.listHorizontal, vertical = Dimens.gapXS),
        )
        ChipRow(
            options = filters,
            selectedIndex = selectedFilter,
            onSelect = onFilterChange,
            contentPadding = PaddingValues(horizontal = Dimens.listHorizontal),
            trailing = trailing,
        )
    }
}

/**
 * 筛选条末尾的「分组」chip：点开从这颗 chip 上弹出分组列表（当前分组打勾），不另起底部面板。
 * 筛着某个组时 chip 亮着、字换成组名。没有分组时调用方别放它（一颗点开只有「全部分组」的 chip 没有意义）。
 *
 * @param selected null = 不按分组筛
 */
@Composable
fun GroupFilterChip(
    groups: List<String>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    // 菜单和 chip 放进同一个 Box，从 chip 上弹出来（见 AppDropdownMenu）
    Box {
        AppFilterChip(
            selected = selected != null,
            onClick = { open = true },
            label = selected ?: "分组",
        )
        AppDropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            items = listOf(
                AppMenuItem(
                    "全部分组",
                    onClick = {
                        open = false
                        onSelect(null)
                    },
                    selected = selected == null,
                ),
            ) + groups.map { group ->
                AppMenuItem(
                    group,
                    onClick = {
                        open = false
                        onSelect(group)
                    },
                    selected = group == selected,
                )
            },
        )
    }
}

/**
 * 一个条目挂着的分组：阅读的分组字段可以一次写几个，逗号（全半角）分开。
 * 来源管理、订阅源的分组筛选与分组列表都按它拆。
 */
fun splitGroups(groupName: String): List<String> =
    groupName.split(',', '，').map { it.trim() }.filter { it.isNotEmpty() }
