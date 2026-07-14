package com.radium.inkwell.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

enum class ThemeMode(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("日间"),
    DARK("夜间"),
}

/** 全局主题配置（持久化于 AppPrefs） */
data class ThemeConfig(
    val mode: ThemeMode = ThemeMode.SYSTEM,
    val lightPreset: String = AppThemes.LIGHT_PAPER,
    val darkPreset: String = AppThemes.DARK_WARM,
    /** 自定义主题的强调色与背景色（ARGB） */
    val customLightSeed: Long = 0xFF92400E,
    val customLightBg: Long = 0xFFFFFBEB,
    val customDarkSeed: Long = 0xFFE8A33D,
    val customDarkBg: Long = 0xFF17140F,
)

data class ThemePreset(
    val id: String,
    val label: String,
    /** 色板预览用 */
    val previewBg: Color,
    val previewAccent: Color,
    val scheme: ColorScheme,
)

object AppThemes {
    const val LIGHT_PAPER = "paper"
    const val LIGHT_WHITE = "white"
    const val LIGHT_BAMBOO = "bamboo"
    const val LIGHT_KRAFT = "kraft"
    const val LIGHT_MIST = "mist"
    const val DARK_WARM = "warm"
    const val DARK_BLACK = "black"
    const val DARK_INKBLUE = "inkblue"
    const val DARK_MOSS = "moss"
    const val DARK_PLUM = "plum"
    const val CUSTOM = "custom"

    val lightPresets: List<ThemePreset> by lazy {
        listOf(
            // 默认。品牌色：墨褐 + 纸黄
            ThemePreset(
                LIGHT_PAPER, "暖纸",
                Color(0xFFFFFBEB), Color(0xFF92400E),
                schemeFrom(Color(0xFF92400E), Color(0xFFFFFBEB), dark = false),
            ),
            ThemePreset(
                LIGHT_WHITE, "净白",
                Color(0xFFFFFFFF), Color(0xFF2B3A55),
                schemeFrom(Color(0xFF2B3A55), Color(0xFFFFFFFF), dark = false),
            ),
            ThemePreset(
                LIGHT_BAMBOO, "青竹",
                Color(0xFFF2F7EE), Color(0xFF3E6B4F),
                schemeFrom(Color(0xFF3E6B4F), Color(0xFFF2F7EE), dark = false),
            ),
            // 比暖纸更沉，像旧书的封皮
            ThemePreset(
                LIGHT_KRAFT, "牛皮",
                Color(0xFFF7EEDD), Color(0xFF7C4A21),
                schemeFrom(Color(0xFF7C4A21), Color(0xFFF7EEDD), dark = false),
            ),
            // 冷调中性灰，给嫌暖色"发黄"的人
            ThemePreset(
                LIGHT_MIST, "晨雾",
                Color(0xFFF4F6F8), Color(0xFF4A5A6A),
                schemeFrom(Color(0xFF4A5A6A), Color(0xFFF4F6F8), dark = false),
            ),
        )
    }

    val darkPresets: List<ThemePreset> by lazy {
        listOf(
            ThemePreset(
                DARK_WARM, "暖黑",
                Color(0xFF17140F), Color(0xFFE8A33D),
                schemeFrom(Color(0xFFE8A33D), Color(0xFF17140F), dark = true),
            ),
            // OLED 上黑像素不点亮，省电
            ThemePreset(
                DARK_BLACK, "纯黑",
                Color(0xFF000000), Color(0xFF9E9E9E),
                schemeFrom(Color(0xFF9E9E9E), Color(0xFF000000), dark = true),
            ),
            ThemePreset(
                DARK_INKBLUE, "墨蓝",
                Color(0xFF10151F), Color(0xFF7C9CD0),
                schemeFrom(Color(0xFF7C9CD0), Color(0xFF10151F), dark = true),
            ),
            ThemePreset(
                DARK_MOSS, "苔绿",
                Color(0xFF10170F), Color(0xFF7FB086),
                schemeFrom(Color(0xFF7FB086), Color(0xFF10170F), dark = true),
            ),
            ThemePreset(
                DARK_PLUM, "紫檀",
                Color(0xFF17111A), Color(0xFFB593C9),
                schemeFrom(Color(0xFFB593C9), Color(0xFF17111A), dark = true),
            ),
        )
    }

