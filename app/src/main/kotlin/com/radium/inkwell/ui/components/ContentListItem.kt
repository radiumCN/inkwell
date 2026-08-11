package com.radium.inkwell.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 内容列表的 M3 Expressive [ListItem] 统一层。
 *
 * 书架 / 搜索 / 书源 / RSS / 设置 / 目录 / 选择面板……凡是「一列可点行」都走这里，
 * 避免各页手搓 `Row + clickable + 半透明 primary` 导致形状、选中色、主题槽位对不齐。
 *
 * 颜色读 [MaterialTheme.colorScheme]：`surfaceContainerLow` / `secondaryContainer` 由
 * `AppThemes.schemeFrom` 从当前主题推导 —— 换预设或自定义强调色时层级跟着变。
 *
 * **形态**走 Expressive ListItem（圆角容器、选中槽、交互重载）；**密度**走
 * [CompactPadding] / [ComfortablePadding] 两档 —— Expressive 不等于必须用官方最松内边距。
 * 别在外层另搓 `Surface` 去压矮：那会丢掉 ListItem 的形状与选中态。
 *
 * `ListItem` 仍是 `ExperimentalMaterial3ExpressiveApi`；opt-in 收在本文件，调用方不用标。
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

    @Composable
    fun colors(
        containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
        selectedContainerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    ): ListItemColors {
        val onSelected = MaterialTheme.colorScheme.onSecondaryContainer
        return ListItemDefaults.colors(
            containerColor = containerColor,
            selectedContainerColor = selectedContainerColor,
            selectedContentColor = onSelected,
            selectedLeadingContentColor = onSelected,
            selectedTrailingContentColor = onSelected,
            selectedOverlineContentColor = onSelected,
            selectedSupportingContentColor = onSelected,
        )
    }

    /**
     * 开关行专用色：开/关**不**换容器色。
     *
     * Expressive ListItem 的 checked 重载会把 `checked=true` 当成「选中」，套上
     * `secondaryContainer` —— 于是同一页上导航行是浅灰小圆角、打开的开关行是深灰大圆角，
     * 像两套组件。开/关状态只该由行内 [Switch] 表达，卡片底色与导航行对齐。
     */
    @Composable
    fun toggleColors(): ListItemColors {
        val scheme = MaterialTheme.colorScheme
        return ListItemDefaults.colors(
            containerColor = scheme.surfaceContainerLow,
            selectedContainerColor = scheme.surfaceContainerLow,
            selectedContentColor = scheme.onSurface,
            selectedLeadingContentColor = scheme.onSurface,
            selectedTrailingContentColor = scheme.onSurface,
            selectedOverlineContentColor = scheme.onSurfaceVariant,
            selectedSupportingContentColor = scheme.onSurfaceVariant,
        )
    }

    /**
     * 开关行专用形：开/关**不**换圆角。
     *
     * Expressive 默认 `selectedShape` 比静息 `shape` 更圆（接近 extraLarge），
     * 开关打开时整行会「鼓」成另一副面孔。这里把 selected 钉成与静息同一条，
     * 按压形变（pressedShape）仍保留。
     */
    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    @Composable
    fun toggleShapes(): ListItemShapes {
        val resting = ListItemDefaults.shapes()
        return resting.copy(selectedShape = resting.shape)
    }

    /** M3 ListItem 官方内边距（约 16×10）；多媒体行用 [ComfortablePadding] */
    val ContentPadding: PaddingValues
        get() = ListItemDefaults.ContentPadding

    /**
     * 带封面 / 大图标预览等多媒体行：比 Compact 松一档，避免 48×64 书封贴边。
     * 与 [ContentPadding] 同值，调用点别再直接读 ListItemDefaults。
     */
    val ComfortablePadding: PaddingValues
        get() = ContentPadding

    /**
     * 全应用默认密度（16×8）。设置、书源、选择面板、纯文字目录都走它；
     * 触控高度靠 ListItem 自身下限兜住。
     */
    val CompactPadding: PaddingValues
        get() = PaddingValues(
            horizontal = Dimens.listHorizontal,
            vertical = Dimens.listVertical,
        )
}

/** 普通可点行（搜索结果、RSS、换源候选……） */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
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
    colors: ListItemColors = ContentListDefaults.colors(),
    content: @Composable () -> Unit,
) {
    ListItem(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        leadingContent = leadingContent,
        trailingContent = trailingContent,
        overlineContent = overlineContent,
        supportingContent = supportingContent,
        verticalAlignment = Alignment.CenterVertically,
        onLongClick = onLongClick,
        colors = colors,
        contentPadding = contentPadding,
        content = content,
    )
}

/** 单选行（OptionPicker、图标选择、当前章节） */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
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
    colors: ListItemColors = ContentListDefaults.colors(),
    content: @Composable () -> Unit,
) {
    ListItem(
        selected = selected,
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        leadingContent = leadingContent,
        trailingContent = trailingContent,
        overlineContent = overlineContent,
        supportingContent = supportingContent,
        verticalAlignment = Alignment.CenterVertically,
        onLongClick = onLongClick,
        colors = colors,
        contentPadding = contentPadding,
        content = content,
    )
}

/** 多选行（书架 / 书源批量操作） */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
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
    colors: ListItemColors = ContentListDefaults.colors(),
    shapes: ListItemShapes = ListItemDefaults.shapes(),
    content: @Composable () -> Unit,
) {
    ListItem(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        leadingContent = leadingContent,
        trailingContent = trailingContent,
        overlineContent = overlineContent,
        supportingContent = supportingContent,
        verticalAlignment = Alignment.CenterVertically,
        onLongClick = onLongClick,
        shapes = shapes,
        colors = colors,
        contentPadding = contentPadding,
        content = content,
    )
}

/**
 * 目录章名行。详情 / 预览 / 阅读菜单共用，当前章走 selected 容器色。
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
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}
