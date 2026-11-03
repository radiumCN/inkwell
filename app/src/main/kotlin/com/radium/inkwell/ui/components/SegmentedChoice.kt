package com.radium.inkwell.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow

/**
 * 几档里选一档的分段按钮（M3 Expressive 连体按钮组）：行距「紧凑 / 标准 / 宽松」、翻页方式这类
 * 选项**固定且不多**（2~5 档）的单选。
 *
 * 比 [ChipRow] 强在选中态：选中那一段整块实心填 primary、圆角收成胶囊，未选中是浅一档的容器色 ——
 * chip 的选中只是淡底，与未选中的细描边分量差不多，一排看下来分不清当前是哪一档（阅读设置面板就栽在这）。
 * 选项多、会变（字体列表、方案列表、自动翻页的七档间隔）仍用 [ChipRow]：它能横滚，这里不能。
 *
 * 每一段都是单选按钮语义（[Role.RadioButton]）+ 整排 selectableGroup：读屏念「行距，标准，已选中，2/3」，
 * 而不是把它当成三个独立的开关。[selectedIndex] 为 -1（当前值不在档位里，比如同步来的自定义值）时一段都不亮。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SegmentedChoice(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val colors = ToggleButtonDefaults.colors(
        containerColor = scheme.surfaceContainerHighest,
        contentColor = scheme.onSurface,
        checkedContainerColor = scheme.primary,
        checkedContentColor = scheme.onPrimary,
    )
    Row(
        modifier
            .fillMaxWidth()
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        options.forEachIndexed { i, label ->
            ToggleButton(
                checked = i == selectedIndex,
                onCheckedChange = { onSelect(i) },
                modifier = Modifier
                    .weight(1f)
                    .semantics { role = Role.RadioButton },
                shapes = when (i) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                colors = colors,
                // 默认左右各 16dp，五档挤在一行时「滚动」两个字会被截成「滚…」
                contentPadding = PaddingValues(horizontal = Dimens.gapXS),
            ) {
                Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
