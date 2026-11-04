package com.radium.inkwell.ui.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.radium.inkwell.reader.api.ReaderTheme
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 让阅读页里的一切都跟着**阅读主题**走，而不是 App 主题。
 *
 * 割裂就是这么来的：正文是米色纸张，而底部弹出的设置面板、目录、换源全都是 App 主题的白卡灰底 ——
 * 两套配色硬拼在一起。与其在每个控件上挨个传颜色（漏一个就露馅，而且以后新加的控件必然会漏），
 * 不如**换掉这一片区域的 MiuixTheme**：从 ReaderTheme 派生一套 Colors，里头所有 Miuix 组件
 * （开关、滑块、按钮、输入框、页签、下拉菜单）和我们的封装自动就协调了。
 *
 * 三层底，各差一档（同色纸上的投影是脏灰线、压暗正文会裂出分界，所以层次只靠色阶）：
 * - `surface` = 纸本身（页面底）；
 * - `background` = 浮层：菜单顶栏/底栏、底部面板、弹窗（Miuix 的面板与弹窗默认就读 background）。
 *   必须与纸面差一档 —— 第一版设置面板就是纸色，面板的上沿等于不存在，正文直接撞进面板；
 * - `surfaceContainer` = 浮层里的分组卡。浅色是「灰底白卡」—— 卡就是纸色本身，像纸片放在托盘上；
 *   深色是「暗底亮卡」，比面板再亮一档。
 * 用法见 [readerOverlayColor] / [readerCardColor]。
 *
 * Miuix 的槽位含义与 M3 不同（`secondary` 是关着的开关轨道、`secondaryContainer` 是输入框底……，
 * 见 AppThemes.schemeFrom 的注释），这里逐个对上，漏一个，用到它的组件就在纸上露出 App 的配色。
 */
@Composable
fun ReaderThemeScope(theme: ReaderTheme, content: @Composable () -> Unit) {
    val base = MiuixTheme.colorScheme
    val scheme = remember(theme, base.primary) {
        val bg = Color(theme.background)
        val fg = Color(theme.textColor)
        val secondaryText = Color(theme.secondaryTextColor)
        val lightPaper = bg.luminance() > 0.5f

        // 强调色：沿用 App 的主色，但在这张纸上必须看得清 —— 它也当文字色用（文字按钮、链接），按正文的 4.5:1 要求。
        // 不够就往远离纸色的方向一点点推（浅纸压深、深纸提亮），色相不变。
        // 从前深色纸上干脆拿正文色顶替：看是看得清了，可选中态、开关、滑块、小标题全成了正文那一种灰白，
        // 浮层里没有任何东西是「强调」的 —— 阅读设置面板「层次感差」，一半就差在这。
        val accent = readableOn(base.primary, bg)
        // 实心强调色上的字直接用纸色：上面已保证两者对比 ≥ 4.5:1
        val onAccent = bg

        // 浅纸压深、深纸提亮；档位对齐 AppThemes.schemeFrom（深色抬得更狠，否则 OLED 纯黑上看不见卡片）
        fun step(amount: Float) = if (lightPaper) bg.darken(amount) else bg.lighten(amount)
        val containerLow = step(if (lightPaper) 0.04f else 0.10f)
        val containerMid = step(if (lightPaper) 0.07f else 0.14f)
        val containerHigh = step(if (lightPaper) 0.10f else 0.18f)
        val containerHighest = step(if (lightPaper) 0.16f else 0.26f)
        val overlay = if (lightPaper) containerHigh else containerLow
        val card = if (lightPaper) bg else containerHigh
        fun mix(from: Color, to: Color, t: Float) = Color(
            red = from.red + (to.red - from.red) * t,
            green = from.green + (to.green - from.green) * t,
            blue = from.blue + (to.blue - from.blue) * t,
        )
        val softAccent = mix(card, accent, 0.20f)
        // 报错色按**纸**的深浅取，不跟 App 走：App 浅色、纸是夜色时，App 的 error 是深红，
        // 压在深色纸上几乎看不见（自定义纸张的对比度警告就是这么糊掉的）
        val error = if (lightPaper) Color(0xFFDC2626) else Color(0xFFF87171)
        val onError = if (lightPaper) Color.White else Color(0xFF450A0A)
        val switchOff = mix(card, fg, 0.20f)
        val disabledAccent = mix(card, accent, 0.38f)

        base.copy(
            primary = accent,
            onPrimary = onAccent,
            primaryVariant = accent,
            onPrimaryVariant = mix(accent, bg, 0.55f),
            error = error,
            onError = onError,
            errorContainer = mix(card, error, 0.12f),
            onErrorContainer = mix(error, fg, 0.5f),
            disabledPrimary = disabledAccent,
            disabledOnPrimary = mix(disabledAccent, onAccent, 0.38f),
            disabledPrimaryButton = disabledAccent,
            disabledOnPrimaryButton = mix(disabledAccent, onAccent, 0.6f),
            disabledPrimarySlider = disabledAccent,
            // 0.24 是上限：强调色在深色纸上是提亮过的，叠得再浓，容器上的正文色就掉到 4.5:1 以下
            primaryContainer = mix(card, accent, 0.24f),
            onPrimaryContainer = fg,
            secondary = switchOff,
            onSecondary = if (lightPaper) Color.White else fg,
            secondaryVariant = containerHighest,
            onSecondaryVariant = fg,
            disabledSecondary = mix(card, switchOff, 0.5f),
            disabledOnSecondary = mix(card, fg, 0.38f),
            disabledSecondaryVariant = mix(card, containerHighest, 0.6f),
            disabledOnSecondaryVariant = mix(card, fg, 0.38f),
            secondaryContainer = containerMid,
            onSecondaryContainer = secondaryText,
            secondaryContainerVariant = containerMid,
            onSecondaryContainerVariant = secondaryText,
            tertiaryContainer = softAccent,
            onTertiaryContainer = readableOn(accent, softAccent),
            tertiaryContainerVariant = softAccent,
            background = overlay,
            onBackground = fg,
            onBackgroundVariant = secondaryText,
            surface = bg,
            onSurface = fg,
            surfaceVariant = containerMid,
            onSurfaceSecondary = mix(fg, bg, 0.15f),
            // 不再是 fg × 0.7 透明度：那在夜色/墨蓝/纯黑和宣纸/牛皮上只有 3.9~4.2:1，小字发虚。
            // secondaryTextColor 仍从「退三成」出发，只在不够 4.5:1 的主题上少退一点（见 ReaderThemeContrastTest）
            onSurfaceVariantSummary = secondaryText,
            onSurfaceVariantActions = mix(fg, bg, 0.55f),
            disabledOnSurface = mix(fg, bg, 0.6f),
            surfaceContainer = card,
            onSurfaceContainer = fg,
            onSurfaceContainerVariant = secondaryText,
            surfaceContainerHigh = containerHigh,
            onSurfaceContainerHigh = secondaryText,
            // 分段按钮未选中的那几段、进度条轨道：与卡在两种纸上都差得开
            surfaceContainerHighest = containerHighest,
            onSurfaceContainerHighest = fg,
            outline = fg.copy(alpha = 0.35f),
            dividerLine = fg.copy(alpha = 0.18f),
            windowDimming = Color.Black.copy(alpha = 0.4f),
            sliderKeyPoint = accent.copy(alpha = 0.3f),
            sliderKeyPointForeground = accent,
            sliderBackground = fg.copy(alpha = 0.12f),
        )
    }
    // colors 版的 MiuixTheme 不提供 LocalContentColor（见 InkwellTheme），这里也补上：
    // 浮层里没写颜色的 Text / Icon 跟纸上的字色走，而不是外层 App 主题的字色。
    // 动效方案（AppTheme.motionScheme）不在 MiuixTheme 里，外层的照常透传进来，「移除动画」不会在阅读页失效
    MiuixTheme(colors = scheme, textStyles = MiuixTheme.textStyles) {
        CompositionLocalProvider(LocalContentColor provides scheme.onSurface, content = content)
    }
}

