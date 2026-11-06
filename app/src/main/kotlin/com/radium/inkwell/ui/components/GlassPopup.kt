package com.radium.inkwell.ui.components

import android.view.WindowManager
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Density
import com.radium.inkwell.ui.theme.AppShapes
import com.radium.inkwell.ui.theme.AppThemes
import java.util.function.Consumer
import top.yukonga.miuix.kmp.basic.DropdownDefaults
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowListPopup

/**
 * 锚在某个按钮 / 某一行上的弹出列表，**毛玻璃**版：[AppDropdownMenu] 和 [SpinnerSettingRow] 都从这里弹。
 *
 * Miuix 的弹出列表（`WindowListPopup`）是一块实心的卡片色，压在一层压暗上。这里换成 HyperOS 的玻璃：
 * - **背后整页糊掉**：弹层是 Miuix 开的一个全屏 Dialog 窗口，在它里面调 [DialogWindowBlur] —— 系统合成器把后面的应用窗口
 *   糊掉（同对话框、底部面板背后那层），Miuix 自己的压暗照旧叠在上面。
 * - **卡片半透明**：卡片底（Miuix 在 `ListPopupContent` 里写死读 `surfaceContainer`）只在这一小块换成
 *   [POPUP_GLASS_ALPHA_LIGHT] / [POPUP_GLASS_ALPHA_DARK] 的卡片色，各行的底（`DropdownColors.containerColor`，默认也是实心卡片色）
 *   换成透明 —— 透出来的是底下那片已经糊开的页面，一层雾，而不是贴上去的一块白。
 * - **边缘高光**：和顶栏的玻璃圆按钮、底部悬浮胶囊同一种（miuix-blur 的 Small Container 那档，[smallGlassHighlight]），
 *   深色下尤其靠它分出卡片的轮廓。从前是自己描的一道白细线，顶栏换成玻璃圆以后从圆里弹出来一张描线的卡，两种边并排着，被要求统一。
 *
 * 字读不读得清按**最坏的页面**算（同 CoverGlassTest 里的玻璃卡）：浅色主题卡片下面一片纯黑、深色主题一片纯白。
 * 叠色厚度保证正文够 4.5:1；说明小字和选中项的强调色在这块最坏的底上现推（[AppThemes.readableOn]），
 * 推完同样够 —— 代价是玻璃上的说明字比实色卡片上深 / 亮一档。
 *
 * 系统关了跨窗口模糊（省电模式、部分机型、开发者选项「停用窗口级模糊」）时退回实色卡片：没有模糊垫着，
 * 半透明的卡片底下就是一行行清清楚楚的字，读不了。
 *
 * 用法与 Miuix 弹层一样：**和锚点放进同一个父布局**，弹层按那个父布局的位置定位、靠右对齐（[PopupPositionProvider.Align.End]）。
 * 点了某项先轻震、执行它的 onClick，再收起（收起走 [onDismissRequest]，退场动画播完调 [onDismissFinished]）。
 */
@Composable
internal fun GlassDropdownPopup(
    show: Boolean,
    items: List<DropdownItem>,
    onDismissRequest: () -> Unit,
    onDismissFinished: () -> Unit = {},
) {
    val scheme = MiuixTheme.colorScheme
    val glass = rememberCrossWindowBlurEnabled()
    val dark = scheme.surface.luminance() < 0.5f
    val container = scheme.surfaceContainer
    val floor = if (glass) popupGlassFloor(container, dark) else container
    // readableOn 是一步步试出来的，别每次重组都试一遍；key 用具体槽位的值（MiuixTheme 的 Colors 对象原地改值，拿它当 key 永远不变）
    val content = remember(scheme.onSurfaceContainer, floor) { AppThemes.readableOn(scheme.onSurfaceContainer, floor) }
    val summary = remember(scheme.onSurfaceVariantSummary, floor) { AppThemes.readableOn(scheme.onSurfaceVariantSummary, floor) }
    val accent = remember(scheme.primary, floor) { AppThemes.readableOn(scheme.primary, floor) }
    val colors = DropdownDefaults.dropdownColors(
        contentColor = content,
        summaryColor = summary,
        containerColor = Color.Transparent,
        selectedContentColor = accent,
        selectedSummaryColor = accent,
        selectedContainerColor = Color.Transparent,
        selectedIndicatorColor = accent,
    )
    val haptic = LocalHapticFeedback.current
    val currentItems by rememberUpdatedState(items)
    PopupCardColor(if (glass) container.copy(alpha = popupGlassAlpha(dark)) else container) {
        WindowListPopup(
            show = show,
            alignment = PopupPositionProvider.Align.End,
            onDismissRequest = onDismissRequest,
            onDismissFinished = onDismissFinished,
        ) {
            // 这里已经在弹层自己的 Dialog 窗口里：只有在这儿拿得到那个窗口
            if (glass) DialogWindowBlur(visible = show)
            val dismiss = LocalDismissState.current
            Box {
                ListPopupColumn {
                    items.forEachIndexed { index, item ->
                        DropdownImpl(
                            item = item,
                            optionSize = items.size,
                            isSelected = item.selected,
                            index = index,
                            dropdownColors = colors,
                            enabled = item.enabled,
                            onSelectedIndexChange = { i ->
                                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                                currentItems.getOrNull(i)?.onClick?.invoke()
                                dismiss?.invoke()
                            },
                        )
                    }
                }
                if (glass) GlassRim(dark, Modifier.matchParentSize())
            }
        }
    }
}

