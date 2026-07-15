package com.radium.inkwell.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
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
    val h = if (horizontalInset) Dimens.rowHorizontal else 0.dp
    Text(
        text,
        modifier.padding(start = h, end = h, top = 20.dp, bottom = 6.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

/**
 * 设置项。`trailing` 槽用来放开关、值文本或箭头。
 * 从前它是 SettingsScreen 的私有函数，WebDAV/主题/书源页只能各自手搓一份，
 * 于是行高和左右留白四五种规格，页面之间对不齐。
 */
@Composable
fun SettingRow(
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = Dimens.rowHorizontal, vertical = Dimens.rowVertical),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing?.let {
            Spacer(Modifier.width(12.dp))
            it()
        }
    }
}

/** 开关项：整行可点，点行等于拨开关 */
@Composable
fun SwitchRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    SettingRow(
        title = title,
        subtitle = subtitle,
        onClick = { onCheckedChange(!checked) },
        trailing = { Switch(checked = checked, onCheckedChange = onCheckedChange) },
    )
}
