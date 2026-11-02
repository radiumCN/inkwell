package com.radium.inkwell.reader.flip

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import com.radium.inkwell.reader.api.FlipDirection
import com.radium.inkwell.reader.render.RenderablePage
import com.radium.inkwell.reader.render.accessibleText

/**
 * 阅读页给读屏的那一层语义，翻页与滚动两种模式共用。
 *
 * 正文是自绘的（Canvas 上直接画 TextLayoutResult），从前整个阅读页没有一个语义节点：开着 TalkBack
 * 读不到正文；双击没有可聚焦的东西 —— 翻不了页，也打不开菜单（菜单只能靠点屏幕中间唤出）。
 * 这部分用户等于完全用不了阅读页。这里把本页的文字给出来，翻页和开菜单做成读屏能触发的动作。
 *
 * [canFlip] 为 false 时不给翻页动作：菜单开着时手势是关的，读屏焦点也在菜单上，不该还能在底下翻页。
 */
internal fun Modifier.readerPageSemantics(
    page: RenderablePage?,
    canFlip: (FlipDirection) -> Boolean,
    onFlip: (FlipDirection) -> Unit,
    onMenu: () -> Unit,
): Modifier = semantics {
    page?.let { contentDescription = it.accessibleText() }
    onClick(label = "打开菜单") {
        onMenu()
        true
    }
    customActions = buildList {
        if (canFlip(FlipDirection.FORWARD)) {
            add(CustomAccessibilityAction("下一页") { onFlip(FlipDirection.FORWARD); true })
        }
        if (canFlip(FlipDirection.BACKWARD)) {
            add(CustomAccessibilityAction("上一页") { onFlip(FlipDirection.BACKWARD); true })
        }
    }
}
