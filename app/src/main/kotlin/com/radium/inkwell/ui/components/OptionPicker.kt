package com.radium.inkwell.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow

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

/** 底部选择面板。选中项左侧打勾 —— 不再靠 "✓ " 前缀拼字符串 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun OptionPickerSheet(
    title: String,
    options: List<PickerOption>,
    selectedId: String?,
    onSelect: (PickerOption) -> Unit,
    onDismiss: () -> Unit,
    header: @Composable (() -> Unit)? = null,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = Dimens.gapXL)) {
            Text(
                title,
                Modifier.padding(horizontal = Dimens.screenPadding, vertical = Dimens.gapS),
                style = MaterialTheme.typography.titleMedium,
            )
            header?.invoke()
            LazyColumn(
                Modifier.heightIn(max = Dimens.sheetListMaxHeight),
                contentPadding = ContentListDefaults.listContentPadding(),
                verticalArrangement = Arrangement.spacedBy(ContentListDefaults.ListSpacing),
            ) {
                items(options, key = { it.id }) { opt ->
                    OptionRow(opt, selected = opt.id == selectedId, onClick = { onSelect(opt) })
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
                    Icons.Default.Check,
                    contentDescription = null,
                    Modifier.size(Dimens.iconSm),
                )
            } else {
                Spacer(Modifier.size(Dimens.iconSm))
            }
        },
        supportingContent = option.subtitle?.takeIf { it.isNotBlank() }?.let {
            {
                Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        contentPadding = ContentListDefaults.ComfortablePadding,
        content = {
            Text(
                option.label,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}
