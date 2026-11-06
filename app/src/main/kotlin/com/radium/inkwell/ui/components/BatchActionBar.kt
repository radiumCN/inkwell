package com.radium.inkwell.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.radium.inkwell.ui.theme.AppThemes
import top.yukonga.miuix.kmp.basic.FloatingToolbar
import top.yukonga.miuix.kmp.basic.FloatingToolbarDefaults
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 「图标在上、字在下」的按钮：阅读菜单底部的操作条、多选态的 [BatchActionBar]。走 [AppTextButton]
 * （按下有 Miuix 的压暗反馈，禁用时自动压到 38%，可点高度保底 48dp）。名字由下面的字给，图标不再单独进读屏。
 */
@Composable
fun IconLabelButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentColor: Color = MiuixTheme.colorScheme.onSurface,
    contentPadding: PaddingValues = PaddingValues(horizontal = Dimens.gapM, vertical = Dimens.gapXS),
) {
    AppTextButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        contentPadding = contentPadding,
        contentColor = contentColor,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(Dimens.iconMd))
            Text(label, style = MiuixTheme.textStyles.footnote1, maxLines = 1)
        }
    }
}

/** 多选态底部操作条上的一个动作 */
@Immutable
data class BatchAction(
    val icon: ImageVector,
    val label: String,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
    /** 删除这类不可撤销的：图标和字用 error 色 */
    val destructive: Boolean = false,
)

/**
 * 多选态的底部操作条，HyperOS 编辑模式的样子：顶栏只留「× · 已选 N 项 · 全选」，批量动作沉到屏幕底部一条悬浮的玻璃胶囊里
 * （[FloatingGlassBar]），图标在上、字在下。放进 [AppScaffold] 的 `floatingToolbar`。
 *
 * 从前批量动作全挤在顶栏：三四个光秃秃的图标加一个 ⋮，认不出哪个是哪个，「设置分组」这种常用的还藏在菜单里。
 * 拇指够得着的是屏幕底部，HyperOS 的相册、笔记、文件管理多选都是这么排的。
 *
 * [onHeightChange] 的用法见 [FloatingGlassBar]：多选态把它垫到列表末尾。
 *
 * @param more 一条放不下的低频动作：末尾放一个「更多」，点开是这些（[AppDropdownMenu]）
 */
@Composable
fun BatchActionBar(
    visible: Boolean,
    actions: List<BatchAction>,
    onHeightChange: (Dp) -> Unit,
    more: List<AppMenuItem> = emptyList(),
) {
    val scheme = MiuixTheme.colorScheme
    FloatingGlassBar(visible = visible, onHeightChange = onHeightChange) { floor ->
        Row(Modifier.padding(horizontal = Dimens.gapS)) {
            // 「删除」的红字：玻璃上按最坏的底推一档（有的配色里 error 红放在实色卡片上都不到 4.5:1），实色照旧
            val delete = remember(scheme.error, floor) { AppThemes.readableOn(scheme.error, floor) }
            actions.forEach { action ->
                IconLabelButton(
                    icon = action.icon,
                    label = action.label,
                    onClick = action.onClick,
                    enabled = action.enabled,
                    contentColor = if (action.destructive) delete else scheme.onSurface,
                )
            }
            if (more.isNotEmpty()) {
                var open by remember { mutableStateOf(false) }
                // 菜单和「更多」放进同一个 Box，从按钮上弹出来（见 AppDropdownMenu）
                Box {
                    IconLabelButton(icon = MiuixIcons.More, label = "更多", onClick = { open = true })
                    AppDropdownMenu(
                        expanded = open,
                        onDismissRequest = { open = false },
                        // 点了哪项都先收起菜单，调用方的 onClick 不必各自记得关
                        items = more.map { item -> item.copy(onClick = { open = false; item.onClick() }) },
                    )
                }
            }
        }
    }
}

/**
 * 屏幕底部悬浮的玻璃胶囊（Miuix [FloatingToolbar]）：多选态的 [BatchActionBar]、书架隐藏区展开后的状态胶囊都是它。
 * 放进 [AppScaffold] 的 `floatingToolbar`，出现 / 收起时从底部滑入滑出（[bottomBarEnter] / [bottomBarExit]）。
 * 几样同时放进那个槽时（书架：隐藏区胶囊 + 多选操作条），各自给 [visible]，同一时刻只亮一个。
 *
 * **玻璃**（在 AppScaffold 里才有可取样的页面图层）：对列表取样、糊开 [TOOLBAR_BLUR_RADIUS]，Small Container 那档边缘高光，
 * 上面只垫一层卡片色薄底（同封面玻璃卡：底下是一本黑封面时字照样读得清，厚度见 CoverGlassTest）—— 照 Miuix 示例 App 的悬浮导航栏。
 * 从前是糊 100 + Colored Thick 混色 + 薄底：100 比胶囊还高一截，底下连同四周一大片被平均成近白，混色里还有一层 20% 的灰，
 * 压着封面的那截也是同一片浅灰，被指出「玻璃怎么没有透明的效果」。胶囊的形状自己裁：
 * Miuix 的悬浮工具条只画一块实心底，没有玻璃这一档。不在 AppScaffold 里时退回实色胶囊（卡片色、带一圈分隔线：
 * 深色下卡片色和列表的卡同一个色，只靠阴影 —— 黑底上看不见 —— 分不出条在哪）。
 *
 * **Miuix Scaffold 不把它算进给内容的 padding**：[onHeightChange] 报出它占掉的高度（连同 Scaffold 把它往上抬的那一截），
 * 调用方在它露着时把这段高度垫到列表末尾 —— 不垫的话最后一两行压在胶囊下面，点不到也看不见。
 * 提示条（Snackbar）不用管：Miuix Scaffold 自己把它摆在悬浮工具条上面。
 *
 * @param content 胶囊里的内容。参数是玻璃最坏时的底色（[toolbarGlassFloor]；实色时就是卡片色）：
 *   说明色、红字这些不是 onSurface 的字拿它推对比度（`AppThemes.readableOn`），见 CLAUDE.md「玻璃上的字按最坏的底算」
 */
