package com.radium.inkwell.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import top.yukonga.miuix.kmp.theme.Colors
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

enum class ThemeMode(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("日间"),
    DARK("夜间"),
}

/** 全局主题配置（持久化于 AppPrefs） */
data class ThemeConfig(
    val mode: ThemeMode = ThemeMode.SYSTEM,
    val lightPreset: String = AppThemes.HYPEROS,
    val darkPreset: String = AppThemes.HYPEROS,
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
    val scheme: Colors,
)

object AppThemes {
    /**
     * HyperOS 原生配色（默认）：Miuix 自带的 `lightColorScheme()` / `darkColorScheme()` 原样用 ——
     * 浅色是 #F7F7F7 灰底白卡、#3482FF 蓝，深色是纯黑底 #242424 卡。日/夜两个槽共用这个 id（同 [DYNAMIC]）。
     * 不经 [schemeFrom]：那套推导是给「纸色 + 强调色」的品牌预设用的，拿来复刻系统配色只会差一点点、处处不对。
     */
    const val HYPEROS = "hyperos"
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
    /**
     * 跟随壁纸（Monet 动态取色）。日/夜两个槽都能选它，配色由系统按壁纸给 —— 不经 [schemeFrom]，
     * 由 Miuix 的 `ThemeController`（Monet 模式）从系统调色板生成。它要 Context 与 Composable 环境，
     * 所以由调用方经 [resolve] 的参数传进来，这个对象仍是纯函数、能 JVM 单测。
     */
    const val DYNAMIC = "dynamic"

