package com.radium.inkwell.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 一排单选 chip。
 *
 * **内部固定横向滚动**，而不是让调用方自己记得加。原来阅读菜单里的 ChipRow 是个固定宽度的
 * Row：选项一多（自动翻页有 7 档）就塞不下，Row 把剩余宽度硬分给最后一个 chip，
 * 它的文字被压成竖排单字 ——「45s」变成了三行。这种事只要有一处忘了加滚动就会再犯，
 * 所以把它焊死在组件里。
 *
 * @param trailing 跟在选项后面的额外 chip（如书源页「分组」打开选择面板），仍在同一条横滚里
 */
@Composable
fun ChipRow(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /** 首尾边距，随内容滚动 —— 让发现页/订阅页的分类条也能收敛到这个组件 */
    contentPadding: PaddingValues = PaddingValues(0.dp),
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(contentPadding),
        horizontalArrangement = Arrangement.spacedBy(Dimens.chipSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { i, label ->
            AppFilterChip(
                selected = i == selectedIndex,
                onClick = { onSelect(i) },
                label = label,
            )
        }
        trailing?.invoke(this)
    }
}

/**
 * 单个筛选 chip。[ChipRow] 装不下的场合用它（阅读菜单的「系统亮度」、书源页的「分组」——
 * 它们是横滚条尾巴上的独立开关，不属于那组单选项）。
 *
 * 存在的理由只有一个：Expressive 的**按压形变**在带 `shapes` 的重载上（按下时圆角收一档，
 * 选中态另有一档更方的圆角）。裸写 `FilterChip` 落到旧重载，形状是死的 —— 同一条 chip 条上
 * 混着两种重载时，尾巴那个按下去不动，看着像失灵。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, maxLines = 1) },
        shapes = FilterChipDefaults.shapes(),
        modifier = modifier,
    )
}
