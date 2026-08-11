package com.radium.inkwell.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
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
    // 与 ContentListItem / SettingGroup 的 listHorizontal 对齐
    val h = if (horizontalInset) Dimens.listHorizontal else 0.dp
    Text(
        text,
        modifier.padding(start = h, end = h, top = Dimens.sectionHeaderTop, bottom = Dimens.gapS),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

/**
 * 设置页分组卡：多条 [SettingRow] / [SwitchRow]（`grouped = true`）收进同一张大圆角 Surface。
 *
 * 圆角只画在这一层，组内行用直角透明底，避免「框套框」。
 * 组间距靠上下各半格 [Dimens.gapM]，组与组之间等于一整格。
 *
 * 色：卡用 [ColorScheme.surfaceContainerLowest]（浅色近白），页画布用
 * [ColorScheme.surfaceContainerLow]（见各设置页 Scaffold）—— 灰底白卡。
 *
 * 角：用 [Shapes.large]（16dp），对齐系统设置分组卡；不要用 extraLarge（28dp，Dialog 档），
 * 并排一看会显得比系统「鼓」一圈。
 */
@Composable
fun SettingGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.listHorizontal)
            .padding(vertical = Dimens.gapM / 2),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        content = { Column(content = content) },
    )
}

/**
 * 设置项。走 [ContentListItem]，与内容列表同一套 Expressive 容器色 / 圆角。
 *
 * 设置页默认只留一行标题，把卡片压矮；当前选中值 / 描述性信息（版本号、许可证等）
 * 走 [value]（右侧同行）。说明性长文案不要塞 [subtitle] —— 需要解释时留给点开后的面板。
 * `trailing` 优先于 `value`（开关等自定义尾部仍走它）。
 *
 * @param grouped 为 true 时放进 [SettingGroup]：去掉行级 chrome，底色透明、角直角。
 *   底栏 / 选择面板等「一条一卡」场景保持默认 false。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingRow(
    title: String,
    subtitle: String? = null,
    value: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    grouped: Boolean = false,
) {
    val supporting: (@Composable () -> Unit)? = subtitle?.takeIf { it.isNotBlank() }?.let {
        {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
    }
    val trailingContent = trailing ?: value?.takeIf { it.isNotBlank() }?.let {
        {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    // 分组行静息透明：Expressive 形变按压看不见，要靠按下态铺一层 state layer。
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val colors = if (grouped) {
        ContentListDefaults.groupedColors(pressed = pressed)
    } else {
        ContentListDefaults.colors()
    }
    val shapes = if (grouped) {
        ContentListDefaults.groupedShapes()
    } else {
        ListItemDefaults.shapes()
    }
    val rowModifier = if (grouped) Modifier else ContentListDefaults.rowChrome()

    if (onClick != null) {
        ContentListItem(
            onClick = onClick,
            modifier = rowModifier,
            trailingContent = trailingContent,
            supportingContent = supporting,
            colors = colors,
            shapes = shapes,
            interactionSource = if (grouped) interactionSource else null,
            content = {
                Text(title, style = MaterialTheme.typography.bodyLarge)
            },
        )
    } else {
        // 纯展示行（版本、许可证…）：用无点击重载，避免 enabled=false 把右侧 value 洗淡。
        ListItem(
            modifier = rowModifier.fillMaxWidth(),
            trailingContent = trailingContent,
            supportingContent = supporting,
            shapes = shapes,
            colors = colors,
            contentPadding = ContentListDefaults.CompactPadding,
            content = {
                Text(title, style = MaterialTheme.typography.bodyLarge)
            },
        )
    }
}

/**
 * 开关项：整行可点，点行等于拨开关。
 *
 * 用 checked 重载让整行成为一个开关语义目标，并把行内 Switch 的
 * onCheckedChange 置空（纯展示）—— 否则读屏会出现「行」和「开关」两个焦点，
 * 且行焦点念不出开/关状态。
 *
 * 独立成卡时形状与底色走 [ContentListDefaults.toggleShapes] / [toggleColors]；
 * 进 [SettingGroup] 时走 grouped 变体（开/关不换外形，圆角交给外层 Surface）。
 */
@Composable
fun SwitchRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    grouped: Boolean = false,
) {
    val supporting: (@Composable () -> Unit)? = subtitle?.takeIf { it.isNotBlank() }?.let {
        {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
    }
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    ContentListItem(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = if (grouped) Modifier else ContentListDefaults.rowChrome(),
        enabled = enabled,
        trailingContent = {
            Switch(checked = checked, enabled = enabled, onCheckedChange = null)
        },
        supportingContent = supporting,
        colors = if (grouped) {
            ContentListDefaults.groupedColors(pressed = pressed)
        } else {
            ContentListDefaults.toggleColors()
        },
        shapes = if (grouped) {
            ContentListDefaults.groupedShapes()
        } else {
            ContentListDefaults.toggleShapes()
        },
        interactionSource = if (grouped) interactionSource else null,
        content = {
            Text(title, style = MaterialTheme.typography.bodyLarge)
        },
    )
}