/**
 * 玻璃卡片的边：miuix-blur 的边缘高光，形状同 Miuix 弹出卡片（[AppShapes.radiusLarge] 的 squircle —— 高光只从
 * CornerBasedShape 读圆角，所以是 [squircleShape]）。画在卡片的内容里（在 Miuix 展开动画的裁切以内），展开时跟着卡片一起长出来。
 *
 * 卡片的玻璃是系统跨窗口模糊糊的，用不着 miuix-blur 取样；借 `drawBackdrop` 只为它最后那一步画高光（画高光的函数是 internal 的）：
 * 取样源给 [NoBackdrop]、效果给空，它录一张空层、什么都不糊，然后照常画高光。
 */
@Composable
private fun GlassRim(dark: Boolean, modifier: Modifier) {
    val shape = remember { squircleShape(AppShapes.radiusLarge) }
    val highlight = smallGlassHighlight(dark)
    Box(
        modifier.drawBackdrop(
            backdrop = NoBackdrop,
            shape = { shape },
            effects = {},
            highlight = { highlight },
        ),
    )
}

/** 什么都不画的取样源，见 [GlassRim] */
private object NoBackdrop : Backdrop {
    override val isCoordinatesDependent: Boolean = false

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?,
        downscaleFactor: Int,
    ) = Unit
}

/**
 * 只把弹出卡片的底换掉：Miuix 的 `ListPopupContent` 写死读 `MiuixTheme.colorScheme.surfaceContainer`，没有参数。
 * 这一层只包住弹层（卡片里的各行用的是显式传进去的 DropdownColors，不读这个槽）。
 * colors 版的 MiuixTheme 不提供 LocalContentColor（见 ReaderThemeScope），照外层的补上
 */
@Composable
private fun PopupCardColor(color: Color, content: @Composable () -> Unit) {
    val base = MiuixTheme.colorScheme
    if (color == base.surfaceContainer) {
        content()
        return
    }
    val contentColor = LocalContentColor.current
    MiuixTheme(colors = base.copy(surfaceContainer = color), textStyles = MiuixTheme.textStyles) {
        CompositionLocalProvider(LocalContentColor provides contentColor, content = content)
    }
}

/**
 * 系统此刻开没开跨窗口模糊（Android 12+ 才有；省电模式、开发者选项会随时关掉它），关了 / 开了实时跟上。
 * 弹层据此决定玻璃还是实色：没有模糊垫着的半透明卡片是读不了的
 */
@Composable
internal fun rememberCrossWindowBlurEnabled(): Boolean {
    val context = LocalContext.current
    val windowManager = remember(context) { context.getSystemService(WindowManager::class.java) }
    var enabled by remember(windowManager) { mutableStateOf(windowManager?.isCrossWindowBlurEnabled == true) }
    DisposableEffect(windowManager) {
        if (windowManager == null) return@DisposableEffect onDispose { }
        val listener = Consumer<Boolean> { enabled = it }
        windowManager.addCrossWindowBlurEnabledListener(listener)
        onDispose { windowManager.removeCrossWindowBlurEnabledListener(listener) }
    }
    return enabled
}

internal fun popupGlassAlpha(dark: Boolean): Float = if (dark) POPUP_GLASS_ALPHA_DARK else POPUP_GLASS_ALPHA_LIGHT

/**
 * 玻璃卡片最坏时的底色：卡片色按 [popupGlassAlpha] 叠在和字反着的一片纯色上（浅色主题纯黑、深色主题纯白）。
 * 卡片上各种字的颜色都按它推、对比度也按它测（CoverGlassTest）
 */
internal fun popupGlassFloor(container: Color, dark: Boolean): Color {
    val under = if (dark) Color.White else Color.Black
    val a = popupGlassAlpha(dark)
    return Color(
        red = container.red * a + under.red * (1 - a),
        green = container.green * a + under.green * (1 - a),
        blue = container.blue * a + under.blue * (1 - a),
    )
}

// 卡片叠多厚：CoverGlassTest 按所有预设和几组极端配色算「正文最少要多厚」，这里往上取整。
// 再薄的话说明小字、选中项要推得太狠，和正文分不出层级
internal const val POPUP_GLASS_ALPHA_LIGHT = 0.85f
internal const val POPUP_GLASS_ALPHA_DARK = 0.85f
