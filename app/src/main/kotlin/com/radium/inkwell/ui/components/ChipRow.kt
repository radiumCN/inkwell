package com.radium.inkwell.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.squircle.squircleSurface
import com.radium.inkwell.ui.theme.AppTheme
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 一排单选 chip。
 *
 * **内部固定横向滚动**，而不是让调用方自己记得加。原来阅读菜单里的 ChipRow 是个固定宽度的
 * Row：选项一多（自动翻页有 7 档）就塞不下，Row 把剩余宽度硬分给最后一个 chip，
 * 它的文字被压成竖排单字 ——「45s」变成了三行。这种事只要有一处忘了加滚动就会再犯，
 * 所以把它焊死在组件里。
 *
 * 整排是一个 selectableGroup、每颗是单选按钮语义：读屏念「45 秒，已选中，3/7」。
 *
 * @param trailing 跟在选项后面的额外 chip（如书源页「分组」打开选择面板），仍在同一条横滚里
 * @param solidSelection 选中态改成实心强调色（与 [SegmentedChoice] 同一种）。见 [ChipPill]
 * @param fontFamilies 每颗 chip 的字用哪款字体（与 [options] 一一对应，null = 默认）。阅读设置的字体条：
 *   字体名用那款字本身写，换之前就看得到长相
 * @param longClickActions 每颗 chip 的长按动作（与 [options] 一一对应，null = 不能长按）。导入的字体长按删除；
 *   [longClickLabel] 是读屏念的那个动作名
 * @param bringSelectedIntoView 选中项换了就把它横滚进视野。给「选中项不是手点出来的」条用：协议页的节 chip
 *   跟着正文滚动亮，亮到条外面去了得自己滚过来。默认关 —— 手点的那颗本来就在眼前，
 *   而 bringIntoView 会一路往外层滚动容器传，条放在可滚动的页面里时会把整页也拽一下
 */
@Composable
fun ChipRow(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /** 首尾边距，随内容滚动 —— 让发现页/订阅页的分类条也能收敛到这个组件 */
    contentPadding: PaddingValues = PaddingValues(0.dp),
    solidSelection: Boolean = false,
    fontFamilies: List<FontFamily?>? = null,
    longClickActions: List<(() -> Unit)?>? = null,
    longClickLabel: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    bringSelectedIntoView: Boolean = false,
) {
    val selectedRequester = remember { BringIntoViewRequester() }
    if (bringSelectedIntoView) {
        LaunchedEffect(selectedIndex) {
            // 等这一帧排好版：新选中的那颗刚挂上 requester，还没有位置
            withFrameNanos { }
            selectedRequester.bringIntoView()
        }
    }
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(contentPadding)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(Dimens.chipSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { i, label ->
            val interactionSource = remember { MutableInteractionSource() }
            val selected = i == selectedIndex
            val onLongClick = longClickActions?.getOrNull(i)
            val bring = if (bringSelectedIntoView && selected) Modifier.bringIntoViewRequester(selectedRequester) else Modifier
            ChipPill(
                selected = selected,
                label = label,
                solid = solidSelection,
                interactionSource = interactionSource,
                fontFamily = fontFamilies?.getOrNull(i),
                modifier = bring.then(if (onLongClick == null) {
                    Modifier.selectable(
                        selected = selected,
                        onClick = { onSelect(i) },
                        role = Role.RadioButton,
                        interactionSource = interactionSource,
                        indication = null,
                    )
                } else {
                    // 能长按的那几颗：combinedClickable 没有「已选中」语义，单选的那一半自己补上
                    Modifier
                        .combinedClickable(
                            interactionSource = interactionSource,
                            indication = null,
                            role = Role.RadioButton,
                            onLongClickLabel = longClickLabel,
                            onLongClick = onLongClick,
                            onClick = { onSelect(i) },
                        )
                        .semantics { this.selected = selected }
                }),
            )
        }
        trailing?.invoke(this)
    }
}

/**
 * 单个独立 chip（开关语义）。[ChipRow] 装不下的场合用它：阅读菜单的「系统亮度」、书源页的「分组」——
 * 它们是横滚条尾巴上的独立开关，不属于那组单选项。
 */
@Composable
fun AppFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    /** 同 [ChipRow] 的那个参数 */
    solidSelection: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    ChipPill(
        selected = selected,
        label = label,
        solid = solidSelection,
        interactionSource = interactionSource,
        modifier = modifier.toggleable(
            value = selected,
            onValueChange = { onClick() },
            role = Role.Checkbox,
            interactionSource = interactionSource,
            indication = null,
        ),
    )
}