    /** 解析当前应生效的配色；返回 (scheme, 是否深色) */
    fun resolve(config: ThemeConfig, systemDark: Boolean): Pair<ColorScheme, Boolean> {
        val dark = when (config.mode) {
            ThemeMode.SYSTEM -> systemDark
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
        }
        val scheme = if (dark) {
            if (config.darkPreset == CUSTOM) {
                schemeFrom(Color(config.customDarkSeed), Color(config.customDarkBg), dark = true)
            } else {
                darkPresets.firstOrNull { it.id == config.darkPreset }?.scheme
                    ?: darkPresets.first().scheme
            }
        } else {
            if (config.lightPreset == CUSTOM) {
                schemeFrom(Color(config.customLightSeed), Color(config.customLightBg), dark = false)
            } else {
                lightPresets.firstOrNull { it.id == config.lightPreset }?.scheme
                    ?: lightPresets.first().scheme
            }
        }
        return scheme to dark
    }

    /**
     * 从「强调色 + 背景色」推导完整 Material3 配色。
     * 手工推导（不引 material-color-utilities）：容器色 = 强调色向背景插值，
     * 文本色按背景亮度取暖白/暖黑，保证正文对比度。
     */
    fun schemeFrom(seed: Color, background: Color, dark: Boolean): ColorScheme {
        val onBg = if (background.luminance() > 0.5f) Color(0xFF1C1917) else Color(0xFFE7E1D7)
        val surface = lerp(background, onBg, 0.02f)
        val surfaceVariant = lerp(background, onBg, if (dark) 0.08f else 0.06f)
        val outline = lerp(onBg, background, 0.45f)
        val outlineVariant = lerp(onBg, background, 0.75f)

        // surfaceContainer* 这一组从前**一个都没设**，于是全部落回 M3 基线值 —— 那是一套
        // 淡紫。而底部弹层(surfaceContainerLow)、卡片、下拉菜单、隐藏区面板读的正是这几个槽位：
        // 一个暖纸色的 App，弹出来的东西却是紫的。之前阅读页那次"割裂"是靠 ReaderThemeScope
        // 局部盖掉的，根子一直在这儿。
        //
        // 都由背景朝正文色迈一小步得来，逐级抬高：浅色纸上是逐级压深，深色纸上是逐级提亮 ——
        // lerp 到 onBg 天然满足两边。
        val containerLowest = lerp(background, onBg, if (dark) 0.00f else 0.05f)
        val containerLow = lerp(background, onBg, if (dark) 0.03f else 0.02f)
        val containerMid = lerp(background, onBg, if (dark) 0.05f else 0.04f)
        val containerHigh = lerp(background, onBg, if (dark) 0.08f else 0.07f)
        val containerHighest = lerp(background, onBg, if (dark) 0.11f else 0.10f)
        // 深色模式下抬高强调色亮度，避免按钮发闷
        val primary = if (dark) lerp(seed, Color.White, 0.25f) else seed
        // 前景取黑/白中对比更高者（中灰强调色下亮度阈值会失效）
        val onPrimary = bestForeground(primary)
        val container = lerp(primary, background, 0.80f)
        val onContainer = lerp(primary, onBg, 0.65f)
        val secondary = lerp(primary, onBg, 0.35f)
        val tertiary = rotateHue(primary, 40f)

        return if (dark) {
            darkColorScheme(
                primary = primary, onPrimary = onPrimary,
                primaryContainer = container, onPrimaryContainer = onContainer,
                secondary = secondary, onSecondary = onPrimary,
                secondaryContainer = lerp(secondary, background, 0.75f),
                onSecondaryContainer = lerp(secondary, onBg, 0.65f),
                tertiary = tertiary, onTertiary = onPrimary,
                background = background, onBackground = onBg,
                surface = surface, onSurface = onBg,
                surfaceVariant = surfaceVariant,
                onSurfaceVariant = lerp(onBg, background, 0.25f),
                surfaceContainerLowest = containerLowest,
                surfaceContainerLow = containerLow,
                surfaceContainer = containerMid,
                surfaceContainerHigh = containerHigh,
                surfaceContainerHighest = containerHighest,
                surfaceTint = primary,
                surfaceDim = lerp(background, onBg, 0.10f),
                surfaceBright = lerp(background, onBg, 0.02f),
                inverseSurface = onBg,
                inverseOnSurface = background,
                inversePrimary = lerp(primary, background, 0.55f),
                scrim = Color.Black,
                outline = outline, outlineVariant = outlineVariant,
                error = Color(0xFFF87171), onError = Color(0xFF450A0A),
            )
        } else {
            lightColorScheme(
                primary = primary, onPrimary = onPrimary,
                primaryContainer = container, onPrimaryContainer = onContainer,
                secondary = secondary, onSecondary = onPrimary,
                secondaryContainer = lerp(secondary, background, 0.75f),
                onSecondaryContainer = lerp(secondary, onBg, 0.65f),
                tertiary = tertiary, onTertiary = onPrimary,
                background = background, onBackground = onBg,
                surface = surface, onSurface = onBg,
                surfaceVariant = surfaceVariant,
                onSurfaceVariant = lerp(onBg, background, 0.25f),
                surfaceContainerLowest = containerLowest,
                surfaceContainerLow = containerLow,
                surfaceContainer = containerMid,
                surfaceContainerHigh = containerHigh,
                surfaceContainerHighest = containerHighest,
                surfaceTint = primary,
                surfaceDim = lerp(background, onBg, 0.10f),
                surfaceBright = lerp(background, onBg, 0.02f),
                inverseSurface = onBg,
                inverseOnSurface = background,
                inversePrimary = lerp(primary, background, 0.55f),
                scrim = Color.Black,
                outline = outline, outlineVariant = outlineVariant,
                error = Color(0xFFDC2626), onError = Color.White,
            )
        }
    }

