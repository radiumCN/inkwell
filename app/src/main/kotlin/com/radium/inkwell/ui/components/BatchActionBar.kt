package com.radium.inkwell.ui.components

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import top.yukonga.miuix.kmp.basic.FloatingToolbar
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.More
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
 * 多选态的底部操作条，HyperOS 编辑模式的样子：顶栏只留「× · 已选 N 项 · 全选」，批量动作沉到屏幕底部一条悬浮的胶囊里
 * （Miuix [FloatingToolbar]），图标在上、字在下。放进 [AppScaffold] 的 `floatingToolbar`。
 *
 * 从前批量动作全挤在顶栏：三四个光秃秃的图标加一个 ⋮，认不出哪个是哪个，「设置分组」这种常用的还藏在菜单里。
 * 拇指够得着的是屏幕底部，HyperOS 的相册、笔记、文件管理多选都是这么排的。
 *
 * 进出多选时从底部滑入滑出（[bottomBarEnter] / [bottomBarExit]）。胶囊是卡片色、带一圈分隔线：深色下卡片色和
 * 列表的卡同一个色，只靠阴影（黑底上看不见）分不出条在哪。
 *
 * **Miuix Scaffold 不把它算进给内容的 padding**：[onHeightChange] 报出它占掉的高度（连同 Scaffold 把它往上抬的那一截），
 * 调用方在多选态把它垫到列表末尾 —— 不垫的话最后一两行压在条下面，选不到也看不见。
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
    val density = LocalDensity.current
    AnimatedVisibility(visible = visible, enter = bottomBarEnter(), exit = bottomBarExit()) {
        FloatingToolbar(
            // 量的是连同外边距在内的整块：Scaffold 就按这一块的高度往导航栏上面摆
            modifier = Modifier.onSizeChanged {
                onHeightChange(with(density) { it.height.toDp() } + TOOLBAR_LIFT)
            },
            showDivider = true,
        ) {
            Row(Modifier.padding(horizontal = Dimens.gapS)) {
                val scheme = MiuixTheme.colorScheme
                actions.forEach { action ->
                    IconLabelButton(
                        icon = action.icon,
                        label = action.label,
                        onClick = action.onClick,
                        enabled = action.enabled,
                        contentColor = if (action.destructive) scheme.error else scheme.onSurface,
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
}

/** Miuix Scaffold 把悬浮工具条摆在导航栏上面时再往上抬的那一截（它的 `FloatingToolbarSpacing`，私有常量） */
private val TOOLBAR_LIFT = Dimens.gapXS