@Composable
fun FloatingGlassBar(
    visible: Boolean,
    onHeightChange: (Dp) -> Unit,
    content: @Composable (floor: Color) -> Unit,
) {
    val density = LocalDensity.current
    val backdrop = LocalBarBackdrop.current
    val scheme = MiuixTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.5f
    val radius = FloatingToolbarDefaults.CornerRadius
    val glass = if (backdrop != null) {
        val shape = remember(radius) { squircleShape(radius) }
        Modifier
            .squircleClip(radius)
            .textureBlur(
                backdrop = backdrop,
                shape = shape,
                blurRadius = TOOLBAR_BLUR_RADIUS,
                // 不混色：透不透看的是糊多狠，发灰的那层正出在 Colored Thick 里（见上面 KDoc）。字的对比度只靠下面那层薄底
                colors = BlurDefaults.blurColors(),
                highlight = smallGlassHighlight(dark),
            )
            .background(toolbarGlassVeil(scheme.surfaceContainer, dark))
    } else {
        Modifier
    }
    val floor = if (backdrop != null) toolbarGlassFloor(scheme.surfaceContainer, dark) else scheme.surfaceContainer
    AnimatedVisibility(visible = visible, enter = bottomBarEnter(), exit = bottomBarExit()) {
        FloatingToolbar(
            // 量的是连同外边距在内的整块：Scaffold 就按这一块的高度往导航栏上面摆。
            // 外边距挪到玻璃外面来（玻璃只铺胶囊本身），所以 Miuix 那份外边距给 0
            modifier = Modifier
                .onSizeChanged { onHeightChange(with(density) { it.height.toDp() } + TOOLBAR_LIFT) }
                .padding(FloatingToolbarDefaults.OutSidePadding)
                .then(glass)
                // 只挡胶囊本身（在外边距之后）：按钮之间的空隙别点到下面那本书 / 那一行
                .blockTouchesBelow(),
            color = if (backdrop != null) Color.Transparent else FloatingToolbarDefaults.defaultColor(),
            outSidePadding = PaddingValues(0.dp),
            // 玻璃不投影、不描实心的边：Miuix 的投影和那圈「分隔线」都是画在胶囊底下的实心块，半透明的玻璃会把它们透出来，
            // 胶囊里糊着一层灰。玻璃的边由边缘高光来画。没有玻璃（实色）时照旧描边
            shadowElevation = if (backdrop != null) 0.dp else FLAT_TOOLBAR_SHADOW,
            showDivider = backdrop == null,
        ) {
            content(floor)
        }
    }
}

/** Miuix Scaffold 把悬浮工具条摆在导航栏上面时再往上抬的那一截（它的 `FloatingToolbarSpacing`，私有常量） */
private val TOOLBAR_LIFT = Dimens.gapXS

/** 实色（没有玻璃）时的投影：Miuix 悬浮工具条的默认值 */
private val FLAT_TOOLBAR_SHADOW = Dimens.gapXS

/**
 * dp。Miuix 示例 App 悬浮导航栏的 25：底下的书名照样糊成认不出的一抹，封面的颜色、明暗块还留着，列表一滚就从胶囊里流过去。
 * 别再调回 100（示例「Texture Blur」那一档）：那是给整张卡片铺满的，放在 56dp 高的胶囊上，底下一大片被平均成一个颜色，看着就是实心
 */
private const val TOOLBAR_BLUR_RADIUS = 25f

/** 操作条玻璃上那层卡片色（为什么要有它见 [BatchActionBar]） */
internal fun toolbarGlassVeil(card: Color, dark: Boolean): Color =
    card.copy(alpha = if (dark) TOOLBAR_VEIL_DARK else TOOLBAR_VEIL_LIGHT)

/**
 * 操作条玻璃最坏时的底色：薄底叠在和字反着的一片纯色上（浅色主题纯黑、深色主题纯白，同封面玻璃卡）。
 * 「删除」的红字按它推（玻璃不混色，糊开的底就是这么坏；模糊再小也不会比「一整片纯色」更坏）
 */
internal fun toolbarGlassFloor(card: Color, dark: Boolean): Color {
    val under = if (dark) Color.White else Color.Black
    val a = toolbarGlassVeil(card, dark).alpha
    return Color(
        red = card.red * a + under.red * (1 - a),
        green = card.green * a + under.green * (1 - a),
        blue = card.blue * a + under.blue * (1 - a),
    )
}

// 薄底厚度：CoverGlassTest 按所有预设算「正文压在最坏的页面上够 4.5:1」最少要多厚，这里往上取整
internal const val TOOLBAR_VEIL_LIGHT = 0.55f
internal const val TOOLBAR_VEIL_DARK = 0.75f