/**
 * 阅读页浮层的底：菜单顶栏、底部面板、设置面板（[ReaderThemeScope] 里的 `background`）。必须与纸面差一档 ——
 * 第一版设置面板就是纸色，又去掉了蒙层，面板的上沿等于不存在：正文直接撞进面板、最后一行被拦腰截断，
 * 连面板的圆角都看不见（纸色上的纸色）。差了这一档，圆角上沿就是浮层的边。
 */
@Composable
@ReadOnlyComposable
internal fun readerOverlayColor(): Color = MiuixTheme.colorScheme.background

/**
 * 设置面板里的分组卡（[ReaderThemeScope] 里的 `surfaceContainer`）。与 App 设置页同一个思路：
 * 浅色是「灰底白卡」、深色是「暗底亮卡」。卡里未选中的分段按钮是 `surfaceContainerHighest`，在两种纸上都与卡差得开。
 */
@Composable
@ReadOnlyComposable
internal fun readerCardColor(): Color = MiuixTheme.colorScheme.surfaceContainer

/**
 * [color] 放在 [paper] 上不够 4.5:1 时，往远离纸色的一端（浅纸往黑、深纸往白）一点点推，直到够为止。
 * 推的是 sRGB 线性混合，色相基本不变：App 主色是暖褐，推完还是褐，只是深一点 / 亮一点。
 */
internal fun readableOn(color: Color, paper: Color): Color {
    val target = if (paper.luminance() > 0.5f) Color.Black else Color.White
    var t = 0f
    var c = color
    while (contrast(c, paper) < MIN_TEXT_CONTRAST && t < 1f) {
        t += 0.05f
        c = Color(
            red = color.red + (target.red - color.red) * t,
            green = color.green + (target.green - color.green) * t,
            blue = color.blue + (target.blue - color.blue) * t,
        )
    }
    return c
}

private fun contrast(a: Color, b: Color): Float {
    val hi = maxOf(a.luminance(), b.luminance()) + 0.05f
    val lo = minOf(a.luminance(), b.luminance()) + 0.05f
    return hi / lo
}

private const val MIN_TEXT_CONTRAST = 4.5f

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
