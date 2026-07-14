package com.radium.inkwell.ui.reader

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.radium.inkwell.reader.api.ReaderTheme

/**
 * 让阅读页里的一切都跟着**阅读主题**走，而不是 App 主题。
 *
 * 割裂就是这么来的：正文是米色纸张，而底部弹出的设置面板、目录、换源全都是 M3 默认的
 * 淡紫白 —— 两套配色硬拼在一起。之前只把菜单的顶栏/底栏手动刷成了纸张色，可面板里的
 * TabRow、Chip、Slider、分隔线还是紫的，因为它们读的是 MaterialTheme。
 *
 * 与其在每个控件上挨个传颜色（漏一个就露馅，而且以后新加的控件必然会漏），
 * 不如**换掉这一片区域的 MaterialTheme**：从 ReaderTheme 派生一套 colorScheme，
 * 里头所有 M3 组件自动就协调了。
 */
@Composable
fun ReaderThemeScope(theme: ReaderTheme, content: @Composable () -> Unit) {
    val base = MaterialTheme.colorScheme
    val scheme = remember(theme, base) {
        val bg = Color(theme.background)
        val fg = Color(theme.textColor)

        // 强调色：浅色纸上沿用 App 的主色（深蓝，压得住）；深色纸上它会糊掉，
        // 改用正文色本身 —— 夜间主题的正文色本就是为深色背景挑的
        val accent = if (theme.isDark) fg else base.primary
        val onAccent = if (theme.isDark) bg else base.onPrimary

        // 面板略微抬高一点，与正文分层；纸张浅就压深一丁点，纸张深就提亮一丁点
        val raised = if (bg.luminance() > 0.5f) bg.darken(0.04f) else bg.lighten(0.06f)

        // 从 App 当前的 scheme 派生，只改与"纸张"相关的那些槽位 ——
        // 其余（error 之类）保持一致，不必凭空造一套
        base.copy(
            primary = accent,
            onPrimary = onAccent,
            background = bg,
            onBackground = fg,
            surface = bg,
            onSurface = fg,
            surfaceVariant = raised,
            onSurfaceVariant = fg.copy(alpha = 0.7f),
            surfaceContainerLowest = bg,
            surfaceContainerLow = bg,
            surfaceContainer = raised,
            surfaceContainerHigh = raised,
            surfaceContainerHighest = raised,
            // 选中的 Chip 用正文色的淡底，而不是 M3 的紫
            secondaryContainer = fg.copy(alpha = 0.14f),
            onSecondaryContainer = fg,
            outline = fg.copy(alpha = 0.35f),
            outlineVariant = fg.copy(alpha = 0.18f),
            scrim = Color.Black.copy(alpha = 0.4f),
        )
    }
    MaterialTheme(
        colorScheme = scheme,
        shapes = MaterialTheme.shapes,
        typography = MaterialTheme.typography,
        content = content,
    )
}

private fun Color.darken(amount: Float): Color =
    Color(
        red = (red * (1 - amount)).coerceIn(0f, 1f),
        green = (green * (1 - amount)).coerceIn(0f, 1f),
        blue = (blue * (1 - amount)).coerceIn(0f, 1f),
        alpha = alpha,
    )

private fun Color.lighten(amount: Float): Color =
    Color(
        red = (red + (1 - red) * amount).coerceIn(0f, 1f),
        green = (green + (1 - green) * amount).coerceIn(0f, 1f),
        blue = (blue + (1 - blue) * amount).coerceIn(0f, 1f),
        alpha = alpha,
    )
