package com.radium.inkwell.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
 */
@Composable
fun ChipRow(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /** 首尾边距，随内容滚动 —— 让发现页/订阅页的分类条也能收敛到这个组件 */
    contentPadding: PaddingValues = PaddingValues(0.dp),
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
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
            ChipPill(
                selected = i == selectedIndex,
                label = label,
                interactionSource = interactionSource,
                modifier = Modifier.selectable(
                    selected = i == selectedIndex,
                    onClick = { onSelect(i) },
                    role = Role.RadioButton,
                    interactionSource = interactionSource,
                    indication = null,
                ),
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
) {
    val interactionSource = remember { MutableInteractionSource() }
    ChipPill(
        selected = selected,
        label = label,
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
 * 可点区（[modifier] 上的 selectable/toggleable）挂在 48dp 高的外框上，按压反馈却画在 32dp 的胶囊里 ——
 * 两者共用一个 [interactionSource]：外框只管「点到了没有」、不画反馈（`indication = null`），
 * 胶囊按同一个交互源画 Miuix 的压暗。否则压暗会是一块 48dp 高的方块，比胶囊大一圈。
 */
@Composable
private fun ChipPill(
    selected: Boolean,
    label: String,
    interactionSource: MutableInteractionSource,
    modifier: Modifier,
) {
    val scheme = MiuixTheme.colorScheme
    val spec = AppTheme.motionScheme.defaultEffectsSpec<Color>()
    val container by animateColorAsState(
        if (selected) scheme.tertiaryContainer else scheme.secondaryVariant,
        spec,
        label = "chipContainer",
    )
    val content by animateColorAsState(
        if (selected) scheme.onTertiaryContainer else scheme.onSecondaryVariant,
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
                .clip(PillShape)
                .background(container)
                .indication(interactionSource, LocalIndication.current)
                .padding(horizontal = Dimens.gapM),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                style = MiuixTheme.textStyles.body2,
                color = content,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
            )
        }
    }
}

private val PillShape = RoundedCornerShape(percent = 50)
