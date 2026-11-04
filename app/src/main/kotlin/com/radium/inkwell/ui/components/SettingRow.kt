package com.radium.inkwell.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import com.radium.inkwell.ui.theme.AppShapes
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 分组小标题：Miuix [SmallTitle]（subtitle 字号、`onBackgroundVariant` 灰）。全 App 的分组标题一套字号/颜色。
 *
 * 左边对齐**卡片里的文字**（卡片外边距 + 行内边距），不是对齐卡片边 —— HyperOS 设置页就是这么排的：
 * 小标题、行标题在同一条竖线上。
 */
@Composable
fun SectionHeader(
    text: String,
    modifier: Modifier = Modifier,
    /** 调用方的内容 Column 已整体内缩时设 false，避免左右留白叠加两次 */
    horizontalInset: Boolean = true,
) {
    val start = if (horizontalInset) Dimens.listHorizontal * 2 else Dimens.listHorizontal
    SmallTitle(
        text = text,
        modifier = modifier,
        insideMargin = PaddingValues(
            start = start,
            end = start,
            top = Dimens.sectionHeaderTop,
            bottom = Dimens.gapS,
        ),
    )
}

/**
 * 组内行位置：决定按压反馈的圆角，与组卡的四角对齐。
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
 * 组内行的切角：从 [SettingGroup] 用的 [AppShapes.large] 直接派生，只把不该圆的那一侧压成直角。
 * 哪天组卡的圆角改了，组内行的按压反馈跟着变，两层角对得上。
 */
internal fun settingGroupItemShape(position: SettingGroupPosition): Shape {
    val group = AppShapes.large
    return when (position) {
        SettingGroupPosition.Alone -> group
        SettingGroupPosition.First -> group.copy(bottomStart = ZeroCornerSize, bottomEnd = ZeroCornerSize)
        SettingGroupPosition.Middle -> RectangleShape
        SettingGroupPosition.Last -> group.copy(topStart = ZeroCornerSize, topEnd = ZeroCornerSize)
    }
}

/**
 * 设置页画布色（Scaffold / 顶栏）：Miuix 的页面底 `surface`。浅色是比卡片深一档的灰底，深色是最深的底。
 *
 * 保留这个函数而不是让各页直接写 `surface`：设置类页面、书籍列表页都要「灰底白卡」这一对，
 * 哪天分层再改，只改这里和 [settingsCardColor]。
 */
@Composable
fun settingsPageColor(): Color = MiuixTheme.colorScheme.surface

/** 设置分组卡底色：Miuix 的卡片色 `surfaceContainer`（浅色白卡、深色亮一档的灰卡） */
@Composable
fun settingsCardColor(): Color = MiuixTheme.colorScheme.surfaceContainer

/**
 * 设置栈灰底上的内容列表行色。现在就是 [ContentListDefaults.colors] 的默认值（卡片色），
 * 留着名字是让书籍列表这类「灰底上一张张白卡」的页面读起来和设置页是一回事。
 */
@Composable
fun settingsStackListColors() =
    ContentListDefaults.colors(containerColor = settingsCardColor())

/**
 * 设置页分组卡：多条 [SettingRow] / [SwitchRow]（带 [SettingGroupPosition]）收进同一张 Miuix [Card]
 * （16dp 连续曲率圆角、卡片色）。
 *
 * 圆角只画在这一层，组内行透明、按位置切按压反馈的角，避免「框套框」。
 * 组间距靠上下各 [Dimens.gapS]，组与组之间合起来正好一档 [Dimens.gapL]（「分组之间」）。
 */
@Composable
fun SettingGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.listHorizontal)
            .padding(vertical = Dimens.gapS),
        cornerRadius = AppShapes.radiusLarge,
        colors = CardDefaults.defaultColors(color = settingsCardColor()),
        content = content,
    )
}

/**
 * 设置项。走 [ContentListItem]，与 Miuix `ArrowPreference` 同一个长相：标题 17sp Medium，
 * 可点的行尾带一个灰色 ›（HyperOS「点进去还有一页」的标记）。
 *
 * 设置页默认只留一行标题，把行压矮；当前选中值 / 描述性信息（版本号、许可证等）
 * 走 [value]（右侧同行，摘要色）。说明性长文案不要塞 [subtitle] —— 需要解释时留给点开后的面板。
 * `trailing` 优先于 `value`（开关等自定义尾部仍走它），给了 `trailing` 就不画 ›。
 *
 * @param position 非 null 表示在 [SettingGroup] 内，并标明组内位置（按压反馈的圆角）。
 *   底栏 / 选择面板等「一条一卡」场景保持 null。
 */
