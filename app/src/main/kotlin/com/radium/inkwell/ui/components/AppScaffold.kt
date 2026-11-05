package com.radium.inkwell.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalLayoutDirection
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.ProgressiveBlur
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.progressiveTextureBlur
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * 顶栏毛玻璃取样的内容层：[AppScaffold] 把页面内容录进这里，[AppTopBar] / [AppToolbar] 读它画毛玻璃。
 * null = 不模糊（不在 AppScaffold 里，或在被录制的内容里面 —— 自己糊自己会递归）。
 */
internal val LocalBarBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

/**
 * 页面骨架：Miuix [Scaffold] + HyperOS 的顶栏毛玻璃。页面一律用它，别再直接铺 Miuix `Scaffold`。
 *
 * Miuix `Scaffold` 本来就把内容铺满整屏、顶栏叠在上面（[padding] 只是告诉内容「上面被顶栏盖住多少」）。
 * 这里把内容录进一个 [LayerBackdrop]（miuix-blur），顶栏改成透明 + 对这层取样做模糊 ——
 * 内容滚到顶栏下面时透出来一片糊开的颜色，就是 HyperOS 设置页那种玻璃顶栏。
 *
 * **前提是内容真的滚到顶栏下面**：顶栏高度要垫在**滚动内容的开头**（Lazy 列表进 `contentPadding.top`，
 * 滚动 Column 加在 `verticalScroll` 之后），外层只让开两侧（[horizontalOnly]）。
 * 外层照旧 `padding(padding)` 整块往下推的话，顶栏下面永远只有页面底色，模糊等于没做。
 * 不滚动的居中态（加载、空态、错误态）照旧整块 `padding(padding)`。
 *
 * 模糊要 RuntimeShader（API 33），我们 minSdk 33，不必做降级。
 *
 * @param blurBars false 时退回实色顶栏：内容里有 WebView 这类不在 Compose 里画的东西时关掉 ——
 *   录不进图层，顶栏下面会是一片空白
 * @param floatingToolbar 底部居中的悬浮工具条（多选态的 [BatchActionBar]）。Miuix Scaffold **不**把它算进
 *   给内容的 padding，列表末尾要自己再垫它的高度（见 [BatchActionBar]）
 */
@Composable
fun AppScaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    floatingToolbar: @Composable () -> Unit = {},
    containerColor: Color = MiuixTheme.colorScheme.surface,
    blurBars: Boolean = true,
    content: @Composable (PaddingValues) -> Unit,
) {
    // 录下来的内容自己不带底色（页面底是 Scaffold 画的，不在这一层里）：透明处先垫页面底，
    // 否则模糊时四周会渗进黑边（miuix-blur 文档点名的坑）
    val backdrop = if (blurBars) {
        rememberLayerBackdrop {
            drawRect(containerColor)
            drawContent()
        }
    } else {
        null
    }
    CompositionLocalProvider(LocalBarBackdrop provides backdrop) {
        Scaffold(
            modifier = modifier,
            topBar = topBar,
            floatingActionButton = floatingActionButton,
            snackbarHost = snackbarHost,
            floatingToolbar = floatingToolbar,
            containerColor = containerColor,
        ) { padding ->
            CompositionLocalProvider(LocalBarBackdrop provides null) {
                Box(if (backdrop != null) Modifier.fillMaxSize().layerBackdrop(backdrop) else Modifier.fillMaxSize()) {
                    content(padding)
                }
            }
        }
    }
}

/**
 * 栏的毛玻璃：对 [LocalBarBackdrop] 取样的**渐进**模糊 —— 状态栏那头糊得最重，往下越来越清，到栏底边糊和叠色
 * 一起淡到零，内容是从栏底下一点点化进去的，没有一道「栏的下边」。参数照 Miuix 示例 App 设置里的
 * 「Progressive」顶栏（`BlurredBar`）：渐变铺满整条栏、曲线 2.2（前大半截几乎满强度，到下面才快速变清），
 * 叠一层三成的栏底色；半径取示例模糊页 Progressive Blur 那一档的 20。
 * 不在 [AppScaffold] 里时原样返回 —— 调用方此时该给栏实色底。
 * [tint] 自带的透明度会乘进去：关于页停在顶部时传透明色，栏就只糊不叠色，彩色头部从栏底下透上来。
 *
 * 从前是「满强度 + 八成底色，只在最底下 16dp 里淡出」：八成底色基本就是一条实色栏，16dp 的淡出在手机上看不出来，
 * 被指出「渐变没有实现」。渐变按比例铺满整条，大标题栏折叠时跟着高度一起伸缩，不用量高度。
 * 标题那一行落在渐变的后半截，背后的内容只糊了一半 —— 这正是 HyperOS 的样子。放进 bottomContent 的东西落在
 * 最清的那一头：chip、搜索框自己有底色，不靠栏托字；纯文字的那几条（搜索页的结果数）列表滚上来时背后几乎是原样。
 */
@Composable
internal fun Modifier.barBlur(tint: Color): Modifier {
    val backdrop = LocalBarBackdrop.current ?: return this
    val colors = BlurDefaults.blurColors(blendColors = listOf(BlendColorEntry(color = tint.copy(alpha = tint.alpha * BAR_TINT_ALPHA))))
    return progressiveTextureBlur(
        backdrop = backdrop,
        shape = RectangleShape,
        blurRadius = BAR_BLUR_RADIUS,
        gradient = BAR_GRADIENT,
        colors = colors,
    )
}

/** 当前栏是否走毛玻璃（是的话栏自己的底色要给透明） */
@Composable
@ReadOnlyComposable
internal fun barBlurActive(): Boolean = LocalBarBackdrop.current != null

private const val BAR_BLUR_RADIUS = 20f
private const val BAR_TINT_ALPHA = 0.3f
private val BAR_GRADIENT = ProgressiveBlur.Top.copy(curve = 2.2f)

/**
 * 页面主滚动区的 HyperOS 手感：拉到头的弹性回弹 + 甩到头轻震一下。挂在 `LazyColumn` / `verticalScroll` 的
 * Column 上，**同时**给那个滚动组件传 `overscrollEffect = null`。
 *
 * `MiuixTheme` 已经给所有滚动组件装了同一套回弹（MiuixOverscrollFactory），但它只在内容超出一屏、
 * 真滚到边时才触发 —— 设置页这种不满一屏的页面拉不动。这个修饰符不管内容多少都弹；两套一起开会叠成双倍位移，
 * 所以滚动组件自己的那份要关掉。
 *
 * 顶栏折叠照旧挂在 Scaffold 上（[topBarScroll]）：回弹层先把滚动量交给外层，顶栏收完 / 展开完才轮到它弹。
 */
fun Modifier.pageScroll(): Modifier = this
    .scrollEndHaptic()
    .overScrollVertical()

/**
 * Scaffold 给的内边距只留两侧（刘海 / 横屏导航栏）。配 [AppScaffold]：顶栏和导航栏的高度都垫进滚动内容的首尾，
 * 内容才能滚到玻璃顶栏下面、也滚到手势条下面。
 */
@Composable
@ReadOnlyComposable
fun PaddingValues.horizontalOnly(): PaddingValues {
    val direction = LocalLayoutDirection.current
    return PaddingValues(
        start = calculateStartPadding(direction),
        end = calculateEndPadding(direction),
    )
}
