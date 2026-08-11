package com.radium.inkwell.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 分组小标题。从前 ReaderMenu 与 ThemeSettings 各定义了一份一模一样的 —— 现在它俩的
 * 私有 SectionLabel 都改成薄薄地转调这里，全 App 的分组标题一套字号/颜色。
 */
@Composable
fun SectionHeader(
    text: String,
    modifier: Modifier = Modifier,
    /** 调用方的内容 Column 已整体内缩时设 false，避免左右留白叠加两次 */
    horizontalInset: Boolean = true,
) {
    // 与 ContentListItem 的 listHorizontal 对齐，设置页卡片列表左右才能齐
    val h = if (horizontalInset) Dimens.listHorizontal else 0.dp
    Text(
        text,
        modifier.padding(start = h, end = h, top = Dimens.sectionHeaderTop, bottom = Dimens.gapS),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

/**
 * 设置项。走 [ContentListItem]，与内容列表同一套 Expressive 容器色 / 圆角。
 * `trailing` 槽用来放开关、值文本或箭头。
 */
@Composable
fun SettingRow(
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val supporting: (@Composable () -> Unit)? = subtitle?.takeIf { it.isNotBlank() }?.let {
        {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
    }
    // 无可点动作时仍用 ListItem 的点击重载、enabled=false，保持同一套容器形态
    ContentListItem(
        onClick = onClick ?: {},
        modifier = ContentListDefaults.rowChrome(),
        enabled = onClick != null,
        trailingContent = trailing,
        supportingContent = supporting,
        content = {
            Text(title, style = MaterialTheme.typography.bodyLarge)
        },
    )
}

/**
 * 开关项：整行可点，点行等于拨开关。
 *
 * 用 checked 重载让整行成为一个开关语义目标，并把行内 Switch 的
 * onCheckedChange 置空（纯展示）—— 否则读屏会出现「行」和「开关」两个焦点，
 * 且行焦点念不出开/关状态。
 *
 * 形状与底色走 [ContentListDefaults.toggleShapes] / [ContentListDefaults.toggleColors]：
 * Expressive 默认会把 checked 当成「选中」换成更圆的角和 secondaryContainer，
 * 设置页里就会和旁边的 [SettingRow] 长得不像一类东西。开/关只由行内 Switch 表达。
 */
@Composable
fun SwitchRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    val supporting: (@Composable () -> Unit)? = subtitle?.takeIf { it.isNotBlank() }?.let {
        {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
    }
    ContentListItem(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = ContentListDefaults.rowChrome(),
        enabled = enabled,
        trailingContent = {
            Switch(checked = checked, enabled = enabled, onCheckedChange = null)
        },
        supportingContent = supporting,
        colors = ContentListDefaults.toggleColors(),
        shapes = ContentListDefaults.toggleShapes(),
        content = {
            Text(title, style = MaterialTheme.typography.bodyLarge)
        },
    )
}
