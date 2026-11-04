package com.radium.inkwell.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.radium.inkwell.ui.theme.AppShapes
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 内容列表行的统一层：书架 / 搜索 / 书源 / RSS / 设置 / 目录 / 选择面板……凡是「一列可点行」都走这里，
 * 避免各页手搓 `Row + clickable` 导致形状、选中色、密度对不齐。
 *
 * **为什么不直接用 Miuix `BasicComponent`**：它没有长按（书架、书源管理的多选靠长按进入），
 * 没有选中 / 多选两种语义，标题与副标题也只收字符串。这里照它的排法自己搭 ——
 * 左（封面/图标）中（上标 · 标题 · 副标题）右（尾部）三栏、最小高度 56dp、标题 17sp Medium、副标题 14sp 摘要色，
 * 和同一张卡里的 Miuix 原生行长得一样。按压反馈是 Miuix 主题的 `LocalIndication`（压暗），不用自己画。
 *
 * **文字样式要显式给**：M3 `ListItem` 会替各个槽位里的 Text 设好默认字号，Miuix 没有公开的「给子树设字号」入口，
 * 槽位里裸写的 `Text` 会落到 Miuix 默认的 17sp。所以槽位只替你设好**颜色**（`LocalContentColor`：
 * 标题 onSurface、上标/副标题摘要色、选中时强调色），字号用 [ContentListDefaults.titleStyle] /
 * [ContentListDefaults.supportingStyle] / [ContentListDefaults.overlineStyle]。
 *
 * **形态**：独立成卡（行与行之间留 [ContentListDefaults.ListSpacing] 的缝、露出页面底）或装进 [SettingGroup]
 * （透明底，圆角只画在组卡上）。**密度**走 [ContentListDefaults.CompactPadding] / [ContentListDefaults.ComfortablePadding] 两档。
 */
object ContentListDefaults {

    /** LazyColumn：左右内缩，让圆角容器露出页面背景 */
    fun listContentPadding(
        bottom: Dp = 0.dp,
        top: Dp = 0.dp,
    ): PaddingValues = PaddingValues(
        start = Dimens.listHorizontal,
        end = Dimens.listHorizontal,
        top = top,
        bottom = bottom,
    )

    /** 行与行之间的缝 —— 没有缝圆角几乎看不见 */
    val ListSpacing = Dimens.gapXS

    /**
     * 非 Lazy 列表（设置页 Column、底部面板里的 SettingRow）行自带的外边距。
     * 左右内缩露出页面背景，上下各半格 [ListSpacing]，圆角容器才分得清条与条。
     */
    fun rowChrome(): Modifier = Modifier
        .padding(horizontal = Dimens.listHorizontal)
        .padding(vertical = ListSpacing / 2)

    /** 独立成卡的行的圆角：与 Miuix 卡片同档（16dp） */
    val Shape: Shape = AppShapes.large

    /**
     * 行的配色。默认底是卡片色 `surfaceContainer`（浅色白卡、深色亮一档），坐在页面底 `surface` 上。
     * 选中走 `tertiaryContainer` 一对（淡强调底 + 强调色字）—— Miuix 下拉菜单的选中项也是这一对。
     */
    @Composable
    @ReadOnlyComposable
    fun colors(
        containerColor: Color = MiuixTheme.colorScheme.surfaceContainer,
        selectedContainerColor: Color = MiuixTheme.colorScheme.tertiaryContainer,
    ): ContentListColors {
        val scheme = MiuixTheme.colorScheme
        return ContentListColors(
            containerColor = containerColor,
            selectedContainerColor = selectedContainerColor,
            contentColor = scheme.onSurfaceContainer,
            selectedContentColor = scheme.onTertiaryContainer,
            supportingColor = scheme.onSurfaceVariantSummary,
            selectedSupportingColor = scheme.onTertiaryContainer,
        )
    }

    /**
     * 开关行 / 组内行：开/关、选中**不**换底色，开关状态只由行内 Switch 表达。
     * [containerColor] 默认透明（装在 [SettingGroup] 里，让组卡露出来）；独立成卡时传卡片色。
     */
    @Composable
    @ReadOnlyComposable
    fun plainColors(containerColor: Color = Color.Transparent): ContentListColors {
        val scheme = MiuixTheme.colorScheme
        return ContentListColors(
            containerColor = containerColor,
            selectedContainerColor = containerColor,
            contentColor = scheme.onSurfaceContainer,
            selectedContentColor = scheme.onSurfaceContainer,
            supportingColor = scheme.onSurfaceVariantSummary,
            selectedSupportingColor = scheme.onSurfaceVariantSummary,
        )
    }

