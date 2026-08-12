package com.radium.inkwell.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
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
 * 组内行位置：决定按压遮罩的圆角，与系统设置一致。
 *
 * - [Alone] 单行组：四角圆（如「关于」）
 * - [First] 组首：上圆下直（如「WebDAV 备份同步」）
 * - [Middle] 组中：四角直
 * - [Last] 组末：上直下圆（如「清除正文缓存」）
 */
enum class SettingGroupPosition {
    Alone,
    First,
    Middle,
    Last,
}

/** 与 [SettingGroup] / [MaterialTheme.shapes.large] 同档的圆角半径 */
private val SettingGroupCorner = 16.dp

@Composable
internal fun settingGroupItemShape(position: SettingGroupPosition): Shape = when (position) {
    SettingGroupPosition.Alone -> MaterialTheme.shapes.large
    SettingGroupPosition.First -> RoundedCornerShape(
        topStart = SettingGroupCorner,
        topEnd = SettingGroupCorner,
        bottomStart = 0.dp,
        bottomEnd = 0.dp,
    )
    SettingGroupPosition.Middle -> RectangleShape
    SettingGroupPosition.Last -> RoundedCornerShape(
        topStart = 0.dp,
        topEnd = 0.dp,
        bottomStart = SettingGroupCorner,
        bottomEnd = SettingGroupCorner,
    )
}

/**
 * 设置页画布色（Scaffold / 顶栏）。
 *
 * 浅色：灰底（[ColorScheme.surfaceContainerLow]）；深色：最深底（[ColorScheme.background]）。
 * 深色不能再用 Low —— Low 比 Lowest 亮，会变成「浅底深卡」凹陷感，和系统设置反着。
 */
@Composable
fun settingsPageColor(): Color {
    val scheme = MaterialTheme.colorScheme
    return if (scheme.background.luminance() > 0.5f) {
        scheme.surfaceContainerLow
    } else {
        scheme.background
    }
}

/**
 * 设置分组卡底色。
 *
 * 浅色：近白（[ColorScheme.surfaceContainerLowest]）；深色：抬一阶的灰
 * （[ColorScheme.surfaceContainerLow]），压在黑画布上才像「浮起来」的卡。
 */
@Composable
fun settingsCardColor(): Color {
    val scheme = MaterialTheme.colorScheme
    return if (scheme.background.luminance() > 0.5f) {
        scheme.surfaceContainerLowest
    } else {
        scheme.surfaceContainerLow
    }
}

/**
 * 设置页分组卡：多条 [SettingRow] / [SwitchRow]（带 [SettingGroupPosition]）收进同一张大圆角 Surface。
 *
 * 圆角只画在这一层，组内行按位置切角，避免「框套框」与按压遮罩四角形状不对。
 * 组间距靠上下各半格 [Dimens.gapM]，组与组之间等于一整格。
 *
 * 色：见 [settingsPageColor] / [settingsCardColor] —— 浅色灰底白卡、深色黑底浅卡。
 * 角：用 [MaterialTheme.shapes.large]（16dp），对齐系统设置分组卡。
 */
@Composable
fun SettingGroup(
    modifier: Modifier = Modifier,
    /**
     * 是否自带左右 [Dimens.listHorizontal]。
     * 父级已经有水平边距时（阅读设置面板外层已有 screenPadding）关掉，
     * 否则开关卡会比上面的 Chip 行左右各再缩一截。
     */
    applyHorizontalInset: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (applyHorizontalInset) {
                    Modifier.padding(horizontal = Dimens.listHorizontal)
                } else {
                    Modifier
                },
            )
            .padding(vertical = Dimens.gapM / 2),
        shape = MaterialTheme.shapes.large,
        color = settingsCardColor(),
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
 * @param position 非 null 表示在 [SettingGroup] 内，并标明组内位置（按压遮罩圆角）。
 *   底栏 / 选择面板等「一条一卡」场景保持 null。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingRow(
    title: String,
    subtitle: String? = null,
    value: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    position: SettingGroupPosition? = null,
) {
    val grouped = position != null
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
    val shapes = if (position != null) {
        ContentListDefaults.groupedShapes(position)
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
 * 进 [SettingGroup] 时传 [position]，按压遮罩圆角跟组内位置走。
 */
@Composable
fun SwitchRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    position: SettingGroupPosition? = null,
) {
    val grouped = position != null
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
        shapes = if (position != null) {
            ContentListDefaults.groupedShapes(position)
        } else {
            ContentListDefaults.toggleShapes()
        },
        interactionSource = if (grouped) interactionSource else null,
        content = {
            Text(title, style = MaterialTheme.typography.bodyLarge)
        },
    )
}