/**
 * chip 的样子：HyperOS 筛选条那种胶囊。选中是淡强调底 + 强调色字（`tertiaryContainer` 一对，
 * Miuix 下拉菜单的选中项也是这一对），未选中是默认按钮那档浅灰。
 *
 * [solid] = 选中实心填 primary、字用 onPrimary，和 [SegmentedChoice] 的选中段一模一样。只给「和分段按钮挤在
 * 同一张面板里」的地方用（阅读设置面板）：那里一边是实心的分段、一边是淡底的 chip，同一个「选中」两种长相，
 * 淡底在纸色上还是一块发灰的混色。页面上的筛选条（书架分组、发现分类）照旧淡底 —— 那里只有 chip，没有对照。
 *
 * 可点区（[modifier] 上的 selectable/toggleable）挂在 48dp 高的外框上，按压反馈却画在 32dp 的胶囊里 ——
 * 两者共用一个 [interactionSource]：外框只管「点到了没有」、不画反馈（`indication = null`），
 * 胶囊按同一个交互源画 Miuix 的压暗。否则压暗会是一块 48dp 高的方块，比胶囊大一圈。
 */
@Composable
private fun ChipPill(
    selected: Boolean,
    label: String,
    solid: Boolean,
    interactionSource: MutableInteractionSource,
    modifier: Modifier,
    fontFamily: FontFamily? = null,
) {
    val scheme = MiuixTheme.colorScheme
    val spec = AppTheme.motionScheme.defaultEffectsSpec<Color>()
    val container by animateColorAsState(
        when {
            !selected -> scheme.secondaryVariant
            solid -> scheme.primary
            else -> scheme.tertiaryContainer
        },
        spec,
        label = "chipContainer",
    )
    val content by animateColorAsState(
        when {
            !selected -> scheme.onSecondaryVariant
            solid -> scheme.onPrimary
            else -> scheme.onTertiaryContainer
        },
        spec,
        label = "chipContent",
    )
    Box(
        modifier.defaultMinSize(minHeight = Dimens.touchTarget),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .height(Dimens.chipHeight)
                // 两头全圆的胶囊，用 squircle 画（半径 = 半高）：Miuix 的胶囊控件也是这么画的，和按钮、卡片一个弯法
                .squircleSurface(container, Dimens.chipHeight / 2)
                .indication(interactionSource, LocalIndication.current)
                .padding(horizontal = Dimens.gapM),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                style = MiuixTheme.textStyles.body2,
                color = content,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                fontFamily = fontFamily,
                maxLines = 1,
            )
        }
    }
}

/**
 * chip 条末尾的「添加」：和 chip 一样高的一颗圆，**虚线**描边、中间一个 ＋（阅读设置里的导入字体）。
 *
 * 动作不和选项长成一个样：从前「导入字体」是条末尾一颗普通 chip，和「默认」「衬线」一模一样，看着像一款叫「导入字体」的字。
 * 虚线框是「这里还空着、可以往里加」的意思，一眼和实心的选项分开。可点区照样保底 48dp（同 [ChipPill] 的外框），
 * 按压反馈画在里面那颗圆上；只有图标，读屏名字由 [contentDescription] 给。
 */
@Composable
fun AddChip(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val color = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val radius = Dimens.chipHeight / 2
    val shape = remember(radius) { squircleShape(radius) }
    Box(
        modifier
            .defaultMinSize(minWidth = Dimens.touchTarget, minHeight = Dimens.touchTarget)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(Dimens.chipHeight)
                .squircleClip(radius)
                .drawBehind {
                    val stroke = Dimens.strokeThick.toPx()
                    drawOutline(
                        shape.createOutline(size, layoutDirection, this),
                        color = color,
                        // 描边一半落在裁切外面：线宽给到粗档，看着是一道细虚线
                        style = Stroke(
                            width = stroke,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(stroke * 2f, stroke * 1.5f)),
                        ),
                    )
                }
                .indication(interactionSource, LocalIndication.current),
            contentAlignment = Alignment.Center,
        ) {
            Icon(MiuixIcons.Add, contentDescription = null, modifier = Modifier.size(Dimens.iconSm), tint = color)
        }
    }
}