    /** 标题字号：照 Miuix `BasicComponent` 的标题（headline1 = 17sp，Medium） */
    val titleStyle: TextStyle
        @Composable @ReadOnlyComposable
        get() = MiuixTheme.textStyles.headline1.copy(fontWeight = FontWeight.Medium)

    /** 副标题字号：照 Miuix `BasicComponent` 的 summary（body2 = 14sp） */
    val supportingStyle: TextStyle
        @Composable @ReadOnlyComposable
        get() = MiuixTheme.textStyles.body2

    /** 标题上方的小字（书籍行的作者、来源）：footnote1 = 13sp */
    val overlineStyle: TextStyle
        @Composable @ReadOnlyComposable
        get() = MiuixTheme.textStyles.footnote1

    /** 带封面 / 大图标预览等多媒体行：比 Compact 松一档，避免 48×64 书封贴边 */
    val ComfortablePadding: PaddingValues = PaddingValues(
        horizontal = Dimens.listHorizontal,
        vertical = Dimens.gapS,
    )

    /**
     * 全应用默认密度（16×4）。设置、书源、选择面板、纯文字目录都走它；
     * 触控高度靠 [Dimens.listItemMinHeight] 兜住。
     */
    val CompactPadding: PaddingValues = PaddingValues(
        horizontal = Dimens.listHorizontal,
        vertical = Dimens.listVertical,
    )
}

/** [ContentListItem] 的配色。用 [ContentListDefaults.colors] / [ContentListDefaults.plainColors] 建 */
@Immutable
class ContentListColors(
    val containerColor: Color,
    val selectedContainerColor: Color,
    val contentColor: Color,
    val selectedContentColor: Color,
    val supportingColor: Color,
    val selectedSupportingColor: Color,
)

