package com.radium.inkwell.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
 * **形态只有一种：装进卡片**。页面上、底部面板里都一样 —— 每行 [groupedCardItem]（Lazy 列表）或放进 [SettingGroup]，
 * 卡画底色和圆角，行本身透明、直角（两样都是默认值）。从前面板里是「一条一卡」：一行一张 16dp 圆角的小卡、行间留缝，
 * 和页面上的列表不是一个样子；放在 Miuix 面板里更糟 —— 面板底和卡片同色，卡看不见，只有按下、选中时冒出一块圆角色块。
 *
 * **密度**：默认 [ContentListDefaults.DefaultPadding]，与 Miuix `BasicComponent` 的内边距一样；
 * 行里有比文字高的东西（书封、48dp 的图标按钮）用 [ContentListDefaults.MediaPadding]。
 */
object ContentListDefaults {

    /** 行与行之间的缝。只剩独立成卡的设置行（[SettingRow] 不给 position）在用 */
    val ListSpacing = Dimens.gapXS

    /**
     * 独立成卡的设置行（[SettingRow] 不给 position）自带的外边距：左右内缩露出页面背景，上下各半格 [ListSpacing]。
     * 列表行不用它 —— 列表一律装进卡片
     */
    fun rowChrome(): Modifier = Modifier
        .padding(horizontal = Dimens.listHorizontal)
        .padding(vertical = ListSpacing / 2)

    /** 独立成卡的设置行的圆角：与 Miuix 卡片同档（16dp）、同一种 squircle 弯法 */
    val Corners: SquircleCorners = SquircleCorners.all(AppShapes.radiusLarge)

