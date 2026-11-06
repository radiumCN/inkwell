package com.radium.inkwell.ui.reader

import com.radium.inkwell.ui.components.blockTouchesBelow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.ui.components.IconLabelButton
import top.yukonga.miuix.kmp.basic.FloatingToolbar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Clear
import top.yukonga.miuix.kmp.icon.extended.Copy
import top.yukonga.miuix.kmp.icon.extended.Replace
import androidx.compose.ui.graphics.luminance
import com.radium.inkwell.ui.components.smallGlassHighlight

/**
 * 选中文字后的操作条：底部居中的一颗玻璃药丸（Miuix [FloatingToolbar]），复制 · 删除 · 替换，图标在上字在下
 * （[IconLabelButton]，同阅读菜单的操作条）。
 *
 * 从前是四个光图标（复制 / 净化 / 替换 / 关闭）：「净化」和「替换」两个图标长得像，得猜；「净化」做的就是删，
 * 提示里也写「已删除」，所以改叫「删除」。关闭键去掉了：点空白、返回键本来就取消选区。
 * 从前药丸上方还把选中的字用一行小字再写一遍，直接画在纸上、没有底，和下面几行正文叠成一片 ——
 * 选中的字在纸上已经高亮了，不用再写。
 *
 * 「替换」弹 [ReplaceTextDialog]（App 统一的输入弹窗），不再原地换成一块贴底的表单。
 *
 * 药丸的底取浮层色（[readerOverlayColor]）而不是卡片色：它直接压在纸面上，
 * 而浅色纸的卡片色就是纸本身 —— 用卡片色等于纸上放一张看不见的纸。
 * 也不投影：同色纸上的阴影是一道脏灰线，层次只靠色阶（见 ReaderThemeScope）。
 * 给了 [pageBackdrop] 时和阅读菜单一样是毛玻璃（[readerGlass]）：浮层色只叠八成，底下的正文隐约透出来，
 * 再描一圈边缘高光（浅色纸用亮模式那档、深色纸用暗模式那档）。
 *
 * 位置由调用方给（落在页脚那行正上方，同自动换源的提示胶囊）。
 *
 * @param replacePreview 原文 → 本章的命中数与预览，见 [ReplaceTextDialog]
 * @param onReplace (原文, 换成)；换成为空就是删掉
 */
@Composable
internal fun SelectionToolbar(
    selectedText: String,
    onCopy: () -> Unit,
    onPurify: () -> Unit,
    onReplace: (find: String, replacement: String) -> Unit,
    replacePreview: (String) -> ReplacePreview?,
    modifier: Modifier = Modifier,
    /** 录着翻页区的图层（见 ReaderScreen）；null 时退回实色底 */
    pageBackdrop: LayerBackdrop? = null,
) {
    val overlayColor = readerOverlayColor()
    val fill = if (pageBackdrop != null) Color.Transparent else overlayColor
    val highlight = smallGlassHighlight(dark = overlayColor.luminance() < 0.5f)
    var replacing by remember { mutableStateOf(false) }

    FloatingToolbar(
        // 药丸是 50dp 圆角，比自身高度一半还大，等于两头全圆：模糊区用同一个胶囊形
        // 按钮之间的空隙别漏到纸面：纸面那层「点空白取消选区」会把选区收掉
        modifier = modifier.readerGlass(pageBackdrop, CircleShape, overlayColor, highlight).blockTouchesBelow(),
        color = fill,
        // 位置由调用方垫好；默认的 12/8dp 外边距再叠一层，药丸就离页脚太远
        outSidePadding = PaddingValues(0.dp),
        shadowElevation = 0.dp,
    ) {
        Row(
            Modifier.padding(horizontal = Dimens.gapXS),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconLabelButton(MiuixIcons.Copy, "复制", onCopy)
            // 最常用的动作：把这句话从本书里删掉
            IconLabelButton(MiuixIcons.Clear, "删除", onPurify)
            IconLabelButton(MiuixIcons.Replace, "替换", { replacing = true })
        }
    }

    if (replacing) {
        ReplaceTextDialog(
            initial = selectedText,
            preview = replacePreview,
            onConfirm = { find, to ->
                replacing = false
                onReplace(find, to)
            },
            // 取消回到药丸，选区还在（还能复制、直接删）
            onDismiss = { replacing = false },
        )
    }
}