/** 普通可点行（搜索结果、RSS、换源候选……） */
@Composable
fun ContentListItem(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    overlineContent: (@Composable () -> Unit)? = null,
    supportingContent: (@Composable () -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = ContentListDefaults.CompactPadding,
    colors: ContentListColors = ContentListDefaults.colors(),
    shape: Shape = ContentListDefaults.Shape,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit,
) {
    ListRow(
        interaction = Modifier.rowClick(
            enabled = enabled,
            role = Role.Button,
            onClick = onClick,
            onLongClick = onLongClick,
            interactionSource = interactionSource,
        ),
        modifier = modifier,
        selected = false,
        enabled = enabled,
        leadingContent = leadingContent,
        trailingContent = trailingContent,
        overlineContent = overlineContent,
        supportingContent = supportingContent,
        contentPadding = contentPadding,
        colors = colors,
        shape = shape,
        content = content,
    )
}

/** 单选行（OptionPicker、图标选择、当前章节）。读屏念「已选中」 */
@Composable
fun ContentListItem(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    overlineContent: (@Composable () -> Unit)? = null,
    supportingContent: (@Composable () -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = ContentListDefaults.CompactPadding,
    colors: ContentListColors = ContentListDefaults.colors(),
    shape: Shape = ContentListDefaults.Shape,
    content: @Composable () -> Unit,
) {
    ListRow(
        interaction = Modifier
            .semantics { this.selected = selected }
            .rowClick(
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick,
                onLongClick = onLongClick,
                interactionSource = null,
            ),
        modifier = modifier,
        selected = selected,
        enabled = enabled,
        leadingContent = leadingContent,
        trailingContent = trailingContent,
        overlineContent = overlineContent,
        supportingContent = supportingContent,
        contentPadding = contentPadding,
        colors = colors,
        shape = shape,
        content = content,
    )
}

/**
 * 多选 / 开关行（书架、书源批量操作；[SwitchRow]）。整行一个语义目标，读屏念「已勾选 / 已开启」。
 * 行内若放了 Checkbox / Switch，它们的回调必须传 null（纯展示），否则读屏出现「行」和「控件」两个焦点。
 *
 * @param role 默认复选框；开关行传 [Role.Switch]
 */
@Composable
fun ContentListItem(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    overlineContent: (@Composable () -> Unit)? = null,
    supportingContent: (@Composable () -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = ContentListDefaults.CompactPadding,
    colors: ContentListColors = ContentListDefaults.colors(),
    shape: Shape = ContentListDefaults.Shape,
    interactionSource: MutableInteractionSource? = null,
    role: Role = Role.Checkbox,
    content: @Composable () -> Unit,
) {
    ListRow(
        interaction = Modifier
            .semantics { toggleableState = ToggleableState(checked) }
            .rowClick(
                enabled = enabled,
                role = role,
                onClick = { onCheckedChange(!checked) },
                onLongClick = onLongClick,
                interactionSource = interactionSource,
            ),
        modifier = modifier,
        selected = checked,
        enabled = enabled,
        leadingContent = leadingContent,
        trailingContent = trailingContent,
        overlineContent = overlineContent,
        supportingContent = supportingContent,
        contentPadding = contentPadding,
        colors = colors,
        shape = shape,
        content = content,
    )
}

/**
 * 不可点的展示行（版本号、许可证、统计读数）。长相与可点行一致，只是没有按压反馈、读屏不当按钮念。
 * 别拿可点重载传 `enabled = false` 冒充：那会把整行文字洗成禁用灰。
 */
@Composable
fun ContentListItem(
    modifier: Modifier = Modifier,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    overlineContent: (@Composable () -> Unit)? = null,
    supportingContent: (@Composable () -> Unit)? = null,
    contentPadding: PaddingValues = ContentListDefaults.CompactPadding,
    colors: ContentListColors = ContentListDefaults.colors(),
    shape: Shape = ContentListDefaults.Shape,
    content: @Composable () -> Unit,
) {
    ListRow(
        // 合并成一个读屏节点：标题、副标题、右侧读数一口气念完，而不是三段
        interaction = Modifier.semantics(mergeDescendants = true) {},
        modifier = modifier,
        selected = false,
        enabled = true,
        leadingContent = leadingContent,
        trailingContent = trailingContent,
        overlineContent = overlineContent,
        supportingContent = supportingContent,
        contentPadding = contentPadding,
        colors = colors,
        shape = shape,
        content = content,
    )
}

/**
 * 目录章名行。详情 / 预览 / 阅读菜单共用，当前章走选中色。
 *
 * @param inset 为 true 时自带左右内缩与行距（详情页 LazyColumn 里夹着全宽 Header 时用）
 */
@Composable
fun ChapterListItem(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    inset: Boolean = true,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    ContentListItem(
        selected = selected,
        onClick = onClick,
        modifier = if (inset) modifier.then(ContentListDefaults.rowChrome()) else modifier,
        enabled = enabled,
        trailingContent = trailingContent,
        content = {
            Text(
                title,
                style = MiuixTheme.textStyles.body2,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}

/**
 * 点击 + 可选长按。长按一律走 [combinedClickable]（Miuix 的 BasicComponent 没有长按，这是自己搭行的主因）。
 * 交互源由调用方给时（设置行要读按压态）沿用，否则就地建一个。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Modifier.rowClick(
    enabled: Boolean,
    role: Role,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    interactionSource: MutableInteractionSource?,
): Modifier {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    return combinedClickable(
        interactionSource = source,
        indication = LocalIndication.current,
        enabled = enabled,
        role = role,
        onLongClick = onLongClick,
        onClick = onClick,
    )
}

@Composable
private fun ListRow(
    interaction: Modifier,
    modifier: Modifier,
    selected: Boolean,
    enabled: Boolean,
    leadingContent: (@Composable () -> Unit)?,
    trailingContent: (@Composable () -> Unit)?,
    overlineContent: (@Composable () -> Unit)?,
    supportingContent: (@Composable () -> Unit)?,
    contentPadding: PaddingValues,
    colors: ContentListColors,
    shape: Shape,
    content: @Composable () -> Unit,
) {
    val container = if (selected) colors.selectedContainerColor else colors.containerColor
    val alpha = if (enabled) 1f else DISABLED_ALPHA
    val main = (if (selected) colors.selectedContentColor else colors.contentColor).let { it.copy(alpha = it.alpha * alpha) }
    val support = (if (selected) colors.selectedSupportingColor else colors.supportingColor).let { it.copy(alpha = it.alpha * alpha) }
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(container)
            .then(interaction)
            .heightIn(min = Dimens.listItemMinHeight)
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingContent != null) {
            Box(Modifier.padding(end = Dimens.gapL), contentAlignment = Alignment.Center) {
                CompositionLocalProvider(LocalContentColor provides main, content = leadingContent)
            }
        }
        Column(Modifier.weight(1f)) {
            if (overlineContent != null) {
                CompositionLocalProvider(LocalContentColor provides support, content = overlineContent)
            }
            CompositionLocalProvider(LocalContentColor provides main, content = content)
            if (supportingContent != null) {
                CompositionLocalProvider(LocalContentColor provides support, content = supportingContent)
            }
        }
        if (trailingContent != null) {
            Box(Modifier.padding(start = Dimens.gapL), contentAlignment = Alignment.CenterEnd) {
                CompositionLocalProvider(LocalContentColor provides support, content = trailingContent)
            }
        }
    }
}

private const val DISABLED_ALPHA = 0.38f
