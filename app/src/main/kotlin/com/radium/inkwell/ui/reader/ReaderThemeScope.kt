package com.radium.inkwell.ui.reader

import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
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
 *
 * `surfaceContainer*` 必须逐级抬高，**不能**把 `surfaceContainerLow` 也钉成纸色：
 * [ContentListItem] 的卡片底色读的就是 Low；BottomSheet 默认容器也是 Low。
 * 两者同色时换源列表就「有 ListItem 却看不见卡片」—— 截图里开关行若隐若现、
 * 候选行整片糊进纸底，就是这么来的。Sheet 再显式用 [surface]，卡片用 Low，层次才分得开。
 *
 * 菜单与设置面板的层次靠这几档色阶，而不是阴影和蒙层（同色纸上的投影是脏灰线，压暗正文会裂出分界）：
 * - 纸面：[surface]，正文与设置面板的底；
 * - 浮层：`surfaceContainerHigh`，菜单顶栏、底部悬浮条、设置面板里的分组卡；
 * - 控件：`surfaceContainerHighest` 是分段按钮未选中的那几段，选中的那段实心填 [primary]。
 * Highest 从前与 High 同值，分组卡里的未选中段就和卡片糊成一片，单独再抬一档。
 */
@Composable
fun ReaderThemeScope(theme: ReaderTheme, content: @Composable () -> Unit) {
    val base = MaterialTheme.colorScheme
    val scheme = remember(theme, base) {
        val bg = Color(theme.background)
        val fg = Color(theme.textColor)
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
        // 报错色按**纸**的深浅取 M3 基线的浅/深两套，不跟 App 走：App 浅色、纸是夜色时，
        // App 的 error 是深红，压在深色纸上几乎看不见（自定义纸张的对比度警告就是这么糊掉的）
        val errorBase = if (lightPaper) lightColorScheme() else darkColorScheme()

        // 从 App 当前的 scheme 派生，只改与"纸张"相关的那些槽位。
        // 浮层里用得到的容器色、报错色都得在这里覆盖：漏一个，用到它的组件就在纸上露出 App 的配色
        base.copy(
            primary = accent,
            onPrimary = onAccent,
            background = bg,
            onBackground = fg,
            surface = bg,
            onSurface = fg,
            surfaceVariant = containerMid,
            // 不再是 fg × 0.7 透明度：那在夜色/墨蓝/纯黑和宣纸/牛皮上只有 3.9~4.2:1，小字发虚。
            // secondaryTextColor 仍从「退三成」出发，只在不够 4.5:1 的主题上少退一点（见 ReaderThemeContrastTest）
            onSurfaceVariant = Color(theme.secondaryTextColor),
            surfaceContainerLowest = bg,
            surfaceContainerLow = containerLow,
            surfaceContainer = containerMid,
            surfaceContainerHigh = containerHigh,
            surfaceContainerHighest = containerHighest,
            // 选中的 Chip 用正文色的淡底，而不是 M3 的紫
            secondaryContainer = fg.copy(alpha = 0.14f),
            onSecondaryContainer = fg,
            // primary / tertiary 两组容器色从前没覆盖，读到的是 App 主题的颜色 —— 纸上冒出一块淡紫/淡蓝。
            // 三组容器要分得开：primary 是强调色的淡底（比 secondary 浓一档），tertiary 退成纸面的实色台阶。
            // 0.24 是上限：强调色在深色纸上是提亮过的，叠得再浓，容器上的正文色就掉到 4.5:1 以下
            primaryContainer = accent.copy(alpha = 0.24f),
            onPrimaryContainer = fg,
            tertiaryContainer = containerHigh,
            onTertiaryContainer = fg,
            error = errorBase.error,
            onError = errorBase.onError,
            errorContainer = errorBase.errorContainer,
            onErrorContainer = errorBase.onErrorContainer,
            outline = fg.copy(alpha = 0.35f),
            outlineVariant = fg.copy(alpha = 0.18f),
            scrim = Color.Black.copy(alpha = 0.4f),
        )
    }
    // 必须也是 Expressive 版：换成普通 MaterialTheme 会把 LocalUsingExpressiveTheme 关掉，
    // 于是一进阅读页，浮层里的组件就悄悄退回非 Expressive 形态。
    // 三个参数都显式透传（不传 = null = 沿用外层，行为上等价），是为了不依赖那条隐含语义 ——
    // 尤其 motionScheme：根主题在「移除动画」下换的是 0 时长方案，这里跟丢了，
    // 阅读页就成了全 App 唯一还在动的地方。
    MaterialExpressiveTheme(
        colorScheme = scheme,
        motionScheme = MaterialTheme.motionScheme,
        shapes = MaterialTheme.shapes,
        typography = MaterialTheme.typography,
        content = content,
    )
}

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