@Composable
fun SettingRow(
    title: String,
    subtitle: String? = null,
    value: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    position: SettingGroupPosition? = null,
    /**
     * 行首图标，放在淡强调色的圆角方底里（[SettingIcon]）。只给设置主页那种「一列入口」用：十几行纯文字长得一模一样，
     * 找一项只能挨行读，图标是扫一眼就能落上去的锚点。二级页保持纯文字 —— 每页就几行，图标只会添乱。
     */
    icon: ImageVector? = null,
) {
    val grouped = position != null
    val leading: (@Composable () -> Unit)? = icon?.let { { SettingIcon(it) } }
    val supporting: (@Composable () -> Unit)? = subtitle?.takeIf { it.isNotBlank() }?.let {
        { Text(it, style = ContentListDefaults.supportingStyle) }
    }
    val valueText = value?.takeIf { it.isNotBlank() }
    val trailingContent: (@Composable () -> Unit)? = when {
        trailing != null -> trailing
        valueText != null || onClick != null -> {
            {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (valueText != null) {
                        Text(
                            valueText,
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (onClick != null) RowChevron(Modifier.padding(start = Dimens.gapS))
                }
            }
        }
        else -> null
    }
    val colors = if (grouped) ContentListDefaults.plainColors() else ContentListDefaults.colors()
    val shape = if (position != null) settingGroupItemShape(position) else ContentListDefaults.Shape
    val rowModifier = if (grouped) Modifier else ContentListDefaults.rowChrome()
    val titleContent: @Composable () -> Unit = {
        Text(title, style = ContentListDefaults.titleStyle)
    }

    if (onClick != null) {
        ContentListItem(
            onClick = onClick,
            modifier = rowModifier,
            leadingContent = leading,
            trailingContent = trailingContent,
            supportingContent = supporting,
            colors = colors,
            shape = shape,
            content = titleContent,
        )
    } else {
        // 纯展示行（版本、许可证…）：用不可点的重载 —— 别用 enabled=false，那会把右侧 value 洗淡
        ContentListItem(
            modifier = rowModifier,
            leadingContent = leading,
            trailingContent = trailingContent,
            supportingContent = supporting,
            colors = colors,
            shape = shape,
            content = titleContent,
        )
    }
}

/**
 * 分组卡里一整块不是「行」的内容：表单（输入框 + 按钮）、分段按钮、说明文字。
 * 左右内缩与行内文字对齐（[Dimens.listHorizontal]），块内各项按 [Dimens.gapM] 拉开。
 *
 * 设置类页面只有一种骨架：灰底 + [SectionHeader] + [SettingGroup]，**所有控件都装在卡里**。
 * 卡里放的不是行时就用这个块。
 */
@Composable
fun SettingBlock(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.listHorizontal, vertical = Dimens.gapM),
        verticalArrangement = Arrangement.spacedBy(Dimens.gapM),
        content = content,
    )
}

/**
 * 分组卡下面的说明小字（「会同步什么」「时长怎么算」）。左边与 [SectionHeader] 对齐 ——
 * 标题、卡里的字、脚注是一组东西，起点在同一条竖线上。
 */
@Composable
fun SettingFootnote(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
) {
    Text(
        text,
        style = MiuixTheme.textStyles.footnote1,
        color = color,
        modifier = modifier.padding(horizontal = Dimens.listHorizontal * 2, vertical = Dimens.gapXS),
    )
}

/**
 * 设置行的行首图标：淡强调底（`tertiaryContainer`）的圆角方块 + 强调色图标 —— HyperOS 设置页的彩色小方块，
 * 只是不一行一个颜色：一页十来个图标全是饱和色，强调就不成其为强调了。纯装饰，不进读屏（行标题就是名字）。
 */
@Composable
private fun SettingIcon(icon: ImageVector) {
    Box(
        Modifier
            .size(Dimens.settingIcon)
            .squircleBackground(MiuixTheme.colorScheme.tertiaryContainer, AppShapes.radiusMedium),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(Dimens.iconMd),
            tint = MiuixTheme.colorScheme.onTertiaryContainer,
        )
    }
}

/**
 * 行尾的 ›：Miuix `ArrowPreference` 用的同一个图（10×16dp、`onSurfaceVariantActions` 色），RTL 下镜像。
 * 纯装饰，不进读屏。
 */
@Composable
internal fun RowChevron(modifier: Modifier = Modifier) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Image(
        imageVector = MiuixIcons.Basic.ArrowRight,
        contentDescription = null,
        colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onSurfaceVariantActions),
        modifier = modifier
            .width(Dimens.rowChevronWidth)
            .height(Dimens.rowChevronHeight)
            .graphicsLayer { scaleX = if (rtl) -1f else 1f },
    )
}

/**
 * 开关项：整行可点，点行等于拨开关（Miuix `SwitchPreference` 的长相）。
 *
 * 不直接用 `SwitchPreference`：它的行和行内 Switch **各自**可点，读屏会出现「行」和「开关」两个焦点，
 * 且行焦点念不出开/关状态。这里整行是一个 [Role.Switch] 语义目标，行内 [Switch] 的
 * onCheckedChange 置空（纯展示）。
 *
 * 开/关**不**换行的底色 —— 状态只由开关表达。独立成卡时底是卡片色；进 [SettingGroup] 时传 [position]，
 * 底透明、按压反馈的圆角跟组内位置走。
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
        { Text(it, style = ContentListDefaults.supportingStyle) }
    }
    ContentListItem(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = if (grouped) Modifier else ContentListDefaults.rowChrome(),
        enabled = enabled,
        role = Role.Switch,
        trailingContent = {
            Switch(checked = checked, enabled = enabled, onCheckedChange = null)
        },
        supportingContent = supporting,
        colors = if (grouped) {
            ContentListDefaults.plainColors()
        } else {
            ContentListDefaults.plainColors(containerColor = settingsCardColor())
        },
        shape = if (position != null) settingGroupItemShape(position) else ContentListDefaults.Shape,
        content = {
            Text(title, style = ContentListDefaults.titleStyle)
        },
    )
}
