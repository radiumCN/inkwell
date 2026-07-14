package com.radium.inkwell.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 一排单选 chip。
 *
 * **内部固定横向滚动**，而不是让调用方自己记得加。原来阅读菜单里的 ChipRow 是个固定宽度的
 * Row：选项一多（自动翻页有 7 档）就塞不下，Row 把剩余宽度硬分给最后一个 chip，
 * 它的文字被压成竖排单字 ——「45s」变成了三行。这种事只要有一处忘了加滚动就会再犯，
 * 所以把它焊死在组件里。
 */
@Composable
fun ChipRow(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Dimens.gapS),
    ) {
        options.forEachIndexed { i, label ->
            FilterChip(
                selected = i == selectedIndex,
                onClick = { onSelect(i) },
                label = { Text(label, maxLines = 1) },
            )
        }
    }
}
