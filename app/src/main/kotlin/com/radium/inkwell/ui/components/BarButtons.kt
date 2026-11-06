package com.radium.inkwell.ui.components

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Constraints
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 顶栏的玻璃按钮组（HyperOS 3 那种圆按钮）：只有一个按钮时是一枚圆，几个动作排在一起时合成一条胶囊。
 * [AppTopBar] / [AppToolbar] 把导航位、动作位各包一层，页面照旧往 `actions` 里放 [AppIconButton]，不用自己调。
 *
 * **组里的 [AppIconButton] 画成「一格」**（[BarButtonSlot]）：可点区仍是 48dp（[Dimens.touchTarget]），
 * 玻璃只铺中间那枚 [Dimens.barButton] 的圆 / 那条胶囊，四周各让出 4dp 透明的触控余量；
 * 按下时压暗的是这一格中间的圆，整组（一整枚圆、一整条胶囊）一起微缩 —— 和书架上的书同一个 [sinkOnPress]。
 * 胶囊是一块玻璃，按其中一格只沉那一格的话，玻璃会被切成一块凹一块平。
 *
 * **玻璃**和底部悬浮胶囊（[FloatingGlassBar]）是同一块：对页面图层取样糊 [TOOLBAR_BLUR_RADIUS]、
 * 叠同一层卡片色薄底（[toolbarGlassVeil]，对比度在 CoverGlassTest 里和操作条一起钉着）、Small 那档边缘高光，
 * **不投影** —— 投影是画在底下的实心块，会从半透明的玻璃里透出来。顶栏本身也是玻璃，按钮取样的是页面图层、
 * 不是栏，所以叠上去不会「糊上加糊」：圆里是糊 25 的页面 + 薄底，圆外是栏的渐进模糊。
 * 不在 [AppScaffold] 里（没有可取样的图层，比如新建书源页）时退回实色的卡片色圆。
 *
 * 组是空的（大标题栏多数页没有动作）时整个不占地、不画 —— 不然右上角会多一枚空的玻璃圆。
 *
 * 弹层（底部面板、对话框）在自己的窗口里，却会继承调用处的 CompositionLocal：声明在 `actions` 里的面板，
 * 面板里的 × 也会被画成组里的一格，按一下顶栏的胶囊跟着沉。所以 [AppBottomSheet] / [AppAlertDialog]
 * 进门先把 [LocalBarButtonGroup] 清掉；弹出菜单（[AppDropdownMenu]）的各行不是图标按钮，用不着。
 */
@Composable
internal fun BarButtonGroup(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val group = remember { BarButtonGroupState() }
    val backdrop = LocalBarBackdrop.current
    val scheme = MiuixTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.5f
    val radius = Dimens.barButton / 2
    val surface = if (backdrop != null) {
        val shape = remember(radius) { squircleShape(radius) }
        Modifier
            .squircleClip(radius)
            .textureBlur(
                backdrop = backdrop,
                shape = shape,
                blurRadius = TOOLBAR_BLUR_RADIUS,
                colors = BlurDefaults.blurColors(),
                highlight = smallGlassHighlight(dark),
            )
            .background(toolbarGlassVeil(scheme.surfaceContainer, dark))
    } else {
        Modifier
            .squircleClip(radius)
            .background(scheme.surfaceContainer)
    }
    Layout(
        content = {
            Box(surface)
            CompositionLocalProvider(LocalBarButtonGroup provides group) {
                Row(verticalAlignment = Alignment.CenterVertically, content = content)
            }
        },
        modifier = modifier.sinkOnPress(group.pressSource),
    ) { measurables, constraints ->
        val row = measurables[1].measure(constraints.copy(minWidth = 0, minHeight = 0))
        if (row.width == 0 || row.height == 0) {
            return@Layout layout(0, 0) {}
        }
        // 玻璃比这一排可点区四周各缩一圈：48dp 的格子里露出 40dp 的圆
        val inset = ((Dimens.touchTarget - Dimens.barButton) / 2).roundToPx()
        val glass = measurables[0].measure(
            Constraints.fixed(
                (row.width - inset * 2).coerceAtLeast(0),
                (row.height - inset * 2).coerceAtLeast(0),
            ),
        )
        layout(row.width, row.height) {
            glass.placeRelative(inset, inset)
            row.placeRelative(0, 0)
        }
    }
}

/** 当前在不在顶栏的玻璃按钮组里；在的话 [AppIconButton] 画成组里的一格（[BarButtonSlot]） */
internal val LocalBarButtonGroup = staticCompositionLocalOf<BarButtonGroupState?> { null }

@Stable
internal class BarButtonGroupState {
    /** 整组下沉听的源：组里每一格把自己的按下 / 松开转发过来 */
    val pressSource = MutableInteractionSource()
}

/**
 * 玻璃按钮组里的一格：48dp 可点、中间 40dp 的圆按下时压暗，按下 / 松开转给整组下沉。
 * 只由 [AppIconButton] 在组里时调用 —— 禁用态、内容色的规矩跟它走，[tint] 由它算好传进来。
 */
@Composable
internal fun BarButtonSlot(
    group: BarButtonGroupState,
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    tint: Color,
    backgroundColor: Color,
    content: @Composable () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    LaunchedEffect(source, group) {
        var pressed: PressInteraction.Press? = null
        try {
            source.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> pressed = interaction
                    is PressInteraction.Release, is PressInteraction.Cancel -> pressed = null
                }
                group.pressSource.emit(interaction)
            }
        } finally {
            // 按住时这一格被摘掉（点了导入、这一格换成转圈）：clickable 补的那个 Cancel 收不到了，
            // 替它补一个，不然整组一直沉着
            pressed?.let { group.pressSource.tryEmit(PressInteraction.Cancel(it)) }
        }
    }
    Box(
        modifier
            .size(Dimens.touchTarget)
            .clickable(
                interactionSource = source,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(Dimens.barButton)
                // 压暗只是按下那一下的一层色，用不着 squircle 的离屏层；40dp 的整圆两种弯法也没有差别
                .clip(CircleShape)
                .then(if (backgroundColor != Color.Unspecified) Modifier.background(backgroundColor) else Modifier)
                .indication(source, LocalIndication.current),
            contentAlignment = Alignment.Center,
        ) {
            // 格子里的东西不再是「组里的一格」：内容里万一又放了图标按钮，别把它也排成一格
            CompositionLocalProvider(
                LocalContentColor provides tint,
                LocalBarButtonGroup provides null,
                content = content,
            )
        }
    }
}
