package com.radium.inkwell.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.radium.inkwell.ui.components.animationsEnabled
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.Colors
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * 当前生效的配色（含「跟随壁纸」）。MainActivity 拿它设窗体底色与系统栏明暗、[InkwellTheme] 拿它铺整树 ——
 * 两处必须是同一个结果，否则选了壁纸配色后窗体底色还是预设的，转场缝隙里露出另一种颜色。
 *
 * 自定义主题的整套配色是现推导出来的（几十个槽位的颜色混合）：remember 住，别每次重组都推一遍。
 * 换壁纸时系统会重建 Activity，这里跟着重新取，不必自己监听。
 *
 * 壁纸配色交给 Miuix 的 [ThemeController]（Monet 模式、不给 keyColor）：它在 Android 13+ 读系统调色板的
 * 种子色与风格（TONAL_SPOT / VIBRANT…）再生成，12 读系统 accent/neutral 资源，更老的系统给一套默认紫 ——
 * 和 M3 `dynamicLightColorScheme` 是同一个来源，只是直接落成 Miuix 的槽位。
 * 别在别处直接调 [AppThemes.resolve]：那样会漏掉壁纸配色。
 */
@Composable
fun rememberResolvedScheme(config: ThemeConfig, systemDark: Boolean): Pair<Colors, Boolean> {
    val dark = AppThemes.isDark(config.mode, systemDark)
    val wantsWallpaper = (if (dark) config.darkPreset else config.lightPreset) == AppThemes.DYNAMIC
    // 只有选了壁纸的那一侧才去读系统调色板：读一次要解析 Settings.Secure 里的 JSON，没必要每个主题都付
    val wallpaper = if (wantsWallpaper) rememberWallpaperColors(dark) else null
    return remember(config, systemDark, wallpaper) {
        AppThemes.resolve(config, systemDark, wallpaper?.let { colors -> { colors } })
    }
}

@Composable
private fun rememberWallpaperColors(dark: Boolean): Colors {
    val controller = remember(dark) {
        ThemeController(if (dark) ColorSchemeMode.MonetDark else ColorSchemeMode.MonetLight)
    }
    return controller.currentColors()
}

/**
 * Inkwell 全局主题：按用户主题配置解析配色（模式 + 预设/自定义日夜主题），铺成 [MiuixTheme]。
 * 页面颜色一律走 `MiuixTheme.colorScheme` 语义槽位，字体走 `MiuixTheme.textStyles`；
 * Miuix 没有的圆角与动效见 [AppTheme]。
 *
 * 走 Miuix 文档推荐的入口 `MiuixTheme(controller = ThemeController(…))`（主题系统一节的
 * 「Controller 中的自定义配色」）：配色是我们自己解析好的（预设 / 自定义 / 壁纸，见 [rememberResolvedScheme]），
 * 按当前日夜放进 controller 对应的那一侧，模式给 `Light` / `Dark`。比 `MiuixTheme(colors = …)` 多两样东西：
 * `LocalContentColor`（没写颜色的 Text / Icon 跟着 onBackground 走，深色下不会黑字压黑底）和
 * `MiuixTheme.colorSchemeMode`。
 *
 * 根上仍铺一层 [Surface]：NavDisplay 的 shared-axis 旧页只让出 1/4，缝隙里没有别的 Compose 节点可画；
 * 若不铺底色，就会透到 Activity 的 windowBackground。底色取 `surface` —— Miuix 的**页面底**是 surface，
 * 不是 background（background 是弹窗与底部面板的底）。内容色顺带设成 onSurface（页面上的字是 onSurface）。
 *
 * 注意：另两处局部主题（阅读器浮层的 [com.radium.inkwell.ui.reader.ReaderThemeScope]、书籍详情的封面取色
 * `CoverTintedTheme`）用的是 `MiuixTheme(colors = …)`（文档里的「直接使用」）：它们是在全局主题里
 * 局部覆盖几个槽位，不是另起一套日夜模式，没有 controller 可言；
 * [LocalMotionScheme] 不会被它们盖掉，「移除动画」一路传进去。
 */
@Composable
fun InkwellTheme(
    config: ThemeConfig = ThemeConfig(),
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val (scheme, dark) = rememberResolvedScheme(config, systemDark)
    // 日夜已经在 rememberResolvedScheme 里按用户设置解析过了，这里只把结果放进对应的一侧
    val controller = remember(scheme, dark) {
        if (dark) {
            ThemeController(ColorSchemeMode.Dark, darkColors = scheme)
        } else {
            ThemeController(ColorSchemeMode.Light, lightColors = scheme)
        }
    }
    // 用户在系统设置里切「移除动画」时只换方案对象，不换调用点：按 animate 拆成两个 MiuixTheme 调用点
    // 的话，子树换槽位重建，滚动位置、展开态之类全丢
    val motion = if (animationsEnabled()) AppMotionScheme.Default else AppMotionScheme.Instant
    MiuixTheme(controller = controller) {
        CompositionLocalProvider(LocalMotionScheme provides motion) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = scheme.surface,
                contentColor = scheme.onSurface,
                content = content,
            )
        }
    }
}