    /**
     * 行的配色。默认透明：行装在卡里，底由卡画（页面上是卡片色，面板里是 [AppBottomSheet] 给的那一档）。
     * 选中走 `tertiaryContainer` 一对（淡强调底 + 强调色字）—— Miuix 下拉菜单的选中项也是这一对；
     * 底不铺满整行，是卡里浮起的一块（四边缩 4dp、和卡同心的圆角，见 `selectionBlock`）。
     */
    @Composable
    @ReadOnlyComposable
    fun colors(
        containerColor: Color = Color.Transparent,
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
     * 开关行、多选行：开/关、勾选**不**换底色，状态只由行内的 Switch / Checkbox 表达（HyperOS 编辑模式就是这样，
     * 选中的行不变色，只看行尾的勾）。
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

    /**
     * 关着的条目（关掉的订阅源）：字淡到和禁用一样，但行**照样能点、能长按**。
     * 别拿 `enabled = false` 冒充 —— 那会连点击、长按一起关掉：关着的源点不开，也长按不进多选，
     * 筛出「已禁用」想一口气删掉时一行都选不上。
     */
    @Composable
    @ReadOnlyComposable
    fun dimmedColors(): ContentListColors {
        val base = colors()
        return ContentListColors(
            containerColor = base.containerColor,
            selectedContainerColor = base.selectedContainerColor,
            contentColor = base.contentColor.let { it.copy(alpha = it.alpha * DISABLED_ALPHA) },
            selectedContentColor = base.selectedContentColor,
            supportingColor = base.supportingColor.let { it.copy(alpha = it.alpha * DISABLED_ALPHA) },
            selectedSupportingColor = base.selectedSupportingColor,
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

    /**
     * 默认内边距：四周 16dp，就是 Miuix `BasicComponent` 的 `InsideMargin` —— 和同一张卡里的 Miuix 原生行
     * （设置行、开关行）一样宽松。单行的行由 [Dimens.listItemMinHeight] 撑到 56dp，两三行字的行上下各留 16dp。
     *
     * 从前默认是上下 4dp（M3 时代 `ListItem` 的密度）：单行看不出来，两三行的行（书源、净化规则、订阅文章）
     * 字贴着行的上下沿，一张卡里的行挤成一片，和旁边设置页的卡一比就是两套东西。
     */
    val DefaultPadding: PaddingValues = PaddingValues(
        horizontal = Dimens.listHorizontal,
        vertical = Dimens.gapL,
    )

    /**
     * 行里有比文字高的东西：48×64 的书封、大图标、48dp 的图标按钮（搜索历史行尾的 ×）。那样东西撑开行高，
     * 上下只留 8dp —— 照 [DefaultPadding] 再加 32dp，书架列表一屏少一两本，单行的历史记录也会胖成 80dp
     */
    val MediaPadding: PaddingValues = PaddingValues(
        horizontal = Dimens.listHorizontal,
        vertical = Dimens.gapS,
    )

    /**
     * 单行的长列表（目录）：最小高度给到触控下限 [Dimens.touchTarget]（48dp）而不是 56dp，上下各 12dp。
     * 目录动辄几千章，56dp 是给两三行字的设置行、内容行配的，放在目录里一屏少两三章；配 `minHeight = Dimens.touchTarget` 用
     */
    val CompactPadding: PaddingValues = PaddingValues(
        horizontal = Dimens.listHorizontal,
        vertical = Dimens.gapM,
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
    contentPadding: PaddingValues = ContentListDefaults.DefaultPadding,
    colors: ContentListColors = ContentListDefaults.colors(),
    corners: SquircleCorners = SquircleCorners.Square,
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
        corners = corners,
        content = content,
    )
}

/** 单选行（图标选择、当前章节）。读屏念「已选中」 */
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
    contentPadding: PaddingValues = ContentListDefaults.DefaultPadding,
    colors: ContentListColors = ContentListDefaults.colors(),
    corners: SquircleCorners = SquircleCorners.Square,
    /** 行的最小高度。只有单行的长列表（目录）配 [ContentListDefaults.CompactPadding] 给到 [Dimens.touchTarget] */
    minHeight: Dp = Dimens.listItemMinHeight,
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
        corners = corners,
        minHeight = minHeight,
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
    contentPadding: PaddingValues = ContentListDefaults.DefaultPadding,
    colors: ContentListColors = ContentListDefaults.colors(),
    corners: SquircleCorners = SquircleCorners.Square,
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
        corners = corners,
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
    contentPadding: PaddingValues = ContentListDefaults.DefaultPadding,
    colors: ContentListColors = ContentListDefaults.colors(),
    corners: SquircleCorners = SquircleCorners.Square,
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
        corners = corners,
        content = content,
    )
}

/**
 * 目录章名行。详情 / 预览页的目录、阅读菜单的目录面板共用，三处都一样装进一张卡：`modifier = Modifier.groupedCardItem(i, n)`。
 *
 * 从前每一行长得一样：读过的、在读的、没读的只有当前章那一抹底色能分，翻目录看不出「读到哪了、后面还剩多少」。
 * - **在读那章**（[selected]）：卡里浮起的一块淡强调底 + 加粗 + 行尾「在读」（占掉行尾，[trailingContent] 这一行不显示）。
 * - **读过的**（[read]）：字换成说明小字那一档（`onSurfaceVariantSummary`，卡上对比度仍 ≥ 4.5:1），读屏补一句「已读」。
 * - **48dp 高**（[ContentListDefaults.CompactPadding]）：目录是单行的长列表，内容行默认的 56dp 放在这里一屏少两三章。
 * - [highlight]：搜索章节时，章名里命中的字标强调色。
 */
@Composable
fun ChapterListItem(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    read: Boolean = false,
    highlight: String? = null,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    val dimmed = read && !selected
    val accent = MiuixTheme.colorScheme.primary
    val text = remember(title, highlight, accent) { highlightMatches(title, highlight, accent) }
    ContentListItem(
        selected = selected,
        onClick = onClick,
        modifier = if (dimmed) modifier.semantics { stateDescription = "已读" } else modifier,
        enabled = enabled,
        contentPadding = ContentListDefaults.CompactPadding,
        minHeight = Dimens.touchTarget,
        trailingContent = if (selected) {
            { Text("在读", style = MiuixTheme.textStyles.footnote1, fontWeight = FontWeight.Medium) }
        } else {
            trailingContent
        },
        content = {
            Text(
                text,
                style = MiuixTheme.textStyles.body2,
                color = if (dimmed) MiuixTheme.colorScheme.onSurfaceVariantSummary else LocalContentColor.current,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}

/** 章名里每一处 [query]（不分大小写）标成 [color]、加粗一档；[query] 为空就原样返回 */
internal fun highlightMatches(text: String, query: String?, color: Color): AnnotatedString {
    val q = query?.trim().orEmpty()
    if (q.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        var from = 0
        while (true) {
            val at = text.indexOf(q, from, ignoreCase = true)
            if (at < 0) break
            addStyle(SpanStyle(color = color, fontWeight = FontWeight.Medium), at, at + q.length)
            from = at + q.length
        }
    }
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
    corners: SquircleCorners,
    content: @Composable () -> Unit,
    minHeight: Dp = Dimens.listItemMinHeight,
) {
    val tinted = selected && colors.selectedContainerColor != colors.containerColor
    val alpha = if (enabled) 1f else DISABLED_ALPHA
    val main = (if (selected) colors.selectedContentColor else colors.contentColor).let { it.copy(alpha = it.alpha * alpha) }
    val support = (if (selected) colors.selectedSupportingColor else colors.supportingColor).let { it.copy(alpha = it.alpha * alpha) }
    Row(
        modifier
            .fillMaxWidth()
            .squircleSurface(colors.containerColor, corners)
            // 画在按压反馈底下：按下选中的那一行，照样整行压暗
            .then(if (tinted) Modifier.selectionBlock(colors.selectedContainerColor) else Modifier)
            .then(interaction)
            .heightIn(min = minHeight)
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

/**
 * 选中行的底：卡里浮起的一块，四边各缩 [SelectionInset]、圆角 [SelectionRadius]（squircle，同 Miuix 卡片的弯法）。
 *
 * 从前选中是一条通栏的直角色带，从卡的左边铺到右边：目录滚到中间时，卡顶被视口切成直线、「在读」又是一条直角带，
 * 一眼看去全是直边直角，被指出和应用的风格不搭。分段选择、页签都是「槽里浮起一块」，这里是同一个意思。
 *
 * 只缩底、不缩字：行的内边距不变，字的位置和没选中的行对齐。按压反馈不跟着缩，仍是整行压暗（Miuix 设置行就是这样）。
 * 画法是一条填充路径（[squircleShape] 的轮廓，填充本身抗锯齿），不用 squircle 修饰符：那是着色器遮罩、要开一个离屏层，
 * 为一块底色不值。
 */
private fun Modifier.selectionBlock(color: Color): Modifier = drawWithCache {
    val inset = SelectionInset.toPx()
    val block = Size(
        (size.width - inset * 2).coerceAtLeast(0f),
        (size.height - inset * 2).coerceAtLeast(0f),
    )
    val outline = squircleShape(SelectionRadius).createOutline(block, layoutDirection, this)
    onDrawBehind { translate(inset, inset) { drawOutline(outline, color) } }
}

/** 选中块离行四边的距离 */
private val SelectionInset = Dimens.gapXS

/**
 * 选中块的圆角：卡的圆角减去内缩（16 − 4 = 12，正好是 [AppShapes.radiusMedium]），和卡的角**同心** ——
 * 选中的是卡里第一行、最后一行时，块的角和卡的角平行地弯过去。从卡的圆角派生，哪天卡的角改了，这里跟着变
 */
private val SelectionRadius = AppShapes.radiusLarge - SelectionInset