    /** 黑白前景中对比度更高者 */
    private fun bestForeground(bg: Color): Color {
        val black = Color(0xFF1C1917)
        return if (contrast(black, bg) >= contrast(Color.White, bg)) black else Color.White
    }

    private fun contrast(a: Color, b: Color): Float {
        val l1 = maxOf(a.luminance(), b.luminance()) + 0.05f
        val l2 = minOf(a.luminance(), b.luminance()) + 0.05f
        return l1 / l2
    }

    /** 纯 Kotlin 色相旋转（不依赖 android.graphics，可 JVM 单测） */
    private fun rotateHue(color: Color, degrees: Float): Color {
        val r = color.red
        val g = color.green
        val b = color.blue
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val delta = max - min
        var h = when {
            delta == 0f -> 0f
            max == r -> 60f * (((g - b) / delta) % 6f)
            max == g -> 60f * ((b - r) / delta + 2f)
            else -> 60f * ((r - g) / delta + 4f)
        }
        val s = if (max == 0f) 0f else delta / max
        val v = max

        h = (h + degrees + 360f) % 360f
        val c = v * s
        val x = c * (1f - kotlin.math.abs((h / 60f) % 2f - 1f))
        val m = v - c
        val (r1, g1, b1) = when {
            h < 60f -> Triple(c, x, 0f)
            h < 120f -> Triple(x, c, 0f)
            h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c)
            h < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return Color(r1 + m, g1 + m, b1 + m, color.alpha)
    }
}
