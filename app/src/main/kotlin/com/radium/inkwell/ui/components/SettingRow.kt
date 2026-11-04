package com.radium.inkwell.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import com.radium.inkwell.ui.theme.AppShapes
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.WindowSpinnerPreference
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
 * 设置项：可点的是 Miuix [ArrowPreference]（行尾灰色 ›，HyperOS「点进去还有一页」的标记），
 * 纯展示的是 Miuix [BasicComponent]。标题 17sp Medium、16dp 内边距、最小行高、按压变暗都是 Miuix 原样。
 *
 * 设置页默认只留一行标题，把行压矮；当前选中值 / 描述性信息（版本号、许可证等）
 * 走 [value]（右侧同行，摘要色）。说明性长文案不要塞 [subtitle] —— 需要解释时留给点开后的面板。
 * `trailing` 优先于 `value`（自定义尾部走它），给了 `trailing` 就不画 ›。
 * 「标题 + 当前值、点开从几项里选一个」的行别用它弹面板，用 [SpinnerSettingRow]。
 *
 * @param position 非 null 表示在 [SettingGroup] 内（卡片负责底色与圆角，按压反馈被卡片裁进圆角）。
 *   底栏 / 面板里「一条一卡」的场景保持 null，行自己画一张卡。
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
    val chrome = if (position != null) Modifier else Modifier.standaloneRowCard()
    val startAction: (@Composable () -> Unit)? = icon?.let { { SettingIcon(it) } }
    val summary = subtitle?.takeIf { it.isNotBlank() }
    val valueText = value?.takeIf { it.isNotBlank() }
    val endActions: (@Composable RowScope.() -> Unit)? = when {
        trailing != null -> { { trailing() } }
        valueText != null -> { { RowValue(valueText) } }
        else -> null
    }
    if (onClick != null && trailing == null) {
        ArrowPreference(
            title = title,
            // ArrowPreference 不收 role：补上，读屏才念「按钮」
            modifier = chrome.semantics { role = Role.Button },
            summary = summary,
            startAction = startAction,
            endActions = endActions ?: {},
            onClick = onClick,
        )
    } else {
        // 纯展示行（版本、许可证…）别传 enabled=false：那会把标题和右侧 value 一起洗淡
        BasicComponent(
            modifier = chrome,
            title = title,
            summary = summary,
            startAction = startAction,
            endActions = endActions,
            onClick = onClick,
            role = if (onClick != null) Role.Button else null,
        )
    }
}

/**
 * 「标题 + 当前值」的选择行：Miuix [WindowSpinnerPreference]。点这一行，选项列表从行上弹出来（当前项打勾、
 * 带说明的项第二行是说明），行尾是当前值和 ⇅ —— HyperOS 设置页「从几项里选一个」都是这个样子，
 * 别再弹底部面板 + 单选圈。选中即收起，[onSelect] 在点选时回调。
 *
 * 不是设置行、而是某个按钮触发的单选（排序方式、切换书源），用 [AppDropdownMenu] + [AppMenuItem.selected]。
 */
@Composable
fun SpinnerSettingRow(
    title: String,
    options: List<PickerOption>,
    selectedId: String?,
    onSelect: (PickerOption) -> Unit,
    position: SettingGroupPosition? = null,
    subtitle: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val currentOnSelect by rememberUpdatedState(onSelect)
    val entry = DropdownEntry(
        items = options.map { opt ->
            DropdownItem(
                text = opt.label,
                summary = opt.subtitle?.takeIf { it.isNotBlank() },
                selected = opt.id == selectedId,
                onClick = { currentOnSelect(opt) },
            )
        },
    )
    WindowSpinnerPreference(
        entry = entry,
        title = title,
        modifier = (if (position != null) Modifier else Modifier.standaloneRowCard())
            .semantics { role = Role.DropdownList },
        summary = subtitle?.takeIf { it.isNotBlank() },
        startAction = icon?.let { { SettingIcon(it) } },
        enabled = enabled,
    )
}

/** 「从几项里选一个」的一项（[SpinnerSettingRow]） */
data class PickerOption(
    val id: String,
    val label: String,
    /** 选项下面一行说明（「已读章节正文一直保留，直到手动清除」）；弹出列表里显示在选项名下面 */
    val subtitle: String? = null,
)

/** 行尾的当前值：同行、摘要色、一行省略（Miuix 设置页的写法） */
@Composable
private fun RowValue(text: String) {
    Text(
        text,
        style = MiuixTheme.textStyles.body2,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** 不在 [SettingGroup] 里的行自己画一张卡：外边距同列表行，底是卡片色、圆角同 Miuix 卡片 */
@Composable
private fun Modifier.standaloneRowCard(): Modifier = this
    .then(ContentListDefaults.rowChrome())
    .clip(ContentListDefaults.Shape)
    .background(settingsCardColor())

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
 * 开关项：Miuix [BasicComponent] + 行尾 [Switch]，整行可点，点行等于拨开关（Miuix `SwitchPreference` 的长相）。
 *
 * 不直接用 `SwitchPreference`：它的行和行内 Switch **各自**可点，读屏会出现「行」和「开关」两个焦点，
 * 且行焦点念不出开/关状态。这里整行是一个 [Role.Switch] 语义目标（带开/关状态），行内 [Switch] 的
 * onCheckedChange 置空（纯展示）。
 *
 * 进 [SettingGroup] 时传 [position]（底与圆角由卡片管）；null 时自己画一张卡。
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
    val chrome = if (position != null) Modifier else Modifier.standaloneRowCard()
    BasicComponent(
        modifier = chrome.semantics { toggleableState = ToggleableState(checked) },
        title = title,
        summary = subtitle?.takeIf { it.isNotBlank() },
        endActions = { Switch(checked = checked, enabled = enabled, onCheckedChange = null) },
        onClick = { onCheckedChange(!checked) },
        role = Role.Switch,
        enabled = enabled,
    )
}

/**
 * 内容列表（书架列表、搜索结果、书源列表）装进卡片：Lazy 列表里一行一个 item，相邻的行拼成一张圆角卡 ——
 * 第一行圆上角、最后一行圆下角、中间直角，左右露出页面底（与 [SettingGroup] 同一档外边距）。
 * HyperOS 的列表就是灰底上的白卡，不是通栏的行。
 *
 * 行本身要透明（[ContentListDefaults.plainColors]），底色由这里画；行间别再加 `spacedBy`，否则卡会断开。
 */
@Composable
fun Modifier.groupedCardItem(index: Int, count: Int): Modifier = this
    .padding(horizontal = Dimens.listHorizontal)
    .clip(settingGroupItemShape(groupPosition(index, count)))
    .background(settingsCardColor())

/** 第 [index] 项（共 [count] 项）在一张卡里的位置 */
fun groupPosition(index: Int, count: Int): SettingGroupPosition = when {
    count <= 1 -> SettingGroupPosition.Alone
    index == 0 -> SettingGroupPosition.First
    index == count - 1 -> SettingGroupPosition.Last
    else -> SettingGroupPosition.Middle
}
