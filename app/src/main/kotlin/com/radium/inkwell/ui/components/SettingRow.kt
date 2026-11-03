package com.radium.inkwell.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.ZeroCornerSize
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

/**
 * 组内行的切角：从 [SettingGroup] 用的 [MaterialTheme.shapes.large] 直接派生，只把不该圆的那一侧压成直角。
 * 从前另存了一份 16dp 半径、手拼 RoundedCornerShape —— 哪天主题改了 large，组卡跟着变、组内行的
 * 按压遮罩却还是旧圆角，两层角对不上。
 */
@Composable
internal fun settingGroupItemShape(position: SettingGroupPosition): Shape {
    val group = MaterialTheme.shapes.large
    return when (position) {
        SettingGroupPosition.Alone -> group
        SettingGroupPosition.First -> group.copy(bottomStart = ZeroCornerSize, bottomEnd = ZeroCornerSize)
        SettingGroupPosition.Middle -> RectangleShape
        SettingGroupPosition.Last -> group.copy(topStart = ZeroCornerSize, topEnd = ZeroCornerSize)
    }
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
 * 设置栈灰底上的内容列表行色。
 *
 * 默认 [ContentListDefaults.colors] 用 Low，和 [settingsPageColor] 同色会糊成一片；
 * 这里改用 [settingsCardColor]（白卡 / 深色浅卡），与 [SettingGroup] 同阶。
 */
@Composable
fun settingsStackListColors() =
    ContentListDefaults.colors(containerColor = settingsCardColor())

/**
 * 设置页分组卡：多条 [SettingRow] / [SwitchRow]（带 [SettingGroupPosition]）收进同一张大圆角 Surface。
 *
 * 圆角只画在这一层，组内行按位置切角，避免「框套框」与按压遮罩四角形状不对。
 * 组间距靠上下各 [Dimens.gapS]，组与组之间合起来正好一档 [Dimens.gapL]（「分组之间」）。
 * 从前是上下各 `gapM / 2`（6dp）—— 为了凑一整格 gapM 拿令牌做除法，落到了 4dp 栅格外面。
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
            .padding(vertical = Dimens.gapS),
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
    /**
     * 行首图标，放在浅色圆底里（[SettingIcon]）。只给设置主页那种「一列入口」用：十几行纯文字长得一模一样，
     * 找一项只能挨行读，图标是扫一眼就能落上去的锚点。二级页保持纯文字 —— 每页就几行，图标只会添乱。
     */
    icon: ImageVector? = null,
) {
    val grouped = position != null
    val leading: (@Composable () -> Unit)? = icon?.let { { SettingIcon(it) } }
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
            leadingContent = leading,
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
            leadingContent = leading,
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
 * 设置行的行首图标：secondaryContainer 的圆底 + onSecondaryContainer 的图标。
 * 用容器色而不是 primary：一页十来个图标全是强调色，强调就不成其为强调了。纯装饰，不进读屏（行标题就是名字）。
 */
@Composable
private fun SettingIcon(icon: ImageVector) {
    Box(
        Modifier
            .size(Dimens.settingIcon)
            .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(Dimens.iconMd),
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
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
