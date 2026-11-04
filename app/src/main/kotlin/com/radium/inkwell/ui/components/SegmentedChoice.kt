package com.radium.inkwell.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.radium.inkwell.ui.theme.AppShapes
import com.radium.inkwell.ui.theme.AppTheme
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 几档里选一档的分段控件：行距「紧凑 / 标准 / 宽松」、翻页方式这类选项**固定且不多**（2~5 档）的单选。
 *
 * HyperOS 的分段控件形态：一条浅灰的槽，选中那一段是槽里浮起的一块圆角。和系统默认不同的是选中块
 * **实心填 primary**、不是白块 —— 白块在白卡上只差一道阴影，阅读设置面板里一排看下来分不清当前是哪一档
 * （M3 时代就栽在 chip 淡底上，这条要求沿用）。
 *
 * Miuix 的 `TabRowWithContour` 长得像，但它按内容定宽、放不下就横滚，档位不会均分整行；这里每段 weight(1f)。
 * 选项多、会变（字体列表、方案列表、自动翻页的七档间隔）仍用 [ChipRow]：它能横滚，这里不能。
 *
 * 槽高 [Dimens.touchTarget]（48dp），选中块在槽里内缩 [Dimens.gapXS] —— 整条都是可点区，不用另留触控余量。
 * 每一段都是单选按钮语义（[Role.RadioButton]）+ 整排 selectableGroup：读屏念「行距，标准，已选中，2/3」。
 * [selectedIndex] 为 -1（当前值不在档位里，比如同步来的自定义值）时一段都不亮。
 */
@Composable
fun SegmentedChoice(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MiuixTheme.colorScheme
    val spec = AppTheme.motionScheme.defaultEffectsSpec<Color>()
    Row(
        modifier
            .fillMaxWidth()
            .height(Dimens.touchTarget)
            .squircleBackground(scheme.surfaceContainerHighest, AppShapes.radiusMedium)
            .padding(Dimens.gapXS)
            .selectableGroup(),
    ) {
        options.forEachIndexed { i, label ->
            val selected = i == selectedIndex
            val container by animateColorAsState(
                if (selected) scheme.primary else Color.Transparent,
                spec,
                label = "segmentContainer",
            )
            val content by animateColorAsState(
                if (selected) scheme.onPrimary else scheme.onSurfaceContainerHighest,
                spec,
                label = "segmentContent",
            )
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    // 内圆角 = 槽的圆角 − 内缩，两层圆角才是同心的。和槽一样走 squircle（HyperOS 的连续曲率）：
                    // 外槽是 squircle、里面这块是普通圆弧的话，两层弯法不一样，贴得这么近一眼就看得出
                    .squircleSurface(container, AppShapes.radiusSmall)
                    .selectable(
                        selected = selected,
                        onClick = { onSelect(i) },
                        role = Role.RadioButton,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MiuixTheme.textStyles.body2,
                    color = content,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // 五档挤在一行时「滚动」两个字别被截成「滚…」：左右只留 gapXS
                    modifier = Modifier.padding(horizontal = Dimens.gapXS),
                )
            }
        }
    }
}
