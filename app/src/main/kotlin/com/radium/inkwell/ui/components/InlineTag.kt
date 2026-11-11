package com.radium.inkwell.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.radium.inkwell.ui.theme.AppShapes
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 跟在列表行标题后面的小标签（来源的「仅发现」、导入面板的「新增 / 更新 / 已有」）。
 *
 * 强调的是淡强调底 + 强调色字（`tertiaryContainer` 一对），与选中的 chip 同一种「标签」语汇；
 * [neutral] 的是灰底灰字，给「已有」这种不需要人管的状态 —— 一屏几十条全是蓝标签，真正要看的反倒不显眼。
 * 上下不留白，贴着 footnote2 的行高，标签和标题行一样矮。
 */
@Composable
fun InlineTag(
    text: String,
    modifier: Modifier = Modifier,
    neutral: Boolean = false,
) {
    val colors = MiuixTheme.colorScheme
    Text(
        text,
        style = MiuixTheme.textStyles.footnote2,
        fontWeight = FontWeight.Medium,
        color = if (neutral) colors.onSurfaceVariantSummary else colors.onTertiaryContainer,
        maxLines = 1,
        modifier = modifier
            .padding(start = Dimens.gapS)
            .background(if (neutral) colors.secondaryContainer else colors.tertiaryContainer, AppShapes.extraSmall)
            .padding(horizontal = Dimens.gapS),
    )
}