    val lightPresets: List<ThemePreset> by lazy {
        listOf(
            ThemePreset(
                HYPEROS, "HyperOS",
                // 色板画的是卡片色（同其他浅色预设：预览底 = 卡片），HyperOS 浅色卡是纯白
                Color(0xFFFFFFFF), Color(0xFF3482FF),
                hyperOsScheme(dark = false),
            ),
            // 品牌色：墨褐 + 纸黄（HyperOS 预设之前的默认）
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
                HYPEROS, "HyperOS",
                Color(0xFF000000), Color(0xFF277AF7),
                hyperOsScheme(dark = true),
            ),
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

    /** 当前该用深色还是浅色（主题模式 × 系统深浅） */
    fun isDark(mode: ThemeMode, systemDark: Boolean): Boolean = when (mode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    /**
     * 解析当前应生效的配色；返回 (scheme, 是否深色)。
     *
     * @param dynamicScheme 按日夜给出系统壁纸配色。null（单测、拿不到 Context 的地方）时「跟随壁纸」
     *   回落到第一个预设 —— 与读到未知预设 id（更新版本备份里的新预设）同一种退路
     */
    fun resolve(
        config: ThemeConfig,
        systemDark: Boolean,
        dynamicScheme: ((dark: Boolean) -> Colors)? = null,
    ): Pair<Colors, Boolean> {
        val dark = isDark(config.mode, systemDark)
        val preset = if (dark) config.darkPreset else config.lightPreset
        if (preset == DYNAMIC && dynamicScheme != null) return dynamicScheme(dark) to dark
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
     * 从「强调色 + 背景色」推导完整的 Miuix 配色（[Colors] 的五十来个槽位一个不漏）。
     * 手工推导（不走 Monet）：中性色是「纸色 → 字色」之间的台阶，强调色只染 primary 一族，
     * 文本色按背景亮度取暖黑/暖白，保证正文对比度。
     *
     * **分层照 HyperOS 来，不照 M3**：Miuix 的页面底是 `surface`、卡片是 `surfaceContainer`、
     * 弹窗与底部面板是 `background`。浅色是「灰底白卡」—— 卡就是纸色本身，页面比纸压深一档；
     * 深色是「黑底亮卡」—— 页面就是纸色，卡片往亮里抬。所以**预设的背景色**在浅色落在
     * `surfaceContainer`、在深色落在 `surface`，不是哪一侧都叫 background。
     * 从前 M3 时代设置页就是这么配的（settingsPageColor / settingsCardColor），现在全应用都是。
     *
     * 槽位的含义也和 M3 不同，别照 M3 的直觉读（见 Miuix `Colors` 的 KDoc）：
     * - `secondary` 是**关着的开关轨道**、`secondaryVariant` 是**默认按钮底**、
     *   `secondaryContainer` 是**输入框底** —— 都是中性灰，不是 M3 那个「次强调色」；
     * - `onBackgroundVariant` 是分组小标题（SmallTitle）的字色；
     * - `tertiaryContainer` 是下拉菜单选中项的淡强调底；
     * - `primaryContainer` / `onPrimaryContainer` Miuix 组件不读，这里保留 M3 的含义（淡强调底 + 上面的字），
     *   页面里「强调色小标签」之类照旧用它。
     *
     * 台阶一律用 [mixSrgb] 而不是 [lerp]：Compose 的 `lerp` 走 Oklab，近黑时同样 fraction 几乎抬不动 sRGB
     * （纯黑 + 10% Oklab ≈ #030202），深色卡片会整片糊进页面 —— 日间看得到卡、夜间「消失」就是这个不对称。
     * 文字色仍用 `lerp`：那几个比例是在 Oklab 里调出来、对比度测过的（AppThemesTest）。
     */
    fun schemeFrom(seed: Color, background: Color, dark: Boolean): Colors {
        val onBg = if (background.luminance() > 0.5f) Color(0xFF1C1917) else Color(0xFFE7E1D7)
        /** 中性台阶：从纸色往字色走 t */
        fun step(t: Float) = mixSrgb(background, onBg, t)

        // ---- 三层底：页面 / 卡片 / 弹层 ----
        val page = if (dark) background else step(0.04f)
        val card = if (dark) step(0.10f) else background
        /** 卡片上的台阶（输入框、按钮、开关轨道都长在卡上）：深色卡已经抬过一档，从卡出发再走 */
        fun onCard(t: Float) = mixSrgb(card, onBg, t)

        // ---- 文字 ----
        // 摘要 / 说明小字。比例沿用 M3 时代的 onSurfaceVariant（对比度 ≥ 4.5:1，见测试）
        val summary = lerp(onBg, background, 0.25f)
        val textSecondary = lerp(onBg, background, 0.15f)
        // 箭头、行尾图标这类「动作提示」：比摘要再淡一档，但仍看得出
        val actions = lerp(onBg, background, 0.55f)
        // 占位文字（输入框 placeholder、搜索框提示）
        val placeholder = lerp(onBg, background, 0.42f)
        val disabledText = lerp(onBg, background, 0.6f)

        // ---- 强调色 ----
        // 深色模式下抬高强调色亮度，避免按钮发闷
        val primary = if (dark) lerp(seed, Color.White, 0.25f) else seed
        // 前景取黑/白中对比更高者（中灰强调色下亮度阈值会失效）
        val onPrimary = bestForeground(primary)
        val softAccent = mixSrgb(card, primary, if (dark) 0.22f else 0.12f)

        // ---- 中性控件 ----
        val switchTrackOff = if (dark) onCard(0.22f) else onCard(0.10f)
        val buttonBg = if (dark) onCard(0.14f) else onCard(0.06f)
        val fieldBg = if (dark) onCard(0.14f) else onCard(0.06f)

        val error = if (dark) Color(0xFFF87171) else Color(0xFFDC2626)
        val onError = if (dark) Color(0xFF450A0A) else Color.White

        val disabledPrimary = mixSrgb(page, primary, 0.38f)
        val disabledPrimaryButton = mixSrgb(card, primary, 0.38f)
        val disabledSecondary = mixSrgb(card, switchTrackOff, 0.5f)
        val disabledSecondaryVariant = mixSrgb(card, buttonBg, 0.6f)

        return Colors(
            primary = primary,
            onPrimary = onPrimary,
            primaryVariant = primary,
            onPrimaryVariant = lerp(primary, background, 0.55f),
            error = error,
            onError = onError,
            errorContainer = mixSrgb(card, error, 0.12f),
            onErrorContainer = lerp(error, onBg, 0.5f),
            disabledPrimary = disabledPrimary,
            disabledOnPrimary = mixSrgb(disabledPrimary, onPrimary, 0.38f),
            disabledPrimaryButton = disabledPrimaryButton,
            disabledOnPrimaryButton = mixSrgb(disabledPrimaryButton, onPrimary, 0.6f),
            disabledPrimarySlider = mixSrgb(card, primary, 0.38f),
            primaryContainer = lerp(primary, background, 0.80f),
            onPrimaryContainer = lerp(primary, onBg, 0.65f),
            secondary = switchTrackOff,
            onSecondary = if (dark) onBg else Color.White,
            secondaryVariant = buttonBg,
            onSecondaryVariant = lerp(onBg, background, 0.08f),
            disabledSecondary = disabledSecondary,
            disabledOnSecondary = mixSrgb(disabledSecondary, onBg, 0.38f),
            disabledSecondaryVariant = disabledSecondaryVariant,
            disabledOnSecondaryVariant = mixSrgb(disabledSecondaryVariant, onBg, 0.38f),
            secondaryContainer = fieldBg,
            onSecondaryContainer = placeholder,
            secondaryContainerVariant = if (dark) onCard(0.18f) else onCard(0.06f),
            onSecondaryContainerVariant = placeholder,
            tertiaryContainer = softAccent,
            onTertiaryContainer = readableOn(primary, softAccent),
            tertiaryContainerVariant = softAccent,
            background = card,
            onBackground = onBg,
            onBackgroundVariant = summary,
            surface = page,
            onSurface = onBg,
            surfaceVariant = card,
            onSurfaceSecondary = textSecondary,
            onSurfaceVariantSummary = summary,
            onSurfaceVariantActions = actions,
            disabledOnSurface = disabledText,
            surfaceContainer = card,
            onSurfaceContainer = onBg,
            onSurfaceContainerVariant = summary,
            // 页面上的搜索框底（Miuix SearchBar 读它）：比页面深/亮一档
            surfaceContainerHigh = if (dark) step(0.14f) else step(0.10f),
            onSurfaceContainerHigh = placeholder,
            // 卡上最深的一档：分段按钮未选中段、阅读进度条的轨道
            surfaceContainerHighest = if (dark) onCard(0.12f) else onCard(0.13f),
            onSurfaceContainerHighest = onBg,
            outline = if (dark) onCard(0.14f) else onCard(0.15f),
            dividerLine = if (dark) onCard(0.10f) else onCard(0.10f),
            windowDimming = Color.Black.copy(alpha = if (dark) 0.6f else 0.3f),
            sliderKeyPoint = primary.copy(alpha = 0.3f),
            sliderKeyPointForeground = primary,
            sliderBackground = if (dark) onCard(0.15f) else onCard(0.08f),
        )
    }

    /**
     * HyperOS 预设的配色：Miuix 默认配色，只动四个槽。
     * - `primaryContainer` / `onPrimaryContainer`：Miuix 组件不读，我们的页面按 M3 的含义用（淡强调底 + 深强调字：
     *   封面玻璃卡还没书可铺时退回的实色卡），而 Miuix 默认值是一块饱和蓝配白字，铺满一张卡太抢眼。照 [schemeFrom] 的推法算。
     * - `onBackgroundVariant`（分组小标题）、`onTertiaryContainer`（选中 chip、下拉菜单选中项的蓝字）：
     *   Miuix 默认值只有约 2.9:1 / 3.4:1，低于正文 4.5:1 的底线（AppThemesTest 钉着）。各自往深处推到刚好够，
     *   色相不变 —— 看上去仍是那个灰蓝小标题和那个蓝。
     */
    private fun hyperOsScheme(dark: Boolean): Colors {
        val base = if (dark) darkColorScheme() else lightColorScheme()
        // schemeFrom 的 background 在浅色是卡片色、在深色是页面底（见它的 KDoc）
        val derived = schemeFrom(base.primary, if (dark) base.surface else base.surfaceContainer, dark)
        // 小标题既在页面底上也在卡片上（面板里的分组）：两处都得够
        val sectionTitle = readableOn(readableOn(base.onBackgroundVariant, base.surface), base.surfaceContainer)
        return base.copy(
            primaryContainer = derived.primaryContainer,
            onPrimaryContainer = derived.onPrimaryContainer,
            onBackgroundVariant = sectionTitle,
            onTertiaryContainer = readableOn(base.onTertiaryContainer, base.tertiaryContainer),
        )
    }

    /** 黑白前景中对比度更高者 */
    private fun bestForeground(bg: Color): Color {
        val black = Color(0xFF1C1917)
        return if (contrast(black, bg) >= contrast(Color.White, bg)) black else Color.White
    }

    /**
     * [color] 放在 [on] 上不够 4.5:1 时，往远离底色的一端（黑白里对比更高的那头）一点点推。
     * 淡强调底上的强调色字（下拉菜单选中项）、提示胶囊上的操作键（AppSnackbar）都要读得清。
     *
     * 往哪头推按「黑、白谁在这块底上对比更高」定，不按亮度过不过 0.5：两者打平的地方在相对亮度 0.18 左右，
     * 0.18~0.5 之间的中灰底（深色主题的浅色玻璃胶囊透出底下的黑）按 0.5 判会往白推，推到纯白也够不上 4.5:1。
     */
    fun readableOn(color: Color, on: Color): Color {
        val target = if (contrast(Color.Black, on) >= contrast(Color.White, on)) Color.Black else Color.White
        var t = 0f
        var c = color
        while (contrast(c, on) < 4.5f && t < 1f) {
            t += 0.05f
            c = mixSrgb(color, target, t)
        }
        return c
    }

    /**
     * sRGB 分量线性混合。中性台阶专用。
     *
     * 不要换成 [lerp]：它在 Oklab 里插值，对「黑 → 暖白」这种两端，同样 fraction 落回
     * sRGB 后亮度远远不够，纯黑主题上的卡片会看起来还是黑的。
     */
    private fun mixSrgb(from: Color, to: Color, fraction: Float): Color {
        val t = fraction.coerceIn(0f, 1f)
        return Color(
            red = from.red + (to.red - from.red) * t,
            green = from.green + (to.green - from.green) * t,
            blue = from.blue + (to.blue - from.blue) * t,
            alpha = from.alpha + (to.alpha - from.alpha) * t,
        )
    }

    private fun contrast(a: Color, b: Color): Float {
        val l1 = maxOf(a.luminance(), b.luminance()) + 0.05f
        val l2 = minOf(a.luminance(), b.luminance()) + 0.05f
        return l1 / l2
    }
}
